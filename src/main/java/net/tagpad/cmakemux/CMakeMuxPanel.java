package net.tagpad.cmakemux;

import com.intellij.icons.AllIcons;
import com.intellij.ide.projectView.ProjectView;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.ui.JBColor;
import com.intellij.ui.JBSplitter;
import com.intellij.ui.SideBorder;
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
    private JBList<String> regexpList;
    private DefaultListModel<String> regexpModel;

    private List<String> snapshotRegexModel() {
        List<String> regs = new ArrayList<>();
        for (int i = 0; i < regexpModel.size(); i++) regs.add(regexpModel.get(i));
        return regs;
    }

    private void persistRegexpModelToState(CMakeMuxEntry selectedEntry) {
        if (selectedEntry == null) return;
        List<String> regs = snapshotRegexModel();
        ApplicationManager.getApplication().runWriteAction(() -> {
            List<CMakeMuxEntry> stateList = CMakeMuxState.getInstance(project).getEntries();
            for (CMakeMuxEntry e : stateList) {
                if (com.intellij.openapi.util.io.FileUtil.pathsEqual(e.getPath(), selectedEntry.getPath())) {
                    e.setRegexps(regs);
                    break;
                }
            }
            // Keep the in-memory selected entry in sync
            selectedEntry.setRegexps(regs);
        });
    }

    public CMakeMuxPanel(@NotNull Project project) {
        super(new BorderLayout());
        this.project = project;
        this.model = new DefaultListModel<>();
        this.list = new JBList<>(model);
        this.list.setCellRenderer(new EntryRenderer(() -> CMakeMuxSelectionService.getInstance(project).getActivePath()));
        setBorder(JBUI.Borders.empty());
        list.setBorder(JBUI.Borders.empty());

        // Build UI first to ensure detail components exist before any updates
        ToolbarDecorator decorator = ToolbarDecorator.createDecorator(list)
                .setEditAction(button -> doRename())
                .setRemoveAction(button -> doDelete())
                .setMoveUpAction(button -> moveEntries(-1))
                .setMoveDownAction(button -> moveEntries(1))
                .addExtraAction(new AnAction("Locate in Project View", "Locate named project CMakeLists.txt in the Project view", AllIcons.Ide.External_link_arrow) {
                    @Override
                    public void actionPerformed(@NotNull AnActionEvent e) {
                        locateSelectedInProjectView();
                    }
                });

        JComponent toolbarPanel = decorator.createPanel();
        toolbarPanel.setBorder(JBUI.Borders.empty());

        JBSplitter splitter = new JBSplitter(false, 0.7f);
        splitter.setBorder(JBUI.Borders.empty());
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
                    if (sel != null) loadCMakeProject(sel);
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

    private void locateSelectedInProjectView() {
        CMakeMuxEntry sel = list.getSelectedValue();
        if (sel == null) {
            return;
        }
        String si = FileUtil.toSystemIndependentName(sel.getPath());
        String url = VfsUtilCore.pathToUrl(si);
        VirtualFile vf = VirtualFileManager.getInstance().findFileByUrl(url);
        if (vf == null) {
            // Do nothin' ...
            return;
        }
        PsiFile psi = PsiManager.getInstance(project).findFile(vf);
        if (psi != null) {
            ProjectView.getInstance(project).selectPsiElement(psi, true);
        } else {
            // Fallback selection by file
            ProjectView.getInstance(project).select(null, vf, true);
        }
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
        // Left-side separator line between the main list and regex panel + minimal inner padding
        p.setBorder(javax.swing.BorderFactory.createCompoundBorder(
                new SideBorder(JBColor.border(), SideBorder.LEFT),
                JBUI.Borders.empty(6)
        ));

        detailsTitleLabel = new JBLabel("Enable CMake Presets");
        Font base = detailsTitleLabel.getFont();
        detailsTitleLabel.setFont(base.deriveFont(Math.max(10f, base.getSize2D() - 1.0f)));
        JPanel titleWrap = new JPanel(new BorderLayout());
        titleWrap.setBorder(JBUI.Borders.empty());
        titleWrap.add(detailsTitleLabel, BorderLayout.NORTH);
        p.add(titleWrap, BorderLayout.NORTH);

        regexpModel = new DefaultListModel<>();
        regexpList = new JBList<>(regexpModel);
        regexpList.setVisibleRowCount(8);

        ToolbarDecorator decorator = ToolbarDecorator.createDecorator(regexpList)
                .setAddAction(e -> addRegex())
                .setEditAction(e -> editRegexp())
                .setRemoveAction(e -> removeRegex())
                .setMoveUpAction(e -> moveRegexp(-1))
                .setMoveDownAction(e -> moveRegexp(1));

        JComponent decoratorPanel = decorator.createPanel();
        decoratorPanel.setBorder(JBUI.Borders.empty());

        p.add(decoratorPanel, BorderLayout.CENTER);
        return p;
    }

    private void updateDetailsForSelection() {
        if (detailsTitleLabel == null || regexpModel == null) return; // UI not ready
        CMakeMuxEntry sel = list.getSelectedValue();
        String targetLabel = sel != null ? sel.getNickname() : "(none)";
        detailsTitleLabel.setText("Enable CMake Presets for " + targetLabel);

        regexpModel.clear();
        if (sel != null) {
            List<String> regs = sel.getRegexps();
            if (regs != null) {
                for (String r : regs) regexpModel.addElement(r);
            }
        }
    }

    private void addRegex() {
        CMakeMuxEntry sel = list.getSelectedValue();
        if (sel == null) return;
        String input = Messages.showInputDialog(project, "Enter regexp to enable presets:", "Add Preset Regex", Messages.getQuestionIcon());
        if (input == null) return;
        String trimmed = input.trim();
        if (trimmed.isEmpty()) return;

        // Update UI first so selection can be set to the new item
        regexpModel.addElement(trimmed);
        regexpList.setSelectedIndex(regexpModel.getSize() - 1);
        regexpList.ensureIndexIsVisible(regexpModel.getSize() - 1);

        // Persist without broadcasting entriesChanged
        persistRegexpModelToState(sel);
    }

    private void editRegexp() {
        CMakeMuxEntry sel = list.getSelectedValue();
        int idx = regexpList.getSelectedIndex();
        if (sel == null || idx < 0) return;

        String current = regexpModel.get(idx);
        String input = Messages.showInputDialog(project, "Edit regexp:", "Edit Enable Preset Regexp", Messages.getQuestionIcon(), current, null);
        if (input == null) return;
        String trimmed = input.trim();
        if (trimmed.isEmpty()) return;

        // Update UI first and keep selection
        regexpModel.set(idx, trimmed);
        regexpList.setSelectedIndex(idx);
        regexpList.ensureIndexIsVisible(idx);

        // Persist without broadcasting entriesChanged
        persistRegexpModelToState(sel);
    }

    private void removeRegex() {
        CMakeMuxEntry sel = list.getSelectedValue();
        int idx = regexpList.getSelectedIndex();
        if (sel == null || idx < 0) return;

        // Update UI first and restore the closest selection
        regexpModel.remove(idx);
        if (!regexpModel.isEmpty()) {
            int next = Math.min(idx, regexpModel.getSize() - 1);
            regexpList.setSelectedIndex(next);
            regexpList.ensureIndexIsVisible(next);
        }

        // Persist without broadcasting entriesChanged
        persistRegexpModelToState(sel);
    }

    private void refreshFromState() {
        // Preserve current selection by path, so p edits (which publish entriesChanged)
        // won't move focus or change the selected CMakeMux entry.
        String selectedPath = null;
        CMakeMuxEntry current = list.getSelectedValue();
        if (current != null) {
            selectedPath = current.getPath();
        }

        model.clear();
        List<CMakeMuxEntry> entries = new ArrayList<>(CMakeMuxService.getInstance(project).getEntries());
        for (CMakeMuxEntry e : entries) model.addElement(e);

        int toSelect = -1;
        if (selectedPath != null) {
            for (int i = 0; i < model.size(); i++) {
                if (FileUtil.pathsEqual(model.get(i).getPath(), selectedPath)) {
                    toSelect = i;
                    break;
                }
            }
        }
        if (toSelect >= 0) {
            list.setSelectedIndex(toSelect);
            list.ensureIndexIsVisible(toSelect);
        } else if (!model.isEmpty() && list.getSelectedIndex() == -1) {
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

    // Wrapper for moving regexpes and persisting on the selected entry
    private void moveRegexp(int delta) {
        moveSelected(regexpList, regexpModel, delta, () -> {
            // Selection is already moved by moveSelected; just persist the new order
            CMakeMuxEntry sel = list.getSelectedValue();
            persistRegexpModelToState(sel);
        });
    }

    private void doRename() {
        CMakeMuxEntry sel = list.getSelectedValue();
        if (sel == null) return;
        String newNick = Messages.showInputDialog(project,
                "New name:", "Rename Entry", Messages.getQuestionIcon(), sel.getNickname(), null);
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

    private void loadCMakeProject(@NotNull CMakeMuxEntry entry) {
        CMakeMuxLoader.loadEntry(project, entry);
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