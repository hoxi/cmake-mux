package net.tagpad.cmakemux;

import com.intellij.icons.AllIcons;
import com.intellij.ide.projectView.ProjectView;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.ui.ColoredListCellRenderer;
import com.intellij.ui.JBColor;
import com.intellij.ui.JBSplitter;
import com.intellij.ui.SideBorder;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.ToolbarDecorator;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBList;
import com.intellij.util.ui.JBFont;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

/** UI for the tool window: list of pinned projects (load on double-click) and the preset regexps of the selected one. */
public class CMakeMuxPanel extends JPanel implements Disposable {

    private final Project project;
    private final JBList<CMakeMuxEntry> list;
    private final DefaultListModel<CMakeMuxEntry> model;

    // Details panel components
    private JBLabel detailsNameLabel;
    private JBList<CMakeMuxPresetFilter> regexpList;
    private DefaultListModel<CMakeMuxPresetFilter> regexpModel;

    private List<CMakeMuxPresetFilter> snapshotRegexModel() {
        List<CMakeMuxPresetFilter> regs = new ArrayList<>();
        for (int i = 0; i < regexpModel.size(); i++) regs.add(regexpModel.get(i).copy());
        return regs;
    }

    private void persistRegexpModelToState(CMakeMuxEntry selectedEntry) {
        if (selectedEntry == null) return;
        List<CMakeMuxPresetFilter> regs = snapshotRegexModel();
        CMakeMuxEntry e = CMakeMuxState.getInstance(project).findById(selectedEntry.getId());
        if (e != null) e.setPresetFilters(regs);
        // Keep the in-memory selected entry in sync
        selectedEntry.setPresetFilters(regs);
        // The regexps decide whether the entry is the active one
        CMakeMuxSelectionService.getInstance(project).scheduleRefresh();
    }

    public CMakeMuxPanel(@NotNull Project project) {
        super(new BorderLayout());
        this.project = project;
        this.model = new DefaultListModel<>();
        this.list = new JBList<>(model);
        this.list.setCellRenderer(new EntryRenderer(CMakeMuxSelectionService.getInstance(project)::getState));
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

        // Double-click on an entry to load its CMake project
        list.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (isDoubleClickOnRow(list, e)) {
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

        // Two rows: a caption and the (emphasized) entry name. Both may shrink (the name is then truncated with
        // "..." and shown in full in the tooltip), so a long name does not force a wider panel.
        JBLabel caption = new JBLabel("Enable CMake presets for");
        caption.setComponentStyle(UIUtil.ComponentStyle.SMALL);
        caption.setFontColor(UIUtil.FontColor.BRIGHTER);
        caption.setMinimumSize(new Dimension(0, caption.getPreferredSize().height));

        detailsNameLabel = new JBLabel("(none)");
        detailsNameLabel.setFont(JBFont.label().asBold());
        detailsNameLabel.setMinimumSize(new Dimension(0, detailsNameLabel.getPreferredSize().height));

        JPanel titleWrap = new JPanel(new BorderLayout());
        titleWrap.setBorder(JBUI.Borders.emptyBottom(4));
        titleWrap.add(caption, BorderLayout.NORTH);
        titleWrap.add(detailsNameLabel, BorderLayout.SOUTH);
        p.add(titleWrap, BorderLayout.NORTH);

        regexpModel = new DefaultListModel<>();
        regexpList = new JBList<>(regexpModel);
        regexpList.setVisibleRowCount(8);
        regexpList.setCellRenderer(new PresetFilterRenderer());

        // Double-click to edit, same as the edit (pen) button
        regexpList.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (isDoubleClickOnRow(regexpList, e)) {
                    editRegexp();
                }
            }
        });

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
        if (detailsNameLabel == null || regexpModel == null) return; // UI not ready
        CMakeMuxEntry sel = list.getSelectedValue();
        String targetLabel = sel != null ? sel.getNickname() : "(none)";
        detailsNameLabel.setText(targetLabel);
        detailsNameLabel.setToolTipText(sel != null ? sel.getNickname() + " - " + sel.getPath() : null);

        regexpModel.clear();
        if (sel != null) {
            List<CMakeMuxPresetFilter> regs = sel.getPresetFilters();
            if (regs != null) {
                for (CMakeMuxPresetFilter r : regs) regexpModel.addElement(r.copy());
            }
        }
    }

    private void addRegex() {
        CMakeMuxEntry sel = list.getSelectedValue();
        if (sel == null) return;
        CMakeMuxPresetFilterDialog dialog = new CMakeMuxPresetFilterDialog(project, "Add Preset Regular Expression",
                new CMakeMuxPresetFilter("", true, true), sel.getPath());
        if (!dialog.showAndGet()) return;

        // Update UI first so selection can be set to the new item
        regexpModel.addElement(dialog.getFilter());
        regexpList.setSelectedIndex(regexpModel.getSize() - 1);
        regexpList.ensureIndexIsVisible(regexpModel.getSize() - 1);

        // Persist without broadcasting entriesChanged
        persistRegexpModelToState(sel);
    }

    private void editRegexp() {
        CMakeMuxEntry sel = list.getSelectedValue();
        int idx = regexpList.getSelectedIndex();
        if (sel == null || idx < 0) return;

        CMakeMuxPresetFilterDialog dialog = new CMakeMuxPresetFilterDialog(project, "Edit Preset Regular Expression",
                regexpModel.get(idx), sel.getPath());
        if (!dialog.showAndGet()) return;

        // Update UI first and keep selection
        regexpModel.set(idx, dialog.getFilter());
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
        // Preserve current selection by id, so p edits (which publish entriesChanged)
        // won't move focus or change the selected CMakeMux entry.
        String selectedId = null;
        CMakeMuxEntry current = list.getSelectedValue();
        if (current != null) {
            selectedId = current.getId();
        }

        model.clear();
        List<CMakeMuxEntry> entries = new ArrayList<>(CMakeMuxService.getInstance(project).getEntries());
        for (CMakeMuxEntry e : entries) model.addElement(e);

        int toSelect = -1;
        if (selectedId != null) {
            for (int i = 0; i < model.size(); i++) {
                if (selectedId.equals(model.get(i).getId())) {
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
            List<CMakeMuxEntry> stateList = CMakeMuxState.getInstance(project).getEntries();
            stateList.clear();
            stateList.addAll(newOrder);
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
        CMakeMuxNameDialog dialog = new CMakeMuxNameDialog(project, "Rename Entry", "New name:",
                sel.getNickname() != null ? sel.getNickname() : "", sel.getId());
        if (!dialog.showAndGet()) return;
        sel.setNickname(dialog.getEntryName());
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
            CMakeMuxService.getInstance(project).removeById(sel.getId());
            model.removeElement(sel);
            updateDetailsForSelection();
        }
    }

    @Override
    public void dispose() { /* disposed with content */ }

    private static class EntryRenderer extends DefaultListCellRenderer {
        private final java.util.function.Function<CMakeMuxEntry, CMakeMuxSelectionService.ActiveState> stateOf;

        EntryRenderer(java.util.function.Function<CMakeMuxEntry, CMakeMuxSelectionService.ActiveState> stateOf) {
            this.stateOf = stateOf;
        }

        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            if (value instanceof CMakeMuxEntry e) {
                setText(e.toString());
                CMakeMuxSelectionService.ActiveState state = stateOf.apply(e);
                setToolTipText(CMakeMuxIcons.tooltip(e, state));
                setBorder(JBUI.Borders.empty(2, 6));

                setIcon(CMakeMuxIcons.forState(state));
                setFont(getFont().deriveFont(Font.PLAIN));
            }
            return this;
        }
    }

    private static class PresetFilterRenderer extends ColoredListCellRenderer<CMakeMuxPresetFilter> {
        @Override
        protected void customizeCellRenderer(@NotNull JList<? extends CMakeMuxPresetFilter> list, CMakeMuxPresetFilter value,
                                             int index, boolean selected, boolean hasFocus) {
            if (value == null) return;
            append(value.getRegexp() != null ? value.getRegexp() : "");
            String target;
            if (value.isMatchName() && value.isMatchDisplayName()) target = "name, display name";
            else if (value.isMatchName()) target = "name";
            else if (value.isMatchDisplayName()) target = "display name";
            else target = "nothing";
            append("  " + target, SimpleTextAttributes.GRAYED_ATTRIBUTES);
            setToolTipText("Matched against: " + target);
        }
    }

    /** Left double-click on a row; locationToIndex alone also returns the last row for clicks below it. */
    private static boolean isDoubleClickOnRow(JList<?> jList, MouseEvent e) {
        if (e.getClickCount() != 2 || !SwingUtilities.isLeftMouseButton(e)) return false;
        int idx = jList.locationToIndex(e.getPoint());
        if (idx < 0) return false;
        Rectangle bounds = jList.getCellBounds(idx, idx);
        return bounds != null && bounds.contains(e.getPoint());
    }

    private void loadCMakeProject(@NotNull CMakeMuxEntry entry) {
        CMakeMuxLoader.loadEntry(project, entry);
    }

    // Best-effort detection of current CMakeLists when the panel loads
    private void initializeActiveSelectionIfMissing() {
        if (CMakeMuxSelectionService.getInstance(project).getActivePath() != null) return;

        // Perform a few delayed attempts since CMake workspace might initialize a bit later.
        CMakeMuxActiveDetector.detectAndSetActiveBestEffort(project, 3, 500);
    }
}