package net.tagpad.cmakemux;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.Alarm;
import com.intellij.util.ui.FormBuilder;
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
    private final JBTextField regexpField = new JBTextField(30);
    private final JBCheckBox matchNameBox = new JBCheckBox("Name");
    private final JBCheckBox matchDisplayNameBox = new JBCheckBox("Display name");
    private final JBLabel matchCountLabel = new JBLabel();
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
        JPanel matchPanel = new JPanel();
        matchPanel.setLayout(new BoxLayout(matchPanel, BoxLayout.X_AXIS));
        matchPanel.add(matchNameBox);
        matchPanel.add(Box.createHorizontalStrut(JBUI.scale(12)));
        matchPanel.add(matchDisplayNameBox);

        JBLabel hint = new JBLabel("Case-insensitive, matches anywhere in the name. Use ^...$ to match the whole name.");
        hint.setComponentStyle(UIUtil.ComponentStyle.SMALL);
        hint.setFontColor(UIUtil.FontColor.BRIGHTER);

        // Fixed sizes, so changing content (long preset names, counts) never re-layouts the dialog;
        // otherwise the layout may fall back to the text field's minimum size
        Dimension fieldSize = regexpField.getPreferredSize();
        regexpField.setMinimumSize(fieldSize);

        FormBuilder form = FormBuilder.createFormBuilder()
                .addLabeledComponent("Regexp:", regexpField)
                .addComponentToRightColumn(hint)
                .addLabeledComponent("Match against:", matchPanel);

        if (showMatches) {
            JBList<String> matchList = new JBList<>(matchModel);
            matchList.setEmptyText("No matching presets");
            JBScrollPane matchScroll = new JBScrollPane(matchList);
            Dimension listSize = new Dimension(fieldSize.width, JBUI.scale(120));
            matchScroll.setPreferredSize(listSize);
            matchScroll.setMinimumSize(listSize);
            form.addLabeledComponent("Matching presets:", matchCountLabel)
                    .addComponentToRightColumn(matchScroll);
        }
        return form.getPanel();
    }

    @Override
    public @Nullable JComponent getPreferredFocusedComponent() {
        return regexpField;
    }

    @Override
    protected @Nullable ValidationInfo doValidate() {
        String regexp = regexpField.getText().trim();
        if (regexp.isEmpty()) {
            return new ValidationInfo("Enter a regexp", regexpField);
        }
        try {
            Pattern.compile(regexp);
        } catch (PatternSyntaxException e) {
            return new ValidationInfo("Invalid regexp: " + e.getDescription(), regexpField);
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
            matchCountLabel.setText("No CMake presets loaded");
            return;
        }
        CMakeMuxPresetFilter filter = getFilter();
        CMakeMuxPresetMatcher matcher = CMakeMuxPresetMatcher.of(List.of(filter));
        if (!matcher.getInvalidRegexps().isEmpty() || filter.getRegexp().isEmpty()) {
            matchCountLabel.setText("- of " + presets.size());
            return;
        }
        int count = 0;
        for (CMakeMuxPresetInfo preset : presets) {
            if (matcher.matches(preset)) {
                matchModel.addElement(preset.label());
                count++;
            }
        }
        matchCountLabel.setText(count + " of " + presets.size());
    }
}
