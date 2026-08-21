package com.mormi.backend.outcome;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mormi.backend.dialogue.DialogueConversation;
import com.mormi.backend.dialogue.DialogueConversationRepository;
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
 * 문제별 집계가 지켜야 하는 것들을 실제 PostgreSQL 로 검증한다.
 * 1) 시도 기록만으로도 집계가 만들어진다
 * 2) 도움 여부를 모르면 독립 완료로 단정하지 않는다
 * 3) 뒤늦게 도착한 관찰이 집계를 고쳐 쓴다
 * 4) 시스템 오류는 아동의 수행 실패와 따로 기록된다
 * 5) 한 번만 관찰된 병목은 횟수로 구분된다
 */
@SpringBootTest(properties = "mormi.observation.ingest-key=test-ingest-key")
@AutoConfigureMockMvc
@Testcontainers
class TaskOutcomeIntegrationTest {

    private static final String TASK = "money-count:1";

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
    void 시도_기록만으로도_집계가_만들어진다() throws Exception {
        학습 학습 = 학습을_시작한다("MORMI-OUT-01");
        시도(학습, 1, false);
        시도(학습, 2, true);
        완료(학습);

        LearningTaskOutcome outcome = 집계(학습);
        assertThat(outcome.getAttemptCount()).isEqualTo(2);
        assertThat(outcome.getWrongAttemptCount()).isEqualTo(1);
        assertThat(outcome.getFirstTrySuccess()).isFalse();
        assertThat(outcome.getRetrySuccess()).isTrue();
        assertThat(outcome.getSourceAttemptIds()).hasSize(2);
        assertThat(outcome.getAggregationRuleVersion()).isEqualTo(TaskOutcomeService.RULE_VERSION);
    }

    @Test
    void 도움_여부를_모르면_독립_완료로_단정하지_않는다() throws Exception {
        학습 학습 = 학습을_시작한다("MORMI-OUT-02");
        시도(학습, 1, true);
        완료(학습);

        LearningTaskOutcome outcome = 집계(학습);
        assertThat(outcome.getFirstTrySuccess()).isTrue();
        // 관찰이 없으므로 도움 사용 여부를 모른다. false 로 채우지 않는다.
        assertThat(outcome.getSuccessAfterHelp()).isNull();
        assertThat(outcome.getCompletionOutcome()).isNull();
        assertThat(outcome.getSystemFailure()).isNull();
    }

    @Test
    void 뒤늦게_도착한_관찰이_집계를_고쳐_쓴다() throws Exception {
        학습 학습 = 학습을_시작한다("MORMI-OUT-03");
        시도(학습, 1, true);
        완료(학습);
        assertThat(집계(학습).getCompletionOutcome()).isNull();

        // 표현은 L4→L2로 내려가고 힌트는 H0→H2로 올라간 상황. 두 사다리가 따로 움직인다.
        관찰(학습, "obs-late", Map.of(
                "help_used", true,
                "expression_before", "L4",
                "expression_after", "L2",
                "hint_before", "H0",
                "hint_after", "H2"));

        LearningTaskOutcome outcome = 집계(학습);
        assertThat(outcome.getSuccessAfterHelp()).isTrue();
        assertThat(outcome.getCompletionOutcome()).isEqualTo("supported");
        assertThat(outcome.getExpressionStart()).isEqualTo("L4");
        assertThat(outcome.getExpressionLowest()).isEqualTo("L2");
        assertThat(outcome.getHintMax()).isEqualTo("H2");
        assertThat(outcome.getSourceObservationIds()).hasSize(1);
    }

    @Test
    void 시스템_오류는_아동의_수행_실패와_따로_기록된다() throws Exception {
        학습 학습 = 학습을_시작한다("MORMI-OUT-04");
        시도(학습, 1, false);
        완료(학습);
        관찰(학습, "obs-sys", Map.of("system_error", true, "help_used", false));

        LearningTaskOutcome outcome = 집계(학습);
        assertThat(outcome.getSystemFailure()).isTrue();
        assertThat(outcome.getCompletionOutcome()).isEqualTo("system_failure");
        // 아이의 오답 기록은 그대로 남는다. 시스템 오류가 이를 지우지 않는다.
        assertThat(outcome.getWrongAttemptCount()).isEqualTo(1);
    }

    @Test
    void 한_번만_관찰된_병목은_횟수로_구분된다() throws Exception {
        학습 학습 = 학습을_시작한다("MORMI-OUT-05");
        시도(학습, 1, false);
        완료(학습);
        관찰(학습, "obs-b1", Map.of("bottleneck_candidate", "carry_over"));

        assertThat(집계(학습).getBottleneckEvidenceCount()).isEqualTo(1);

        관찰(학습, "obs-b2", Map.of("bottleneck_candidate", "carry_over"));

        LearningTaskOutcome outcome = 집계(학습);
        assertThat(outcome.getBottleneckCandidate()).isEqualTo("carry_over");
        assertThat(outcome.getBottleneckEvidenceCount()).isEqualTo(2);
    }

    @Test
    void 리포트가_단일_관찰과_반복_관찰_병목을_구분한다() throws Exception {
        학습 학습 = 학습을_시작한다("MORMI-OUT-06");
        시도(학습, 1, false);
        완료(학습);

        관찰(학습, "obs-rep1", Map.of("bottleneck_candidate", "carry_over"));
        mockMvc.perform(get("/v1/reports/summary")
                        .header("Authorization", "Bearer " + 학습.token()))
                .andExpect(jsonPath("$.bottleneck_candidates[0].candidate").value("carry_over"))
                .andExpect(jsonPath("$.bottleneck_candidates[0].evidence_count").value(1))
                // 한 번 관찰된 후보는 확정 오개념으로 표시하면 안 된다.
                .andExpect(jsonPath("$.bottleneck_candidates[0].repeated").value(false));

        관찰(학습, "obs-rep2", Map.of("bottleneck_candidate", "carry_over"));
        mockMvc.perform(get("/v1/reports/summary")
                        .header("Authorization", "Bearer " + 학습.token()))
                .andExpect(jsonPath("$.bottleneck_candidates[0].evidence_count").value(2))
                .andExpect(jsonPath("$.bottleneck_candidates[0].repeated").value(true));
    }

    private record 학습(String token, long learnerId, String publicId, long sessionId, String conversationId) {
    }

    private 학습 학습을_시작한다(String researchCode) throws Exception {
        JsonNode learner = AuthTestSupport.signupLearner(mockMvc, objectMapper, "집계", researchCode);
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

    private void 시도(학습 학습, int attemptNo, boolean correct) throws Exception {
        mockMvc.perform(post("/v1/learning-sessions/" + 학습.publicId() + "/attempts")
                        .header("Authorization", "Bearer " + 학습.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "activity", "drill",
                                "attempt_no", attemptNo,
                                "item_id", TASK,
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

    private void 관찰(학습 학습, String observationId, Map<String, Object> extra) throws Exception {
        Map<String, Object> observation = new LinkedHashMap<>();
        observation.put("observation_id", observationId);
        observation.put("conversation_id", 학습.conversationId());
        observation.put("task_id", TASK);
        observation.putAll(extra);

        Map<String, Object> event = new LinkedHashMap<>();
        event.put("event_id", "evt-" + observationId);
        // 계약(OBSERVATION_EVENTS.md)은 schema_version 을 숫자로 보낸다. 문자열 강제 변환을 함께 검증한다.
        event.put("schema_version", 1);
        event.put("event_type", "dialogue_observation");
        event.put("observation", observation);

        mockMvc.perform(post("/internal/v1/observations/events")
                        .header("X-Mormi-Service-Key", "test-ingest-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(event)))
                .andExpect(status().isOk());
    }

    private LearningTaskOutcome 집계(학습 학습) {
        return outcomeRepository.findByLearningSessionIdAndTaskKey(학습.sessionId(), TASK).orElseThrow();
    }
}
