package net.tagpad.cmakemux;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowAnchor;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;

public final class CMakeMuxToolWindowFactory implements ToolWindowFactory {

    public static final String TOOL_WINDOW_ID = "CMake Mux";

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        CMakeMuxPanel panel = new CMakeMuxPanel(project);
        Content content = ContentFactory.getInstance().createContent(panel, "", false);
        content.setDisposer(panel); // ensures message-bus connection is disposed on unload
        toolWindow.getContentManager().addContent(content);
        toolWindow.setAnchor(ToolWindowAnchor.LEFT, null);
        toolWindow.setSplitMode(true, null);
    }

    public static void activate(@NotNull Project project) {
        ToolWindowManager manager = ToolWindowManager.getInstance(project);
        ToolWindow tw = manager.getToolWindow(TOOL_WINDOW_ID);
        if (tw != null) {
            tw.activate(null, true, true);
            return;
        }
        ApplicationManager.getApplication().invokeLater(() -> {
            ToolWindow later = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID);
            if (later != null) {
                later.activate(null, true, true);
            }
        });
    }
}