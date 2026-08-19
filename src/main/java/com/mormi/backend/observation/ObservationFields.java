package com.mormi.backend.observation;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 이벤트 payload 에서 뽑아낸 리포트용 관찰값.
 *
 * <p>모든 필드가 null 을 허용한다. null 은 '수집되지 않음'이고 false/0 과 뜻이 다르다.
 * 이벤트에 없던 항목을 기본값으로 채우면 아이가 도움을 쓰지 않은 것과
 * 도움 사용 여부를 모르는 것을 구분할 수 없게 된다.
 */
public record ObservationFields(
        String aiObservationVersion,
        String scenarioId,
        String taskId,
        String stage,
        Integer turnIndex,
        String responseCategory,
        String difficultyClass,
        String conceptResult,
        String bottleneckCandidate,
        String expressionBefore,
        String expressionAfter,
        String hintBefore,
        String hintAfter,
        String transitionReason,
        Boolean helpUsed,
        Boolean fallbackOccurred,
        Boolean systemError,
        String completionOutcome,
        BigDecimal classifierConfidence,
        String evidenceStrength,
        OffsetDateTime observedAt) {
}
