package com.mormi.backend.outcome;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mormi.backend.dialogue.DialogueConversation;
import com.mormi.backend.dialogue.DialogueConversationRepository;
import com.mormi.backend.observation.ObservationIngestService;
import com.mormi.backend.session.LearningSessionRepository;
import java.util.LinkedHashMap;
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
 * 별노트 원장과 learning_task_outcomes 의 star_note_* 컬럼 연결(이슈 #14)을 검증한다.
 * 1) 집계가 있으면 별노트가 도착하는 즉시 연결된다
 * 2) 별노트가 먼저 도착해도 집계가 생길 때 채워진다 (순서 역전)
 * 3) 같은 과제에 별노트가 여러 개면 최신 생성 노트로 연결된다
 * 4) 재계산은 별노트 연결을 지우지 않는다
 * 5) 비활성화 재발행이 오면 연결이 풀린다
 * 6) 재발행으로 과제가 바뀌면 이전 과제의 연결이 풀린다
 */
@SpringBootTest(properties = "mormi.observation.ingest-key=test-ingest-key")
@AutoConfigureMockMvc
@Testcontainers
class StarNoteOutcomeLinkIntegrationTest {

    private static final String TASK = "money-count:1";
    private static final String OTHER_TASK = "money-count:2";

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
    LearningTaskOutcomeRepository outcomeRepository;

    @Test
    void 집계가_있으면_별노트가_도착하는_즉시_연결된다() throws Exception {
        학습 학습 = 학습을_시작한다("MORMI-LNK-01");
        시도(학습, TASK, 1, true);
        완료(학습);

        별노트(학습, "evt-lnk-01", "note-lnk-01", Map.of());

        LearningTaskOutcome outcome = 집계(학습, TASK);
        assertThat(outcome.getStarNoteId()).isEqualTo("note-lnk-01");
        assertThat(outcome.getStarNoteAttribution()).isEqualTo("child");
        assertThat(outcome.getStarNoteEvidence()).isEqualTo("direct_explanation");
    }

    @Test
    void 별노트가_먼저_도착해도_집계가_생길_때_채워진다() throws Exception {
        학습 학습 = 학습을_시작한다("MORMI-LNK-02");

        // 시도·완료 전이므로 outcome 행이 아직 없다. 별노트는 원장에서 기다린다.
        별노트(학습, "evt-lnk-02", "note-lnk-02", Map.of());
        assertThat(outcomeRepository.findByLearningSessionIdAndTaskKey(학습.sessionId(), TASK))
                .isEmpty();

        시도(학습, TASK, 1, true);
        완료(학습);

        LearningTaskOutcome outcome = 집계(학습, TASK);
        assertThat(outcome.getStarNoteId()).isEqualTo("note-lnk-02");
    }

    @Test
    void 같은_과제에_별노트가_여러_개면_최신_생성_노트로_연결된다() throws Exception {
        학습 학습 = 학습을_시작한다("MORMI-LNK-03");
        시도(학습, TASK, 1, true);
        완료(학습);

        별노트(학습, "evt-lnk-03a", "note-lnk-03a", Map.of(
                "created_at", "2026-08-19T00:00:00+00:00"));
        별노트(학습, "evt-lnk-03b", "note-lnk-03b", Map.of(
                "created_at", "2026-08-19T01:00:00+00:00", "attribution", "mormi"));

        assertThat(집계(학습, TASK).getStarNoteId()).isEqualTo("note-lnk-03b");
        assertThat(집계(학습, TASK).getStarNoteAttribution()).isEqualTo("mormi");

        // 더 오래된 노트가 뒤늦게 도착해도(순서 역전) 최신 연결을 빼앗지 않는다.
        별노트(학습, "evt-lnk-03c", "note-lnk-03c", Map.of(
                "created_at", "2026-08-18T00:00:00+00:00"));
        assertThat(집계(학습, TASK).getStarNoteId()).isEqualTo("note-lnk-03b");
    }

    @Test
    void 재계산은_별노트_연결을_지우지_않는다() throws Exception {
        학습 학습 = 학습을_시작한다("MORMI-LNK-04");
        시도(학습, TASK, 1, true);
        완료(학습);
        별노트(학습, "evt-lnk-04", "note-lnk-04", Map.of());

        // 관찰이 뒤늦게 도착하면 집계가 다시 계산된다. 이때 별노트 연결이 살아 있어야 한다.
        관찰(학습, "obs-lnk-04", Map.of("help_used", true));

        LearningTaskOutcome outcome = 집계(학습, TASK);
        assertThat(outcome.getSuccessAfterHelp()).isTrue();
        assertThat(outcome.getStarNoteId()).isEqualTo("note-lnk-04");
    }

    @Test
    void 비활성화_재발행이_오면_연결이_풀린다() throws Exception {
        학습 학습 = 학습을_시작한다("MORMI-LNK-05");
        시도(학습, TASK, 1, true);
        완료(학습);
        별노트(학습, "evt-lnk-05a", "note-lnk-05", Map.of());
        assertThat(집계(학습, TASK).getStarNoteId()).isEqualTo("note-lnk-05");

        별노트(학습, "evt-lnk-05b", "note-lnk-05", Map.of(
                "note_version", 2, "active", false));

        LearningTaskOutcome outcome = 집계(학습, TASK);
        assertThat(outcome.getStarNoteId()).isNull();
        assertThat(outcome.getStarNoteAttribution()).isNull();
        assertThat(outcome.getStarNoteEvidence()).isNull();
    }

    @Test
    void 재발행으로_과제가_바뀌면_이전_과제의_연결이_풀린다() throws Exception {
        학습 학습 = 학습을_시작한다("MORMI-LNK-06");
        시도(학습, TASK, 1, true);
        // attempt_no 가 같으면 재전송으로 취급돼 무시되므로 과제마다 다른 번호를 쓴다.
        시도(학습, OTHER_TASK, 2, true);
        완료(학습);

        별노트(학습, "evt-lnk-06a", "note-lnk-06", Map.of());
        assertThat(집계(학습, TASK).getStarNoteId()).isEqualTo("note-lnk-06");

        별노트(학습, "evt-lnk-06b", "note-lnk-06", Map.of(
                "note_version", 2, "task_id", OTHER_TASK));

        assertThat(집계(학습, TASK).getStarNoteId()).isNull();
        assertThat(집계(학습, OTHER_TASK).getStarNoteId()).isEqualTo("note-lnk-06");
    }

    private record 학습(String token, long learnerId, String publicId, long sessionId, String conversationId) {
    }

    private 학습 학습을_시작한다(String researchCode) throws Exception {
        JsonNode learner = AuthTestSupport.signupLearner(mockMvc, objectMapper, "연결", researchCode);
        String token = learner.get("access_token").asString();

        String sessionBody = mockMvc.perform(post("/v1/learning-sessions")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("curriculum_session_id", "money-count", "variant_seed", 0))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String publicId = objectMapper.readTree(sessionBody).get("learning_session_id").asString();
        long sessionId = learningSessionRepository.findByPublicId(publicId).orElseThrow().getId();

        String conversationId = "conv-" + researchCode;
        dialogueConversationRepository.save(DialogueConversation.forLearningSession(
                conversationId, learner.get("id").asLong(), sessionId));
        return new 학습(token, learner.get("id").asLong(), publicId, sessionId, conversationId);
    }

    private void 시도(학습 학습, String task, int attemptNo, boolean correct) throws Exception {
        mockMvc.perform(post("/v1/learning-sessions/" + 학습.publicId() + "/attempts")
                        .header("Authorization", "Bearer " + 학습.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "activity", "drill",
                                "attempt_no", attemptNo,
                                "item_id", task,
                                "question_index", 0,
                                "is_correct", correct,
                                "elapsed_ms", 1000))))
                .andExpect(status().isCreated());
    }

    private void 완료(학습 학습) throws Exception {
        mockMvc.perform(post("/v1/learning-sessions/" + 학습.publicId() + "/complete")
                        .header("Authorization", "Bearer " + 학습.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "transfer_solved", false,
                                "timed_out", false,
                                "scaffold_level", 1,
                                "elapsed_seconds", 60))))
                .andExpect(status().isOk());
    }

    /** AI 계약 예시를 기본값으로 하되 task_id 는 집계 과제(TASK)에 맞춘다. */
    private void 별노트(학습 학습, String eventId, String noteId, Map<String, Object> extra) throws Exception {
        Map<String, Object> starNote = new LinkedHashMap<>();
        starNote.put("note_id", noteId);
        starNote.put("note_version", 1);
        starNote.put("conversation_id", 학습.conversationId());
        starNote.put("scene", "home_teach");
        starNote.put("scenario_id", "money-count");
        starNote.put("task_id", TASK);
        starNote.put("stage", "practice");
        starNote.put("task_index", 0);
        starNote.put("skill_id", "money-count");
        starNote.put("text", "동전을 하나씩 세면 모두 3개야.");
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

        mockMvc.perform(post("/internal/v1/observations/events")
                        .header("X-Mormi-Service-Key", "test-ingest-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(event)))
                .andExpect(status().isOk());
    }

    private void 관찰(학습 학습, String observationId, Map<String, Object> extra) throws Exception {
        Map<String, Object> observation = new LinkedHashMap<>();
        observation.put("observation_id", observationId);
        observation.put("conversation_id", 학습.conversationId());
        observation.put("task_id", TASK);
        observation.putAll(extra);

        Map<String, Object> event = new LinkedHashMap<>();
        event.put("event_id", "evt-" + observationId);
        event.put("schema_version", 1);
        event.put("event_type", "dialogue_observation");
        event.put("observation", observation);

        mockMvc.perform(post("/internal/v1/observations/events")
                        .header("X-Mormi-Service-Key", "test-ingest-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(event)))
                .andExpect(status().isOk());
    }

    private LearningTaskOutcome 집계(학습 학습, String task) {
        return outcomeRepository.findByLearningSessionIdAndTaskKey(학습.sessionId(), task).orElseThrow();
    }
}
