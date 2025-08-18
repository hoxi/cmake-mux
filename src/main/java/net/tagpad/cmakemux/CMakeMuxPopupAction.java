package net.tagpad.cmakemux;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

public class CMakeMuxPopupAction extends AnAction implements DumbAware {

    @Override
    public void actionPerformed(AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) return;

        List<CMakeMuxEntry> entries = new ArrayList<>(CMakeMuxService.getInstance(project).getEntries());
        if (entries.isEmpty()) {
            Messages.showInfoMessage(project, "No entries in CMake Mux.", "CMake Mux - Load Project");
            return;
        }

        int max = Math.min(9, entries.size());
        DefaultActionGroup group = new DefaultActionGroup();

        for (int i = 0; i < max; i++) {
            final int index = i;
            CMakeMuxEntry entry = entries.get(i);
            String title = entry.getNickname() != null && !entry.getNickname().isEmpty()
                    ? entry.getNickname()
                    : entry.getPath();

            group.add(new AnAction(title, entry.getPath(), AllIcons.Actions.Execute) {
                @Override
                public void actionPerformed(@NotNull AnActionEvent ignored) {
                    CMakeMuxLoader.loadEntry(project, entries.get(index));
                }
            });
        }

        JBPopupFactory.getInstance()
                .createActionGroupPopup(
                        "CMake Mux - Load Project",
                        group,
                        e.getDataContext(),
                        true,   // showNumbers
                        true,   // showDisabledActions
                        true,   // autoDispose
                        null,
                        max,    // maxRowCount
                        null
                )
                .showInBestPositionFor(e.getDataContext());
    }
}