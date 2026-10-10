package net.tagpad.cmakemux;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Compiled preset regexps. Regexps are case-insensitive and match anywhere in the name and/or display name.
 * Invalid regexps and regexps matching neither name nor display name are skipped.
 */
public final class CMakeMuxPresetMatcher {

    private record CompiledFilter(Pattern pattern, boolean matchName, boolean matchDisplayName) {
        boolean matches(@Nullable String name, @Nullable String displayName) {
            return (matchName && name != null && pattern.matcher(name).find())
                    || (matchDisplayName && displayName != null && pattern.matcher(displayName).find());
        }
    }

    private final List<CompiledFilter> filters;
    private final List<String> invalidRegexps;

    private CMakeMuxPresetMatcher(List<CompiledFilter> filters, List<String> invalidRegexps) {
        this.filters = filters;
        this.invalidRegexps = invalidRegexps;
    }

    public static @NotNull CMakeMuxPresetMatcher of(@NotNull List<CMakeMuxPresetFilter> presetFilters) {
        List<CompiledFilter> filters = new ArrayList<>();
        List<String> invalid = new ArrayList<>();
        for (CMakeMuxPresetFilter f : presetFilters) {
            if (f == null) continue;
            String regex = f.getRegexp();
            if (regex == null || regex.isBlank()) continue;
            if (!f.isMatchName() && !f.isMatchDisplayName()) continue;
            try {
                filters.add(new CompiledFilter(Pattern.compile(regex, Pattern.CASE_INSENSITIVE),
                        f.isMatchName(), f.isMatchDisplayName()));
            } catch (PatternSyntaxException e) {
                invalid.add(regex);
            }
        }
        return new CMakeMuxPresetMatcher(List.copyOf(filters), List.copyOf(invalid));
    }

    /** True if there are no usable regexps, i.e. nothing matches. */
    public boolean isEmpty() {
        return filters.isEmpty();
    }

    public @NotNull List<String> getInvalidRegexps() {
        return invalidRegexps;
    }

    public boolean matches(@Nullable String name, @Nullable String displayName) {
        for (CompiledFilter f : filters) {
            if (f.matches(name, displayName)) return true;
        }
        return false;
    }

    public boolean matches(@NotNull CMakeMuxPresetInfo preset) {
        return matches(preset.name(), preset.displayName());
    }
}
