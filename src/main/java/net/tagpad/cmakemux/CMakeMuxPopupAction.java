package net.tagpad.cmakemux;

import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.ui.popup.ListPopup;
import com.intellij.openapi.ui.popup.JBPopupListener;
import com.intellij.openapi.ui.popup.LightweightWindowEvent;
import com.intellij.ui.popup.list.ListPopupImpl;
import org.jetbrains.annotations.NotNull;
import com.intellij.openapi.project.DumbAwareAction;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

public class CMakeMuxPopupAction extends AnAction implements DumbAware {

    // Keep a strong reference while popup is shown; clear on close
    private static ListPopup lastPopup = null;

    @Override
    public void actionPerformed(AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) return;

        // If a popup is already visible, advance selection and return
        if (lastPopup != null && lastPopup.isVisible()) {
            advanceSelection(lastPopup);
            return;
        }

        List<CMakeMuxEntry> entries = new ArrayList<>(CMakeMuxService.getInstance(project).getEntries());
        if (entries.isEmpty()) {
            Messages.showInfoMessage(project, "No entries in CMake Mux.", "CMake Mux - Load Project");
            return;
        }

        int max = Math.min(9, entries.size());
        DefaultActionGroup group = new DefaultActionGroup();

        CMakeMuxSelectionService selection = CMakeMuxSelectionService.getInstance(project);

        // Track the action that corresponds to the active entry to preselect it in the popup,
        // falling back to the first entry for the loaded CMakeLists.txt
        final AnAction[] activeActionRef = new AnAction[1];
        AnAction loadedAction = null;

        for (int i = 0; i < max; i++) {
            final int index = i;
            CMakeMuxEntry entry = entries.get(i);
            String title = entry.getNickname() != null && !entry.getNickname().isEmpty()
                    ? entry.getNickname()
                    : entry.getPath();

            CMakeMuxSelectionService.ActiveState state = selection.getState(entry);
            Icon icon = CMakeMuxIcons.forState(state);

            AnAction action = new AnAction(title, CMakeMuxIcons.tooltip(entry, state), icon) {
                @Override
                public void actionPerformed(@NotNull AnActionEvent ignored) {
                    CMakeMuxLoader.loadEntry(project, entries.get(index));
                }
            };
            group.add(action);
            if (state == CMakeMuxSelectionService.ActiveState.ACTIVE && activeActionRef[0] == null) {
                activeActionRef[0] = action;
            }
            if (state == CMakeMuxSelectionService.ActiveState.CMAKELISTS_LOADED && loadedAction == null) {
                loadedAction = action;
            }
        }
        if (activeActionRef[0] == null) activeActionRef[0] = loadedAction;

        ListPopup popup = JBPopupFactory.getInstance()
                .createActionGroupPopup(
                        "CMake Mux - Load Project",
                        group,
                        e.getDataContext(),
                        true,   // showNumbers
                        true,   // showDisabledActions
                        true,   // autoDispose
                        null,
                        max,    // maxRowCount
                        action -> action == activeActionRef[0] // preselect active (first one if several)
                );

        // Install the same shortcut inside the popup to cycle selection on repeated presses
        installCyclingShortcut(popup);

        // Remember popup while shown; clear when closed
        lastPopup = popup;
        popup.addListener(new JBPopupListener() {
            @Override public void onClosed(@NotNull LightweightWindowEvent event) {
                if (lastPopup == popup) lastPopup = null;
            }
        });

        popup.showInBestPositionFor(e.getDataContext());
    }

    // Bind this action's shortcut to the popup so repeated presses advance selection
    private void installCyclingShortcut(@NotNull ListPopup popup) {
        AnAction original = ActionManager.getInstance().getAction("net.tagpad.cmakemux.CMakeMuxPopupAction");
        if (original == null) return;
        ShortcutSet shortcutSet = original.getShortcutSet();

        AnAction cycleAction = new DumbAwareAction() {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                if (lastPopup != null && lastPopup.isVisible()) {
                    advanceSelection(lastPopup);
                }
            }
        };
        JComponent content = popup.getContent();
        cycleAction.registerCustomShortcutSet(shortcutSet, content);
    }

    // Emulate pressing Arrow Down on the active popup list (with wrap-around)
    private void advanceSelection(@NotNull ListPopup popup) {
        // Prefer direct access via the implementation class
        if (popup instanceof ListPopupImpl impl) {
            JList<?> list = impl.getList();
            if (list != null) {
                moveListSelectionDown(list);
                return;
            }
        }
        // Fallback: search for the list in the popup content
        JComponent content = popup.getContent();
        JList<?> list = findFirstJList(content);
        if (list != null) {
            moveListSelectionDown(list);
        }
    }

    private void moveListSelectionDown(@NotNull JList<?> list) {
        int size = list.getModel().getSize();
        if (size <= 0) return;
        int current = Math.max(0, list.getSelectedIndex());
        int next = (current + 1) % size;
        list.setSelectedIndex(next);
        list.ensureIndexIsVisible(next);
    }

    private JList<?> findFirstJList(Component root) {
        if (root instanceof JList<?> jList) return jList;
        if (root instanceof Container container) {
            for (Component child : container.getComponents()) {
                JList<?> found = findFirstJList(child);
                if (found != null) return found;
            }
        }
        return null;
    }
}