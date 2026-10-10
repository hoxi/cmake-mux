package net.tagpad.cmakemux;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.util.concurrency.AppExecutorUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/** Shared helper to detect the currently loaded CMakeLists.txt and initialize the active path. */
final class CMakeMuxActiveDetector {
    private static final Logger LOG = Logger.getInstance(CMakeMuxActiveDetector.class);

    private CMakeMuxActiveDetector() {}

    /** Try once to detect and set the active CMakeLists path. Returns true on success. Call on the EDT. */
    static boolean detectAndSetActiveOnce(@NotNull Project project) {
        File cmakeLists = detectLoadedCMakeLists(project);
        if (cmakeLists == null) return false;
        CMakeMuxSelectionService.getInstance(project).setActivePath(cmakeLists.getAbsolutePath());
        return true;
    }

    /**
     * Best-effort: try once immediately, then up to {@code retries} more attempts {@code delayMs} apart,
     * since the CMake workspace may initialize later. Stops at the first success.
     * Later loads are picked up by CMakeMuxSelectionService listening for reloadingFinished.
     */
    static void detectAndSetActiveBestEffort(@NotNull Project project, int retries, long delayMs) {
        if (project.isDisposed()) return;
        if (CMakeMuxSelectionService.getInstance(project).getActivePath() != null) return;

        if (detectAndSetActiveOnce(project)) return;

        if (retries > 0) {
            AppExecutorUtil.getAppScheduledExecutorService().schedule(
                    () -> ApplicationManager.getApplication().invokeLater(
                            () -> detectAndSetActiveBestEffort(project, retries - 1, delayMs),
                            project.getDisposed()),
                    delayMs, TimeUnit.MILLISECONDS);
        }
    }

    /** The CMakeLists.txt of the loaded CMake project, or null if unknown (yet). */
    private static @Nullable File detectLoadedCMakeLists(@NotNull Project project) {
        try {
            Class<?> wsClass = Class.forName("com.jetbrains.cidr.cpp.cmake.workspace.CMakeWorkspace");
            Method getInstance = wsClass.getMethod("getInstance", Project.class);
            Object ws = getInstance.invoke(null, project);
            if (ws == null) return null;

            // The selected project path, may point to the CMakeLists.txt itself or to its directory
            File found = cmakeListsFor(invokeIfPresent(wsClass, ws, "getNullableProjectPath"));
            if (found == null) {
                // The directory of the loaded CMake model
                found = cmakeListsFor(invokeIfPresent(wsClass, ws, "getModelProjectDir"));
            }
            return found;
        } catch (Throwable t) {
            LOG.debug("[CMakeMux] Detect active CMakeLists failed: " + t.getMessage(), t);
            return null;
        }
    }

    private static @Nullable Object invokeIfPresent(Class<?> cls, Object target, String name) {
        try {
            return cls.getMethod(name).invoke(target);
        } catch (NoSuchMethodException e) {
            return null;
        } catch (Throwable t) {
            LOG.debug("[CMakeMux] " + name + "() failed: " + t.getMessage(), t);
            return null;
        }
    }

    private static @Nullable File cmakeListsFor(@Nullable Object pathOrFile) {
        File file = pathOrFile instanceof Path p ? p.toFile() : pathOrFile instanceof File f ? f : null;
        if (file == null) return null;
        if (file.isFile() && "CMakeLists.txt".equals(file.getName())) return file;
        if (file.isDirectory()) {
            File cmakeLists = new File(file, "CMakeLists.txt");
            if (cmakeLists.isFile()) return cmakeLists;
        }
        return null;
    }
}
