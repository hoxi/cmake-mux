package net.tagpad.cmakemux;

import com.intellij.util.xmlb.annotations.Attribute;
import com.intellij.util.xmlb.annotations.Tag;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/** Regexp for enabling CMake presets, matched against the profile name and/or display name. */
@Tag("filter")
public class CMakeMuxPresetFilter {
    @Attribute("regexp")
    private String regexp;

    @Attribute("matchName")
    private boolean matchName = true;

    @Attribute("matchDisplayName")
    private boolean matchDisplayName = true;

    // Required for XML serialization
    public CMakeMuxPresetFilter() {
    }

    public CMakeMuxPresetFilter(String regexp, boolean matchName, boolean matchDisplayName) {
        this.regexp = regexp;
        this.matchName = matchName;
        this.matchDisplayName = matchDisplayName;
    }

    public String getRegexp() {
        return regexp;
    }

    public void setRegexp(String regexp) {
        this.regexp = regexp;
    }

    public boolean isMatchName() {
        return matchName;
    }

    public void setMatchName(boolean matchName) {
        this.matchName = matchName;
    }

    public boolean isMatchDisplayName() {
        return matchDisplayName;
    }

    public void setMatchDisplayName(boolean matchDisplayName) {
        this.matchDisplayName = matchDisplayName;
    }

    public CMakeMuxPresetFilter copy() {
        return new CMakeMuxPresetFilter(regexp, matchName, matchDisplayName);
    }

    @Override
    public String toString() {
        return regexp;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) return true;
        if (!(o instanceof CMakeMuxPresetFilter that)) return false;
        return matchName == that.matchName
                && matchDisplayName == that.matchDisplayName
                && Objects.equals(regexp, that.regexp);
    }

    @Override
    public int hashCode() {
        return Objects.hash(regexp, matchName, matchDisplayName);
    }
}
