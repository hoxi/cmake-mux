package net.tagpad.cmakemux;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.actionSystem.ex.AnActionListener;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

@Service(Service.Level.PROJECT)
public final class CMakeMuxSelectionService implements Disposable {
    private final Project project;
    private volatile @Nullable String activePath;

    public CMakeMuxSelectionService(Project project) {
        this.project = project;

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
    }

    public static CMakeMuxSelectionService getInstance(@NotNull Project project) {
        return project.getService(CMakeMuxSelectionService.class);
    }

    public @Nullable String getActivePath() {
        return activePath;
    }

    /** Set the loaded CMakeLists.txt. Forgets the active entry if it points to another file. */
    public void setActivePath(@Nullable String path) {
        if (path != null && path.equals(activePath)) return;
        this.activePath = path;
        CMakeMuxState state = CMakeMuxState.getInstance(project);
        CMakeMuxEntry activeEntry = state.findById(state.getActiveEntryId());
        if (activeEntry != null && (path == null || !FileUtil.pathsEqual(path, activeEntry.getPath()))) {
            state.setActiveEntryId(null);
        }
        fireChanged();
    }

    /** Set the entry that was loaded (several entries may share the same CMakeLists.txt). */
    public void setActiveEntry(@NotNull CMakeMuxEntry entry) {
        this.activePath = entry.getPath();
        CMakeMuxState.getInstance(project).setActiveEntryId(entry.getId());
        fireChanged();
    }

    public @Nullable String getActiveEntryId() {
        return CMakeMuxState.getInstance(project).getActiveEntryId();
    }

    /**
     * Whether the entry is the active one. If the active entry is unknown (e.g. the CMakeLists.txt was loaded
     * outside the mux), all entries for the loaded CMakeLists.txt are considered active.
     */
    public boolean isActive(@NotNull CMakeMuxEntry entry) {
        String path = activePath;
        if (path == null || !FileUtil.pathsEqual(path, entry.getPath())) return false;
        CMakeMuxState state = CMakeMuxState.getInstance(project);
        CMakeMuxEntry activeEntry = state.findById(state.getActiveEntryId());
        if (activeEntry != null && FileUtil.pathsEqual(path, activeEntry.getPath())) {
            return activeEntry.getId().equals(entry.getId());
        }
        return true;
    }

    private void fireChanged() {
        project.getMessageBus().syncPublisher(CMakeMuxSelectionEvents.TOPIC).activeSelectionChanged();
    }

    @Override
    public void dispose() {
        // No-op; connection is disposed automatically because connect(this) was used
    }
}