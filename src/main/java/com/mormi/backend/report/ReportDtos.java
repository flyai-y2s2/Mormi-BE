package com.mormi.backend.report;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

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
            /**
             * 하위 호환용. 오답이 하나라도 있으면 true 가 되므로 오개념 확정 표시로 쓰면 안 된다.
             * 오개념 후보 여부는 bottleneckCandidates 의 repeated 로 판단한다.
             */
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
            int firstTryCorrectCount,
            List<BottleneckCandidateView> bottleneckCandidates) {
    }

    /**
     * 관찰에서 나온 병목 후보. repeated 가 false 면 한 번 관찰된 것이므로
     * 화면에서 확정 오개념처럼 표시하지 않는다.
     */
    public record BottleneckCandidateView(
            String candidate,
            int evidenceCount,
            boolean repeated) {
    }
}
