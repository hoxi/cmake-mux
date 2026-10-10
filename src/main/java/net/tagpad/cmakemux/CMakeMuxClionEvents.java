package net.tagpad.cmakemux;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.util.messages.MessageBusConnection;
import com.intellij.util.messages.Topic;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.function.BiConsumer;

/**
 * Reflective subscription to CLion's (internal) CMake message bus topics.
 * The listener receives the name of the invoked listener method and its arguments.
 */
final class CMakeMuxClionEvents {
    private static final Logger LOG = Logger.getInstance(CMakeMuxClionEvents.class);

    static final String SETTINGS_LISTENER = "com.jetbrains.cidr.cpp.cmake.CMakeSettingsListener";
    static final String WORKSPACE_LISTENER = "com.jetbrains.cidr.cpp.cmake.workspace.CMakeWorkspaceListener";

    private CMakeMuxClionEvents() {}

    /** CMakeSettingsListener (profilesChanged); the topic is on its Kotlin companion. */
    static boolean subscribeSettings(@NotNull MessageBusConnection connection,
                                     @NotNull BiConsumer<String, Object[]> listener) {
        try {
            Class<?> listenerClass = Class.forName(SETTINGS_LISTENER);
            Object companion = listenerClass.getField("Companion").get(null);
            Method getTopic = companion.getClass().getMethod("getTOPIC");
            return subscribe(connection, listenerClass, getTopic.invoke(companion), listener);
        } catch (Throwable t) {
            LOG.warn("[CMakeMux] Failed to subscribe to " + SETTINGS_LISTENER, t);
            return false;
        }
    }

    /** CMakeWorkspaceListener (reloadingStarted, reloadingFinished, ...); the topic is a static field. */
    static boolean subscribeWorkspace(@NotNull MessageBusConnection connection,
                                      @NotNull BiConsumer<String, Object[]> listener) {
        try {
            Class<?> listenerClass = Class.forName(WORKSPACE_LISTENER);
            return subscribe(connection, listenerClass, listenerClass.getField("TOPIC").get(null), listener);
        } catch (Throwable t) {
            LOG.warn("[CMakeMux] Failed to subscribe to " + WORKSPACE_LISTENER, t);
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private static boolean subscribe(MessageBusConnection connection, Class<?> listenerClass, Object topic,
                                     BiConsumer<String, Object[]> listener) {
        if (!(topic instanceof Topic)) {
            LOG.warn("[CMakeMux] No message bus topic found for " + listenerClass.getName());
            return false;
        }
        Object proxy = Proxy.newProxyInstance(
                listenerClass.getClassLoader(),
                new Class<?>[]{listenerClass},
                (self, method, args) -> {
                    switch (method.getName()) {
                        case "equals":
                            return self == args[0];
                        case "hashCode":
                            return System.identityHashCode(self);
                        case "toString":
                            return "CMakeMux " + listenerClass.getSimpleName();
                        default:
                            listener.accept(method.getName(), args != null ? args : new Object[0]);
                            return null;
                    }
                });
        connection.subscribe((Topic<Object>) topic, proxy);
        return true;
    }
}
