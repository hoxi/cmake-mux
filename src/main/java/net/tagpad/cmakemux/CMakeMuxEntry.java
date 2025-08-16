package net.tagpad.cmakemux;

import com.intellij.openapi.util.io.FileUtil;
import com.intellij.util.xmlb.annotations.Attribute;
import com.intellij.util.xmlb.annotations.Tag;
import com.intellij.util.xmlb.annotations.XCollection;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Simple model: nickname + absolute path to the CMakeLists.txt */
@Tag("entry")
public class CMakeMuxEntry {
    @Attribute("nickname")
    private String nickname;

    @Attribute("path")
    private String path;

    // Store default regexes for enabling CMake presets (per entry/target)
    private List<String> regexes = new ArrayList<>();

    // Required for XML serialization
    public CMakeMuxEntry() {
    }

    public CMakeMuxEntry(String nickname, String path) {
        this.nickname = nickname;
        this.path = FileUtil.toSystemIndependentName(path);
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

    @Tag("regexps")
    @XCollection(style = XCollection.Style.v2, elementName = "re")
    public List<String> getRegexes() {
        return regexes;
    }

    public void setRegexes(List<String> regexes) {
        this.regexes = (regexes == null) ? new ArrayList<>() : new ArrayList<>(regexes);
    }

    @Override
    public String toString() {
        return nickname;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) return true;
        if (!(o instanceof CMakeMuxEntry)) return false;
        CMakeMuxEntry that = (CMakeMuxEntry) o;
        return Objects.equals(path, that.path);
    }

    @Override
    public int hashCode() {
        return Objects.hash(path);
    }
}