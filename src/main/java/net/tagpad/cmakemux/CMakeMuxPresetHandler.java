package net.tagpad.cmakemux;

import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.util.Alarm;
import com.intellij.util.messages.MessageBusConnection;
import com.intellij.util.messages.Topic;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Internal-API based helper to enable all imported CMake profiles
 * whose name and/or display name match any of the provided regex patterns.
 * "Enable presets" in CLion is implemented via enabling the imported
 * read-only CMake profiles that correspond to those presets.
 * This code is intentionally reflective and defensive to survive across CLion changes,
 * but it is still fragile by nature. These APIs are however present since long time and probably
 * will not change very often.
 */
@Service(Service.Level.PROJECT)
public final class CMakeMuxPresetHandler implements Disposable {
    private static final Logger LOG = Logger.getInstance(CMakeMuxPresetHandler.class);

    // Stop listening for profilesChanged when no event has been received for this long
    private static final int IDLE_TIMEOUT_MS = 10_000;
    // Never listen longer than this, even if events keep coming
    private static final long MAX_SESSION_MS = 60_000;

    private final Project project;

    // The currently active enable session (EDT only). Replaced when a new entry is loaded.
    private Disposable activeSession;

    public CMakeMuxPresetHandler(@NotNull Project project) {
        this.project = project;
    }

    public static CMakeMuxPresetHandler getInstance(@NotNull Project project) {
        return project.getService(CMakeMuxPresetHandler.class);
    }

    /** A compiled regex and which profile names it should be matched against. */
    record CompiledFilter(Pattern pattern, boolean matchName, boolean matchDisplayName) {
        boolean matches(@Nullable String name, @Nullable String displayName) {
            return (matchName && name != null && pattern.matcher(name).find())
                    || (matchDisplayName && displayName != null && pattern.matcher(displayName).find());
        }
    }

    /**
     * Enable the presets matching {@code presetFilters} for the CMake project being loaded.
     * Cancels any session started by a previous call. Must be called on the EDT.
     */
    public void enableMatchingPresets(@NotNull List<CMakeMuxPresetFilter> presetFilters) {
        cancelActiveSession();

        List<CompiledFilter> filters = compile(presetFilters);
        if (filters.isEmpty()) return;

        Disposable session = Disposer.newDisposable(this, "CMakeMux preset enabling");
        activeSession = session;

        // Subscribe to CMakeSettingsListener.profilesChanged to act exactly when
        // the preset loader finishes importing new profiles after the CMakeLists switch.
        subscribeAndEnableOnChange(session, filters);
    }

    /**
     * Switch presets of the already loaded CMake project without reloading it: matching profiles are enabled
     * and all other profiles imported from presets are disabled. Must be called on the EDT.
     */
    public void switchPresets(@NotNull List<CMakeMuxPresetFilter> presetFilters) {
        cancelActiveSession();
        List<CompiledFilter> filters = compile(presetFilters);
        try {
            int changed = enableMatchingImportedProfiles(filters, true);
            LOG.info("[CMakeMux] Switched presets, changed " + changed + " CMake profiles.");
        } catch (Throwable t) {
            LOG.warn("[CMakeMux] Failed to switch presets", t);
        }
    }

    private List<CompiledFilter> compile(List<CMakeMuxPresetFilter> presetFilters) {
        List<CompiledFilter> filters = new ArrayList<>();
        List<String> invalid = new ArrayList<>();
        for (CMakeMuxPresetFilter f : presetFilters) {
            if (f == null) continue;
            String regex = f.getRegexp();
            if (regex == null || regex.isBlank()) continue;
            if (!f.isMatchName() && !f.isMatchDisplayName()) {
                LOG.warn("[CMakeMux] Ignoring preset regexp '" + regex + "' that matches neither name nor display name");
                continue;
            }
            try {
                filters.add(new CompiledFilter(Pattern.compile(regex, Pattern.CASE_INSENSITIVE),
                        f.isMatchName(), f.isMatchDisplayName()));
            } catch (PatternSyntaxException e) {
                LOG.warn("[CMakeMux] Ignoring invalid preset regexp '" + regex + "': " + e.getDescription());
                invalid.add(regex);
            }
        }
        if (!invalid.isEmpty()) {
            notifyWarning("Invalid CMake preset regexp ignored", String.join(", ", invalid));
        }
        return filters;
    }

    private void cancelActiveSession() {
        if (activeSession != null) {
            Disposer.dispose(activeSession);
            activeSession = null;
        }
    }

    private void finishSession(Disposable session) {
        if (activeSession == session) activeSession = null;
        Disposer.dispose(session);
    }

    private boolean isActive(Disposable session) {
        return activeSession == session && !project.isDisposed();
    }

    @SuppressWarnings("unchecked")
    private void subscribeAndEnableOnChange(Disposable session, List<CompiledFilter> filters) {
        Alarm alarm = new Alarm(Alarm.ThreadToUse.SWING_THREAD, session);
        long deadline = System.currentTimeMillis() + MAX_SESSION_MS;

        // Final pass when the profiles have been quiet for a while (or we have waited long enough)
        Runnable onIdle = () -> {
            if (!isActive(session)) return;
            LOG.info("[CMakeMux] No more profilesChanged events, final pass enabling matching profiles.");
            enableSafely(filters);
            finishSession(session);
        };

        try {
            Class<?> listenerClass = Class.forName("com.jetbrains.cidr.cpp.cmake.CMakeSettingsListener");
            Class<?> companionClass = Class.forName("com.jetbrains.cidr.cpp.cmake.CMakeSettingsListener$Companion");
            Field companionField = listenerClass.getField("Companion");
            Object companion = companionField.get(null);
            Method getTopic = findMethod(companionClass, "getTOPIC");
            if (getTopic == null) {
                LOG.warn("[CMakeMux] CMakeSettingsListener.Companion.getTOPIC() not found, bail out.");
                finishSession(session);
                return;
            }
            Object topic = getTopic.invoke(companion);

            // Listener proxy, active for the lifetime of the session
            Object listener = Proxy.newProxyInstance(
                    listenerClass.getClassLoader(),
                    new Class<?>[]{listenerClass},
                    (proxy, method, args) -> {
                        if ("profilesChanged".equals(method.getName())) {
                            LOG.info("[CMakeMux] profilesChanged event received, enabling matching profiles.");
                            ApplicationManager.getApplication().invokeLater(() -> {
                                if (!isActive(session)) return;
                                int count = enableSafely(filters);
                                LOG.info("[CMakeMux] Enabled " + count + " CMake profiles (from presets) by regex.");
                                // Restart the idle timer, but respect the overall deadline
                                alarm.cancelAllRequests();
                                long remaining = deadline - System.currentTimeMillis();
                                alarm.addRequest(onIdle, (int) Math.max(0, Math.min(IDLE_TIMEOUT_MS, remaining)));
                            }, project.getDisposed());
                        }
                        if ("equals".equals(method.getName())) return proxy == args[0];
                        if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                        if ("toString".equals(method.getName())) return "CMakeMuxSettingsListener";
                        return null;
                    }
            );

            // Disconnected automatically when the session is disposed
            MessageBusConnection connection = project.getMessageBus().connect(session);
            connection.subscribe((Topic<Object>) topic, listener);

            // If no event fires at all, enable directly after the idle timeout
            alarm.addRequest(onIdle, IDLE_TIMEOUT_MS);

        } catch (Throwable t) {
            LOG.warn("[CMakeMux] Failed to subscribe to CMakeSettingsListener, falling back to direct enable.", t);
            ApplicationManager.getApplication().invokeLater(() -> {
                if (!isActive(session)) return;
                enableSafely(filters);
                finishSession(session);
            }, project.getDisposed());
        }
    }

    private int enableSafely(List<CompiledFilter> filters) {
        try {
            return enableMatchingImportedProfiles(filters, false);
        } catch (Throwable t) {
            LOG.warn("[CMakeMux] Failed to enable matching presets", t);
            return 0;
        }
    }

    @SuppressWarnings("unchecked")
    /**
     * Enables matching profiles. With {@code disableOtherPresets}, non-matching profiles imported from presets are
     * disabled as well. Returns the number of profiles changed.
     */
    private int enableMatchingImportedProfiles(List<CompiledFilter> filters, boolean disableOtherPresets) throws Exception {
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
        Method setProfiles = findMethod(settingsClass, "setProfiles", List.class);
        if (setProfiles == null) {
            LOG.warn("[CMakeMux] CMakeSettings.setProfiles(List) not found, bail out.");
            return 0;
        }

        // Read, modify and write the profiles in one write action, so the list cannot change in between
        int[] enabled = {0};
        Exception[] failure = {null};
        ApplicationManager.getApplication().runWriteAction(() -> {
            try {
                Object res = getProfiles.invoke(settings);
                if (!(res instanceof List)) {
                    LOG.warn("[CMakeMux] CMakeSettings.getProfiles() returned non-list or null, bail out.");
                    return;
                }
                List<Object> updatedProfiles = new ArrayList<>((List<Object>) res);
                LOG.info("[CMakeMux] Found " + updatedProfiles.size() + " CMake profiles to check against "
                        + filters.size() + " patterns.");

                enabled[0] = applyFilters(updatedProfiles, filters, disableOtherPresets);
                if (enabled[0] < 0) {
                    // Never leave CLion without any enabled profile
                    LOG.warn("[CMakeMux] No CMake profile would be enabled, keeping the current profiles.");
                    ApplicationManager.getApplication().invokeLater(() -> notifyWarning(
                            "No CMake profile matches the preset regexps",
                            "The currently enabled CMake profiles are kept."), project.getDisposed());
                    enabled[0] = 0;
                }
                // Only write back when something changed. This also avoids re-triggering ourselves
                // through the profilesChanged event setProfiles() fires.
                if (enabled[0] > 0) {
                    setProfiles.invoke(settings, updatedProfiles);
                }
            } catch (Exception e) {
                failure[0] = e;
            }
        });
        if (failure[0] != null) throw failure[0];
        return enabled[0];
    }

    /**
     * Enables matching profiles (and optionally disables non-matching preset profiles) in place in
     * {@code profiles}. Returns the number of profiles changed, or -1 if no profile would be left enabled.
     */
    private static int applyFilters(List<Object> profiles, List<CompiledFilter> filters, boolean disableOtherPresets)
            throws Exception {
        // Plan the changes first, so nothing is touched if the result would be unusable
        Map<Integer, Boolean> changes = new LinkedHashMap<>();
        int enabledAfter = 0;
        for (int i = 0; i < profiles.size(); i++) {
            Object profile = profiles.get(i);
            if (profile == null) continue;

            boolean current = Boolean.TRUE.equals(invokeBooleanGetter(profile, "getEnabled"));
            String name = invokeStringGetter(profile, "getName");
            String displayName = invokeStringGetter(profile, "getDisplayName");
            boolean wanted = current;
            if (matchesAny(filters, name, displayName)) {
                wanted = true;
            } else if (disableOtherPresets && Boolean.TRUE.equals(invokeBooleanGetter(profile, "getFromPreset"))) {
                wanted = false;
            }
            if (wanted != current) changes.put(i, wanted);
            if (wanted) enabledAfter++;
        }
        if (changes.isEmpty()) return 0;
        if (enabledAfter == 0) return -1;

        int changed = 0;
        for (Map.Entry<Integer, Boolean> change : changes.entrySet()) {
            Object updated = withEnabled(profiles.get(change.getKey()), change.getValue());
            if (updated == null) {
                LOG.warn("[CMakeMux] Neither withEnabled() nor 'enabled' field found, bail out.");
                return changed;
            }
            profiles.set(change.getKey(), updated);
            changed++;
        }
        return changed;
    }

    /** Returns the profile with the enabled flag set, or null if not supported. */
    private static Object withEnabled(Object profile, boolean enabled) throws Exception {
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

    private void notifyWarning(String title, String content) {
        NotificationGroupManager.getInstance()
                .getNotificationGroup("CMake Mux")
                .createNotification(title, content, NotificationType.WARNING)
                .notify(project);
    }

    @Override
    public void dispose() {
        activeSession = null;
    }

    // Utility helpers

    private static boolean matchesAny(List<CompiledFilter> filters, String name, String displayName) {
        for (CompiledFilter f : filters) {
            if (f.matches(name, displayName)) return true;
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
}
