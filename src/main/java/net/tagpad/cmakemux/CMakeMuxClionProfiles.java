package net.tagpad.cmakemux;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Reflective access to CLion's (internal) CMake settings and their profiles. "Enable presets" in CLion is
 * implemented via enabling the imported read-only CMake profiles that correspond to those presets.
 * This code is intentionally reflective and defensive to survive across CLion changes,
 * but it is still fragile by nature. These APIs are however present since long time and probably
 * will not change very often.
 */
final class CMakeMuxClionProfiles {
    private static final Logger LOG = Logger.getInstance(CMakeMuxClionProfiles.class);

    private final Object settings;
    private final Method getProfiles;
    private final Method setProfiles;

    private CMakeMuxClionProfiles(Object settings, Method getProfiles, Method setProfiles) {
        this.settings = settings;
        this.getProfiles = getProfiles;
        this.setProfiles = setProfiles;
    }

    /** Resolve CLion's CMake settings for the project, or null if not available. */
    static @Nullable CMakeMuxClionProfiles of(@NotNull Project project) throws Exception {
        Class<?> settingsClass;
        try {
            settingsClass = Class.forName("com.jetbrains.cidr.cpp.cmake.CMakeSettings");
        } catch (ClassNotFoundException e) {
            LOG.warn("[CMakeMux] CMakeSettings class not found, bail out.");
            return null;
        }

        Method getInstance = findMethod(settingsClass, "getInstance", Project.class);
        if (getInstance == null) {
            LOG.warn("[CMakeMux] CMakeSettings.getInstance(Project) not found, bail out.");
            return null;
        }
        Object settings = getInstance.invoke(null, project);
        if (settings == null) {
            LOG.warn("[CMakeMux] CMakeSettings instance is null, bail out.");
            return null;
        }

        Method getProfiles = findMethod(settingsClass, "getProfiles");
        if (getProfiles == null) {
            LOG.warn("[CMakeMux] CMakeSettings.getProfiles() not found, bail out.");
            return null;
        }
        Method setProfiles = findMethod(settingsClass, "setProfiles", List.class);
        if (setProfiles == null) {
            LOG.warn("[CMakeMux] CMakeSettings.setProfiles(List) not found, bail out.");
            return null;
        }
        return new CMakeMuxClionProfiles(settings, getProfiles, setProfiles);
    }

    /** CLion's profile objects, or null if they cannot be read. */
    @SuppressWarnings("unchecked")
    @Nullable List<Object> getProfiles() throws Exception {
        Object res = getProfiles.invoke(settings);
        if (!(res instanceof List)) {
            LOG.warn("[CMakeMux] CMakeSettings.getProfiles() returned non-list or null, bail out.");
            return null;
        }
        return (List<Object>) res;
    }

    void setProfiles(@NotNull List<Object> profiles) throws Exception {
        setProfiles.invoke(settings, profiles);
    }

    /** The profiles imported from CMake presets. */
    @NotNull List<CMakeMuxPresetInfo> getPresets() throws Exception {
        List<Object> profiles = getProfiles();
        if (profiles == null) return List.of();
        List<CMakeMuxPresetInfo> presets = new ArrayList<>();
        for (Object profile : profiles) {
            if (profile == null) continue;
            CMakeMuxPresetInfo info = info(profile);
            if (info.fromPreset()) presets.add(info);
        }
        return presets;
    }

    static @NotNull CMakeMuxPresetInfo info(@NotNull Object profile) {
        return new CMakeMuxPresetInfo(
                invokeStringGetter(profile, "getName"),
                invokeStringGetter(profile, "getDisplayName"),
                Boolean.TRUE.equals(invokeBooleanGetter(profile, "getEnabled")),
                Boolean.TRUE.equals(invokeBooleanGetter(profile, "getFromPreset")));
    }

    /** Returns the profile with the enabled flag set, or null if not supported. */
    static @Nullable Object withEnabled(@NotNull Object profile, boolean enabled) throws Exception {
        // Try withEnabled(boolean) first (Kotlin data-class copy pattern, 2026.1+),
        // fall back to direct field set for older CLion versions.
        Method withEnabled = findMethod(profile.getClass(), "withEnabled", boolean.class);
        if (withEnabled != null) {
            // 2026.1+: Profile is immutable, use withEnabled() copy method
            return withEnabled.invoke(profile, enabled);
        }
        // Legacy: mutate the field directly (pre-2026.1)
        Field enabledField = findBooleanField(profile.getClass(), "enabled");
        if (enabledField == null) return null;
        enabledField.set(profile, enabled);
        return profile;
    }

    // Utility helpers

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
}
