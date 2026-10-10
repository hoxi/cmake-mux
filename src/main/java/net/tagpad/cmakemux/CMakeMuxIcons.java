package net.tagpad.cmakemux;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.util.IconLoader;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;

/** Icons and tooltips for entries depending on their active state. */
final class CMakeMuxIcons {
    /** Yellow arrow: CMakeLists.txt loaded and the enabled presets match the entry's regexps. */
    static final Icon ACTIVE = AllIcons.Debugger.NextStatement;
    /** Gray arrow: only the CMakeLists.txt is known to be loaded. */
    static final Icon CMAKELISTS_LOADED = IconLoader.getDisabledIcon(AllIcons.Debugger.NextStatement);
    static final Icon INACTIVE = AllIcons.Actions.ProjectDirectory;

    private CMakeMuxIcons() {}

    static @NotNull Icon forState(@NotNull CMakeMuxSelectionService.ActiveState state) {
        return switch (state) {
            case ACTIVE -> ACTIVE;
            case CMAKELISTS_LOADED -> CMAKELISTS_LOADED;
            case NONE -> INACTIVE;
        };
    }

    static @NotNull String tooltip(@NotNull CMakeMuxEntry entry, @NotNull CMakeMuxSelectionService.ActiveState state) {
        return switch (state) {
            case ACTIVE -> entry.getPath() + " (loaded, presets match)";
            case CMAKELISTS_LOADED -> entry.getPath() + " (loaded, presets differ or unknown)";
            case NONE -> entry.getPath();
        };
    }
}
