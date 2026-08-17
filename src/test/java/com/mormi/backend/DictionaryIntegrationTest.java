package com.mormi.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mormi.backend.dialogue.DialogueConversation;
import com.mormi.backend.dialogue.DialogueConversationRepository;
import com.mormi.backend.session.LearningSessionRepository;
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
import tools.jackson.databind.ObjectMapper;

/**
 * 궁금해사전 중계를 가짜 AI 서버로 검증한다. 가짜가 돌려주는 카드 본문은 지어낸 것이 아니라
 * Mormi-AI 카탈로그의 실제 number-count 카드다(fixtures/dictionary-number-count-envelope.json).
 * 1) BE 응답은 AI 원본과 완전히 같다 (문장 보정·필드 손실 없음)
 * 2) 소유권 없는 요청은 AI 호출 전에 차단된다
 * 3) 미등록·버전 불일치·스냅샷 없음·AI 장애가 서로 다른 코드로 온다
 * 4) 읽기 실패는 한 번 재시도하고, 4xx 는 재시도하지 않는다
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class DictionaryIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    /** 실제 HTTP 를 타야 타임아웃·오류 본문 해석까지 검증된다. JDK 내장 서버라 의존성 추가가 없다. */
    static HttpServer fakeAi;
    static String fixtureEnvelope;
    static final Map<String, AtomicInteger> hits = new ConcurrentHashMap<>();
    static final AtomicReference<String> lastServiceKey = new AtomicReference<>();
    static final AtomicReference<String> lastQuery = new AtomicReference<>();

    static {
        try (InputStream in = DictionaryIntegrationTest.class
                .getResourceAsStream("/fixtures/dictionary-number-count-envelope.json")) {
            fixtureEnvelope = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            fakeAi = HttpServer.create(new InetSocketAddress(0), 0);
            fakeAi.createContext("/v1", DictionaryIntegrationTest::handle);
            fakeAi.start();
        } catch (IOException e) {
            throw new IllegalStateException("가짜 AI 서버를 띄우지 못했습니다", e);
        }
    }

    static void handle(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        hits.computeIfAbsent(path, key -> new AtomicInteger()).incrementAndGet();
        lastServiceKey.set(exchange.getRequestHeaders().getFirst("X-Mormi-Service-Key"));
        lastQuery.set(exchange.getRequestURI().getQuery());

        String query = exchange.getRequestURI().getQuery();
        if (path.equals("/v1/content/dictionary-cards/number-count")) {
            if (query != null && query.contains("expected_content_version=") 
                    && !query.contains("expected_content_version=2")) {
                respond(exchange, 409,
                        "{\"detail\":{\"code\":\"dictionary_version_mismatch\","
                                + "\"expected_content_version\":99,\"current_content_version\":2}}");
                return;
            }
            respond(exchange, 200, fixtureEnvelope);
            return;
        }
        if (path.equals("/v1/content/dictionary-cards/sub-borrow")) {
            respond(exchange, 500, "{\"detail\":{\"code\":\"engine_down\"}}");
            return;
        }
        if (path.startsWith("/v1/content/dictionary-cards/")) {
            respond(exchange, 404, "{\"detail\":{\"code\":\"dictionary_card_not_found\"}}");
            return;
        }
        if (path.equals("/v1/conversations/conv-pinned/dictionary-card")) {
            respond(exchange, 200, fixtureEnvelope);
            return;
        }
        if (path.equals("/v1/conversations/conv-legacy/dictionary-card")) {
            respond(exchange, 409, "{\"detail\":{\"code\":\"dictionary_snapshot_unavailable\"}}");
            return;
        }
        // AI 의 대화 404 는 detail 이 객체가 아니라 문자열이다. 이 모양 차이를 그대로 흉내내야
        // BE 가 detail.code 유무로 카드 미등록과 대화 없음을 구분하는 로직이 검증된다.
        respond(exchange, 404, "{\"detail\":\"Conversation not found\"}");
    }

    static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @DynamicPropertySource
    static void aiProperties(DynamicPropertyRegistry registry) {
        registry.add("mormi.dialogue.base-url", () -> "http://localhost:" + fakeAi.getAddress().getPort());
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

    @Autowired
    LearningSessionRepository sessionRepository;

    @Autowired
    DialogueConversationRepository dialogueRepository;

    @BeforeEach
    void resetFakeAi() {
        hits.clear();
        lastServiceKey.set(null);
        lastQuery.set(null);
    }

    @Test
    void 사전_카드는_AI_원본과_완전히_같은_본문으로_온다() throws Exception {
        String token = signup("지아", "DICT-A1", "dicta1");
        String sessionId = startSession(token, "number-count");

        String body = mockMvc.perform(get("/v1/learning-sessions/{id}/dictionary-card", sessionId)
                        .header("Authorization", token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        // 필드 몇 개 뽑아 보는 게 아니라 트리 전체를 비교한다. BE 가 문장을 고치거나
        // 필드를 떨어뜨리면 여기서 바로 깨진다. (이슈 #5 완료 조건: 원본과 동일)
        JsonNode expected = objectMapper.readTree(fixtureEnvelope);
        JsonNode actual = objectMapper.readTree(body);
        assertThat(actual).isEqualTo(expected);
        assertThat(lastServiceKey.get()).isEqualTo("test-service-key");
    }

    @Test
    void 기대_버전이_다르면_409_version_mismatch_로_온다() throws Exception {
        String token = signup("서준", "DICT-A2", "dicta2");
        String sessionId = startSession(token, "number-count");

        mockMvc.perform(get("/v1/learning-sessions/{id}/dictionary-card", sessionId)
                        .queryParam("expected_content_version", "99")
                        .header("Authorization", token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("dictionary_version_mismatch"));

        // BE 는 버전을 판단하지 않고 AI 로 전달만 한다.
        assertThat(lastQuery.get()).isEqualTo("expected_content_version=99");
    }

    @Test
    void 미등록_카드는_404_card_not_found_이고_재시도하지_않는다() throws Exception {
        String token = signup("하린", "DICT-A3", "dicta3");
        String sessionId = startSession(token, "money-count");

        mockMvc.perform(get("/v1/learning-sessions/{id}/dictionary-card", sessionId)
                        .header("Authorization", token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("dictionary_card_not_found"));

        assertThat(hits.get("/v1/content/dictionary-cards/money-count").get()).isEqualTo(1);
    }

    @Test
    void AI_5xx_는_한_번_재시도한_뒤_503_ai_error_로_온다() throws Exception {
        String token = signup("이준", "DICT-A4", "dicta4");
        String sessionId = startSession(token, "sub-borrow");

        mockMvc.perform(get("/v1/learning-sessions/{id}/dictionary-card", sessionId)
                        .header("Authorization", token))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("dictionary_ai_error"));

        // 최초 1회 + 재시도 1회 = 2회. 재시도가 실제로 도는지의 유일한 증거다.
        assertThat(hits.get("/v1/content/dictionary-cards/sub-borrow").get()).isEqualTo(2);
    }

    @Test
    void 남의_세션_사전은_AI_호출_전에_403_으로_막힌다() throws Exception {
        String owner = signup("소율", "DICT-A5", "dicta5");
        String intruder = signup("침입", "DICT-A6", "dicta6");
        String sessionId = startSession(owner, "number-count");

        mockMvc.perform(get("/v1/learning-sessions/{id}/dictionary-card", sessionId)
                        .header("Authorization", intruder))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("forbidden"));

        // 소유권은 BE 가 판정한다. 실패 요청은 AI 까지 가면 안 된다.
        assertThat(hits).isEmpty();
    }

    @Test
    void 대화에_고정된_카드는_소유자에게만_원본_그대로_온다() throws Exception {
        String token = signup("유나", "DICT-A7", "dicta7");
        String intruder = signup("남남", "DICT-A8", "dicta8");
        String conversationId = linkConversation(token, "conv-pinned");

        mockMvc.perform(get("/v1/dialogue/conversations/{id}/dictionary-card", conversationId)
                        .header("Authorization", intruder))
                .andExpect(status().isForbidden());

        String body = mockMvc.perform(get("/v1/dialogue/conversations/{id}/dictionary-card", conversationId)
                        .header("Authorization", token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(objectMapper.readTree(body)).isEqualTo(objectMapper.readTree(fixtureEnvelope));
    }

    @Test
    void 스냅샷_없는_구버전_대화는_409_snapshot_unavailable_로_온다() throws Exception {
        String token = signup("도현", "DICT-A9", "dicta9");
        String conversationId = linkConversation(token, "conv-legacy");

        mockMvc.perform(get("/v1/dialogue/conversations/{id}/dictionary-card", conversationId)
                        .header("Authorization", token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("dictionary_snapshot_unavailable"));
    }

    @Test
    void AI_에_대화가_없으면_일반_not_found_로_온다() throws Exception {
        String token = signup("라온", "DICT-B1", "dictb1");
        String conversationId = linkConversation(token, "conv-vanished");

        // BE 에는 대화 기록이 있는데 AI 가 404(detail 문자열)를 준 경우.
        // 카드 미등록(detail.code 객체)과 다른 코드로 와야 한다.
        mockMvc.perform(get("/v1/dialogue/conversations/{id}/dictionary-card", conversationId)
                        .header("Authorization", token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("not_found"));
    }

    private String signup(String name, String code, String loginId) throws Exception {
        String body = mockMvc.perform(post("/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "display_name", name,
                                "research_code", code,
                                "login_id", loginId,
                                "password", "password123"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return "Bearer " + objectMapper.readTree(body).path("access_token").asString();
    }

    private String startSession(String token, String curriculumSessionId) throws Exception {
        String body = mockMvc.perform(post("/v1/learning-sessions")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "curriculum_session_id", curriculumSessionId,
                                "variant_seed", 7))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(body).path("learning_session_id").asString();
    }

    /** 대화 시작 API 는 AI 대화 생성까지 얽혀 있어, 소유권 연결 행만 직접 넣는다. */
    private String linkConversation(String token, String conversationId) throws Exception {
        String sessionPublicId = startSession(token, "number-count");
        var session = sessionRepository.findByPublicId(sessionPublicId).orElseThrow();
        dialogueRepository.save(DialogueConversation.forLearningSession(
                conversationId, session.getLearnerId(), session.getId()));
        return conversationId;
    }
}
