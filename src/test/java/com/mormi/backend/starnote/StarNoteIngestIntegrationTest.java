package com.mormi.backend.starnote;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mormi.backend.dialogue.DialogueConversation;
import com.mormi.backend.dialogue.DialogueConversationRepository;
import com.mormi.backend.observation.ObservationEvent;
import com.mormi.backend.observation.ObservationEventRepository;
import com.mormi.backend.observation.ObservationIngestService;
import com.mormi.backend.session.LearningSessionRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import com.mormi.backend.AuthTestSupport;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * star_note_created 이벤트 수집이 지켜야 하는 것들을 실제 PostgreSQL 로 검증한다.
 * 1) 같은 event_id 재전송은 한 번만 반영된다 (전송 멱등성)
 * 2) 다른 event_id 로 온 같은 note_id 도 한 행만 남는다 (원장 멱등성)
 * 3) note_version 이 오른 재발행만 반영된다 (순서 역전 방어)
 * 4) 근거 관찰이 아직 도착하지 않은 별노트도 수용한다 (FK 없는 evidence_links)
 * 5) 모르는 대화는 409 로 재전송을, 영구 오류는 422 를 돌려준다
 */
@SpringBootTest(properties = "mormi.observation.ingest-key=test-ingest-key")
@AutoConfigureMockMvc
@Testcontainers
class StarNoteIngestIntegrationTest {

    private static final String INGEST_PATH = "/internal/v1/observations/events";
    private static final String SERVICE_KEY_HEADER = "X-Mormi-Service-Key";
    private static final String SERVICE_KEY = "test-ingest-key";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    DialogueConversationRepository dialogueConversationRepository;

    @Autowired
    LearningSessionRepository learningSessionRepository;

    @Autowired
    ObservationEventRepository observationEventRepository;

    @Autowired
    StarNoteRepository starNoteRepository;

    @Test
    void 같은_별노트_이벤트가_두_번_와도_한_행만_남는다() throws Exception {
        대화 대화 = 대화를_만든다("MORMI-SN-01", "conv-sn-dup");
        Map<String, Object> event = 별노트_이벤트("evt-sn-dup", "note-dup", 대화.conversationId(), Map.of());

        String first = 수집(event)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("processed"))
                .andExpect(jsonPath("$.duplicate").value(false))
                .andReturn().getResponse().getContentAsString();

        String second = 수집(event)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("processed"))
                .andExpect(jsonPath("$.duplicate").value(true))
                .andReturn().getResponse().getContentAsString();

        // 재전송 응답도 처음과 같은 원장 행을 가리켜야 AI 가 안심하고 sent 처리할 수 있다.
        assertThat(objectMapper.readTree(second).get("star_note_id").asLong())
                .isEqualTo(objectMapper.readTree(first).get("star_note_id").asLong());
        StarNote stored = starNoteRepository.findByNoteId("note-dup").orElseThrow();
        assertThat(stored.getLearnerId()).isEqualTo(대화.learnerId());
        assertThat(stored.getNoteText()).isEqualTo("색칠된 칸을 하나씩 세면 모두 3개야.");
    }

    @Test
    void 다른_이벤트로_재전송된_같은_노트는_행이_늘지_않는다() throws Exception {
        대화 대화 = 대화를_만든다("MORMI-SN-02", "conv-sn-note-dup");

        수집(별노트_이벤트("evt-sn-a", "note-same", 대화.conversationId(), Map.of()))
                .andExpect(status().isOk());
        // 같은 노트(버전 동일)를 새 event_id 로 재발행. 원장은 그대로여야 한다.
        수집(별노트_이벤트("evt-sn-b", "note-same", 대화.conversationId(), Map.of(
                "text", "다른 문장으로 바꿔치기 시도")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("processed"));

        StarNote stored = starNoteRepository.findByNoteId("note-same").orElseThrow();
        assertThat(stored.getNoteVersion()).isEqualTo(1);
        // 버전을 올리지 않은 내용 변경은 반영하지 않는다. AI 계약: 수정하려면 note_version 을 올린다.
        assertThat(stored.getNoteText()).isEqualTo("색칠된 칸을 하나씩 세면 모두 3개야.");
    }

    @Test
    void 버전이_오른_재발행만_반영된다() throws Exception {
        대화 대화 = 대화를_만든다("MORMI-SN-03", "conv-sn-version");

        수집(별노트_이벤트("evt-sn-v1", "note-ver", 대화.conversationId(), Map.of()))
                .andExpect(status().isOk());
        // 버전 2: 문장 수정 + 비활성화. 반영돼야 한다.
        수집(별노트_이벤트("evt-sn-v2", "note-ver", 대화.conversationId(), Map.of(
                "note_version", 2, "text", "고친 문장", "active", false)))
                .andExpect(status().isOk());

        StarNote afterUpgrade = starNoteRepository.findByNoteId("note-ver").orElseThrow();
        assertThat(afterUpgrade.getNoteVersion()).isEqualTo(2);
        assertThat(afterUpgrade.getNoteText()).isEqualTo("고친 문장");
        assertThat(afterUpgrade.isActive()).isFalse();

        // 버전 1이 뒤늦게 다시 도착해도(순서 역전) 최신 버전을 덮지 않는다.
        수집(별노트_이벤트("evt-sn-v1-late", "note-ver", 대화.conversationId(), Map.of(
                "text", "옛날 문장")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("processed"));

        StarNote afterStale = starNoteRepository.findByNoteId("note-ver").orElseThrow();
        assertThat(afterStale.getNoteVersion()).isEqualTo(2);
        assertThat(afterStale.getNoteText()).isEqualTo("고친 문장");
    }

    @Test
    void 근거_관찰보다_별노트가_먼저_도착해도_수집된다() throws Exception {
        대화 대화 = 대화를_만든다("MORMI-SN-04", "conv-sn-order");

        // evidence_links 가 가리키는 관찰(obs-not-yet)은 아직 수집되지 않았다.
        수집(별노트_이벤트("evt-sn-order", "note-order", 대화.conversationId(), Map.of(
                "evidence_links", List.of(Map.of(
                        "observation_id", "obs-not-yet",
                        "source_slot_ids", List.of("tracking"))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("processed"));

        StarNote stored = starNoteRepository.findByNoteId("note-order").orElseThrow();
        assertThat(stored.getEvidenceLinks()).hasSize(1);
        assertThat(stored.getEvidenceLinks().get(0)).containsEntry("observation_id", "obs-not-yet");
    }

    @Test
    void 모르는_대화의_별노트는_409로_재전송을_알린다() throws Exception {
        수집(별노트_이벤트("evt-sn-unknown", "note-unknown", "conv-sn-never", Map.of()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("unknown_conversation"));

        ObservationEvent stored = observationEventRepository.findByEventId("evt-sn-unknown").orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(ObservationEvent.STATUS_FAILED);
        assertThat(starNoteRepository.findByNoteId("note-unknown")).isEmpty();
    }

    @Test
    void 대화_소유자와_다른_learner_id는_422로_거절한다() throws Exception {
        대화 주인 = 대화를_만든다("MORMI-SN-05", "conv-sn-owner");
        대화 남 = 대화를_만든다("MORMI-SN-06", "conv-sn-other");

        Map<String, Object> event = 별노트_이벤트(
                "evt-sn-owner", "note-owner", 주인.conversationId(), Map.of("learner_id", 남.learnerId()));

        수집(event)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("star_note_owner_mismatch"));

        assertThat(starNoteRepository.findByNoteId("note-owner")).isEmpty();
    }

    @Test
    void 필수값이_빠진_별노트는_422로_사유와_함께_남는다() throws Exception {
        대화 대화 = 대화를_만든다("MORMI-SN-07", "conv-sn-invalid");
        Map<String, Object> event = 별노트_이벤트("evt-sn-invalid", "note-invalid", 대화.conversationId(), Map.of());
        ((Map<String, Object>) event.get("star_note")).remove("text");

        수집(event)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("invalid_payload"));

        // 실패 이벤트도 payload 원본과 사유가 남아야 재처리할 수 있다.
        ObservationEvent stored = observationEventRepository.findByEventId("evt-sn-invalid").orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(ObservationEvent.STATUS_FAILED);
        assertThat(stored.getErrorMessage()).contains("invalid_payload");
        assertThat(stored.getPayload()).isNotEmpty();
    }

    @Test
    void observation_본문이_없는_dialogue_이벤트는_422로_남는다() throws Exception {
        // observation 의 @NotNull 을 서비스 검증으로 옮긴 계약 변화 회귀 방지.
        // 400(수신함에 안 남음)이 아니라 422(실패 이벤트로 보존)여야 한다.
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("event_id", "evt-dialogue-nobody");
        event.put("schema_version", ObservationIngestService.SCHEMA_VERSION);
        event.put("event_type", "dialogue_observation");

        수집(event)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("invalid_payload"));

        ObservationEvent stored =
                observationEventRepository.findByEventId("evt-dialogue-nobody").orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(ObservationEvent.STATUS_FAILED);
    }

    @Test
    void 서비스_키가_없으면_별노트_이벤트도_닿지_못한다() throws Exception {
        mockMvc.perform(post(INGEST_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                별노트_이벤트("evt-sn-noauth", "note-noauth", "conv-any", Map.of()))))
                .andExpect(status().isUnauthorized());

        assertThat(observationEventRepository.findByEventId("evt-sn-noauth")).isEmpty();
    }

    private org.springframework.test.web.servlet.ResultActions 수집(Map<String, Object> event) throws Exception {
        return mockMvc.perform(post(INGEST_PATH)
                .header(SERVICE_KEY_HEADER, SERVICE_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(event)));
    }

    /** AI 계약(OBSERVATION_EVENTS.md)의 star_note_created 예시를 기본값으로 한 이벤트를 만든다. */
    private Map<String, Object> 별노트_이벤트(
            String eventId, String noteId, String conversationId, Map<String, Object> extra) {
        Map<String, Object> starNote = new LinkedHashMap<>();
        starNote.put("note_id", noteId);
        starNote.put("note_version", 1);
        starNote.put("conversation_id", conversationId);
        starNote.put("scene", "home_teach");
        starNote.put("scenario_id", "home_teach");
        starNote.put("task_id", "home_teaching");
        starNote.put("stage", "home_teaching");
        starNote.put("task_index", 0);
        starNote.put("skill_id", "number-count");
        starNote.put("text", "색칠된 칸을 하나씩 세면 모두 3개야.");
        starNote.put("attribution", "child");
        starNote.put("attribution_label", "아이가 알려줌");
        starNote.put("evidence", "direct_explanation");
        starNote.put("active", true);
        starNote.put("created_at", "2026-08-19T00:00:00+00:00");
        starNote.putAll(extra);

        Map<String, Object> event = new LinkedHashMap<>();
        event.put("event_id", eventId);
        event.put("schema_version", ObservationIngestService.SCHEMA_VERSION);
        event.put("event_type", "star_note_created");
        event.put("star_note", starNote);
        return event;
    }

    private record 대화(String conversationId, Long learnerId) {
    }

    /** 학습자·세션은 API 로 만들고, 대화 소유권 행만 직접 넣는다. */
    private 대화 대화를_만든다(String researchCode, String conversationId) throws Exception {
        JsonNode learner = AuthTestSupport.signupLearner(mockMvc, objectMapper, "별노트", researchCode);

        String sessionBody = mockMvc.perform(post("/v1/learning-sessions")
                        .header("Authorization", "Bearer " + learner.get("access_token").asString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("curriculum_session_id", "money-count", "variant_seed", 0))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String publicId = objectMapper.readTree(sessionBody).get("learning_session_id").asString();
        Long sessionId = learningSessionRepository.findByPublicId(publicId).orElseThrow().getId();

        Long learnerId = learner.get("id").asLong();
        dialogueConversationRepository.save(DialogueConversation.forLearningSession(
                conversationId, learnerId, sessionId));
        return new 대화(conversationId, learnerId);
    }
}
