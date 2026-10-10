package net.tagpad.cmakemux;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.ProjectActivity;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Initializes the active CMakeLists.txt path on project startup so UI/popup can highlight it. */
public class CMakeMuxStartupActivity implements ProjectActivity, DumbAware {
    @Override
    public @Nullable Object execute(@NotNull Project project, @NotNull Continuation<? super Unit> continuation) {
        // Best-effort detection: try now and then every 500 ms for up to 15 s (stops early if already known).
        // Loads finishing later are picked up through CMakeWorkspaceListener.reloadingFinished.
        // Runs on the EDT like all other updates of the active state.
        ApplicationManager.getApplication().invokeLater(
                () -> CMakeMuxActiveDetector.detectAndSetActiveBestEffort(project, 30, 500L),
                project.getDisposed());
        return Unit.INSTANCE;
    }
}