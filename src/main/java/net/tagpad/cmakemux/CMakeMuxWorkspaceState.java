package net.tagpad.cmakemux;

import com.intellij.openapi.components.*;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Per-user, per-project state stored in the workspace file (not shared through VCS),
 * for data that changes while working, like the entry loaded last.
 */
@Service(Service.Level.PROJECT)
@State(name = "CMakeMuxWorkspaceState", storages = @Storage(StoragePathMacros.WORKSPACE_FILE))
public final class CMakeMuxWorkspaceState implements PersistentStateComponent<CMakeMuxWorkspaceState.State> {

    public static class State {
        // Id of the entry last loaded (several entries may share the same CMakeLists.txt)
        public String activeEntryId;
    }

    private State state = new State();

    public static CMakeMuxWorkspaceState getInstance(@NotNull Project project) {
        return project.getService(CMakeMuxWorkspaceState.class);
    }

    @Override
    public @NotNull State getState() {
        return state;
    }

    @Override
    public void loadState(@NotNull State state) {
        this.state = state;
    }

    public @Nullable String getActiveEntryId() {
        return state.activeEntryId;
    }

    public void setActiveEntryId(@Nullable String id) {
        state.activeEntryId = id;
    }
}
