package com.mormi.backend.dialogue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mormi.backend.curriculum.CurriculumCatalog;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    /** 실제 HTTP 를 타야 JsonNode 수신부터 응답 직렬화까지 전 구간이 검증된다. */
    static HttpServer fakeAi;
    static String homeTeachEnvelope;
    static String cafeQueueEnvelope;
    static String legacyNoAnchorEnvelope;
    static final Map<String, AtomicInteger> hits = new ConcurrentHashMap<>();
    /** 발급한 대화 ID 별로 그때 내려준 봉투 원문. 조회·응답도 같은 것을 돌려준다. */
    static final Map<String, String> servedByConversationId = new ConcurrentHashMap<>();
    static final AtomicInteger conversationSequence = new AtomicInteger();
    /** 값이 있으면 scene 과 무관하게 이 봉투를 내려준다. 구버전 AI 를 흉내낼 때 쓴다. */
    static final AtomicReference<String> forcedEnvelope = new AtomicReference<>();
    static final ObjectMapper FIXTURE_JSON = new ObjectMapper();

    static {
        homeTeachEnvelope = readFixture("/fixtures/dialogue-home-teach-anchor-envelope.json");
        cafeQueueEnvelope = readFixture("/fixtures/dialogue-cafe-queue-anchor-envelope.json");
        legacyNoAnchorEnvelope = readFixture("/fixtures/dialogue-legacy-no-anchor-envelope.json");
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
            // BE 가 보낸 scene 으로 어떤 화면의 대화인지 가른다. 실제 AI 도 같은 값으로
            // 시나리오를 고르므로, 가짜가 요청을 무시하면 검증이 헐거워진다.
            String scene = FIXTURE_JSON
                    .readTree(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8))
                    .path("scene").asString();
            String forced = forcedEnvelope.get();
            respond(exchange, 201, issueConversation(forced != null ? forced
                    : "cafe".equals(scene) ? cafeQueueEnvelope : homeTeachEnvelope));
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
    static String issueConversation(String fixture) {
        ObjectNode envelope = (ObjectNode) FIXTURE_JSON.readTree(fixture);
        String conversationId = envelope.path("conversation_id").asString()
                + "-" + conversationSequence.incrementAndGet();
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
        forcedEnvelope.set(null);
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


    // ---------------------------------------------------------------- 카페 경로
    // 집과 달리 카페는 BE 가 AI 봉투를 재조립한다. conversation_id 와 turn 만 옮겨 담고
    // scenario_context, stage_progress 를 얹기 때문에 봉투 전체 비교가 성립하지 않는다.
    // 대신 turn 노드가 통째로 같은지, 그 안의 앵커가 온전한지를 본다.

    @Test
    void 카페_대화_시작_응답에_앵커가_그대로_실린다() throws Exception {
        String token = createLearner("연우", "MORMI-TA05");
        String visitId = unlockCafeAndStartVisit(token);

        JsonNode response = startQueueDialogue(token, visitId, false);
        String conversationId = response.path("conversation_id").asString();

        assertThat(response.path("turn")).isEqualTo(served(conversationId).path("turn"));
        assertThat(anchorOf(response)).isEqualTo(expectedCafeAnchor());
        // 재조립이 정상 동작했는지도 함께 본다. 앵커만 살고 나머지가 죽으면 안 된다.
        assertThat(response.path("scenario_context").path("queue_context").path("left_count").asInt())
                .isEqualTo(3);
        assertThat(response.path("stage_progress").path("stage").asString()).isEqualTo("queue");
    }

    @Test
    void 카페_대화_조회에_앵커가_그대로_실린다() throws Exception {
        String token = createLearner("지호", "MORMI-TA06");
        String visitId = unlockCafeAndStartVisit(token);
        String conversationId = startQueueDialogue(token, visitId, false)
                .path("conversation_id").asString();

        String body = mockMvc.perform(get("/v1/dialogue/conversations/{id}", conversationId)
                        .header("Authorization", token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode response = objectMapper.readTree(body);

        assertThat(response.path("turn")).isEqualTo(served(conversationId).path("turn"));
        assertThat(anchorOf(response)).isEqualTo(expectedCafeAnchor());
    }

    @Test
    void 카페_아이_응답_뒤에도_앵커가_그대로_실린다() throws Exception {
        String token = createLearner("수아", "MORMI-TA07");
        String visitId = unlockCafeAndStartVisit(token);
        JsonNode created = startQueueDialogue(token, visitId, false);
        String conversationId = created.path("conversation_id").asString();

        String body = mockMvc.perform(
                        post("/v1/dialogue/conversations/{id}/responses", conversationId)
                                .header("Authorization", token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(Map.of(
                                        "turn_id", created.path("turn").path("turn_id").asString(),
                                        "type", "text",
                                        "text", "오른쪽은 다섯 명이야"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode response = objectMapper.readTree(body);

        assertThat(response.path("turn")).isEqualTo(served(conversationId).path("turn"));
        assertThat(anchorOf(response)).isEqualTo(expectedCafeAnchor());
    }

    @Test
    void 카페_새로고침_복구에도_같은_앵커가_온다() throws Exception {
        String token = createLearner("루아", "MORMI-TA08");
        String visitId = unlockCafeAndStartVisit(token);
        String conversationId = startQueueDialogue(token, visitId, false)
                .path("conversation_id").asString();

        // restart 가 false 면 새 회차를 열지 않고 마지막 회차를 그대로 이어 준다.
        JsonNode recovered = startQueueDialogue(token, visitId, false);

        assertThat(recovered.path("conversation_id").asString()).isEqualTo(conversationId);
        assertThat(recovered.path("turn")).isEqualTo(served(conversationId).path("turn"));
        assertThat(anchorOf(recovered)).isEqualTo(expectedCafeAnchor());
        assertThat(hits.get("/v1/conversations").get()).isEqualTo(1);
    }

    @Test
    void 카페_다시_연습은_새_회차에도_앵커를_싣는다() throws Exception {
        String token = createLearner("가온", "MORMI-TA09");
        String visitId = unlockCafeAndStartVisit(token);
        String firstRound = startQueueDialogue(token, visitId, false)
                .path("conversation_id").asString();

        // restart 가 true 면 BE 가 새 회차 대화를 연다. 새 대화에도 앵커가 실려야 한다.
        JsonNode replay = startQueueDialogue(token, visitId, true);
        String secondRound = replay.path("conversation_id").asString();

        assertThat(secondRound).isNotEqualTo(firstRound);
        assertThat(replay.path("turn")).isEqualTo(served(secondRound).path("turn"));
        assertThat(anchorOf(replay)).isEqualTo(expectedCafeAnchor());
        assertThat(hits.get("/v1/conversations").get()).isEqualTo(2);
    }


    // ------------------------------------------------------- 구버전 AI 하위 호환
    // 앵커는 나중에 추가된 필드다. 신버전 AI 가 아직 배포되지 않은 서버로 요청이 가면
    // 필드가 아예 없는 응답이 온다. 이때 BE 가 할 일은 두 가지다.
    // 1) 기존 응답을 깨뜨리지 않는다  2) 없는 앵커를 대사에서 지어내지 않는다

    @Test
    void 구버전_AI_응답에_앵커가_없어도_카페_대화가_열린다() throws Exception {
        String token = createLearner("아린", "MORMI-TA10");
        String visitId = unlockCafeAndStartVisit(token);
        forcedEnvelope.set(legacyNoAnchorEnvelope);

        JsonNode response = startQueueDialogue(token, visitId, false);
        String conversationId = response.path("conversation_id").asString();

        assertThat(response.path("turn")).isEqualTo(served(conversationId).path("turn"));
        assertThat(response.path("turn").has("task_anchor"))
                .as("BE 가 없는 앵커를 지어냈습니다")
                .isFalse();
        // 앵커가 없다고 나머지가 죽으면 안 된다. 재조립은 그대로 동작해야 한다.
        assertThat(response.path("scenario_context").path("queue_context").path("right_count").asInt())
                .isEqualTo(5);
        assertThat(response.path("stage_progress").path("stage").asString()).isEqualTo("queue");
    }

    @Test
    void 구버전_AI_응답에_앵커가_없어도_가르치기가_열린다() throws Exception {
        String token = createLearner("시우", "MORMI-TA11");
        String sessionId = startSessionWithFiveCorrectDrills(token, "number-count");
        forcedEnvelope.set(withoutAnchor(homeTeachEnvelope));

        JsonNode response = startTeaching(token, sessionId);

        assertThat(response).isEqualTo(served(response.path("conversation_id").asString()));
        assertThat(response.path("turn").has("task_anchor"))
                .as("BE 가 없는 앵커를 지어냈습니다")
                .isFalse();
        assertThat(response.path("turn").path("mormi").path("text").asString()).isNotBlank();
    }

    @Test
    void 앵커가_null_이면_null_그대로_전달한다() throws Exception {
        String token = createLearner("하율", "MORMI-TA12");
        String visitId = unlockCafeAndStartVisit(token);
        // 신버전 AI 도 완료된 턴이나 입력이 없는 턴에는 앵커를 넣지 않는다. 그때는 키가
        // 사라지는 것이 아니라 null 로 온다. 이 차이까지 그대로 넘어가야 한다.
        forcedEnvelope.set(withNullAnchor(cafeQueueEnvelope));

        JsonNode response = startQueueDialogue(token, visitId, false);
        JsonNode anchor = response.path("turn").path("task_anchor");

        assertThat(response.path("turn").has("task_anchor")).isTrue();
        assertThat(anchor.isNull()).as("null 이 객체로 바뀌었습니다: %s", anchor).isTrue();
        assertThat(response.path("stage_progress").path("completed").asBoolean()).isFalse();
    }

    /** 구버전 AI 처럼 키 자체를 지운 봉투. */
    private String withoutAnchor(String fixture) {
        ObjectNode envelope = (ObjectNode) objectMapper.readTree(fixture);
        ((ObjectNode) envelope.path("turn")).remove("task_anchor");
        return objectMapper.writeValueAsString(envelope);
    }

    /** 키는 두고 값만 null 로 둔 봉투. */
    private String withNullAnchor(String fixture) {
        ObjectNode envelope = (ObjectNode) objectMapper.readTree(fixture);
        ((ObjectNode) envelope.path("turn")).putNull("task_anchor");
        return objectMapper.writeValueAsString(envelope);
    }

    private JsonNode expectedCafeAnchor() {
        return objectMapper.readTree(cafeQueueEnvelope).path("turn").path("task_anchor");
    }

    /** 화면 문제는 픽스처의 visual 과 같은 3명 대 5명으로 맞춘다. */
    private JsonNode startQueueDialogue(String token, String visitId, boolean restart)
            throws Exception {
        String body = mockMvc.perform(post("/v1/cafe-visits/{id}/dialogues", visitId)
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "scenario_id", "cafe_queue",
                                "queue_context", Map.of("left_count", 3, "right_count", 5),
                                "restart", restart))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    /** 카페는 필수 세션 5개를 모두 마쳐야 열린다. 방문을 만들면 첫 단계가 줄 서기다. */
    private String unlockCafeAndStartVisit(String token) throws Exception {
        for (String sessionKey : CurriculumCatalog.CAFE_REQUIRED_SESSION_IDS) {
            completeSession(token, sessionKey);
        }
        String body = mockMvc.perform(post("/v1/cafe-visits").header("Authorization", token))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("cafe_visit_id").asString();
    }

    /** 반복 5문제를 모두 맞히고 세션을 끝낸다. 카페 해금 조건을 채우는 용도다. */
    private void completeSession(String token, String curriculumSessionId) throws Exception {
        String sessionId = startSessionWithFiveCorrectDrills(token, curriculumSessionId);
        mockMvc.perform(post("/v1/learning-sessions/{id}/complete", sessionId)
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "transfer_solved", true,
                                "timed_out", false,
                                "scaffold_level", 3,
                                "elapsed_seconds", 150))))
                .andExpect(status().isOk());
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
