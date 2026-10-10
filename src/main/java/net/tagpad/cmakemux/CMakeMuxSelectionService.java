package net.tagpad.cmakemux;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.actionSystem.ex.AnActionListener;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.Alarm;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tracks which CMakeLists.txt is loaded and which entries correspond to it.
 * The CMakeLists.txt can also be loaded outside the mux (CLion's "Load CMake Project"), so the state per entry
 * is determined heuristically from the loaded CMakeLists.txt and the enabled presets.
 */
@Service(Service.Level.PROJECT)
public final class CMakeMuxSelectionService implements Disposable {

    public enum ActiveState {
        /** The entry's CMakeLists.txt is not loaded. */
        NONE,
        /** The entry's CMakeLists.txt is loaded, but the enabled presets do not (or cannot be shown to) match. */
        CMAKELISTS_LOADED,
        /** The entry's CMakeLists.txt is loaded and the enabled presets match its regexps. */
        ACTIVE
    }

    private static final int REFRESH_DELAY_MS = 200;

    private final Project project;
    private final Alarm refreshAlarm;
    private volatile @Nullable String activePath;
    private volatile Map<String, ActiveState> states = Map.of();

    public CMakeMuxSelectionService(Project project) {
        this.project = project;
        this.refreshAlarm = new Alarm(Alarm.ThreadToUse.SWING_THREAD, this);

        var connection = project.getMessageBus().connect(this);
        connection.subscribe(AnActionListener.TOPIC, new AnActionListener() {
            @Override
            public void afterActionPerformed(@NotNull AnAction action,
                                             @NotNull AnActionEvent event,
                                             @NotNull AnActionResult result) {
                if (!result.isPerformed()) return;

                String id = ActionManager.getInstance().getId(action);
                if (!"CMake.LoadCMakeProject".equals(id)) return;

                VirtualFile vf = event.getData(CommonDataKeys.VIRTUAL_FILE);
                if (vf == null) {
                    VirtualFile[] arr = event.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY);
                    if (arr != null && arr.length > 0) vf = arr[0];
                }
                if (vf != null) {
                    setActivePath(vf.getPath());
                }
            }
        });

        // Entries or their regexps changed
        connection.subscribe(CMakeMuxEvents.TOPIC, (CMakeMuxEvents) this::scheduleRefresh);

        // Enabled presets changed (by us, by the user or by CLion importing presets)
        CMakeMuxClionEvents.subscribeSettings(connection, (method, args) -> {
            if ("profilesChanged".equals(method)) scheduleRefresh();
        });

        // A CMake project finished loading, however it was loaded: find out which CMakeLists.txt it is
        CMakeMuxClionEvents.subscribeWorkspace(connection, (method, args) -> {
            if (!"reloadingFinished".equals(method)) return;
            ApplicationManager.getApplication().invokeLater(() -> {
                CMakeMuxActiveDetector.detectAndSetActiveOnce(project);
                scheduleRefresh();
            }, project.getDisposed());
        });
    }

    public static CMakeMuxSelectionService getInstance(@NotNull Project project) {
        return project.getService(CMakeMuxSelectionService.class);
    }

    public @Nullable String getActivePath() {
        return activePath;
    }

    /** Set the loaded CMakeLists.txt. Forgets the active entry if it points to another file. */
    public void setActivePath(@Nullable String path) {
        if (path != null) path = FileUtil.toSystemIndependentName(path);
        if (path != null && path.equals(activePath)) return;
        this.activePath = path;
        CMakeMuxState state = CMakeMuxState.getInstance(project);
        CMakeMuxEntry activeEntry = state.findById(state.getActiveEntryId());
        if (activeEntry != null && (path == null || !FileUtil.pathsEqual(path, activeEntry.getPath()))) {
            state.setActiveEntryId(null);
        }
        scheduleRefresh();
    }

    /** Set the entry that was loaded (several entries may share the same CMakeLists.txt). */
    public void setActiveEntry(@NotNull CMakeMuxEntry entry) {
        this.activePath = entry.getPath();
        CMakeMuxState.getInstance(project).setActiveEntryId(entry.getId());
        scheduleRefresh();
    }

    public @Nullable String getActiveEntryId() {
        return CMakeMuxState.getInstance(project).getActiveEntryId();
    }

    public @NotNull ActiveState getState(@NotNull CMakeMuxEntry entry) {
        return states.getOrDefault(entry.getId(), ActiveState.NONE);
    }

    /** Recompute the entry states soon; bursts of events (e.g. while presets are imported) are coalesced. */
    public void scheduleRefresh() {
        if (project.isDisposed()) return;
        refreshAlarm.cancelAllRequests();
        refreshAlarm.addRequest(this::refresh, REFRESH_DELAY_MS);
    }

    private void refresh() {
        if (project.isDisposed()) return;
        String path = activePath;
        CMakeMuxState state = CMakeMuxState.getInstance(project);
        Map<String, ActiveState> newStates = new HashMap<>();
        if (path != null) {
            List<CMakeMuxEntry> loaded = new ArrayList<>();
            List<CMakeMuxEntry> matching = new ArrayList<>();
            for (CMakeMuxEntry e : state.getEntries()) {
                if (e.getPath() == null || !FileUtil.pathsEqual(path, e.getPath())) continue;
                loaded.add(e);
                List<CMakeMuxPresetFilter> filters = e.getPresetFilters() != null ? e.getPresetFilters() : List.of();
                if (Boolean.TRUE.equals(CMakeMuxPresetHandler.getInstance(project).presetsMatch(filters))) {
                    matching.add(e);
                }
            }
            // Several entries may select the same presets; prefer the one loaded last
            String activeId = state.getActiveEntryId();
            boolean activeMatches = matching.stream().anyMatch(e -> e.getId().equals(activeId));
            for (CMakeMuxEntry e : loaded) {
                boolean active = matching.contains(e) && (!activeMatches || e.getId().equals(activeId));
                newStates.put(e.getId(), active ? ActiveState.ACTIVE : ActiveState.CMAKELISTS_LOADED);
            }
        }
        states = Map.copyOf(newStates);
        // Always notify, the active path may have changed even if the states did not
        project.getMessageBus().syncPublisher(CMakeMuxSelectionEvents.TOPIC).activeSelectionChanged();
    }

    @Override
    public void dispose() {
        // No-op; connection and alarm are disposed automatically because they use this as parent
    }
}
