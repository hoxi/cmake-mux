package net.tagpad.cmakemux;

import com.intellij.openapi.components.*;
import com.intellij.openapi.project.Project;
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
            if (e != null) {
                e.ensureId();
                e.migrateLegacyRegexps();
            }
        }
    }

    public static CMakeMuxState getInstance(Project project) {
        return project.getService(CMakeMuxState.class);
    }

    public List<CMakeMuxEntry> getEntries() {
        return state.entries;
    }

    public @Nullable CMakeMuxEntry findById(@Nullable String id) {
        if (id == null) return null;
        for (CMakeMuxEntry e : state.entries) {
            if (id.equals(e.getId())) return e;
        }
        return null;
    }

    public boolean isNicknameTaken(@NotNull String nickname, @Nullable String exceptId) {
        for (CMakeMuxEntry e : state.entries) {
            if (nickname.equals(e.getNickname()) && !e.getId().equals(exceptId)) return true;
        }
        return false;
    }

    public void addOrReplace(CMakeMuxEntry entry) {
        // Replace on same id; otherwise add.
        for (int i = 0; i < state.entries.size(); i++) {
            if (state.entries.get(i).getId().equals(entry.getId())) {
                state.entries.set(i, entry);
                return;
            }
        }
        state.entries.add(entry);
    }

    public void removeById(String id) {
        state.entries.removeIf(e -> e.getId().equals(id));
    }
}