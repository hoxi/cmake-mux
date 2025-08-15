package net.tagpad.cmakemux;

import com.intellij.util.messages.Topic;

public interface CMakeMuxEvents {
    Topic<CMakeMuxEvents> TOPIC = Topic.create("CMakeMux entries changed", CMakeMuxEvents.class);

    void entriesChanged();
}
