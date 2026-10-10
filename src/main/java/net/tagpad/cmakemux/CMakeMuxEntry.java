package net.tagpad.cmakemux;

import com.intellij.openapi.util.io.FileUtil;
import com.intellij.util.xmlb.annotations.Attribute;
import com.intellij.util.xmlb.annotations.Tag;
import com.intellij.util.xmlb.annotations.XCollection;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Simple model: nickname + absolute path to the CMakeLists.txt. Several entries may share the same path. */
@Tag("entry")
public class CMakeMuxEntry {
    // Stable identity, the path is not unique and the nickname can be renamed
    @Attribute("id")
    private String id;

    @Attribute("nickname")
    private String nickname;

    @Attribute("path")
    private String path;

    // Regexps for enabling CMake presets (per entry/target)
    private List<CMakeMuxPresetFilter> presetFilters = new ArrayList<>();

    // Legacy format (plain regexps matched against the profile name), migrated to presetFilters on load
    private List<String> regexps = new ArrayList<>();

    // Required for XML serialization
    public CMakeMuxEntry() {
    }

    public CMakeMuxEntry(String nickname, String path) {
        this.id = UUID.randomUUID().toString();
        this.nickname = nickname;
        this.path = FileUtil.toSystemIndependentName(path);
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    /** Entries stored by older versions have no id. */
    public void ensureId() {
        if (id == null || id.isEmpty()) id = UUID.randomUUID().toString();
    }

    public String getNickname() {
        return nickname;
    }

    public void setNickname(String nickname) {
        this.nickname = nickname;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = FileUtil.toSystemIndependentName(path);
    }

    @Tag("presetFilters")
    @XCollection(style = XCollection.Style.v2)
    public List<CMakeMuxPresetFilter> getPresetFilters() {
        return presetFilters;
    }

    public void setPresetFilters(List<CMakeMuxPresetFilter> presetFilters) {
        this.presetFilters = (presetFilters == null) ? new ArrayList<>() : new ArrayList<>(presetFilters);
    }

    /** @deprecated legacy storage, only kept to read old configurations. Use {@link #getPresetFilters()}. */
    @Deprecated
    @Tag("regexps")
    @XCollection(style = XCollection.Style.v2, elementName = "re")
    public List<String> getRegexps() {
        return regexps;
    }

    /** @deprecated legacy storage, only kept to read old configurations. Use {@link #setPresetFilters(List)}. */
    @Deprecated
    public void setRegexps(List<String> regexps) {
        this.regexps = (regexps == null) ? new ArrayList<>() : new ArrayList<>(regexps);
    }

    /** Converts legacy regexps to preset filters. They used to be matched against the profile name only. */
    public void migrateLegacyRegexps() {
        if (presetFilters == null) presetFilters = new ArrayList<>();
        if (regexps == null || regexps.isEmpty()) return;
        for (String re : regexps) {
            if (re != null && !re.isBlank()) presetFilters.add(new CMakeMuxPresetFilter(re, true, false));
        }
        regexps = new ArrayList<>();
    }

    @Override
    public String toString() {
        return nickname;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) return true;
        if (!(o instanceof CMakeMuxEntry that)) return false;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}