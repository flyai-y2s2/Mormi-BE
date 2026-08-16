package com.mormi.backend.report;

import com.mormi.backend.auth.LearnerPrincipal;
import com.mormi.backend.common.ApiException;
import com.mormi.backend.report.DiagnosticReportDtos.DiagnosticReport;
import com.mormi.backend.report.DiagnosticReportDtos.SpeechEvidence;
import java.util.Set;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Exposes the current, non-persisted diagnostic report for the authenticated learner only. */
@RestController
@RequestMapping("/v1/reports/diagnostic")
public class DiagnosticReportController {

    private static final Set<String> REPORT_DOMAINS = Set.of(
            "number-count",
            "number-compare",
            "money-count",
            "money-price",
            "money-budget",
            "queue",
            "menu",
            "calculate",
            "change",
            "complete");

    private final DiagnosticReportService diagnosticReportService;

    public DiagnosticReportController(DiagnosticReportService diagnosticReportService) {
        this.diagnosticReportService = diagnosticReportService;
    }

    @GetMapping
    public DiagnosticReport current(@AuthenticationPrincipal LearnerPrincipal principal) {
        return diagnosticReportService.current(principal.learnerId());
    }

    @GetMapping("/speech-evidence")
    public SpeechEvidence speechEvidence(
            @AuthenticationPrincipal LearnerPrincipal principal,
            @RequestParam("domain_id") String domainId) {
        requireSupportedDomain(domainId);
        return diagnosticReportService.speechEvidence(principal.learnerId(), domainId);
    }

    private void requireSupportedDomain(String domainId) {
        if (domainId == null || domainId.isBlank() || !REPORT_DOMAINS.contains(domainId)) {
            throw ApiException.badRequest("domain_id", "지원하지 않는 리포트 영역입니다.");
        }
    }
}
