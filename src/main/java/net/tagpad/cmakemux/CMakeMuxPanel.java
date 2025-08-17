package net.tagpad.cmakemux;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.actionSystem.ex.ActionUtil;
import com.intellij.openapi.actionSystem.impl.SimpleDataContext;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.VfsUtilCore;
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
    private static final Logger LOG = Logger.getInstance(CMakeMuxPanel.class);

    private final Project project;
    private final JBList<CMakeMuxEntry> list;
    private final DefaultListModel<CMakeMuxEntry> model;

    // Details panel components
    private JBLabel detailsTitleLabel;
    private JBList<String> regexList;
    private DefaultListModel<String> regexModel;

    public CMakeMuxPanel(@NotNull Project project) {
        super(new BorderLayout());
        this.project = project;
        this.model = new DefaultListModel<>();
        this.list = new JBList<>(model);
        this.list.setCellRenderer(new EntryRenderer(() -> CMakeMuxSelectionService.getInstance(project).getActivePath()));

        // Build UI first to ensure detail components exist before any updates
        JComponent toolbarPanel = ToolbarDecorator.createDecorator(list)
                .setEditAction(button -> doRename())
                .setRemoveAction(button -> doDelete())
                .setMoveUpAction(button -> moveEntries(-1))
                .setMoveDownAction(button -> moveEntries(1))
                .createPanel();

        JBSplitter splitter = new JBSplitter(false, 0.7f);
        splitter.setFirstComponent(toolbarPanel);
        splitter.setSecondComponent(buildDetailsPanel());
        add(splitter, BorderLayout.CENTER);

        // Now wire listeners
        project.getMessageBus()
                .connect(this)
                .subscribe(CMakeMuxEvents.TOPIC, (CMakeMuxEvents) this::onEntriesChanged);

        project.getMessageBus()
                .connect(this)
                .subscribe(CMakeMuxSelectionEvents.TOPIC, (CMakeMuxSelectionEvents) this::onActiveSelectionChanged);

        list.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                updateDetailsForSelection();
            }
        });

        // Double-click to open target file
        list.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    CMakeMuxEntry sel = list.getSelectedValue();
                    if (sel != null) loadCMakeProject(sel.getPath());
                }
            }
        });

        // Populate model and initialize view
        refreshFromState();

        // Best-effort: detect current active CMakeLists on panel load and set it
        initializeActiveSelectionIfMissing();

        // Reflect current stored active path even if no event was received yet
        onActiveSelectionChanged();

        // Initialize details content for initial selection
        updateDetailsForSelection();
    }

    private void onEntriesChanged() {
        if (SwingUtilities.isEventDispatchThread()) {
            refreshFromState();
        } else {
            SwingUtilities.invokeLater(this::refreshFromState);
        }
    }

    private void onActiveSelectionChanged() {
        if (SwingUtilities.isEventDispatchThread()) {
            list.repaint();
        } else {
            SwingUtilities.invokeLater(list::repaint);
        }
    }

    private JComponent buildDetailsPanel() {
        JPanel p = new JPanel(new BorderLayout());
        // Reduce outer padding (was 10)
        p.setBorder(JBUI.Borders.empty(6));

        detailsTitleLabel = new JBLabel("Default Enable CMake Presets");
        // Slightly smaller font
        Font base = detailsTitleLabel.getFont();
        detailsTitleLabel.setFont(base.deriveFont(Math.max(10f, base.getSize2D() - 1.0f)));
        // Add a small bottom gap under the title
        JPanel titleWrap = new JPanel(new BorderLayout());
        titleWrap.setBorder(JBUI.Borders.empty());
        titleWrap.add(detailsTitleLabel, BorderLayout.NORTH);
        p.add(titleWrap, BorderLayout.NORTH);

        regexModel = new DefaultListModel<>();
        regexList = new JBList<>(regexModel);
        regexList.setVisibleRowCount(8);

        // Toolbar for regex list: + (add), pencil (edit), - (remove), and move up/down
        ToolbarDecorator decorator = ToolbarDecorator.createDecorator(regexList)
                .setAddAction(e -> addRegex())
                .setEditAction(e -> editRegex())
                .setRemoveAction(e -> removeRegex())
                .setMoveUpAction(e -> moveRegex(-1))
                .setMoveDownAction(e -> moveRegex(1));

        JComponent decoratorPanel = decorator.createPanel();
        decoratorPanel.setBorder(JBUI.Borders.empty());

        p.add(decoratorPanel, BorderLayout.CENTER);
        return p;
    }

    private void updateDetailsForSelection() {
        if (detailsTitleLabel == null || regexModel == null) return; // UI not ready
        CMakeMuxEntry sel = list.getSelectedValue();
        String targetLabel = sel != null ? sel.getNickname() : "(none)";
        detailsTitleLabel.setText("Default Enable CMake Presets for " + targetLabel);

        regexModel.clear();
        if (sel != null) {
            List<String> regs = sel.getRegexes();
            if (regs != null) {
                for (String r : regs) regexModel.addElement(r);
            }
        }
    }

    private void addRegex() {
        CMakeMuxEntry sel = list.getSelectedValue();
        if (sel == null) return;
        String input = Messages.showInputDialog(project, "Enter regex to enable presets:", "Add Preset Regex", Messages.getQuestionIcon());
        if (input == null) return;
        String trimmed = input.trim();
        if (trimmed.isEmpty()) return;

        List<String> regs = new ArrayList<>(sel.getRegexes());
        regs.add(trimmed);
        sel.setRegexes(regs);
        CMakeMuxService.getInstance(project).addOrReplace(sel);

        regexModel.addElement(trimmed);
    }

    private void editRegex() {
        CMakeMuxEntry sel = list.getSelectedValue();
        int idx = regexList.getSelectedIndex();
        if (sel == null || idx < 0) return;

        String current = regexModel.get(idx);
        String input = Messages.showInputDialog(project, "Edit regex:", "Edit Preset Regex", Messages.getQuestionIcon(), current, null);
        if (input == null) return;
        String trimmed = input.trim();
        if (trimmed.isEmpty()) return;

        regexModel.set(idx, trimmed);

        List<String> regs = new ArrayList<>(sel.getRegexes());
        regs.set(idx, trimmed);
        sel.setRegexes(regs);
        CMakeMuxService.getInstance(project).addOrReplace(sel);
    }

    private void removeRegex() {
        CMakeMuxEntry sel = list.getSelectedValue();
        int idx = regexList.getSelectedIndex();
        if (sel == null || idx < 0) return;

        regexModel.remove(idx);

        List<String> regs = new ArrayList<>(sel.getRegexes());
        regs.remove(idx);
        sel.setRegexes(regs);
        CMakeMuxService.getInstance(project).addOrReplace(sel);
    }

    private void refreshFromState() {
        model.clear();
        List<CMakeMuxEntry> entries = new ArrayList<>(CMakeMuxService.getInstance(project).getEntries());
        for (CMakeMuxEntry e : entries) model.addElement(e);
        if (!model.isEmpty() && list.getSelectedIndex() == -1) {
            list.setSelectedIndex(0); // enables Edit/Delete right away
        }
        // Keep details in sync after a refresh
        updateDetailsForSelection();
    }

    // Generic mover for any JBList/DefaultListModel pair with selection following and a persistence hook
    private <T> void moveSelected(JList<T> jList, DefaultListModel<T> jModel, int delta, Runnable persist) {
        int idx = jList.getSelectedIndex();
        if (idx < 0) return;
        int target = idx + delta;
        if (target < 0 || target >= jModel.size()) return;

        T a = jModel.get(idx);
        T b = jModel.get(target);
        jModel.set(idx, b);
        jModel.set(target, a);

        jList.setSelectedIndex(target);
        jList.ensureIndexIsVisible(target);
        jList.requestFocusInWindow();

        if (persist != null) persist.run();
        jList.repaint();
    }

    // Wrapper for moving entries in the main list and persisting to state
    private void moveEntries(int delta) {
        moveSelected(list, model, delta, () -> {
            List<CMakeMuxEntry> newOrder = new ArrayList<>();
            for (int i = 0; i < model.size(); i++) newOrder.add(model.get(i));
            ApplicationManager.getApplication().runWriteAction(() -> {
                List<CMakeMuxEntry> stateList = CMakeMuxState.getInstance(project).getEntries();
                stateList.clear();
                stateList.addAll(newOrder);
            });
        });
    }

    // Wrapper for moving regexes and persisting on the selected entry
    private void moveRegex(int delta) {
        moveSelected(regexList, regexModel, delta, () -> {
            CMakeMuxEntry sel = list.getSelectedValue();
            if (sel == null) return;

            List<String> regs = new ArrayList<>();
            for (int i = 0; i < regexModel.size(); i++) regs.add(regexModel.get(i));
            sel.setRegexes(regs);
            CMakeMuxService.getInstance(project).addOrReplace(sel);
        });
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
        updateDetailsForSelection();
    }

    private void doDelete() {
        CMakeMuxEntry sel = list.getSelectedValue();
        if (sel == null) return;
        int res = Messages.showYesNoDialog(project,
                "Remove '" + sel.getNickname() + "'?", "Delete Entry", Messages.getQuestionIcon());
        if (res == Messages.YES) {
            CMakeMuxService.getInstance(project).removeByPath(sel.getPath());
            model.removeElement(sel);
            updateDetailsForSelection();
        }
    }

    private void openFile(String path) {
        String si = FileUtil.toSystemIndependentName(path);
        String url = VfsUtilCore.pathToUrl(si);
        VirtualFile vf = VirtualFileManager.getInstance().findFileByUrl(url);
        if (vf != null) {
            FileEditorManager.getInstance(project).openFile(vf, true);
        } else {
            Messages.showWarningDialog(project, "Cannot locate file:\n" + path, "Open File");
        }
    }

    @Override
    public void dispose() { /* disposed with content */ }

    private static class EntryRenderer extends DefaultListCellRenderer {
        private final java.util.function.Supplier<String> activePathSupplier;

        EntryRenderer(java.util.function.Supplier<String> activePathSupplier) {
            this.activePathSupplier = activePathSupplier;
        }

        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            if (value instanceof CMakeMuxEntry e) {
                setText(e.toString());
                setToolTipText(e.getPath());
                setBorder(JBUI.Borders.empty(2, 6));

                String activePath = activePathSupplier.get();
                boolean isActive = activePath != null
                        && FileUtil.pathsEqual(activePath, e.getPath());

                Icon icon = isActive ? AllIcons.Debugger.NextStatement : null;
                setIcon(icon);
                setFont(getFont().deriveFont(Font.PLAIN));
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
            AnActionEvent event = AnActionEvent.createEvent(
                    action,
                    dataContext,
                    action.getTemplatePresentation().clone(),
                    ActionPlaces.PROJECT_VIEW_POPUP,
                    ActionUiKind.POPUP,
                    null // no InputEvent
            );
            ActionUtil.performAction(action, event);

            // Mark as active (in case external processing is async)
            CMakeMuxSelectionService.getInstance(project).setActivePath(vf.getPath());

            CMakeMuxPresetHandler.enableMatchingPresets(project, java.util.List.of(".*"));
        });
    }

    // Best-effort detection of current CMakeLists when the panel loads
    private void initializeActiveSelectionIfMissing() {
        if (CMakeMuxSelectionService.getInstance(project).getActivePath() != null) return;

        ApplicationManager.getApplication().invokeLater(() -> {
            try {
                com.jetbrains.cidr.cpp.cmake.workspace.CMakeWorkspace ws =
                        com.jetbrains.cidr.cpp.cmake.workspace.CMakeWorkspace.getInstance(project);

                java.io.File modelProjectDir = ws.getModelProjectDir();
                if (modelProjectDir != null) {
                    java.io.File f = new java.io.File(modelProjectDir, "CMakeLists.txt");
                    if (f.isFile()) {
                        CMakeMuxSelectionService.getInstance(project).setActivePath(f.getAbsolutePath());
                    }
                }
            } catch (Throwable t) {
                LOG.debug("[CMakeMux] Initial CMakeLists detection failed (best-effort): " + t.getMessage(), t);
            }
        });
    }

}