package net.tagpad.cmakemux;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

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
        ApplicationManager.getApplication().runWriteAction(() -> {
            CMakeMuxState.getInstance(project).addOrReplace(entry);
            // Notify listeners on the UI thread
            ApplicationManager.getApplication().invokeLater(() ->
                    project.getMessageBus().syncPublisher(CMakeMuxEvents.TOPIC).entriesChanged()
            );
        });
    }

    public void removeByPath(@NotNull String path) {
        ApplicationManager.getApplication().runWriteAction(() -> {
            CMakeMuxState.getInstance(project).removeByPath(path);
            // Notify listeners on the UI thread
            ApplicationManager.getApplication().invokeLater(() ->
                    project.getMessageBus().syncPublisher(CMakeMuxEvents.TOPIC).entriesChanged()
            );
        });
    }
}