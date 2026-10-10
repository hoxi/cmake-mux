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
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Enables the CMake presets (imported CMake profiles) whose name and/or display name match the preset regexps
 * of an entry, and provides the presets of the loaded CMake project.
 * CLion is accessed through {@link CMakeMuxClionProfiles}.
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

    /** The presets of the loaded CMake project; empty if none are imported (yet) or they cannot be read. */
    public @NotNull List<CMakeMuxPresetInfo> getLoadedPresets() {
        try {
            CMakeMuxClionProfiles profiles = CMakeMuxClionProfiles.of(project);
            return profiles != null ? profiles.getPresets() : List.of();
        } catch (Throwable t) {
            LOG.debug("[CMakeMux] Failed to read presets", t);
            return List.of();
        }
    }

    /**
     * Enable the presets matching {@code presetFilters} (and disable other presets) for the CMake project being
     * loaded. Cancels any session started by a previous call. Must be called on the EDT.
     */
    public void enableMatchingPresets(@NotNull List<CMakeMuxPresetFilter> presetFilters) {
        cancelActiveSession();

        CMakeMuxPresetMatcher matcher = compile(presetFilters);
        if (matcher.isEmpty()) return;

        Disposable session = Disposer.newDisposable(this, "CMakeMux preset enabling");
        activeSession = session;

        // Subscribe to CMakeSettingsListener.profilesChanged to act exactly when
        // the preset loader finishes importing new profiles after the CMakeLists switch.
        subscribeAndEnableOnChange(session, matcher);
    }

    /**
     * Switch presets of the already loaded CMake project without reloading it: matching profiles are enabled
     * and all other profiles imported from presets are disabled. Must be called on the EDT.
     */
    public void switchPresets(@NotNull List<CMakeMuxPresetFilter> presetFilters) {
        cancelActiveSession();
        CMakeMuxPresetMatcher matcher = compile(presetFilters);
        try {
            int changed = applyToProfiles(matcher, true);
            LOG.info("[CMakeMux] Switched presets, changed " + changed + " CMake profiles.");
        } catch (Throwable t) {
            LOG.warn("[CMakeMux] Failed to switch presets", t);
        }
    }

    /**
     * Whether the enabled presets are exactly the ones selected by {@code presetFilters}.
     * Returns null when it cannot be determined (e.g. no presets imported (yet)). Must be called on the EDT.
     */
    public @Nullable Boolean presetsMatch(@NotNull List<CMakeMuxPresetFilter> presetFilters) {
        List<CMakeMuxPresetInfo> presets = getLoadedPresets();
        if (presets.isEmpty()) return null;
        CMakeMuxPresetMatcher matcher = CMakeMuxPresetMatcher.of(presetFilters);
        for (CMakeMuxPresetInfo preset : presets) {
            if (matcher.matches(preset) != preset.enabled()) return false;
        }
        return true;
    }

    /** Compile the regexps, reporting invalid ones. */
    private CMakeMuxPresetMatcher compile(List<CMakeMuxPresetFilter> presetFilters) {
        CMakeMuxPresetMatcher matcher = CMakeMuxPresetMatcher.of(presetFilters);
        List<String> invalid = matcher.getInvalidRegexps();
        if (!invalid.isEmpty()) {
            LOG.warn("[CMakeMux] Ignoring invalid preset regexps: " + invalid);
            notifyWarning("Invalid CMake preset regexp ignored", String.join(", ", invalid));
        }
        return matcher;
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

    private void subscribeAndEnableOnChange(Disposable session, CMakeMuxPresetMatcher matcher) {
        Alarm alarm = new Alarm(Alarm.ThreadToUse.SWING_THREAD, session);
        long deadline = System.currentTimeMillis() + MAX_SESSION_MS;

        // Final pass when the profiles have been quiet for a while (or we have waited long enough)
        Runnable onIdle = () -> {
            if (!isActive(session)) return;
            LOG.info("[CMakeMux] No more profilesChanged events, final pass enabling matching profiles.");
            enableSafely(matcher);
            finishSession(session);
        };

        // Listen to CMakeSettingsListener.profilesChanged, disconnected automatically when the session is disposed
        MessageBusConnection connection = project.getMessageBus().connect(session);
        boolean subscribed = CMakeMuxClionEvents.subscribeSettings(connection, (method, args) -> {
            if (!"profilesChanged".equals(method)) return;
            LOG.info("[CMakeMux] profilesChanged event received, enabling matching profiles.");
            ApplicationManager.getApplication().invokeLater(() -> {
                if (!isActive(session)) return;
                int count = enableSafely(matcher);
                LOG.info("[CMakeMux] Changed " + count + " CMake profiles (from presets) by regex.");
                // Restart the idle timer, but respect the overall deadline
                alarm.cancelAllRequests();
                long remaining = deadline - System.currentTimeMillis();
                alarm.addRequest(onIdle, (int) Math.max(0, Math.min(IDLE_TIMEOUT_MS, remaining)));
            }, project.getDisposed());
        });

        if (subscribed) {
            // If no event fires at all, enable directly after the idle timeout
            alarm.addRequest(onIdle, IDLE_TIMEOUT_MS);
        } else {
            LOG.warn("[CMakeMux] Falling back to direct enable.");
            ApplicationManager.getApplication().invokeLater(onIdle, project.getDisposed());
        }
    }

    private int enableSafely(CMakeMuxPresetMatcher matcher) {
        try {
            // While loading, profiles change in several steps; never warn about intermediate states
            return applyToProfiles(matcher, false);
        } catch (Throwable t) {
            LOG.warn("[CMakeMux] Failed to enable matching presets", t);
            return 0;
        }
    }

    /**
     * Enables matching profiles and disables non-matching profiles imported from presets.
     * Returns the number of profiles changed.
     */
    private int applyToProfiles(CMakeMuxPresetMatcher matcher, boolean warnIfNoneEnabled) throws Exception {
        CMakeMuxClionProfiles clionProfiles = CMakeMuxClionProfiles.of(project);
        if (clionProfiles == null) return 0;

        // Read, modify and write the profiles in one write action, so the list cannot change in between
        int[] changed = {0};
        Exception[] failure = {null};
        ApplicationManager.getApplication().runWriteAction(() -> {
            try {
                List<Object> profiles = clionProfiles.getProfiles();
                if (profiles == null) return;
                List<Object> updatedProfiles = new ArrayList<>(profiles);
                LOG.info("[CMakeMux] Found " + updatedProfiles.size() + " CMake profiles to check.");

                changed[0] = applyMatcher(updatedProfiles, matcher);
                if (changed[0] < 0) {
                    // Never leave CLion without any enabled profile
                    LOG.warn("[CMakeMux] No CMake profile would be enabled, keeping the current profiles.");
                    if (warnIfNoneEnabled) {
                        ApplicationManager.getApplication().invokeLater(() -> notifyWarning(
                                "No CMake profile matches the preset regexps",
                                "The currently enabled CMake profiles are kept."), project.getDisposed());
                    }
                    changed[0] = 0;
                }
                // Only write back when something changed. This also avoids re-triggering ourselves
                // through the profilesChanged event setProfiles() fires.
                if (changed[0] > 0) {
                    clionProfiles.setProfiles(updatedProfiles);
                }
            } catch (Exception e) {
                failure[0] = e;
            }
        });
        if (failure[0] != null) throw failure[0];
        return changed[0];
    }

    /**
     * Enables matching profiles and disables non-matching preset profiles in place in {@code profiles}.
     * Returns the number of profiles changed, or -1 if no profile would be left enabled.
     */
    private static int applyMatcher(List<Object> profiles, CMakeMuxPresetMatcher matcher) throws Exception {
        // Plan the changes first, so nothing is touched if the result would be unusable
        Map<Integer, Boolean> changes = new LinkedHashMap<>();
        int enabledAfter = 0;
        for (int i = 0; i < profiles.size(); i++) {
            Object profile = profiles.get(i);
            if (profile == null) continue;

            CMakeMuxPresetInfo info = CMakeMuxClionProfiles.info(profile);
            boolean wanted = info.enabled();
            if (matcher.matches(info)) {
                wanted = true;
            } else if (info.fromPreset()) {
                wanted = false;
            }
            if (wanted != info.enabled()) changes.put(i, wanted);
            if (wanted) enabledAfter++;
        }
        if (changes.isEmpty()) return 0;
        if (enabledAfter == 0) return -1;

        int changed = 0;
        for (Map.Entry<Integer, Boolean> change : changes.entrySet()) {
            Object updated = CMakeMuxClionProfiles.withEnabled(profiles.get(change.getKey()), change.getValue());
            if (updated == null) {
                LOG.warn("[CMakeMux] Neither withEnabled() nor 'enabled' field found, bail out.");
                return changed;
            }
            profiles.set(change.getKey(), updated);
            changed++;
        }
        return changed;
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
}
