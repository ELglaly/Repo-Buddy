package com.repoinspector.inspections.scan;

import com.intellij.openapi.project.Project;
import com.repoinspector.core.AnalysisScope;
import com.repoinspector.core.RepoBuddyException;
import com.repoinspector.core.ScanRequest;
import com.repoinspector.core.ScopedIssueRequest;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IntelliJRepoBuddyApplicationServiceTest {
    @Test
    void freshAnalysisRequestsFailBeforeQueuingForDisposedProject() {
        Project project = mock(Project.class);
        when(project.isDisposed()).thenReturn(true);
        IntelliJRepoBuddyApplicationService service = new IntelliJRepoBuddyApplicationService(project);

        assertDisposed(() -> service.scan(new ScanRequest(null, null)).toCompletableFuture().join());
        assertDisposed(() -> service.getIssues(new ScopedIssueRequest(
                AnalysisScope.ALL, null, null, null, null, 25, 0, null)).toCompletableFuture().join());
        assertDisposed(() -> service.checkChanges(null, null).toCompletableFuture().join());
    }

    private static void assertDisposed(Runnable request) {
        CompletionException failure = assertThrows(CompletionException.class, request::run);
        RepoBuddyException cause = (RepoBuddyException) failure.getCause();
        assertEquals("REPOBUDDY_PROJECT_DISPOSED", cause.code().name());
    }
}
