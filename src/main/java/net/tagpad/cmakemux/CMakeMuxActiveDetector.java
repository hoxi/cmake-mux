package net.tagpad.cmakemux;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Method;

/** Shared helper to detect the current CMake model project and initialize the active path. */
final class CMakeMuxActiveDetector {
    private static final Logger LOG = Logger.getInstance(CMakeMuxActiveDetector.class);

    private CMakeMuxActiveDetector() {}

    /** Try once to detect and set the active CMakeLists path. Returns true on success. */
    static boolean detectAndSetActiveOnce(@NotNull Project project) {
        try {
            Class<?> wsClass = Class.forName("com.jetbrains.cidr.cpp.cmake.workspace.CMakeWorkspace");
            Method getInstance = wsClass.getMethod("getInstance", Project.class);
            Object ws = getInstance.invoke(null, project);
            if (ws != null) {
                Method getModelProjectDir = wsClass.getMethod("getModelProjectDir");
                Object dirObj = getModelProjectDir.invoke(ws);
                if (dirObj instanceof java.io.File modelProjectDir) {
                    java.io.File f = new java.io.File(modelProjectDir, "CMakeLists.txt");
                    if (f.isFile()) {
                        CMakeMuxSelectionService.getInstance(project).setActivePath(f.getAbsolutePath());
                        return true;
                    }
                }
            }
        } catch (Throwable t) {
            LOG.debug("[CMakeMux] Detect active CMakeLists failed: " + t.getMessage(), t);
        }
        return false;
    }

    /** Best-effort: try once immediately, then schedule up to 'retries' attempts with 'delayMs' spacing. */
    static void detectAndSetActiveBestEffort(@NotNull Project project, int retries, long delayMs) {
        if (project.isDisposed()) return;
        if (CMakeMuxSelectionService.getInstance(project).getActivePath() != null) return;

        if (detectAndSetActiveOnce(project)) return;

        if (retries > 0) {
            ApplicationManager.getApplication().invokeLater(() -> {
                if (!project.isDisposed()) {
                    detectAndSetActiveBestEffort(project, retries - 1, delayMs);
                }
            });
        }
    }
}