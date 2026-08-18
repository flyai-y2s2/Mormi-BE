package com.mormi.backend.observation;

import tools.jackson.databind.JsonNode;
import com.mormi.backend.dialogue.DialogueConversation;
import com.mormi.backend.dialogue.DialogueConversationRepository;
import com.mormi.backend.outcome.TaskOutcomeService;
import com.mormi.backend.observation.ObservationDtos.IngestObservationRequest;
import com.mormi.backend.observation.ObservationDtos.IngestObservationResponse;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * AI 관찰 이벤트를 멱등하게 받아 리포트용 관찰값으로 반영한다.
 *
 * <p>세 가지를 보장한다.
 * <ul>
 *   <li>재전송: 같은 event_id 가 몇 번 와도 관찰은 한 번만 반영된다.</li>
 *   <li>순서 역전: 늦게 도착한 오래된 관측이 최신 값을 덮어쓰지 않는다.</li>
 *   <li>실패 보존: 반영하지 못한 이벤트도 원본과 사유를 남겨 재처리할 수 있다.</li>
 * </ul>
 */
@Service
public class ObservationIngestService {

    /** Mormi-AI docs/OBSERVATION_EVENTS.md 의 계약 버전. 올릴 때는 AI와 함께 합의한 뒤 바꾼다. */
    public static final String SCHEMA_VERSION = "1";

    private static final Set<String> SUPPORTED_EVENT_TYPES = Set.of("dialogue_observation");

    private final ObservationEventRepository eventRepository;
    private final LearningObservationRepository observationRepository;
    private final DialogueConversationRepository dialogueConversationRepository;
    private final TaskOutcomeService taskOutcomeService;
    private final JsonMapper jsonMapper;

    public ObservationIngestService(
            ObservationEventRepository eventRepository,
            LearningObservationRepository observationRepository,
            DialogueConversationRepository dialogueConversationRepository,
            TaskOutcomeService taskOutcomeService,
            JsonMapper jsonMapper) {
        this.eventRepository = eventRepository;
        this.observationRepository = observationRepository;
        this.dialogueConversationRepository = dialogueConversationRepository;
        this.taskOutcomeService = taskOutcomeService;
        this.jsonMapper = jsonMapper;
    }

    @Transactional
    public IngestObservationResponse ingest(IngestObservationRequest request) {
        boolean inserted = eventRepository.insertIfAbsent(
                request.eventId(),
                request.schemaVersion(),
                request.eventType(),
                writePayload(request)) == 1;

        ObservationEvent event = eventRepository.findByEventIdForUpdate(request.eventId())
                .orElseThrow(() -> new IllegalStateException(
                        "이벤트를 저장한 직후 찾지 못했습니다: " + request.eventId()));

        // 이미 반영된 재전송이면 아무것도 다시 쓰지 않고 같은 결과를 돌려준다.
        if (!inserted && ObservationEvent.STATUS_PROCESSED.equals(event.getStatus())) {
            Long observationId = observationRepository
                    .findByAiObservationId(text(request.observation(), "observation_id", 100))
                    .map(LearningObservation::getId)
                    .orElse(null);
            return new IngestObservationResponse(
                    event.getEventId(), ObservationEvent.STATUS_PROCESSED, true, observationId, null, null);
        }

        try {
            Long observationId = apply(event, request);
            event.markProcessed();
            // 늦게 도착한 관찰이 집계를 낡은 채로 두지 않도록 같은 규칙으로 다시 계산한다.
            recomputeOutcomes(observationId);
            return new IngestObservationResponse(
                    event.getEventId(), ObservationEvent.STATUS_PROCESSED, !inserted, observationId, null, null);
        } catch (ObservationRejectedException rejected) {
            // 예외를 그대로 던지면 트랜잭션이 되돌아가 이벤트 원본까지 사라진다.
            // 여기서 잡아 failed 로 남기고, 응답 코드는 컨트롤러가 정한다.
            event.markFailed(rejected.getCode() + ": " + rejected.getMessage());
            return new IngestObservationResponse(
                    event.getEventId(),
                    ObservationEvent.STATUS_FAILED,
                    !inserted,
                    null,
                    rejected.getCode(),
                    rejected.getMessage());
        }
    }

    private void recomputeOutcomes(Long observationId) {
        observationRepository.findById(observationId)
                .map(LearningObservation::getLearningSessionId)
                .ifPresent(taskOutcomeService::recompute);
    }

    private Long apply(ObservationEvent event, IngestObservationRequest request) {
        if (!SCHEMA_VERSION.equals(request.schemaVersion())) {
            throw new ObservationRejectedException(
                    "unsupported_schema_version",
                    "지원하지 않는 이벤트 스키마 버전입니다: " + request.schemaVersion());
        }
        if (!SUPPORTED_EVENT_TYPES.contains(request.eventType())) {
            throw new ObservationRejectedException(
                    "unsupported_event_type", "지원하지 않는 이벤트 유형입니다: " + request.eventType());
        }

        JsonNode body = request.observation();
        String aiObservationId = requireText(body, "observation_id", 100);
        String conversationId = requireText(body, "conversation_id", 100);

        // 소유권은 이벤트가 보낸 learner_id 가 아니라 우리가 가진 대화 기록에서 끌어온다.
        DialogueConversation conversation = dialogueConversationRepository
                .findByConversationId(conversationId)
                .orElseThrow(() -> new ObservationRejectedException(
                        "unknown_conversation", "등록되지 않은 대화입니다: " + conversationId));

        ObservationFields fields = readFields(body);
        LearningObservation existing = observationRepository.findByAiObservationId(aiObservationId).orElse(null);
        if (existing == null) {
            return observationRepository
                    .save(LearningObservation.from(event.getId(), aiObservationId, conversation, fields))
                    .getId();
        }
        if (!existing.getLearnerId().equals(conversation.getLearnerId())) {
            throw new ObservationRejectedException(
                    "observation_owner_mismatch", "다른 학습자의 관찰을 덮어쓸 수 없습니다: " + aiObservationId);
        }
        // 순서 역전. 늦게 도착한 오래된 관측은 최신 값을 덮어쓰지 않고 버린다.
        if (isStale(fields.observedAt(), existing.getObservedAt())) {
            return existing.getId();
        }
        existing.apply(event.getId(), fields);
        return existing.getId();
    }

    private ObservationFields readFields(JsonNode body) {
        return new ObservationFields(
                text(body, "observation_version", 20),
                text(body, "scenario_id", 100),
                text(body, "task_id", 120),
                text(body, "stage", 40),
                integer(body, "turn_index"),
                text(body, "response_category", 40),
                text(body, "difficulty_class", 40),
                text(body, "concept_result", 40),
                text(body, "bottleneck_candidate", 60),
                text(body, "expression_before", 4),
                text(body, "expression_after", 4),
                text(body, "hint_before", 4),
                text(body, "hint_after", 4),
                text(body, "transition_reason", 60),
                bool(body, "help_used"),
                bool(body, "fallback_occurred"),
                bool(body, "system_error"),
                text(body, "completion_outcome", 30),
                confidence(body),
                text(body, "evidence_strength", 20),
                time(body, "observed_at"));
    }

    private boolean isStale(OffsetDateTime incoming, OffsetDateTime stored) {
        if (stored == null) {
            return false;
        }
        // 시각이 없는 관측으로 시각이 있는 관측을 덮어쓰지 않는다.
        if (incoming == null) {
            return true;
        }
        return incoming.isBefore(stored);
    }

    private String writePayload(IngestObservationRequest request) {
        return jsonMapper.writeValueAsString(request);
    }

    /** 값이 없으면 null 을 준다. null 은 '수집 안 됨'이고 빈 문자열·false 와 구분된다. */
    private String text(JsonNode body, String field, int maxLength) {
        JsonNode value = body.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String result = value.asString();
        if (result.isBlank()) {
            return null;
        }
        // 길이를 여기서 막지 않으면 DB 제약 위반이 500 으로 터지면서
        // 실패 이벤트 기록까지 함께 롤백된다.
        if (result.length() > maxLength) {
            throw new ObservationRejectedException(
                    "invalid_payload", field + " 길이가 " + maxLength + "자를 넘습니다.");
        }
        return result;
    }

    private String requireText(JsonNode body, String field, int maxLength) {
        String value = text(body, field, maxLength);
        if (value == null) {
            throw new ObservationRejectedException("invalid_payload", field + " 는 필수입니다.");
        }
        return value;
    }

    private Integer integer(JsonNode body, String field) {
        JsonNode value = body.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isInt()) {
            throw new ObservationRejectedException("invalid_payload", field + " 는 정수여야 합니다.");
        }
        return value.intValue();
    }

    private Boolean bool(JsonNode body, String field) {
        JsonNode value = body.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isBoolean()) {
            throw new ObservationRejectedException("invalid_payload", field + " 는 true/false 여야 합니다.");
        }
        return value.booleanValue();
    }

    private BigDecimal confidence(JsonNode body) {
        JsonNode value = body.get("classifier_confidence");
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isNumber()) {
            throw new ObservationRejectedException("invalid_payload", "classifier_confidence 는 숫자여야 합니다.");
        }
        BigDecimal result = value.decimalValue();
        if (result.compareTo(BigDecimal.ZERO) < 0 || result.compareTo(BigDecimal.ONE) > 0) {
            throw new ObservationRejectedException(
                    "invalid_payload", "classifier_confidence 는 0 과 1 사이여야 합니다.");
        }
        return result.setScale(3, java.math.RoundingMode.HALF_UP);
    }

    private OffsetDateTime time(JsonNode body, String field) {
        String value = text(body, field, 40);
        if (value == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value);
        } catch (DateTimeParseException e) {
            throw new ObservationRejectedException(
                    "invalid_payload", field + " 는 ISO-8601 시각이어야 합니다: " + value);
        }
    }
}
