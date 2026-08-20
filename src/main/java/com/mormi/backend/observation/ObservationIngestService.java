package com.mormi.backend.observation;

import tools.jackson.databind.JsonNode;
import com.mormi.backend.dialogue.DialogueConversation;
import com.mormi.backend.dialogue.DialogueConversationRepository;
import com.mormi.backend.outcome.StarNoteOutcomeLinker;
import com.mormi.backend.outcome.TaskOutcomeService;
import com.mormi.backend.observation.ObservationDtos.IngestObservationRequest;
import com.mormi.backend.observation.ObservationDtos.IngestObservationResponse;
import com.mormi.backend.starnote.StarNote;
import com.mormi.backend.starnote.StarNoteFields;
import com.mormi.backend.starnote.StarNoteRepository;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * AI 이벤트를 멱등하게 받아 event_type 별 원장으로 반영한다.
 * dialogue_observation 은 리포트용 관찰값으로, star_note_created 는 별노트 원장으로 간다.
 *
 * <p>세 가지를 보장한다.
 * <ul>
 *   <li>재전송: 같은 event_id 가 몇 번 와도 원장에는 한 번만 반영된다.
 *       별노트는 note_id 로도 막아, 다른 event_id 로 재전송된 같은 노트가 중복되지 않는다.</li>
 *   <li>순서 역전: 늦게 도착한 오래된 관측·낮은 버전의 별노트가 최신 값을 덮어쓰지 않는다.</li>
 *   <li>실패 보존: 반영하지 못한 이벤트도 원본과 사유를 남겨 재처리할 수 있다.</li>
 * </ul>
 */
@Service
public class ObservationIngestService {

    /** Mormi-AI docs/OBSERVATION_EVENTS.md 의 계약 버전. 올릴 때는 AI와 함께 합의한 뒤 바꾼다. */
    public static final String SCHEMA_VERSION = "1";

    public static final String EVENT_TYPE_DIALOGUE_OBSERVATION = "dialogue_observation";
    public static final String EVENT_TYPE_STAR_NOTE_CREATED = "star_note_created";

    private static final Set<String> SUPPORTED_EVENT_TYPES =
            Set.of(EVENT_TYPE_DIALOGUE_OBSERVATION, EVENT_TYPE_STAR_NOTE_CREATED);

    private static final TypeReference<Map<String, Object>> EVIDENCE_LINK_TYPE = new TypeReference<>() {
    };

    private final ObservationEventRepository eventRepository;
    private final LearningObservationRepository observationRepository;
    private final DialogueConversationRepository dialogueConversationRepository;
    private final StarNoteRepository starNoteRepository;
    private final TaskOutcomeService taskOutcomeService;
    private final StarNoteOutcomeLinker starNoteLinker;
    private final JsonMapper jsonMapper;

    public ObservationIngestService(
            ObservationEventRepository eventRepository,
            LearningObservationRepository observationRepository,
            DialogueConversationRepository dialogueConversationRepository,
            StarNoteRepository starNoteRepository,
            TaskOutcomeService taskOutcomeService,
            StarNoteOutcomeLinker starNoteLinker,
            JsonMapper jsonMapper) {
        this.eventRepository = eventRepository;
        this.observationRepository = observationRepository;
        this.dialogueConversationRepository = dialogueConversationRepository;
        this.starNoteRepository = starNoteRepository;
        this.taskOutcomeService = taskOutcomeService;
        this.starNoteLinker = starNoteLinker;
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
            return duplicateResponse(event, request);
        }

        try {
            validateEnvelope(request);
            if (EVENT_TYPE_STAR_NOTE_CREATED.equals(request.eventType())) {
                // 별노트는 과제 집계(recomputeOutcomes)의 입력이 아니므로 재계산하지 않는다.
                Long starNoteId = applyStarNote(event, request);
                event.markProcessed();
                return new IngestObservationResponse(
                        event.getEventId(), ObservationEvent.STATUS_PROCESSED, !inserted,
                        null, null, null, starNoteId);
            }
            Long observationId = applyDialogueObservation(event, request);
            event.markProcessed();
            // 늦게 도착한 관찰이 집계를 낡은 채로 두지 않도록 같은 규칙으로 다시 계산한다.
            recomputeOutcomes(observationId);
            return new IngestObservationResponse(
                    event.getEventId(), ObservationEvent.STATUS_PROCESSED, !inserted,
                    observationId, null, null, null);
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
                    rejected.getMessage(),
                    null);
        }
    }

    /** 처리 완료된 이벤트의 재전송. 타입에 맞는 원장 행 ID 를 찾아 처음과 같은 응답을 돌려준다. */
    private IngestObservationResponse duplicateResponse(
            ObservationEvent event, IngestObservationRequest request) {
        if (EVENT_TYPE_STAR_NOTE_CREATED.equals(request.eventType())) {
            Long starNoteId = request.starNote() == null
                    ? null
                    : starNoteRepository.findByNoteId(text(request.starNote(), "note_id", 100))
                            .map(StarNote::getId)
                            .orElse(null);
            return new IngestObservationResponse(
                    event.getEventId(), ObservationEvent.STATUS_PROCESSED, true,
                    null, null, null, starNoteId);
        }
        Long observationId = request.observation() == null
                ? null
                : observationRepository
                        .findByAiObservationId(text(request.observation(), "observation_id", 100))
                        .map(LearningObservation::getId)
                        .orElse(null);
        return new IngestObservationResponse(
                event.getEventId(), ObservationEvent.STATUS_PROCESSED, true,
                observationId, null, null, null);
    }

    private void recomputeOutcomes(Long observationId) {
        observationRepository.findById(observationId)
                .map(LearningObservation::getLearningSessionId)
                .ifPresent(taskOutcomeService::recompute);
    }

    /** 봉투(schema_version, event_type) 검증. 본문 검증은 타입별 apply 가 맡는다. */
    private void validateEnvelope(IngestObservationRequest request) {
        if (!SCHEMA_VERSION.equals(request.schemaVersion())) {
            throw new ObservationRejectedException(
                    "unsupported_schema_version",
                    "지원하지 않는 이벤트 스키마 버전입니다: " + request.schemaVersion());
        }
        if (!SUPPORTED_EVENT_TYPES.contains(request.eventType())) {
            throw new ObservationRejectedException(
                    "unsupported_event_type", "지원하지 않는 이벤트 유형입니다: " + request.eventType());
        }
    }

    private Long applyDialogueObservation(ObservationEvent event, IngestObservationRequest request) {
        JsonNode body = request.observation();
        if (body == null) {
            throw new ObservationRejectedException(
                    "invalid_payload", "dialogue_observation 이벤트에는 observation 본문이 필요합니다.");
        }
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

    /**
     * 별노트 이벤트를 원장에 반영한다.
     *
     * <p>evidence_links 의 관찰은 아직 도착하지 않았을 수 있으므로 존재를 검사하지 않는다
     * (순서 역전 허용). 같은 note_id 가 다시 오면 note_version 이 올랐을 때만 갱신한다.
     */
    private Long applyStarNote(ObservationEvent event, IngestObservationRequest request) {
        JsonNode body = request.starNote();
        if (body == null) {
            throw new ObservationRejectedException(
                    "invalid_payload", "star_note_created 이벤트에는 star_note 본문이 필요합니다.");
        }
        String noteId = requireText(body, "note_id", 100);
        String conversationId = requireText(body, "conversation_id", 100);

        // 소유권은 이벤트가 보낸 learner_id 가 아니라 우리가 가진 대화 기록에서 끌어온다.
        DialogueConversation conversation = dialogueConversationRepository
                .findByConversationId(conversationId)
                .orElseThrow(() -> new ObservationRejectedException(
                        "unknown_conversation", "등록되지 않은 대화입니다: " + conversationId));

        // 이벤트의 learner_id 는 저장에 쓰지 않지만, 대화 소유자와 다르면 잘못 발행된 이벤트다.
        Long eventLearnerId = longValue(body, "learner_id");
        if (eventLearnerId != null && !eventLearnerId.equals(conversation.getLearnerId())) {
            throw new ObservationRejectedException(
                    "star_note_owner_mismatch",
                    "별노트의 learner_id 가 대화 소유자와 다릅니다: " + noteId);
        }

        StarNoteFields fields = readStarNoteFields(body);
        StarNote existing = starNoteRepository.findByNoteId(noteId).orElse(null);
        if (existing == null) {
            StarNote saved = starNoteRepository
                    .save(StarNote.from(event.getId(), noteId, conversation, fields));
            // outcome 행이 이미 있으면 바로 연결한다. 아직 없으면 recompute 가 나중에 채운다.
            starNoteLinker.relink(saved.getLearningSessionId(), saved.getTaskId());
            return saved.getId();
        }
        if (!existing.getLearnerId().equals(conversation.getLearnerId())) {
            throw new ObservationRejectedException(
                    "star_note_owner_mismatch", "다른 학습자의 별노트를 덮어쓸 수 없습니다: " + noteId);
        }
        // 재발행 판별. 같거나 낮은 버전은 재전송으로 보고 버린다. 재전송은 오류가 아니므로 200.
        if (fields.noteVersion() <= existing.getNoteVersion()) {
            return existing.getId();
        }
        String previousTaskId = existing.getTaskId();
        existing.apply(event.getId(), fields);
        starNoteLinker.relink(existing.getLearningSessionId(), existing.getTaskId());
        // 재발행으로 과제가 바뀌었으면 이전 과제에 남은 연결도 원장 기준으로 다시 계산해 푼다.
        if (previousTaskId != null && !previousTaskId.equals(existing.getTaskId())) {
            starNoteLinker.relink(existing.getLearningSessionId(), previousTaskId);
        }
        return existing.getId();
    }

    private StarNoteFields readStarNoteFields(JsonNode body) {
        Integer noteVersion = integer(body, "note_version");
        if (noteVersion == null || noteVersion < 1) {
            throw new ObservationRejectedException(
                    "invalid_payload", "note_version 은 1 이상의 정수여야 합니다.");
        }
        OffsetDateTime noteCreatedAt = time(body, "created_at");
        if (noteCreatedAt == null) {
            // 목록 정렬 키라서 없으면 커서 페이지네이션이 깨진다.
            throw new ObservationRejectedException("invalid_payload", "created_at 는 필수입니다.");
        }
        return new StarNoteFields(
                noteVersion,
                text(body, "scene", 40),
                text(body, "scenario_id", 100),
                text(body, "task_id", 120),
                text(body, "stage", 40),
                integer(body, "task_index"),
                text(body, "skill_id", 100),
                requireNoteText(body),
                requireText(body, "attribution", 20),
                text(body, "attribution_label", 60),
                text(body, "evidence", 40),
                readEvidenceLinks(body),
                bool(body, "active"),
                noteCreatedAt);
    }

    /** 아이에게 보여줄 문장. TEXT 컬럼이라 길이 제한은 없지만 비어 있으면 노트가 성립하지 않는다. */
    private String requireNoteText(JsonNode body) {
        JsonNode value = body.get("text");
        if (value == null || value.isNull() || value.asString().isBlank()) {
            throw new ObservationRejectedException("invalid_payload", "text 는 필수입니다.");
        }
        return value.asString();
    }

    /**
     * 근거 연결을 검증만 하고 원본 그대로 보존한다.
     *
     * <p>observation_id 존재까지만 확인한다. 해당 관찰이 DB 에 있는지는 검사하지 않는다 —
     * 관찰 이벤트가 별노트보다 늦게 도착해도 수용하기 위해서다(순서 역전 허용).
     */
    private List<Map<String, Object>> readEvidenceLinks(JsonNode body) {
        JsonNode value = body.get("evidence_links");
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isArray()) {
            throw new ObservationRejectedException("invalid_payload", "evidence_links 는 배열이어야 합니다.");
        }
        List<Map<String, Object>> links = new ArrayList<>();
        for (JsonNode link : value) {
            if (!link.isObject() || text(link, "observation_id", 100) == null) {
                throw new ObservationRejectedException(
                        "invalid_payload", "evidence_links 항목에는 observation_id 가 필요합니다.");
            }
            links.add(jsonMapper.convertValue(link, EVIDENCE_LINK_TYPE));
        }
        return links;
    }

    private Long longValue(JsonNode body, String field) {
        JsonNode value = body.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isIntegralNumber()) {
            throw new ObservationRejectedException("invalid_payload", field + " 는 정수여야 합니다.");
        }
        return value.longValue();
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
