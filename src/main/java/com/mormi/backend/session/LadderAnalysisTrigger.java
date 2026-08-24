package com.mormi.backend.session;

import java.util.List;
import java.util.Map;

/** Metadata-only request for a subunit ladder analysis. No child speech is copied here. */
public record LadderAnalysisTrigger(long learnerId, String triggerSessionId) {

    public record Performance(int correct, int attempts) {}

    public record Request(
            String idempotencyKey,
            long learnerId,
            String skillId,
            String triggerSessionId,
            List<String> sessionIds,
            String currentLevel,
            Map<String, Performance> performanceByLevel,
            int lowerRuleEvidenceCount) {}
}
