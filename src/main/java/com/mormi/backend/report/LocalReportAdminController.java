package com.mormi.backend.report;

import static com.mormi.backend.report.LocalReportAdminDtos.LocalLearnerResult;

import com.mormi.backend.report.DiagnosticReportDtos.DiagnosticReport;
import com.mormi.backend.report.DiagnosticReportDtos.SpeechEvidence;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Profile("local")
@RestController
@RequestMapping("/v1/local-report-admin")
@ConditionalOnProperty(name = "mormi.local-report-admin.enabled", havingValue = "true")
public class LocalReportAdminController {

    private final LocalReportAdminGuard guard;
    private final LocalReportAdminService service;
    private final DiagnosticReportService diagnosticReportService;

    public LocalReportAdminController(
            LocalReportAdminGuard guard,
            LocalReportAdminService service,
            DiagnosticReportService diagnosticReportService) {
        this.guard = guard;
        this.service = service;
        this.diagnosticReportService = diagnosticReportService;
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

    private void requireAllowed(HttpServletRequest request) {
        guard.requireAllowed(request.getHeader("X-Mormi-Local-Admin-Key"), request.getRemoteAddr());
    }
}
