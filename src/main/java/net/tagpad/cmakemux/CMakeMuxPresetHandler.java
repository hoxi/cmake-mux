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
 *
 * "Enable presets" in CLion is implemented via enabling the imported
 * read-only CMake profiles that correspond to those presets.
 *
 * This code is intentionally reflective and defensive to survive across CLion changes,
 * but it is still fragile by nature. Expect it to break on platform updates.
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
            if (loader != null) {
                // Prefer load(false) to avoid redundant reloads; profiles will be in place for toggling
                Method loadMethod = null;
                for (Method m : loaderCls.getMethods()) {
                    if (m.getName().equals("load")) {
                        loadMethod = m;
                        break;
                    }
                }
                if (loadMethod != null) {
                    if (loadMethod.getParameterCount() == 1 && loadMethod.getParameterTypes()[0] == boolean.class) {
                        loadMethod.invoke(loader, false);
                    } else {
                        loadMethod.invoke(loader);
                    }
                }
            }
        } catch (Throwable t) {
            // Non-fatal; proceed with best-effort
            LOG.debug("[CMakeMux] ensurePresetsLoaded failed (continuing): " + t.getMessage(), t);
        }
    }

    @SuppressWarnings("unchecked")
    private static int enableMatchingImportedProfiles(Project project, List<Pattern> patterns) throws Exception {
        // Prioritize CIDR namespace first (CLion’s CMake plugin)
        String[] settingsClassCandidates = new String[] {
                "com.jetbrains.cidr.cpp.cmake.CMakeSettings",
                "com.jetbrains.cidr.cpp.cmake.settings.CMakeSettings",
                "com.jetbrains.cidr.cpp.cmake.settings.CMakeProfilesSettings",
                "com.jetbrains.cidr.cpp.cmake.workspace.CMakeWorkspaceSettings",
                "com.jetbrains.cmake.settings.CMakeSettings",
                "com.jetbrains.cmake.workspace.CMakeWorkspaceSettings",
                "com.jetbrains.cmake.project.CMakeSettings"
        };

        Object settings = null;
        Class<?> settingsClass = null;

        for (String fqcn : settingsClassCandidates) {
            try {
                Class<?> cls = Class.forName(fqcn);
                Method getInstance = findMethod(cls, "getInstance", Project.class);
                if (getInstance != null) {
                    Object inst = getInstance.invoke(null, project);
                    if (inst != null) {
                        settings = inst;
                        settingsClass = cls;
                        break;
                    }
                }
            } catch (ClassNotFoundException ignored) {
            }
        }
        if (settings == null) {
            LOG.warn("[CMakeMux] Could not resolve CLion CMake settings class via known candidates.");
            return 0;
        }

        List<Object> profiles = null;

        // 1) Common direct getters
        for (String getterName : new String[] {"getProfiles", "getConfigurations"}) {
            Method m = findMethod(settingsClass, getterName);
            if (m != null) {
                Object res = m.invoke(settings);
                if (res instanceof List) {
                    profiles = (List<Object>) res;
                    break;
                }
            }
        }

        // 2) Via state bean
        if (profiles == null) {
            Method getState = findMethod(settingsClass, "getState");
            if (getState != null) {
                Object state = getState.invoke(settings);
                if (state != null) {
                    for (String fieldName : new String[] {"profiles", "configurations"}) {
                        Field f = findField(state.getClass(), fieldName);
                        if (f != null) {
                            f.setAccessible(true);
                            Object val = f.get(state);
                            if (val instanceof List) {
                                profiles = (List<Object>) val;
                                break;
                            }
                        }
                    }
                    if (profiles == null) {
                        for (String getterName : new String[] {"getProfiles", "getConfigurations"}) {
                            Method m = findMethod(state.getClass(), getterName);
                            if (m != null) {
                                Object val = m.invoke(state);
                                if (val instanceof List) {
                                    profiles = (List<Object>) val;
                                    break;
                                }
                            }
                        }
                    }
                }
            }
        }

        if (profiles == null) {
            LOG.warn("[CMakeMux] Could not obtain CMake profiles list from CLion settings.");
            return 0;
        }

        int enabled = 0;
        for (Object profile : profiles) {
            String name = firstNonNull(
                    invokeStringGetter(profile, "getName"),
                    invokeStringGetter(profile, "getDisplayName")
            );
            if (name == null || name.isEmpty()) continue;

            if (!matchesAny(patterns, name)) continue;

            Boolean current = coalesceBoolean(
                    invokeBooleanGetter(profile, "isEnabled"),
                    invokeBooleanGetter(profile, "getEnabled"),
                    invokeBooleanGetter(profile, "isActive")
            );
            if (Boolean.TRUE.equals(current)) continue;

            Method setEnabled = firstNonNullMethod(profile.getClass(),
                    new String[] {"setEnabled", "setActive"},
                    new Class<?>[] {boolean.class}
            );
            if (setEnabled == null) {
                Field enabledField = coalesceBooleanField(profile.getClass(), "enabled", "active");
                if (enabledField != null) {
                    enabledField.setAccessible(true);
                    enabledField.set(profile, true);
                    enabled++;
                    continue;
                }
            } else {
                setEnabled.invoke(profile, true);
                enabled++;
            }
        }

        // Persist if necessary
        Method setProfiles = findMethod(settingsClass, "setProfiles", List.class);
        Method setConfigurations = findMethod(settingsClass, "setConfigurations", List.class);
        if (setProfiles != null) {
            setProfiles.invoke(settings, profiles);
        } else if (setConfigurations != null) {
            setConfigurations.invoke(settings, profiles);
        } else {
            Method loadState = findMethod(settingsClass, "loadState", Object.class);
            Method getState = findMethod(settingsClass, "getState");
            if (loadState != null && getState != null) {
                Object state = getState.invoke(settings);
                if (state != null) {
                    loadState.invoke(settings, state);
                }
            }
        }

        return enabled;
    }

    private static void scheduleCMakeReload(Project project) {
        String[] workspaceCandidates = new String[] {
                "com.jetbrains.cidr.cpp.cmake.workspace.CMakeWorkspace",
                "com.jetbrains.cidr.cpp.cmake.project.CMakeWorkspace",
                "com.jetbrains.cmake.workspace.CMakeWorkspace",
                "com.jetbrains.cmake.project.CMakeWorkspace"
        };
        for (String fqcn : workspaceCandidates) {
            try {
                Class<?> wsClass = Class.forName(fqcn);
                Method getInstance = findMethod(wsClass, "getInstance", Project.class);
                if (getInstance == null) continue;

                Object ws = getInstance.invoke(null, project);
                if (ws == null) continue;

                for (String mName : new String[] {"scheduleRebuild", "scheduleReload", "reload", "generate", "scheduleGenerate"}) {
                    Method m = findAnyMethod(wsClass, mName);
                    if (m != null) {
                        if (m.getParameterCount() == 1 && m.getParameterTypes()[0] == boolean.class) {
                            m.invoke(ws, true);
                        } else {
                            m.invoke(ws);
                        }
                        return;
                    }
                }
            } catch (Throwable ignored) {
            }
        }
    }

    // Utility helpers

    private static boolean matchesAny(List<Pattern> patterns, String name) {
        for (Pattern p : patterns) {
            if (p.matcher(name).find()) return true;
        }
        return false;
    }

    private static Method findAnyMethod(Class<?> cls, String name) {
        Method m = findMethod(cls, name);
        if (m != null) return m;
        for (Method cand : cls.getMethods()) {
            if (cand.getName().equals(name)) {
                cand.setAccessible(true);
                return cand;
            }
        }
        for (Method cand : cls.getDeclaredMethods()) {
            if (cand.getName().equals(name)) {
                cand.setAccessible(true);
                return cand;
            }
        }
        return null;
    }

    private static Method firstNonNullMethod(Class<?> cls, String[] names, Class<?>[] paramTypes) {
        for (String n : names) {
            Method m = findMethod(cls, n, paramTypes);
            if (m != null) return m;
        }
        return null;
    }

    private static Field coalesceBooleanField(Class<?> cls, String... names) {
        for (String n : names) {
            Field f = findBooleanField(cls, n);
            if (f != null) return f;
        }
        return null;
    }

    private static Boolean coalesceBoolean(Boolean... vals) {
        for (Boolean v : vals) if (v != null) return v;
        return null;
    }

    private static String firstNonNull(String a, String b) {
        return a != null ? a : b;
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

    private static Field findField(Class<?> cls, String name) {
        try {
            return cls.getField(name);
        } catch (NoSuchFieldException e1) {
            try {
                Field f = cls.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException e2) {
                return null;
            }
        }
    }

    private static Field findBooleanField(Class<?> cls, String name) {
        Field f = findField(cls, name);
        if (f == null) return null;
        Class<?> t = f.getType();
        if (t == boolean.class || t == Boolean.class) return f;
        return null;
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

    private CMakeMuxPresetHandler() {}
}