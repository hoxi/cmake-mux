package net.tagpad.cmakemux;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.actionSystem.ex.ActionUtil;
import com.intellij.openapi.actionSystem.impl.SimpleDataContext;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.ui.JBSplitter;
import com.intellij.ui.ToolbarDecorator;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBList;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

/** UI for the tool window: list with rename/delete and open on double-click. */
public class CMakeMuxPanel extends JPanel implements Disposable {
    private final Project project;
    private final JBList<CMakeMuxEntry> list;
    private final DefaultListModel<CMakeMuxEntry> model;

    public CMakeMuxPanel(@NotNull Project project) {
        super(new BorderLayout());
        this.project = project;
        this.model = new DefaultListModel<>();
        this.list = new JBList<>(model);
        this.list.setCellRenderer(new EntryRenderer());
        refreshFromState();

        // Listen for changes and refresh (provide the listener!)
        project.getMessageBus()
                .connect(this)
                .subscribe(CMakeMuxEvents.TOPIC, (CMakeMuxEvents) this::onEntriesChanged);

        // Double-click to open target file
        list.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    CMakeMuxEntry sel = list.getSelectedValue();
                    if (sel != null) loadCMakeProject(sel.getPath());
                }
            }
        });

        // The decorator embeds the list and shows toolbar with Edit/Delete
        JComponent toolbarPanel = ToolbarDecorator.createDecorator(list)
                .setEditAction(button -> doRename())
                .setRemoveAction(button -> doDelete())
                .disableUpDownActions()
                .createPanel();

        JBSplitter splitter = new JBSplitter(false, 0.7f);
        splitter.setFirstComponent(toolbarPanel);
        splitter.setSecondComponent(buildDetailsPanel());

        add(splitter, BorderLayout.CENTER);
    }

    private void onEntriesChanged() {
        if (SwingUtilities.isEventDispatchThread()) {
            refreshFromState();
        } else {
            SwingUtilities.invokeLater(this::refreshFromState);
        }
    }

    private JComponent buildDetailsPanel() {
        JPanel p = new JPanel(new BorderLayout());
        p.setBorder(JBUI.Borders.empty(10));
        p.add(new JBLabel("Tip: Double‑click an item to open its CMakeLists.txt."), BorderLayout.NORTH);
        return p;
    }

    private void refreshFromState() {
        model.clear();
        List<CMakeMuxEntry> entries = new ArrayList<>(CMakeMuxService.getInstance(project).getEntries());
        for (CMakeMuxEntry e : entries) model.addElement(e);
        if (!model.isEmpty() && list.getSelectedIndex() == -1) {
            list.setSelectedIndex(0); // enables Edit/Delete right away
        }
    }

    private void doRename() {
        CMakeMuxEntry sel = list.getSelectedValue();
        if (sel == null) return;
        String newNick = Messages.showInputDialog(project,
                "New nickname:", "Rename Entry", Messages.getQuestionIcon(), sel.getNickname(), null);
        if (newNick == null || newNick.trim().isEmpty()) return;
        sel.setNickname(newNick.trim());
        CMakeMuxService.getInstance(project).addOrReplace(sel);
        list.repaint();
    }

    private void doDelete() {
        CMakeMuxEntry sel = list.getSelectedValue();
        if (sel == null) return;
        int res = Messages.showYesNoDialog(project,
                "Remove '" + sel.getNickname() + "'?", "Delete Entry", Messages.getQuestionIcon());
        if (res == Messages.YES) {
            CMakeMuxService.getInstance(project).removeByPath(sel.getPath());
            model.removeElement(sel);
        }
    }

    private void openFile(String path) {
        VirtualFile vf = VirtualFileManager.getInstance().findFileByUrl("file://" + path);
        if (vf != null) {
            FileEditorManager.getInstance(project).openFile(vf, true);
        } else {
            Messages.showWarningDialog(project, "Cannot locate file:\n" + path, "Open File");
        }
    }

    @Override
    public void dispose() { /* disposed with content */ }

    private static class EntryRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            if (value instanceof CMakeMuxEntry) {
                CMakeMuxEntry e = (CMakeMuxEntry) value;
                setText(e.getNickname() + "  —  " + e.getPath());
                setBorder(JBUI.Borders.empty(2, 6));
            }
            return this;
        }
    }

    private void loadCMakeProject(@NotNull String path) {
        VirtualFile vf = VirtualFileManager.getInstance().findFileByUrl("file://" + path);
        if (vf == null) {
            Messages.showWarningDialog(project, "Cannot locate file:\n" + path, "Load CMake Project");
            return;
        }

        AnAction action = ActionManager.getInstance().getAction("CMake.LoadCMakeProject");

        DataContext dataContext = SimpleDataContext.builder()
                .add(CommonDataKeys.PROJECT, project)
                .add(CommonDataKeys.VIRTUAL_FILE, vf)
                .add(CommonDataKeys.VIRTUAL_FILE_ARRAY, new VirtualFile[]{vf})
                .build();

        ApplicationManager.getApplication().invokeLater(() -> {
            ActionUtil.invokeAction(
                    action,
                    dataContext,
                    ActionPlaces.PROJECT_VIEW_POPUP,
                    null,
                    null
            );
            CMakeMuxPresetHandler.enableMatchingPresets(project, java.util.List.of(".*"));
        });

    }

}