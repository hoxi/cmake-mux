package net.tagpad.cmakemux;

import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

/** Context menu action on CMakeLists.txt to add with a nickname. */
public class AddToCMakeMuxAction extends AnAction implements DumbAware {

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        final VirtualFile file = e.getData(CommonDataKeys.VIRTUAL_FILE);
        boolean visible = file != null && "CMakeLists.txt".equals(file.getName());
        e.getPresentation().setEnabledAndVisible(visible);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        final Project project = e.getProject();
        final VirtualFile file = e.getData(CommonDataKeys.VIRTUAL_FILE);
        if (project == null || file == null) return;

        // The same CMakeLists.txt may be pinned several times (e.g. with different presets), names must be unique
        String defaultNick = file.getParent() != null ? file.getParent().getName() : "CMakeLists";
        CMakeMuxNameDialog dialog = new CMakeMuxNameDialog(project, "Pin to CMake Mux",
                "Enter name for this project CMakeLists.txt:", defaultNick, null);
        if (!dialog.showAndGet()) {
            return; // cancelled
        }

        CMakeMuxEntry entry = new CMakeMuxEntry(dialog.getEntryName(), file.getPath());
        CMakeMuxService.getInstance(project).addOrReplace(entry);

        // Optionally, ensure the tool window shows up.
        CMakeMuxToolWindowFactory.activate(project);
    }
}
