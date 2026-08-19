package com.mormi.backend.outcome;

import java.util.List;

/**
 * attempts 와 learning_observations 에서 파생한 문제별 수행 결과.
 *
 * <p>모든 판정 필드가 null 을 허용한다. null 은 '판단할 근거가 없음'이고
 * false 는 '근거를 보고 아니라고 판단함'이다. 이 둘을 합치면
 * 관찰되지 않은 항목이 수행 실패처럼 리포트에 나타난다.
 */
public record TaskOutcomeFields(
        String activity,
        Integer attemptCount,
        Integer wrongAttemptCount,
        Boolean firstTrySuccess,
        Boolean retrySuccess,
        Boolean successAfterHelp,
        String expressionStart,
        String expressionEnd,
        String expressionLowest,
        String hintStart,
        String hintEnd,
        String hintMax,
        String completionOutcome,
        Boolean systemFailure,
        String bottleneckCandidate,
        Integer bottleneckEvidenceCount,
        List<Long> sourceAttemptIds,
        List<Long> sourceObservationIds) {
}
