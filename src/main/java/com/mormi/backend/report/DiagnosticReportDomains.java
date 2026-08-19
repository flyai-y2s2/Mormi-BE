package com.mormi.backend.report;

import com.mormi.backend.common.ApiException;
import java.util.Set;

final class DiagnosticReportDomains {

    private static final Set<String> SUPPORTED = Set.of(
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

    private DiagnosticReportDomains() {
    }

    static void requireSupported(String domainId) {
        if (domainId == null || domainId.isBlank() || !SUPPORTED.contains(domainId)) {
            throw ApiException.badRequest("domain_id", "지원하지 않는 리포트 영역입니다.");
        }
    }
}
