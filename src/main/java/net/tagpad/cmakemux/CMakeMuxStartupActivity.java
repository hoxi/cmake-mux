package net.tagpad.cmakemux;

import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.ProjectActivity;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import java.util.concurrent.CompletableFuture;

/** Initializes the active CMakeLists.txt path on project startup so UI/popup can highlight it. */
public class CMakeMuxStartupActivity implements ProjectActivity, DumbAware {
    @Override
    public @Nullable Object execute(@NotNull Project project, @NotNull Continuation<? super Unit> continuation) {
        // If already set (e.g., persisted), do nothing.
        if (CMakeMuxSelectionService.getInstance(project).getActivePath() != null) {
            return null; // synchronous completion
        }

        // Best-effort detection: try now and then a few delayed attempts.
        CMakeMuxActiveDetector.detectAndSetActiveBestEffort(project, 5, 200L);

        return CompletableFuture.completedFuture(null);
    }
}