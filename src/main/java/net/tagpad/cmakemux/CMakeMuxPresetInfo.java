package net.tagpad.cmakemux;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Snapshot of a CMake profile in CLion, independent of CLion's (internal) classes. */
public record CMakeMuxPresetInfo(@Nullable String name, @Nullable String displayName, boolean enabled,
                                 boolean fromPreset) {

    /** Name for display: the display name, with the name added when they differ. */
    public @NotNull String label() {
        if (displayName == null || displayName.isEmpty()) return name != null ? name : "";
        if (name == null || name.isEmpty() || name.equals(displayName)) return displayName;
        return displayName + " (" + name + ")";
    }
}
