package net.tagpad.cmakemux;

import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.actionSystem.ex.AnActionListener;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

@Service(Service.Level.PROJECT)
public final class CMakeMuxSelectionService {
    private final Project project;
    private volatile @Nullable String activePath;

    public CMakeMuxSelectionService(Project project) {
        this.project = project;

        // Subscribe via MessageBus, scoped to the project
        project.getMessageBus().connect(project).subscribe(AnActionListener.TOPIC, new AnActionListener() {

            @Override
            public void afterActionPerformed(@NotNull AnAction action,
                                             @NotNull AnActionEvent event,
                                             @NotNull AnActionResult result) {
                // Only proceed if the action actually succeeded
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

    public void setActivePath(@Nullable String path) {
        if (path != null && path.equals(activePath)) return;
        this.activePath = path;
        project.getMessageBus().syncPublisher(CMakeMuxSelectionEvents.TOPIC).activeSelectionChanged();
    }
}