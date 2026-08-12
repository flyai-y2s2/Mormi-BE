package com.mormi.backend.report;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * ReportDashboard.tsx 의 Report 타입과 같은 필드 구성이다.
 * 프런트가 localStorage 대신 이 응답을 그대로 렌더링할 수 있게 맞췄다.
 *
 * <p>sessionTitle, sessionUnit, misconception, learnedLine 처럼 커리큘럼 본문에 있는 값은
 * 서버가 갖고 있지 않으므로 sessionId 만 돌려주고 프런트가 정적 커리큘럼에서 채운다.
 */
public final class ReportDtos {

    private ReportDtos() {
    }

    public record ReportSummary(
            String date,
            String learningSessionId,
            String sessionId,
            int masteryTarget,
            int repetitions,
            int masterySeconds,
            /** 오개념에 한 번이라도 동조했는지. 오답이 있었으면 true. */
            @JsonProperty("synchronized") boolean misconceptionSynchronized,
            boolean transfer,
            int ladder,
            boolean timedOut,
            Long learnerId,
            String learnerName,
            int earnedCoins,
            int drillCoins,
            int teachCoins,
            int walletBalance,
            int wrongAttemptCount,
            int firstTryCorrectCount) {
    }
}
