package net.tagpad.cmakemux;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Dialog for adding/editing a preset regexp and selecting what it is matched against. */
public class CMakeMuxPresetFilterDialog extends DialogWrapper {
    private final JBTextField regexpField = new JBTextField(30);
    private final JBCheckBox matchNameBox = new JBCheckBox("Name");
    private final JBCheckBox matchDisplayNameBox = new JBCheckBox("Display name");

    public CMakeMuxPresetFilterDialog(@NotNull Project project, @NotNull String title, @NotNull CMakeMuxPresetFilter initial) {
        super(project);
        setTitle(title);
        regexpField.setText(initial.getRegexp() != null ? initial.getRegexp() : "");
        matchNameBox.setSelected(initial.isMatchName());
        matchDisplayNameBox.setSelected(initial.isMatchDisplayName());
        init();
        initValidation();
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

        return FormBuilder.createFormBuilder()
                .addLabeledComponent("Regexp:", regexpField)
                .addComponentToRightColumn(hint)
                .addLabeledComponent("Match against:", matchPanel)
                .getPanel();
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
}
