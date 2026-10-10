package net.tagpad.cmakemux;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;

/** Dialog asking for an entry name; names must be unique, an existing name marks the field as invalid. */
public class CMakeMuxNameDialog extends DialogWrapper {
    private final Project project;
    private final @Nullable String entryId;
    private final String label;
    private final JBTextField nameField = new JBTextField(30);

    /**
     * @param entryId id of the entry being renamed (its current name is allowed), or null for a new entry
     */
    public CMakeMuxNameDialog(@NotNull Project project, @NotNull String title, @NotNull String label,
                              @NotNull String initialName, @Nullable String entryId) {
        super(project);
        this.project = project;
        this.entryId = entryId;
        this.label = label;
        setTitle(title);
        nameField.setText(initialName);
        nameField.selectAll();
        init();
        initValidation();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        return FormBuilder.createFormBuilder()
                .addLabeledComponent(label, nameField, true)
                .getPanel();
    }

    @Override
    public @Nullable JComponent getPreferredFocusedComponent() {
        return nameField;
    }

    @Override
    protected @Nullable ValidationInfo doValidate() {
        String name = getEntryName();
        if (name.isEmpty()) {
            return new ValidationInfo("Enter a name", nameField);
        }
        if (CMakeMuxService.getInstance(project).isNicknameTaken(name, entryId)) {
            return new ValidationInfo("Name '" + name + "' already exists", nameField);
        }
        return null;
    }

    public @NotNull String getEntryName() {
        return nameField.getText().trim();
    }
}
