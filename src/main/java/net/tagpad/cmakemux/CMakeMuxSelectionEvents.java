package net.tagpad.cmakemux;

import com.intellij.util.messages.Topic;

public interface CMakeMuxSelectionEvents {
    Topic<CMakeMuxSelectionEvents> TOPIC =
            Topic.create("CMakeMux active selection changed", CMakeMuxSelectionEvents.class);

    void activeSelectionChanged();
}
