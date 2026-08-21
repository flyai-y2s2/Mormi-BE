package com.mormi.backend.observation;

import static org.assertj.core.api.Assertions.assertThat;
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
 * AI 관찰 이벤트 수집이 지켜야 하는 것들을 실제 PostgreSQL 로 검증한다.
 * 1) 같은 이벤트가 여러 번 와도 한 번만 반영된다
 * 2) 늦게 도착한 오래된 관측이 최신 값을 덮지 않는다
 * 3) 반영하지 못한 이벤트도 사유와 함께 남아 재처리할 수 있다
 * 4) 서비스 키 없이는 내부 경로에 닿지 못한다
 */
@SpringBootTest(properties = "mormi.observation.ingest-key=test-ingest-key")
@AutoConfigureMockMvc
@Testcontainers
class ObservationIngestIntegrationTest {

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
    LearningObservationRepository learningObservationRepository;

    @Test
    void 같은_이벤트가_두_번_와도_관찰은_한_번만_반영된다() throws Exception {
        String conversationId = 대화를_만든다("MORMI-OBS-01", "conv-dup");
        Map<String, Object> event = 이벤트(
                "evt-dup", "obs-dup", conversationId, Map.of("observed_at", "2026-08-18T10:00:00Z"));

        수집(event)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("processed"))
                .andExpect(jsonPath("$.duplicate").value(false));

        수집(event)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("processed"))
                .andExpect(jsonPath("$.duplicate").value(true));

        assertThat(observationEventRepository.findByEventId("evt-dup")).isPresent();
        assertThat(learningObservationRepository.findByAiObservationId("obs-dup")).isPresent();
        // 통합 테스트는 같은 DB 를 공유하므로 전체 행이 아니라 이 대화의 행만 센다.
        assertThat(learningObservationRepository.countByConversationId(conversationId)).isEqualTo(1);
    }

    @Test
    void 늦게_도착한_오래된_관측은_최신_값을_덮지_않는다() throws Exception {
        String conversationId = 대화를_만든다("MORMI-OBS-02", "conv-order");

        수집(이벤트("evt-new", "obs-order", conversationId, Map.of(
                "observed_at", "2026-08-18T10:05:00Z",
                "expression_after", "L2")))
                .andExpect(status().isOk());

        // 같은 관찰의 더 오래된 관측이 뒤늦게 도착한다. 이벤트는 새로 쌓이되 값은 그대로여야 한다.
        수집(이벤트("evt-old", "obs-order", conversationId, Map.of(
                "observed_at", "2026-08-18T10:00:00Z",
                "expression_after", "L4")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("processed"));

        LearningObservation observation =
                learningObservationRepository.findByAiObservationId("obs-order").orElseThrow();
        assertThat(observation.getExpressionAfter()).isEqualTo("L2");
        assertThat(observationEventRepository.findByEventId("evt-old")).isPresent();
    }

    @Test
    void 지원하지_않는_스키마_버전은_거절하고_사유와_함께_남는다() throws Exception {
        String conversationId = 대화를_만든다("MORMI-OBS-03", "conv-schema");
        Map<String, Object> event = 이벤트("evt-schema", "obs-schema", conversationId, Map.of());
        event.put("schema_version", "9.9");

        수집(event)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("unsupported_schema_version"));

        ObservationEvent stored = observationEventRepository.findByEventId("evt-schema").orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(ObservationEvent.STATUS_FAILED);
        assertThat(stored.getErrorMessage()).contains("unsupported_schema_version");
        assertThat(stored.getPayload()).isNotEmpty();
        assertThat(learningObservationRepository.findByAiObservationId("obs-schema")).isEmpty();
    }

    @Test
    void 모르는_대화는_재전송하라고_409로_알린다() throws Exception {
        수집(이벤트("evt-unknown", "obs-unknown", "conv-never-registered", Map.of()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("unknown_conversation"));

        ObservationEvent stored = observationEventRepository.findByEventId("evt-unknown").orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(ObservationEvent.STATUS_FAILED);
    }

    @Test
    void 수집되지_않은_항목은_null_로_남아_false_와_구분된다() throws Exception {
        String conversationId = 대화를_만든다("MORMI-OBS-04", "conv-null");
        // help_used 만 보내고 fallback_occurred 는 보내지 않는다.
        수집(이벤트("evt-null", "obs-null", conversationId, Map.of("help_used", false)))
                .andExpect(status().isOk());

        LearningObservation observation =
                learningObservationRepository.findByAiObservationId("obs-null").orElseThrow();
        assertThat(observation.getHelpUsed()).isFalse();
        assertThat(observation.getFallbackOccurred()).isNull();
    }

    @Test
    void 서비스_키가_없으면_내부_경로에_닿지_못한다() throws Exception {
        mockMvc.perform(post(INGEST_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                이벤트("evt-noauth", "obs-noauth", "conv-any", Map.of()))))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post(INGEST_PATH)
                        .header(SERVICE_KEY_HEADER, "wrong-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                이벤트("evt-badauth", "obs-badauth", "conv-any", Map.of()))))
                .andExpect(status().isUnauthorized());

        assertThat(observationEventRepository.findByEventId("evt-noauth")).isEmpty();
    }

    private org.springframework.test.web.servlet.ResultActions 수집(Map<String, Object> event) throws Exception {
        return mockMvc.perform(post(INGEST_PATH)
                .header(SERVICE_KEY_HEADER, SERVICE_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(event)));
    }

    private Map<String, Object> 이벤트(
            String eventId, String observationId, String conversationId, Map<String, Object> extra) {
        Map<String, Object> observation = new LinkedHashMap<>();
        observation.put("observation_id", observationId);
        observation.put("conversation_id", conversationId);
        observation.put("response_category", "explained_with_support");
        observation.putAll(extra);

        Map<String, Object> event = new LinkedHashMap<>();
        event.put("event_id", eventId);
        event.put("schema_version", ObservationIngestService.SCHEMA_VERSION);
        event.put("event_type", "dialogue_observation");
        event.put("observation", observation);
        return event;
    }

    /** 학습자·세션은 API 로 만들고, 대화 소유권 행만 직접 넣는다. */
    private String 대화를_만든다(String researchCode, String conversationId) throws Exception {
        JsonNode learner = AuthTestSupport.signupLearner(mockMvc, objectMapper, "관찰", researchCode);

        String sessionBody = mockMvc.perform(post("/v1/learning-sessions")
                        .header("Authorization", "Bearer " + learner.get("access_token").asString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("curriculum_session_id", "money-count", "variant_seed", 0))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String publicId = objectMapper.readTree(sessionBody).get("learning_session_id").asString();
        Long sessionId = learningSessionRepository.findByPublicId(publicId).orElseThrow().getId();

        dialogueConversationRepository.save(DialogueConversation.forLearningSession(
                conversationId, learner.get("id").asLong(), sessionId));
        return conversationId;
    }
}
