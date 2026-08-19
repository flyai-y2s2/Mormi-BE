package com.mormi.backend.observation;

import tools.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class ObservationDtos {

    private ObservationDtos() {
    }

    /**
     * AI가 보내는 관찰 이벤트 1건.
     *
     * <p>본문은 JsonNode 로 그대로 받는다. 스키마 버전이 올라가 우리가 모르는
     * 필드가 섞여 와도 payload 원본이 보존돼야 재처리할 수 있기 때문이다.
     *
     * <p>event_type 에 따라 observation(dialogue_observation) 또는 star_note(star_note_created)
     * 중 하나만 채워져 온다. 어느 쪽이 필수인지는 타입별로 다르므로 여기서 @NotNull 로 막지 않고
     * 서비스가 invalid_payload(422) 로 거절한다. 그래야 잘못된 이벤트도 수신함에 남아 재처리할 수 있다.
     */
    public record IngestObservationRequest(
            @NotBlank @Size(max = 100) String eventId,
            @NotBlank @Size(max = 20) String schemaVersion,
            @NotBlank @Size(max = 40) String eventType,
            JsonNode observation,
            JsonNode starNote) {
    }

    /**
     * @param duplicate 이미 받은 적 있는 event_id 인지. 재전송이어도 오류가 아니다.
     * @param observationId 반영된 learning_observations 행. 관찰 이벤트가 아니거나 거절된 경우 null.
     * @param starNoteId 반영된 star_notes 행. 별노트 이벤트가 아니거나 거절된 경우 null.
     */
    public record IngestObservationResponse(
            String eventId,
            String status,
            boolean duplicate,
            Long observationId,
            String rejectionCode,
            String rejectionMessage,
            Long starNoteId) {
    }
}
