package net.tagpad.cmakemux;

import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.actionSystem.ex.ActionUtil;
import com.intellij.openapi.actionSystem.impl.SimpleDataContext;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;

import java.util.List;

public final class CMakeMuxLoader {
    private CMakeMuxLoader() {}

    public static void loadEntry(Project project, CMakeMuxEntry entry) {
        if (project == null || entry == null) return;

        String si = FileUtil.toSystemIndependentName(entry.getPath());
        String url = VfsUtilCore.pathToUrl(si);
        VirtualFile vf = VirtualFileManager.getInstance().findFileByUrl(url);
        if (vf == null) {
            Messages.showWarningDialog(project, "Cannot locate file:\n" + entry.getPath(), "Load CMake Project");
            return;
        }

        AnAction action = ActionManager.getInstance().getAction("CMake.LoadCMakeProject");
        if (action == null) {
            Messages.showWarningDialog(project, "Cannot find CLion action: CMake.LoadCMakeProject", "Load CMake Project");
            return;
        }

        DataContext dataContext = SimpleDataContext.builder()
                .add(CommonDataKeys.PROJECT, project)
                .add(CommonDataKeys.VIRTUAL_FILE, vf)
                .add(CommonDataKeys.VIRTUAL_FILE_ARRAY, new VirtualFile[]{vf})
                .build();

        ApplicationManager.getApplication().invokeLater(() -> {
            AnActionEvent event = AnActionEvent.createEvent(
                    action,
                    dataContext,
                    action.getTemplatePresentation().clone(),
                    ActionPlaces.PROJECT_VIEW_POPUP,
                    ActionUiKind.POPUP,
                    null // no InputEvent
            );
            ActionUtil.performAction(action, event);

            CMakeMuxSelectionService.getInstance(project).setActivePath(vf.getPath());

            // Small delay to allow the action to run before we enable presets.
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                // Do nothing
            }

            List<String> regexps = entry.getRegexps();
            CMakeMuxPresetHandler.enableMatchingPresets(project, regexps != null ? regexps : List.of());
        });
    }
}