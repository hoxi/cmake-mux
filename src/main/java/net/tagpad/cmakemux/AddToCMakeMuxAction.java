package net.tagpad.cmakemux;

import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
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

        String defaultNick = file.getParent() != null ? file.getParent().getName() : "CMakeLists";
        String nickname = Messages.showInputDialog(project,
                "Enter a name for this project CMakeLists.txt:",
                "Pin to CMake Mux",
                Messages.getQuestionIcon(),
                defaultNick,
                null);
        if (nickname == null || nickname.trim().isEmpty()) {
            return; // cancelled or empty
        }

        CMakeMuxEntry entry = new CMakeMuxEntry(nickname.trim(), file.getPath());
        CMakeMuxService.getInstance(project).addOrReplace(entry);

        // Optionally, ensure the tool window shows up.
        CMakeMuxToolWindowFactory.activate(project);
    }
}
