package com.mormi.backend.report;

import com.mormi.backend.auth.AccountPrincipal;
import com.mormi.backend.report.DiagnosticReportDtos.DiagnosticReport;
import com.mormi.backend.report.DiagnosticReportDtos.SpeechEvidence;
import com.mormi.backend.report.DiagnosticReportDtos.LadderApprovalResponse;
import java.time.LocalDate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Exposes the current, non-persisted diagnostic report for the authenticated learner only. */
@RestController
@RequestMapping("/v1/reports/diagnostic")
public class DiagnosticReportController {

    public record LadderApprovalRequest(int recommendationVersion) {
    }

    private final DiagnosticReportService diagnosticReportService;

    public DiagnosticReportController(DiagnosticReportService diagnosticReportService) {
        this.diagnosticReportService = diagnosticReportService;
    }

    @GetMapping
    public DiagnosticReport current(
            @AuthenticationPrincipal AccountPrincipal principal,
            @RequestParam(name = "week_start", required = false) LocalDate weekStart) {
        return diagnosticReportService.current(principal.subjectId(), weekStart);
    }

    @GetMapping("/speech-evidence")
    public SpeechEvidence speechEvidence(
            @AuthenticationPrincipal AccountPrincipal principal,
            @RequestParam("domain_id") String domainId,
            @RequestParam(name = "week_start", required = false) LocalDate weekStart) {
        DiagnosticReportDomains.requireSupported(domainId);
        return diagnosticReportService.speechEvidence(principal.subjectId(), domainId, weekStart);
    }

    @PostMapping("/ladder-recommendations/{analysisId}/approve")
    public LadderApprovalResponse approveLadderRecommendation(
            @AuthenticationPrincipal AccountPrincipal principal,
            @PathVariable String analysisId,
            @RequestBody LadderApprovalRequest request) {
        return diagnosticReportService.approveLadderRecommendation(
                principal.subjectId(), analysisId, request.recommendationVersion());
    }
}
