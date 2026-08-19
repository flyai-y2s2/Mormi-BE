package com.mormi.backend.report;

import static com.mormi.backend.report.LocalReportAdminDtos.LocalLearnerResult;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
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

    public LocalReportAdminController(LocalReportAdminGuard guard, LocalReportAdminService service) {
        this.guard = guard;
        this.service = service;
    }

    @GetMapping("/learners")
    public List<LocalLearnerResult> learners(
            @RequestParam String query,
            @RequestParam(defaultValue = "10") int limit,
            HttpServletRequest request) {
        guard.requireAllowed(request.getHeader("X-Mormi-Local-Admin-Key"), request.getRemoteAddr());
        return service.search(query, limit);
    }
}
