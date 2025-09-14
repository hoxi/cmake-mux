package net.tagpad.cmakemux;

import com.intellij.openapi.components.*;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.io.FileUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/** Project-level persisted state.
 *  Stored in a dedicated file (cmake-mux.xml). Legacy data is read from workspace.xml. */
@State(
        name = "CMakeMuxState",
        storages = {
                @Storage(value = "cmake-mux.xml", roamingType = RoamingType.DISABLED),
                @Storage(value = StoragePathMacros.WORKSPACE_FILE, deprecated = true)
        }
)
public class CMakeMuxState implements PersistentStateComponent<CMakeMuxState.State> {

    public static class State {
        public List<CMakeMuxEntry> entries = new ArrayList<>();
    }

    private final Project project;
    private final State state = new State();

    public CMakeMuxState(Project project) {
        this.project = project;
    }

    @Nullable
    @Override
    public State getState() {
        return state;
    }

    @Override
    public void loadState(@NotNull State state) {
        this.state.entries = new ArrayList<>(state.entries);
        // Normalize legacy paths on load
        for (CMakeMuxEntry e : this.state.entries) {
            if (e != null && e.getPath() != null) {
                e.setPath(e.getPath());
            }
            if (e != null && e.getRegexps() == null) {
                e.setRegexps(new ArrayList<>());
            }
        }
    }

    public static CMakeMuxState getInstance(Project project) {
        return project.getService(CMakeMuxState.class);
    }

    public List<CMakeMuxEntry> getEntries() {
        return state.entries;
    }

    public void addOrReplace(CMakeMuxEntry entry) {
        // Replace on same path; otherwise add.
        for (int i = 0; i < state.entries.size(); i++) {
            if (FileUtil.pathsEqual(state.entries.get(i).getPath(), entry.getPath())) {
                state.entries.set(i, entry);
                return;
            }
        }
        state.entries.add(entry);
    }

    public void removeByPath(String path) {
        state.entries.removeIf(e -> FileUtil.pathsEqual(e.getPath(), path));
    }
}