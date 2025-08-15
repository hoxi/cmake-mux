package net.tagpad.cmakemux;

import com.intellij.util.xmlb.annotations.Attribute;
import com.intellij.util.xmlb.annotations.Tag;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/** Simple model: nickname + absolute path to the CMakeLists.txt */
@Tag("entry")
public class CMakeMuxEntry {
    @Attribute("nickname")
    private String nickname;

    @Attribute("path")
    private String path;

    // Required for XML serialization
    public CMakeMuxEntry() {
    }

    public CMakeMuxEntry(String nickname, String path) {
        this.nickname = nickname;
        this.path = path;
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
        this.path = path;
    }

    @Override
    public String toString() {
        return nickname + " \u2014 " + path;
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