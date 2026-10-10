package net.tagpad.cmakemux;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.TitledSeparator;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.Alarm;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBFont;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import java.awt.*;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Dialog for adding/editing a preset regexp and selecting what it is matched against.
 * When the entry's CMakeLists.txt is the loaded one, shows which of its presets the regexp matches while typing.
 */
public class CMakeMuxPresetFilterDialog extends DialogWrapper {
    // Delay before recomputing the matching presets after a change
    private static final int MATCH_DELAY_MS = 300;

    private final Project project;
    // Only the presets of the loaded CMake project are known, so matches are shown for its entries only
    private final boolean showMatches;
    private final JBTextField regexpField = new JBTextField(40);
    private final JBCheckBox matchNameBox = new JBCheckBox("Name");
    private final JBCheckBox matchDisplayNameBox = new JBCheckBox("Display name");
    private JLabel matchCountComment;
    private final DefaultListModel<String> matchModel = new DefaultListModel<>();
    private final Alarm matchAlarm;

    /**
     * @param entryPath the CMakeLists.txt of the entry the regexp belongs to; matching presets are only shown
     *                  when it is the loaded CMakeLists.txt
     */
    public CMakeMuxPresetFilterDialog(@NotNull Project project, @NotNull String title,
                                      @NotNull CMakeMuxPresetFilter initial, @Nullable String entryPath) {
        super(project);
        this.project = project;
        String loadedPath = CMakeMuxSelectionService.getInstance(project).getActivePath();
        this.showMatches = entryPath != null && loadedPath != null && FileUtil.pathsEqual(loadedPath, entryPath);
        this.matchAlarm = new Alarm(Alarm.ThreadToUse.SWING_THREAD, getDisposable());
        setTitle(title);
        regexpField.setText(initial.getRegexp() != null ? initial.getRegexp() : "");
        matchNameBox.setSelected(initial.isMatchName());
        matchDisplayNameBox.setSelected(initial.isMatchDisplayName());

        regexpField.getDocument().addDocumentListener(new DocumentAdapter() {
            @Override
            protected void textChanged(@NotNull DocumentEvent e) {
                scheduleMatchUpdate();
            }
        });
        matchNameBox.addItemListener(e -> scheduleMatchUpdate());
        matchDisplayNameBox.addItemListener(e -> scheduleMatchUpdate());

        init();
        initValidation();
        if (showMatches) updateMatches();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        // Layout following the IntelliJ Platform UI guidelines: labeled rows, comments below their component,
        // related checkboxes on one row and a titled separator for the preview group
        JPanel matchAgainstRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        matchAgainstRow.add(matchNameBox);
        matchAgainstRow.add(Box.createHorizontalStrut(JBUI.scale(UIUtil.DEFAULT_HGAP * 2)));
        matchAgainstRow.add(matchDisplayNameBox);

        // Fixed sizes, so changing content (long preset names, counts) never re-layouts the dialog;
        // otherwise the layout may fall back to the text field's minimum size
        Dimension fieldSize = regexpField.getPreferredSize();
        regexpField.setMinimumSize(fieldSize);

        FormBuilder form = FormBuilder.createFormBuilder()
                .addLabeledComponent("Regular expression:", regexpField)
                .addComponentToRightColumn(comment(
                        "Case-insensitive, matches anywhere in the name. Use ^...$ to match the whole name."), 0)
                .addLabeledComponent("Match against:", matchAgainstRow, UIUtil.LARGE_VGAP)
                .addComponentToRightColumn(comment(
                        "The preset name and/or the display name from CMakePresets.json."), 0);

        if (showMatches) {
            JBList<String> matchList = new JBList<>(matchModel);
            matchList.setEmptyText("No matching presets");
            JBScrollPane matchScroll = new JBScrollPane(matchList);
            Dimension listSize = new Dimension(fieldSize.width, JBUI.scale(120));
            matchScroll.setPreferredSize(listSize);
            matchScroll.setMinimumSize(listSize);
            matchCountComment = comment(" ");

            form.addComponent(new TitledSeparator("Matching Presets"), UIUtil.LARGE_VGAP)
                    .addComponent(matchScroll)
                    .addComponent(matchCountComment, 0);
        }
        return form.getPanel();
    }

    /** Comment (context help) below a component, styled like the platform's comments. */
    private static JLabel comment(String text) {
        JBLabel label = new JBLabel(text);
        label.setForeground(JBUI.CurrentTheme.ContextHelp.FOREGROUND);
        label.setFont(JBFont.medium());
        label.setBorder(JBUI.Borders.emptyTop(2));
        return label;
    }

    @Override
    public @Nullable JComponent getPreferredFocusedComponent() {
        return regexpField;
    }

    @Override
    protected @Nullable ValidationInfo doValidate() {
        String regexp = regexpField.getText().trim();
        if (regexp.isEmpty()) {
            return new ValidationInfo("Enter a regular expression", regexpField);
        }
        try {
            Pattern.compile(regexp);
        } catch (PatternSyntaxException e) {
            return new ValidationInfo("Invalid regular expression: " + e.getDescription(), regexpField);
        }
        if (!matchNameBox.isSelected() && !matchDisplayNameBox.isSelected()) {
            return new ValidationInfo("Select name, display name or both", matchDisplayNameBox);
        }
        return null;
    }

    public CMakeMuxPresetFilter getFilter() {
        return new CMakeMuxPresetFilter(regexpField.getText().trim(),
                matchNameBox.isSelected(), matchDisplayNameBox.isSelected());
    }

    private void scheduleMatchUpdate() {
        if (!showMatches) return;
        matchAlarm.cancelAllRequests();
        matchAlarm.addRequest(this::updateMatches, MATCH_DELAY_MS);
    }

    /** Show how many (and which) presets of the loaded CMake project the current input matches. */
    private void updateMatches() {
        matchModel.clear();
        List<CMakeMuxPresetInfo> presets = CMakeMuxPresetHandler.getInstance(project).getLoadedPresets();
        if (presets.isEmpty()) {
            matchCountComment.setText("No CMake presets are loaded.");
            return;
        }
        CMakeMuxPresetFilter filter = getFilter();
        CMakeMuxPresetMatcher matcher = CMakeMuxPresetMatcher.of(List.of(filter));
        if (!matcher.getInvalidRegexps().isEmpty() || filter.getRegexp().isEmpty()) {
            matchCountComment.setText(presets.size() + " presets loaded.");
            return;
        }
        int count = 0;
        for (CMakeMuxPresetInfo preset : presets) {
            if (matcher.matches(preset)) {
                matchModel.addElement(preset.label());
                count++;
            }
        }
        matchCountComment.setText(count + " of " + presets.size() + " presets match.");
    }
}
