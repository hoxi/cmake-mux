package net.tagpad.cmakemux;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/** Simple model: nickname + absolute path to the CMakeLists.txt */
public class CMakeMuxEntry {
    private String nickname;
    private String path; // absolute filesystem path

    public CMakeMuxEntry() { }

    public CMakeMuxEntry(@NotNull String nickname, @NotNull String path) {
        this.nickname = nickname;
        this.path = path;
    }

    @NotNull
    public String getNickname() { return nickname; }

    public void setNickname(@NotNull String nickname) { this.nickname = nickname; }

    @NotNull
    public String getPath() { return path; }

    public void setPath(@NotNull String path) { this.path = path; }

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