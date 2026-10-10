package net.tagpad.cmakemux;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

@Service(Service.Level.PROJECT)
public final class CMakeMuxService {
    private final Project project;

    public CMakeMuxService(Project project) {
        this.project = project;
    }

    public static CMakeMuxService getInstance(@NotNull Project project) {
        return project.getService(CMakeMuxService.class);
    }

    public List<CMakeMuxEntry> getEntries() {
        return CMakeMuxState.getInstance(project).getEntries();
    }

    public void addOrReplace(@NotNull CMakeMuxEntry entry) {
        CMakeMuxState.getInstance(project).addOrReplace(entry);
        fireEntriesChanged();
    }

    public boolean isNicknameTaken(@NotNull String nickname, @Nullable String exceptId) {
        return CMakeMuxState.getInstance(project).isNicknameTaken(nickname, exceptId);
    }

    public void removeById(@NotNull String id) {
        CMakeMuxState.getInstance(project).removeById(id);
        fireEntriesChanged();
    }

    // Notify listeners on the UI thread, after the current UI event
    private void fireEntriesChanged() {
        ApplicationManager.getApplication().invokeLater(() ->
                project.getMessageBus().syncPublisher(CMakeMuxEvents.TOPIC).entriesChanged(),
                project.getDisposed());
    }
}