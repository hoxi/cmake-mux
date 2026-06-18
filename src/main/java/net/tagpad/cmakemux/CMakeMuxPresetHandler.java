package net.tagpad.cmakemux;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Internal-API based helper to enable all imported CMake profiles
 * whose names match any of the provided regex patterns.
 * "Enable presets" in CLion is implemented via enabling the imported
 * read-only CMake profiles that correspond to those presets.
 * This code is intentionally reflective and defensive to survive across CLion changes,
 * but it is still fragile by nature. These APIs are however present since long time and probably
 * will not change very often.
 */
public final class CMakeMuxPresetHandler {
    private static final Logger LOG = Logger.getInstance(CMakeMuxPresetHandler.class);

    public static void enableMatchingPresets(@NotNull Project project, @NotNull List<String> regexes) {
        if (regexes.isEmpty()) return;

        List<Pattern> patterns = regexes.stream()
                .map(p -> Pattern.compile(p, Pattern.CASE_INSENSITIVE))
                .collect(Collectors.toList());

        // Subscribe to CMakeSettingsListener.profilesChanged to act exactly when
        // the preset loader finishes importing new profiles after the CMakeLists switch.
        subscribeAndEnableOnChange(project, patterns);
    }

    @SuppressWarnings("unchecked")
    private static void subscribeAndEnableOnChange(Project project, List<Pattern> patterns) {
        try {
            Class<?> listenerClass = Class.forName("com.jetbrains.cidr.cpp.cmake.CMakeSettingsListener");
            Class<?> companionClass = Class.forName("com.jetbrains.cidr.cpp.cmake.CMakeSettingsListener$Companion");
            Field companionField = listenerClass.getField("Companion");
            Object companion = companionField.get(null);
            Method getTopic = findMethod(companionClass, "getTOPIC");
            if (getTopic == null) {
                LOG.warn("[CMakeMux] CMakeSettingsListener.Companion.getTOPIC() not found, bail out.");
                return;
            }
            Object topic = getTopic.invoke(companion);

            // Create a one-shot listener proxy
            Object listener = java.lang.reflect.Proxy.newProxyInstance(
                    listenerClass.getClassLoader(),
                    new Class<?>[]{listenerClass},
                    (proxy, method, args) -> {
                        if ("profilesChanged".equals(method.getName())) {
                            LOG.info("[CMakeMux] profilesChanged event received, enabling matching profiles.");
                            ApplicationManager.getApplication().invokeLater(() -> {
                                try {
                                    int count = enableMatchingImportedProfiles(project, patterns);
                                    LOG.info("[CMakeMux] Enabled " + count + " CMake profiles (from presets) by regex.");
                                } catch (Throwable t) {
                                    LOG.warn("[CMakeMux] Failed to enable presets after profilesChanged", t);
                                }
                            });
                        }
                        if ("equals".equals(method.getName())) return proxy == args[0];
                        if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                        if ("toString".equals(method.getName())) return "CMakeMuxSettingsListener";
                        return null;
                    }
            );

            // Subscribe via the message bus connection
            com.intellij.util.messages.MessageBusConnection connection =
                    project.getMessageBus().connect();
            connection.subscribe((com.intellij.util.messages.Topic<Object>) topic, listener);

            // Timeout: disconnect after 10s if no event fires (fallback to direct enable)
            com.intellij.util.Alarm alarm = new com.intellij.util.Alarm(com.intellij.util.Alarm.ThreadToUse.SWING_THREAD, connection);
            alarm.addRequest(() -> {
                connection.disconnect();
                LOG.info("[CMakeMux] Timeout waiting for profilesChanged, enabling matching profiles directly.");
                try {
                    enableMatchingImportedProfiles(project, patterns);
                } catch (Throwable t) {
                    LOG.warn("[CMakeMux] Failed to enable presets on timeout", t);
                }
            }, 10_000);

        } catch (Throwable t) {
            LOG.warn("[CMakeMux] Failed to subscribe to CMakeSettingsListener, falling back to direct enable.", t);
            ApplicationManager.getApplication().invokeLater(() -> {
                try {
                    enableMatchingImportedProfiles(project, patterns);
                } catch (Throwable ex) {
                    LOG.warn("[CMakeMux] Direct enable fallback failed", ex);
                }
            });
        }
    }

    @SuppressWarnings("unchecked")
    private static int enableMatchingImportedProfiles(Project project, List<Pattern> patterns) throws Exception {
        // Resolve CLion’s CMake settings
        Class<?> settingsClass;
        try {
            settingsClass = Class.forName("com.jetbrains.cidr.cpp.cmake.CMakeSettings");
        } catch (ClassNotFoundException e) {
            LOG.warn("[CMakeMux] CMakeSettings class not found, bail out.");
            return 0;
        }

        Method getInstance = findMethod(settingsClass, "getInstance", Project.class);
        if (getInstance == null) {
            LOG.warn("[CMakeMux] CMakeSettings.getInstance(Project) not found, bail out.");
            return 0;
        }
        Object settings = getInstance.invoke(null, project);
        if (settings == null) {
            LOG.warn("[CMakeMux] CMakeSettings instance is null, bail out.");
            return 0;
        }

        Method getProfiles = findMethod(settingsClass, "getProfiles");
        if (getProfiles == null) {
            LOG.warn("[CMakeMux] CMakeSettings.getProfiles() not found, bail out.");
            return 0;
        }
        Object res = getProfiles.invoke(settings);
        if (!(res instanceof List)) {
            LOG.warn("[CMakeMux] CMakeSettings.getProfiles() returned non-list or null, bail out.");
            return 0;
        }
        List<Object> profiles = (List<Object>) res;
        LOG.info("[CMakeMux] Found " + profiles.size() + " CMake profiles to check against " + patterns.size() + " patterns.");

        // Try withEnabled(boolean) first (Kotlin data-class copy pattern, 2026.1+),
        // fall back to direct field set for older CLion versions.
        Method withEnabled = findMethod(profiles.getFirst().getClass(), "withEnabled", boolean.class);

        List<Object> updatedProfiles = new java.util.ArrayList<>(profiles);
        int enabled = 0;
        for (int i = 0; i < updatedProfiles.size(); i++) {
            Object profile = updatedProfiles.get(i);
            if (profile == null) {
                LOG.warn("[CMakeMux] Encountered null profile, bail out.");
                return enabled;
            }

            // Keep dual getter for name/displayName
            String name = firstNonNull(
                    invokeStringGetter(profile, "getName"),
                    invokeStringGetter(profile, "getDisplayName")
            );
            if (name == null || name.isEmpty()) continue;

            if (!matchesAny(patterns, name)) continue;


            Boolean current = invokeBooleanGetter(profile, "getEnabled");
            if (Boolean.TRUE.equals(current)) continue;

            if (withEnabled != null) {
                // 2026.1+: Profile is immutable, use withEnabled() copy method
                Object updated = withEnabled.invoke(profile, true);
                updatedProfiles.set(i, updated);
            } else {
                // Legacy: mutate the field directly (pre-2026.1)
                Field enabledField = findBooleanField(profile.getClass(), "enabled");
                if (enabledField == null) {
                    LOG.warn("[CMakeMux] Neither withEnabled() nor 'enabled' field found, bail out.");
                    return enabled;
                }
                enabledField.setAccessible(true);
                enabledField.set(profile, true);
            }
            enabled++;
        }

        Method setProfiles = findMethod(settingsClass, "setProfiles", List.class);
        if (setProfiles == null) {
            LOG.warn("[CMakeMux] CMakeSettings.setProfiles(List) not found, bail out.");
            return enabled;
        }
        final Object finalSettings = settings;
        final List<Object> finalProfiles = updatedProfiles;
        final Method finalSetProfiles = setProfiles;
        ApplicationManager.getApplication().runWriteAction(() -> {
            try {
                finalSetProfiles.invoke(finalSettings, finalProfiles);
            } catch (Exception e) {
                LOG.warn("[CMakeMux] setProfiles invocation failed", e);
            }
        });
        return enabled;
    }

    // Utility helpers

    private static boolean matchesAny(List<Pattern> patterns, String name) {
        for (Pattern p : patterns) {
            if (p.matcher(name).find()) return true;
        }
        return false;
    }

    private static Method findMethod(Class<?> cls, String name, Class<?>... paramTypes) {
        try {
            return cls.getMethod(name, paramTypes);
        } catch (NoSuchMethodException e1) {
            try {
                Method m = cls.getDeclaredMethod(name, paramTypes);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException e2) {
                for (Method m : cls.getDeclaredMethods()) {
                    if (m.getName().equals(name) && (paramTypes.length == 0 || Arrays.equals(m.getParameterTypes(), paramTypes))) {
                        m.setAccessible(true);
                        return m;
                    }
                }
                return null;
            }
        }
    }

    private static Field findBooleanField(Class<?> cls, String name) {
        try {
            Field f = cls.getDeclaredField(name);
            f.setAccessible(true);
            Class<?> t = f.getType();
            if (t == boolean.class || t == Boolean.class) return f;
            return null;
        } catch (NoSuchFieldException e) {
            return null;
        }
    }

    private static String invokeStringGetter(Object obj, String getter) {
        Method m = findMethod(obj.getClass(), getter);
        if (m == null) return null;
        try {
            Object v = m.invoke(obj);
            return v != null ? v.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static Boolean invokeBooleanGetter(Object obj, String getter) {
        Method m = findMethod(obj.getClass(), getter);
        if (m == null) return null;
        try {
            Object v = m.invoke(obj);
            if (v instanceof Boolean) return (Boolean) v;
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String firstNonNull(String a, String b) {
        return a != null ? a : b;
    }

    private CMakeMuxPresetHandler() {
    }
}