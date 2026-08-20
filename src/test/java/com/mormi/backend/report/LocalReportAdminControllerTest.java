package com.mormi.backend.report;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.mormi.backend.auth.LearnerPrincipal;
import com.mormi.backend.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class LocalReportAdminControllerTest {

    private LocalReportAdminGuard guard;
    private LocalReportAdminService localReportAdminService;
    private DiagnosticReportService diagnosticReportService;
    private LocalReportLoginAttemptLimiter loginAttemptLimiter;
    private LocalReportAdminController controller;

    @BeforeEach
    void setUp() {
        guard = mock(LocalReportAdminGuard.class);
        localReportAdminService = mock(LocalReportAdminService.class);
        diagnosticReportService = mock(DiagnosticReportService.class);
        loginAttemptLimiter = mock(LocalReportLoginAttemptLimiter.class);
        controller = new LocalReportAdminController(
                guard, localReportAdminService, diagnosticReportService, loginAttemptLimiter);
    }

    @Test
    void loginAttemptUsesTheSharedLimiterAfterGuarding() {
        when(loginAttemptLimiter.evaluate("opaque-client", false))
                .thenReturn(LocalReportLoginAttemptLimiter.Decision.BLOCKED);

        var response = controller.authAttempt(
                new LocalReportAdminController.AuthAttempt(false, "opaque-client"), requestWithLoopbackAndKey());

        verify(guard).requireAllowed("local-secret", "127.0.0.1");
        verify(loginAttemptLimiter).evaluate("opaque-client", false);
        org.assertj.core.api.Assertions.assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void diagnosticUsesThePathLearnerAndRequestedWeekAfterGuarding() {
        LocalDate monday = LocalDate.of(2026, 8, 17);

        controller.diagnostic(19L, monday, requestWithLoopbackAndKey());

        verify(guard).requireAllowed("local-secret", "127.0.0.1");
        verify(diagnosticReportService).current(19L, monday);
    }

    @Test
    void speechEvidenceUsesTheSameSelectedLearner() {
        LocalDate monday = LocalDate.of(2026, 8, 17);

        controller.speechEvidence(19L, "money-count", monday, requestWithLoopbackAndKey());

        verify(guard).requireAllowed("local-secret", "127.0.0.1");
        verify(diagnosticReportService).speechEvidence(19L, "money-count", monday);
    }

    @Test
    void speechEvidenceRejectsUnsupportedDomainsBeforeDelegating() {
        assertThatThrownBy(() -> controller.speechEvidence(19L, "outside-report", null, requestWithLoopbackAndKey()))
                .isInstanceOf(ApiException.class);

        verify(guard).requireAllowed("local-secret", "127.0.0.1");
        verifyNoInteractions(diagnosticReportService);
    }

    @Test
    void authenticatedDiagnosticStillUsesOnlyThePrincipalLearner() {
        DiagnosticReportService authenticatedService = mock(DiagnosticReportService.class);
        DiagnosticReportController authenticatedController = new DiagnosticReportController(authenticatedService);
        LocalDate monday = LocalDate.of(2026, 8, 17);

        authenticatedController.current(new LearnerPrincipal(7L, 101L), monday);

        verify(authenticatedService).current(7L, monday);
    }

    private HttpServletRequest requestWithLoopbackAndKey() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("X-Mormi-Local-Admin-Key")).thenReturn("local-secret");
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        return request;
    }
}
