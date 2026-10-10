package net.tagpad.cmakemux;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.NlsContexts;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.wm.StatusBar;
import com.intellij.openapi.wm.StatusBarWidget;
import com.intellij.openapi.wm.StatusBarWidgetFactory;
import com.intellij.util.Consumer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.*;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Status bar widget showing the active CMake Mux entry (yellow arrow).
 * Only available while there is an active entry; CMakeMuxSelectionService updates it when the state changes.
 */
public class CMakeMuxStatusBarWidgetFactory implements StatusBarWidgetFactory {
    static final String ID = "CMakeMuxStatus";

    @Override
    public @NotNull String getId() {
        return ID;
    }

    @Override
    public @NlsContexts.ConfigurableName @NotNull String getDisplayName() {
        return "CMake Mux Active Project";
    }

    @Override
    public boolean isAvailable(@NotNull Project project) {
        return !CMakeMuxSelectionService.getInstance(project).getActiveEntries().isEmpty();
    }

    @Override
    public @NotNull StatusBarWidget createWidget(@NotNull Project project) {
        return new Widget(project);
    }

    private static final class Widget implements StatusBarWidget, StatusBarWidget.TextPresentation {
        private final Project project;

        Widget(Project project) {
            this.project = project;
        }

        @Override
        public @NotNull String ID() {
            return ID;
        }

        @Override
        public @Nullable WidgetPresentation getPresentation() {
            return this;
        }

        @Override
        public void install(@NotNull StatusBar statusBar) {
            // The selection service fires on every state change; refresh the text (renames, other active entry)
            project.getMessageBus().connect(this).subscribe(CMakeMuxSelectionEvents.TOPIC,
                    (CMakeMuxSelectionEvents) () -> statusBar.updateWidget(ID));
        }

        @Override
        public @NotNull String getText() {
            return activeNames();
        }

        @Override
        public float getAlignment() {
            return Component.CENTER_ALIGNMENT;
        }

        @Override
        public @Nullable String getTooltipText() {
            List<CMakeMuxEntry> active = CMakeMuxSelectionService.getInstance(project).getActiveEntries();
            if (active.isEmpty()) return null;
            return "<html>" + active.stream()
                    .map(Widget::describe)
                    .collect(Collectors.joining("<br><br>")) + "</html>";
        }

        @Override
        public @Nullable Consumer<MouseEvent> getClickConsumer() {
            return e -> CMakeMuxToolWindowFactory.activate(project);
        }

        /** Full information about the entry for the tooltip. */
        private static String describe(CMakeMuxEntry e) {
            return "<b>CMake Mux: " + StringUtil.escapeXmlEntities(e.getNickname()) + "</b><br>"
                    + StringUtil.escapeXmlEntities(e.getPath());
        }

        private String activeNames() {
            return CMakeMuxSelectionService.getInstance(project).getActiveEntries().stream()
                    .map(CMakeMuxEntry::getNickname)
                    .collect(Collectors.joining(", "));
        }

        @Override
        public void dispose() {
            // Message bus connection is disposed with this widget
        }
    }
}
