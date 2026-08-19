package com.mormi.backend.report;

public final class LocalReportAdminDtos {

    private LocalReportAdminDtos() {}

    public record LocalLearnerResult(long learnerId, String displayName) {}
}
