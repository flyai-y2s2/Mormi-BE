package com.mormi.backend.observation;

import tools.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class ObservationDtos {

    private ObservationDtos() {
    }

    /**
     * AI가 보내는 관찰 이벤트 1건.
     *
     * <p>observation 본문은 JsonNode 로 그대로 받는다. 스키마 버전이 올라가 우리가 모르는
     * 필드가 섞여 와도 payload 원본이 보존돼야 재처리할 수 있기 때문이다.
     */
    public record IngestObservationRequest(
            @NotBlank @Size(max = 100) String eventId,
            @NotBlank @Size(max = 20) String schemaVersion,
            @NotBlank @Size(max = 40) String eventType,
            @NotNull JsonNode observation) {
    }

    /**
     * @param duplicate 이미 받은 적 있는 event_id 인지. 재전송이어도 오류가 아니다.
     * @param observationId 반영된 learning_observations 행. 거절된 경우 null.
     */
    public record IngestObservationResponse(
            String eventId,
            String status,
            boolean duplicate,
            Long observationId,
            String rejectionCode,
            String rejectionMessage) {
    }
}
