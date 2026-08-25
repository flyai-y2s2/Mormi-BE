package com.mormi.backend.report;

import com.mormi.backend.common.ApiException;
import com.mormi.backend.curriculum.CurriculumCatalog;
import java.util.HashSet;
import java.util.Set;

final class DiagnosticReportDomains {

    private static final Set<String> SUPPORTED = supportedDomains();

    private DiagnosticReportDomains() {
    }

    private static Set<String> supportedDomains() {
        Set<String> domains = new HashSet<>(CurriculumCatalog.SESSION_REPORT_LABELS.keySet());
        domains.addAll(Set.of("queue", "menu", "calculate", "change", "complete"));
        return Set.copyOf(domains);
    }

    static void requireSupported(String domainId) {
        if (domainId == null || domainId.isBlank() || !SUPPORTED.contains(domainId)) {
            throw ApiException.badRequest("domain_id", "지원하지 않는 리포트 영역입니다.");
        }
    }
}
