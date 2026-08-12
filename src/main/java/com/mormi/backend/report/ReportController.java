package com.mormi.backend.report;

import com.mormi.backend.auth.LearnerPrincipal;
import com.mormi.backend.report.ReportDtos.ReportSummary;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/reports")
public class ReportController {

    private final ReportService reportService;

    public ReportController(ReportService reportService) {
        this.reportService = reportService;
    }

    @GetMapping("/summary")
    public ReportSummary summary(@AuthenticationPrincipal LearnerPrincipal principal) {
        return reportService.latest(principal.learnerId());
    }

    @GetMapping("/history")
    public List<ReportSummary> history(
            @AuthenticationPrincipal LearnerPrincipal principal,
            @RequestParam(defaultValue = "8") int limit) {
        return reportService.history(principal.learnerId(), limit);
    }
}
