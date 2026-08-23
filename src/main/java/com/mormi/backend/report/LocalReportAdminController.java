package com.mormi.backend.report;

import static com.mormi.backend.report.LocalReportAdminDtos.LocalLearnerResult;

import com.mormi.backend.report.DiagnosticReportDtos.DiagnosticReport;
import com.mormi.backend.report.DiagnosticReportDtos.SpeechEvidence;
import com.mormi.backend.report.DiagnosticReportDtos.LadderApprovalResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.ResponseEntity;

@RestController
@RequestMapping("/v1/local-report-admin")
@ConditionalOnProperty(name = "mormi.local-report-admin.enabled", havingValue = "true")
public class LocalReportAdminController {

    private final LocalReportAdminGuard guard;
    private final LocalReportAdminService service;
    private final DiagnosticReportService diagnosticReportService;
    private final LocalReportLoginAttemptLimiter loginAttemptLimiter;

    public LocalReportAdminController(
            LocalReportAdminGuard guard,
            LocalReportAdminService service,
            DiagnosticReportService diagnosticReportService,
            LocalReportLoginAttemptLimiter loginAttemptLimiter) {
        this.guard = guard;
        this.service = service;
        this.diagnosticReportService = diagnosticReportService;
        this.loginAttemptLimiter = loginAttemptLimiter;
    }

    public record AuthAttempt(boolean accepted, String clientFingerprint) {}

    @PostMapping("/auth-attempt")
    public ResponseEntity<Void> authAttempt(@RequestBody AuthAttempt attempt, HttpServletRequest request) {
        requireAllowed(request);
        return switch (loginAttemptLimiter.evaluate(attempt.clientFingerprint(), attempt.accepted())) {
            case ACCEPTED -> ResponseEntity.noContent().build();
            case REJECTED -> ResponseEntity.status(401).build();
            case BLOCKED -> ResponseEntity.status(429)
                    .header("Retry-After", Long.toString(LocalReportLoginAttemptLimiter.WINDOW.toSeconds()))
                    .build();
        };
    }

    @GetMapping("/learners")
    public List<LocalLearnerResult> learners(
            @RequestParam String query,
            @RequestParam(defaultValue = "10") int limit,
            HttpServletRequest request) {
        requireAllowed(request);
        return service.search(query, limit);
    }

    @GetMapping("/learners/{learnerId}/diagnostic")
    public DiagnosticReport diagnostic(
            @PathVariable long learnerId,
            @RequestParam(name = "week_start", required = false) LocalDate weekStart,
            HttpServletRequest request) {
        requireAllowed(request);
        return diagnosticReportService.current(learnerId, weekStart);
    }

    @GetMapping("/learners/{learnerId}/speech-evidence")
    public SpeechEvidence speechEvidence(
            @PathVariable long learnerId,
            @RequestParam("domain_id") String domainId,
            @RequestParam(name = "week_start", required = false) LocalDate weekStart,
            HttpServletRequest request) {
        requireAllowed(request);
        DiagnosticReportDomains.requireSupported(domainId);
        return diagnosticReportService.speechEvidence(learnerId, domainId, weekStart);
    }

    @PostMapping("/learners/{learnerId}/ladder-recommendations/{analysisId}/approve")
    public LadderApprovalResponse approveLadderRecommendation(
            @PathVariable long learnerId,
            @PathVariable String analysisId,
            @RequestBody DiagnosticReportController.LadderApprovalRequest approval,
            HttpServletRequest request) {
        requireAllowed(request);
        return diagnosticReportService.approveLadderRecommendation(
                learnerId, analysisId, approval.recommendationVersion());
    }

    private void requireAllowed(HttpServletRequest request) {
        guard.requireAllowed(request.getHeader("X-Mormi-Local-Admin-Key"), request.getRemoteAddr());
    }
}
