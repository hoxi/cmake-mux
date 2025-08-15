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

        ApplicationManager.getApplication().invokeLater(() -> {
            try {
                // Ensure presets are parsed/imported into profiles first
                ensurePresetsLoaded(project);

                int enabledCount = enableMatchingImportedProfiles(project, patterns);
                scheduleCMakeReload(project);
                LOG.info("[CMakeMux] Enabled " + enabledCount + " CMake profiles (from presets) by regex.");
            } catch (Throwable t) {
                LOG.warn("[CMakeMux] Failed to enable presets via internal API", t);
            }
        });
    }

    // Ensure CMakePresetLoader has loaded and imported presets into profiles
    private static void ensurePresetsLoaded(Project project) {
        try {
            Class<?> loaderCls = Class.forName("com.jetbrains.cidr.cpp.cmake.presets.CMakePresetLoader");
            Method getService = project.getClass().getMethod("getService", Class.class);
            Object loader = getService.invoke(project, loaderCls);
            if (loader == null) {
                LOG.warn("[CMakeMux] CMakePresetLoader service is null, bail out.");
                return;
            }
            // Use load(boolean) to avoid redundant reloads; bail if not available
            Method loadMethod = findMethod(loaderCls, "load", boolean.class);
            if (loadMethod == null) {
                LOG.warn("[CMakeMux] CMakePresetLoader.load(boolean) not found, bail out.");
                return;
            }
            loadMethod.invoke(loader, false);
        } catch (Throwable t) {
            // Non-fatal; proceed with best-effort
            LOG.debug("[CMakeMux] ensurePresetsLoaded failed (continuing): " + t.getMessage(), t);
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

        int enabled = 0;
        for (Object profile : profiles) {
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

            Field enabledField = findBooleanField(profile.getClass(), "enabled");
            if (enabledField == null) {
                LOG.warn("[CMakeMux] 'enabled' field not found on profile, bail out.");
                return enabled;
            }
            enabledField.setAccessible(true);
            enabledField.set(profile, true);
            enabled++;
        }

        Method setProfiles = findMethod(settingsClass, "setProfiles", List.class);
        if (setProfiles == null) {
            LOG.warn("[CMakeMux] CMakeSettings.setProfiles(List) not found, bail out.");
            return enabled;
        }
        setProfiles.invoke(settings, profiles);
        return enabled;
    }

    private static void scheduleCMakeReload(Project project) {
        try {
            Class<?> wsClass = Class.forName("com.jetbrains.cidr.cpp.cmake.workspace.CMakeWorkspace");
            Method getInstance = findMethod(wsClass, "getInstance", Project.class);
            if (getInstance == null) {
                LOG.warn("[CMakeMux] CMakeWorkspace.getInstance(Project) not found, bail out.");
                return;
            }
            Object ws = getInstance.invoke(null, project);
            if (ws == null) {
                LOG.warn("[CMakeMux] CMakeWorkspace instance is null, bail out.");
                return;
            }
            Method scheduleReload = findMethod(wsClass, "scheduleReload");
            if (scheduleReload == null) {
                LOG.warn("[CMakeMux] CMakeWorkspace.scheduleReload() not found, bail out.");
                return;
            }
            scheduleReload.invoke(ws);
        } catch (Throwable t) {
            LOG.debug("[CMakeMux] scheduleCMakeReload failed (continuing): " + t.getMessage(), t);
        }
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