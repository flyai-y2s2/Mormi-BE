package com.mormi.backend.dialogue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.ObjectMapper;

/**
 * AI가 보낸 turn.task_anchor 를 BE가 변형·누락 없이 전달하는지 검증한다.
 *
 * <p>앵커는 아이가 지금 답해야 할 고정 질문이다. 모르미 대사는 도움 반응으로 계속 바뀌므로
 * FE는 대사에서 질문을 되살릴 수 없고, AI가 보낸 이 필드에만 의존한다. 이 필드가 유실돼도
 * 예외는 나지 않는다. FE가 값이 없으면 조용히 렌더링을 건너뛰기 때문에, 화면에서 질문만
 * 사라지고 로그에는 아무것도 남지 않는다. 그래서 계약 테스트가 유일한 탐지 수단이다.
 *
 * <p>가짜 AI가 돌려주는 응답은 지어낸 것이 아니라 Mormi-AI 의 ConversationEngine 을 실제로
 * 실행해 뽑은 뒤 SessionEnvelope 스키마로 검증한 것이다(fixtures/dialogue-*-envelope.json).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class TaskAnchorContractIntegrationTest {

    /** 픽스처가 담고 있는 대화 ID. 가짜 AI 는 대화마다 이 자리를 새 값으로 바꿔 발급한다. */
    private static final String FIXTURE_CONVERSATION_ID = "conversation_hometeachanchor01";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    /** 실제 HTTP 를 타야 JsonNode 수신부터 응답 직렬화까지 전 구간이 검증된다. */
    static HttpServer fakeAi;
    static String homeTeachEnvelope;
    static final Map<String, AtomicInteger> hits = new ConcurrentHashMap<>();
    /** 발급한 대화 ID 별로 그때 내려준 봉투 원문. 조회·응답도 같은 것을 돌려준다. */
    static final Map<String, String> servedByConversationId = new ConcurrentHashMap<>();
    static final AtomicInteger conversationSequence = new AtomicInteger();
    static final ObjectMapper FIXTURE_JSON = new ObjectMapper();

    static {
        homeTeachEnvelope = readFixture("/fixtures/dialogue-home-teach-anchor-envelope.json");
        try {
            fakeAi = HttpServer.create(new InetSocketAddress(0), 0);
            fakeAi.createContext("/v1", TaskAnchorContractIntegrationTest::handle);
            fakeAi.start();
        } catch (IOException error) {
            throw new IllegalStateException("가짜 AI 서버를 띄우지 못했습니다", error);
        }
    }

    static String readFixture(String path) {
        try (InputStream in = TaskAnchorContractIntegrationTest.class.getResourceAsStream(path)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new IllegalStateException("픽스처를 읽지 못했습니다: " + path, error);
        }
    }

    static void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        hits.computeIfAbsent(path, key -> new AtomicInteger()).incrementAndGet();

        // 진짜 AI 는 대화마다 새 conversation_id 를 발급한다. 고정 ID 를 돌려주면
        // dialogue_conversations 의 UNIQUE 제약에 걸려 두 번째 테스트부터 500 이 난다.
        if (path.equals("/v1/conversations")) {
            respond(exchange, 201, issueConversation());
            return;
        }
        // 조회와 응답 모두 그 대화에 내려준 봉투를 그대로 돌려준다. 여기서 확인할 것은
        // AI 의 턴 전개가 아니라 BE 가 받은 턴을 그대로 넘기는지이기 때문이다.
        String conversationId = conversationIdOf(path);
        String served = conversationId == null ? null : servedByConversationId.get(conversationId);
        if (served != null) {
            respond(exchange, 200, served);
            return;
        }
        respond(exchange, 404, "{\"detail\":\"Conversation not found\"}");
    }

    /** 픽스처의 대화 ID 자리만 새 값으로 바꿔 발급한다. 나머지 필드는 손대지 않는다. */
    static String issueConversation() {
        String conversationId = FIXTURE_CONVERSATION_ID + "-" + conversationSequence.incrementAndGet();
        ObjectNode envelope = (ObjectNode) FIXTURE_JSON.readTree(homeTeachEnvelope);
        envelope.put("conversation_id", conversationId);
        String body = FIXTURE_JSON.writeValueAsString(envelope);
        servedByConversationId.put(conversationId, body);
        return body;
    }

    /** /v1/conversations/{id} 와 /v1/conversations/{id}/responses 에서 대화 ID 를 뽑는다. */
    static String conversationIdOf(String path) {
        String prefix = "/v1/conversations/";
        if (!path.startsWith(prefix)) {
            return null;
        }
        String rest = path.substring(prefix.length());
        return rest.endsWith("/responses") ? rest.substring(0, rest.length() - "/responses".length()) : rest;
    }

    static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @DynamicPropertySource
    static void aiProperties(DynamicPropertyRegistry registry) {
        registry.add("mormi.dialogue.base-url",
                () -> "http://localhost:" + fakeAi.getAddress().getPort());
        registry.add("mormi.dialogue.service-key", () -> "test-service-key");
    }

    @AfterAll
    static void stopFakeAi() {
        fakeAi.stop(0);
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @BeforeEach
    void resetFakeAi() {
        hits.clear();
    }

    /** 가짜 AI 가 그 대화에 실제로 내려준 봉투. BE 응답을 이것과 통째로 비교한다. */
    private JsonNode served(String conversationId) {
        String body = servedByConversationId.get(conversationId);
        assertThat(body).as("가짜 AI 가 발급하지 않은 대화입니다: %s", conversationId).isNotNull();
        return objectMapper.readTree(body);
    }

    @Test
    void 가르치기_시작_응답에_앵커가_그대로_실린다() throws Exception {
        String token = createLearner("민서", "MORMI-TA01");
        String sessionId = startSessionWithFiveCorrectDrills(token, "number-count");

        JsonNode response = startTeaching(token, sessionId);

        // 집 경로는 AI 봉투를 재조립하지 않는다. 봉투 전체가 같아야 한다.
        assertThat(response).isEqualTo(served(response.path("conversation_id").asString()));
        assertThat(anchorOf(response)).isEqualTo(expectedAnchor());
    }

    @Test
    void 새로고침_복구에도_같은_앵커가_온다() throws Exception {
        String token = createLearner("도윤", "MORMI-TA02");
        String sessionId = startSessionWithFiveCorrectDrills(token, "number-count");

        JsonNode created = startTeaching(token, sessionId);
        String conversationId = created.path("conversation_id").asString();
        // 두 번째 호출은 대화를 새로 만들지 않고 저장된 대화를 조회해 돌려준다.
        JsonNode recovered = startTeaching(token, sessionId);

        assertThat(recovered.path("conversation_id").asString()).isEqualTo(conversationId);
        assertThat(anchorOf(recovered)).isEqualTo(expectedAnchor());
        assertThat(hits.get("/v1/conversations").get()).isEqualTo(1);
        assertThat(hits.get("/v1/conversations/" + conversationId).get()).isEqualTo(1);
    }

    @Test
    void 대화_조회에_앵커가_그대로_실린다() throws Exception {
        String token = createLearner("서아", "MORMI-TA03");
        String sessionId = startSessionWithFiveCorrectDrills(token, "number-count");
        String conversationId = startTeaching(token, sessionId).path("conversation_id").asString();

        String body = mockMvc.perform(get("/v1/dialogue/conversations/{id}", conversationId)
                        .header("Authorization", token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode response = objectMapper.readTree(body);

        assertThat(response).isEqualTo(served(conversationId));
        assertThat(anchorOf(response)).isEqualTo(expectedAnchor());
    }

    @Test
    void 아이_응답_뒤에도_앵커가_그대로_실린다() throws Exception {
        String token = createLearner("하준", "MORMI-TA04");
        String sessionId = startSessionWithFiveCorrectDrills(token, "number-count");
        JsonNode created = startTeaching(token, sessionId);
        String conversationId = created.path("conversation_id").asString();

        String body = mockMvc.perform(
                        post("/v1/dialogue/conversations/{id}/responses", conversationId)
                                .header("Authorization", token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(Map.of(
                                        "turn_id", created.path("turn").path("turn_id").asString(),
                                        "type", "text",
                                        "text", "점은 세 개야"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode response = objectMapper.readTree(body);

        assertThat(response).isEqualTo(served(conversationId));
        assertThat(anchorOf(response)).isEqualTo(expectedAnchor());
    }

    /** 필드 몇 개가 아니라 노드 전체를 비교해야 "누락이 없다"가 증명된다. */
    private JsonNode anchorOf(JsonNode envelope) {
        JsonNode anchor = envelope.path("turn").path("task_anchor");
        assertThat(anchor.isObject())
                .as("turn.task_anchor 가 응답에서 사라졌습니다: %s", envelope.path("turn").propertyNames())
                .isTrue();
        return anchor;
    }

    private JsonNode expectedAnchor() {
        return objectMapper.readTree(homeTeachEnvelope).path("turn").path("task_anchor");
    }

    private JsonNode startTeaching(String token, String sessionId) throws Exception {
        String body = mockMvc.perform(
                        post("/v1/learning-sessions/{id}/teaching", sessionId)
                                .header("Authorization", token))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    private String createLearner(String name, String code) throws Exception {
        String body = mockMvc.perform(post("/v1/learners")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("display_name", name, "research_code", code))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return "Bearer " + objectMapper.readTree(body).get("access_token").asString();
    }

    /**
     * 가르치기는 서로 다른 반복 문제 5개를 맞힌 뒤에만 열린다.
     * 세션을 끝내지는 않는다. 완료된 세션은 가르치기를 거부하기 때문이다.
     */
    private String startSessionWithFiveCorrectDrills(String token, String curriculumSessionId)
            throws Exception {
        String body = mockMvc.perform(post("/v1/learning-sessions")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "curriculum_session_id", curriculumSessionId, "variant_seed", 7))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String sessionId = objectMapper.readTree(body).get("learning_session_id").asString();

        for (int index = 0; index < 5; index++) {
            mockMvc.perform(post("/v1/learning-sessions/{id}/attempts", sessionId)
                            .header("Authorization", token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "activity", "drill",
                                    "attempt_no", index + 1,
                                    "item_id", curriculumSessionId + ":" + index,
                                    "question_index", index,
                                    "is_correct", true,
                                    "elapsed_ms", 2500,
                                    "answer_meta", Map.of("selected_choice_id", "c1")))))
                    .andExpect(status().isCreated());
        }
        return sessionId;
    }
}
