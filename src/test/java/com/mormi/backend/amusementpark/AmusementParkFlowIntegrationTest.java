package com.mormi.backend.amusementpark;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mormi.backend.AuthTestSupport;
import com.mormi.backend.curriculum.CurriculumCatalog;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 놀이동산 3스테이지 방문 계약(#29)을 실제 PostgreSQL 로 검증한다.
 * 1) 카페를 마쳐야 열린다
 * 2) 단계는 인증된 AI 완료 증거로만 해금된다
 * 3) 방문 응답에는 문제·정답·힌트가 섞이지 않는다
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class AmusementParkFlowIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    /**
     * 대화 시작이 저장까지 가는지만 보면 되므로 가짜 AI 는 대화 발급 한 가지만 응답한다.
     * 진짜 AI 처럼 대화마다 새 ID 를 발급해야 conversation_id 유니크 제약에 걸리지 않는다.
     */
    static HttpServer fakeAi;
    static final AtomicInteger conversationSequence = new AtomicInteger();

    static {
        try {
            fakeAi = HttpServer.create(new InetSocketAddress(0), 0);
            fakeAi.createContext(
                    "/v1/conversations", AmusementParkFlowIntegrationTest::issueConversation);
            fakeAi.start();
        } catch (IOException error) {
            throw new IllegalStateException("가짜 AI 서버를 띄우지 못했습니다", error);
        }
    }

    /** 턴 내용은 이 테스트의 관심사가 아니라 status 만 둔다. 완료가 아니므로 진행도는 pending 이다. */
    static void issueConversation(HttpExchange exchange) throws IOException {
        exchange.getRequestBody().readAllBytes();
        byte[] body = """
                {"conversation_id":"conversation_park_%d","turn":{"status":"active"}}"""
                .formatted(conversationSequence.incrementAndGet())
                .getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(201, body.length);
        exchange.getResponseBody().write(body);
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

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    AmusementParkService amusementParkService;

    @Test
    void 카페를_마쳐야_놀이동산이_열린다() throws Exception {
        String token = learnerToken("하준", "MORMI-P01");

        // 카페 해금 전에는 놀이동산도 닫혀 있다.
        mockMvc.perform(post("/v1/amusement-park-visits").header("Authorization", token))
                .andExpect(status().isForbidden());

        for (String sessionKey : CurriculumCatalog.CAFE_REQUIRED_SESSION_IDS) {
            completeSession(token, sessionKey);
        }
        // 카페가 해금만 되고 아직 완료되지 않은 상태에서도 놀이동산은 닫혀 있어야 한다.
        mockMvc.perform(post("/v1/cafe-visits").header("Authorization", token))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/v1/amusement-park-visits").header("Authorization", token))
                .andExpect(status().isForbidden());

        completeCafe(token);

        mockMvc.perform(get("/v1/themes").header("Authorization", token))
                .andExpect(jsonPath("$[2].theme_id").value("amusement_park"))
                .andExpect(jsonPath("$[2].unlocked").value(true));
        mockMvc.perform(post("/v1/amusement-park-visits").header("Authorization", token))
                .andExpect(status().isCreated());
    }

    @Test
    void 거스름돈_정답_즉시_카페_완료_원장이_찍혀_놀이동산이_열린다() throws Exception {
        String token = learnerToken("민재", "MORMI-P43");
        for (String sessionKey : CurriculumCatalog.CAFE_REQUIRED_SESSION_IDS) {
            completeSession(token, sessionKey);
        }

        String cafeVisitId = completeCafeStages(token);

        Map<String, Object> visit = jdbc.queryForMap(
                "SELECT stage, completed_at FROM cafe_visits WHERE public_id = ?", cafeVisitId);
        assertThat(visit.get("stage")).isEqualTo("complete");
        assertThat(visit.get("completed_at")).isNotNull();

        Map<String, Object> theme = jdbc.queryForMap(
                "SELECT completed_at FROM theme_progress WHERE theme_id = 'cafe' "
                        + "AND learner_id = (SELECT learner_id FROM cafe_visits WHERE public_id = ?)",
                cafeVisitId);
        assertThat(theme.get("completed_at")).isNotNull();

        mockMvc.perform(get("/v1/themes").header("Authorization", token))
                .andExpect(jsonPath("$[2].theme_id").value("amusement_park"))
                .andExpect(jsonPath("$[2].unlocked").value(true));
        mockMvc.perform(post("/v1/amusement-park-visits").header("Authorization", token))
                .andExpect(status().isCreated());
    }

    @Test
    void 방문_응답은_지도_껍데기와_잠금_상태만_내려준다() throws Exception {
        String token = unlockedParkToken("서우", "MORMI-P02");

        String body = mockMvc.perform(
                        post("/v1/amusement-park-visits").header("Authorization", token))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.theme_id").value("amusement_park"))
                .andExpect(jsonPath("$.stage_order").value(
                        org.hamcrest.Matchers.contains("ticket", "snack_split", "pass_break_even")))
                .andExpect(jsonPath("$.stage_progress.ticket").value("available"))
                .andExpect(jsonPath("$.stage_progress.snack_split").value("locked"))
                .andExpect(jsonPath("$.stage_progress.pass_break_even").value("locked"))
                .andExpect(jsonPath("$.stages[0].scenario_id").value("amusement_ticket_multiply"))
                .andExpect(jsonPath("$.stages[0].title").value("매표소"))
                .andExpect(jsonPath("$.stages[0].skill").value("multiply"))
                .andExpect(jsonPath("$.stages[0].prompt").doesNotExist())
                .andExpect(jsonPath("$.stages[0].mormi_misconception").doesNotExist())
                .andExpect(jsonPath("$.stages[0].facts").doesNotExist())
                .andExpect(jsonPath("$.stages[0].verified_facts").doesNotExist())
                .andExpect(jsonPath("$.stages[0].transfer").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        // 문제·정답·힌트·전이 문제는 AI 대화를 연 뒤에만 내려온다.
        JsonNode root = objectMapper.readTree(body);
        assertThat(root.toString()).doesNotContain(
                "ticket_price", "total_price", "mormi_misconception", "expected_answers");
    }

    @Test
    void AI가_검증한_완료만_순서대로_진행시키고_증거를_기록한다() throws Exception {
        String token = unlockedParkToken("나은", "MORMI-P03");
        String visitId = startParkVisit(token);
        Long learnerId = parkLearnerId(visitId);

        assertThatThrownBy(() -> amusementParkService.completeFromDialogue(
                        learnerId,
                        visitId,
                        "snack_split",
                        Map.of("snack_total", 6000, "payer_count", 3, "per_person", 2000),
                        900001,
                        "independent",
                        true))
                .hasFieldOrPropertyWithValue("code", "stage_locked");

        var ticket = amusementParkService.completeFromDialogue(
                learnerId,
                visitId,
                "ticket",
                Map.of("ticket_price", 3000, "party_count", 2, "total_price", 6000),
                900001,
                "independent",
                true);
        assertThat(ticket.nextStage()).isEqualTo("snack_split");

        // 세 단계를 다 마치기 전에는 완료할 수 없다.
        mockMvc.perform(post("/v1/amusement-park-visits/{id}/complete", visitId)
                        .header("Authorization", token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("stage_incomplete"));

        var snack = amusementParkService.completeFromDialogue(
                learnerId,
                visitId,
                "snack_split",
                Map.of("snack_total", 6000, "payer_count", 3, "per_person", 2000),
                900002,
                "supported",
                true);
        assertThat(snack.nextStage()).isEqualTo("pass_break_even");

        var pass = amusementParkService.completeFromDialogue(
                learnerId,
                visitId,
                "pass_break_even",
                Map.of(
                        "single_ride_price", 2000,
                        "day_pass_price", 10000,
                        "break_even_rides", 5,
                        "benefit_from_rides", 6),
                900003,
                "supported",
                false);
        assertThat(pass.nextStage()).isEqualTo("complete");

        // FE는 방문 completed_at이 이미 있으면 /complete를 호출하지 않는다(#45).
        // 마지막 정답 처리와 같은 트랜잭션에서 장소 완료 원장도 반드시 함께 기록돼야 한다.
        Map<String, Object> completion = jdbc.queryForMap(
                "SELECT visit.completed_at AS visit_completed_at, theme.completed_at AS theme_completed_at "
                        + "FROM amusement_park_visits visit "
                        + "JOIN theme_progress theme ON theme.learner_id = visit.learner_id "
                        + "AND theme.theme_id = 'amusement_park' "
                        + "WHERE visit.public_id = ?",
                visitId);
        assertThat(completion.get("visit_completed_at")).isNotNull();
        assertThat(completion.get("theme_completed_at")).isNotNull();

        // 명시적 완료 API는 기존 계약대로 멱등 호출로 남는다.
        mockMvc.perform(post("/v1/amusement-park-visits/{id}/complete", visitId)
                        .header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.completed_at").isNotEmpty())
                .andExpect(jsonPath("$.stage_progress.pass_break_even").value("completed"));

        // 새로고침해도 AI 완료 증거와 공동 수행 여부가 보존된다.
        mockMvc.perform(get("/v1/amusement-park-visits/{id}", visitId).header("Authorization", token))
                .andExpect(jsonPath("$.attempts.length()").value(3))
                .andExpect(jsonPath("$.attempts[0].is_correct").value(true))
                .andExpect(jsonPath("$.attempts[0].payload.content_owner").value("mormi_ai"))
                .andExpect(jsonPath("$.attempts[0].payload.verified_facts.total_price").value(6000))
                .andExpect(jsonPath("$.attempts[2].payload.teach_reward_eligible").value(false));
    }

    @Test
    void 같은_학습자가_완료_뒤_다시_시작하면_완료된_방문을_연습_모드로_이어받는다() throws Exception {
        String token = unlockedParkToken("지우", "MORMI-R47");
        String firstVisitId = startParkVisit(token);
        completeParkStages(firstVisitId);

        String body = mockMvc.perform(post("/v1/amusement-park-visits").header("Authorization", token))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.visit_id").value(firstVisitId))
                .andExpect(jsonPath("$.stage_progress.ticket").value("completed"))
                .andExpect(jsonPath("$.stage_progress.snack_split").value("completed"))
                .andExpect(jsonPath("$.stage_progress.pass_break_even").value("completed"))
                .andExpect(jsonPath("$.completed_at").isNotEmpty())
                .andExpect(jsonPath("$.attempts.length()").value(3))
                .andReturn().getResponse().getContentAsString();

        JsonNode root = objectMapper.readTree(body);
        assertThat(root.get("visit_id").asString()).isEqualTo(firstVisitId);
    }

    @Test
    void 완료된_방문_id로_원하는_스테이지_대화를_독립적으로_restart할_수_있다() throws Exception {
        String token = unlockedParkToken("로아", "MORMI-R48");
        String visitId = startParkVisit(token);
        completeParkStages(visitId);

        mockMvc.perform(post("/v1/amusement-park-visits/{id}/dialogues", visitId)
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                                "scenario_id", "amusement_pass_compare",
                                "start_mode", "restart",
                                "request_id", "restart-completed-visit"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.conversation_id").isNotEmpty())
                .andExpect(jsonPath("$.stage_progress.stage").value("pass_break_even"))
                .andExpect(jsonPath("$.stage_progress.completed").value(false))
                .andExpect(jsonPath("$.stage_progress.next_stage").value("complete"));
    }

    @Test
    void 다른_학습자의_방문은_열어볼_수_없다() throws Exception {
        String owner = unlockedParkToken("건우", "MORMI-P05");
        String visitId = startParkVisit(owner);
        String stranger = unlockedParkToken("예린", "MORMI-P06");

        mockMvc.perform(get("/v1/amusement-park-visits/{id}", visitId)
                        .header("Authorization", stranger))
                .andExpect(status().isForbidden());
    }

    /**
     * 놀이동산 대화가 대화 원장에 저장되는지 본다(#42).
     *
     * <p>회귀 배경: dialogue_conversations 의 소유자 CHECK 제약이 학습세션/카페방문 2택이라
     * park_visit_id 만 채우는 놀이동산 대화는 INSERT 가 통째로 막혔다(SQLState 23514).
     * 방문·제출 REST 경로만 보던 기존 테스트로는 잡히지 않아 배포 뒤 500 으로 드러났다.
     * 제약은 DB 에만 있으므로 실제 PostgreSQL 을 띄우는 이 테스트가 유일한 탐지 수단이다.
     */
    @Test
    void 놀이동산_대화_시작이_방문_소유로_저장된다() throws Exception {
        String token = unlockedParkToken("소율", "MORMI-P07");
        String visitId = startParkVisit(token);

        String body = mockMvc.perform(post("/v1/amusement-park-visits/{id}/dialogues", visitId)
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "scenario_id", "amusement_ticket_multiply"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.stage_progress.stage").value("ticket"))
                .andExpect(jsonPath("$.scenario_context.content_owner").value("mormi_ai"))
                .andExpect(jsonPath("$.scenario_context.park_context").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        String conversationId = objectMapper.readTree(body).get("conversation_id").asString();
        Map<String, Object> owner = jdbc.queryForMap(
                "SELECT learning_session_id, cafe_visit_id, park_visit_id "
                        + "FROM dialogue_conversations WHERE conversation_id = ?", conversationId);
        assertThat(owner.get("learning_session_id")).isNull();
        assertThat(owner.get("cafe_visit_id")).isNull();
        assertThat(owner.get("park_visit_id")).isNotNull();
    }

    /**
     * 제약을 셋으로 넓히면서 "소유자 없는 대화"까지 열어 주지는 않았는지 본다.
     * 소유자가 없으면 나중에 그 대화를 어느 방문에 붙일지 알 수 없어 원장이 고아 행을 갖게 된다.
     */
    @Test
    void 소유자가_없는_대화는_여전히_거절된다() throws Exception {
        learnerToken("고아", "MORMI-P08");
        Long learnerId = jdbc.queryForObject(
                "SELECT id FROM learners WHERE display_name = ?", Long.class, "고아");

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO dialogue_conversations (conversation_id, learner_id, scenario_id) "
                        + "VALUES ('conversation_orphan', ?, 'amusement_ticket_multiply')", learnerId))
                .hasMessageContaining("ck_dialogue_owner_scope");
    }

    private String startParkVisit(String token) throws Exception {
        String body = mockMvc.perform(post("/v1/amusement-park-visits").header("Authorization", token))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("visit_id").asString();
    }

    private Long parkLearnerId(String visitId) {
        return jdbc.queryForObject(
                "SELECT learner_id FROM amusement_park_visits WHERE public_id = ?",
                Long.class,
                visitId);
    }

    private void completeParkStages(String visitId) {
        Long learnerId = parkLearnerId(visitId);
        amusementParkService.completeFromDialogue(
                learnerId,
                visitId,
                "ticket",
                Map.of("ticket_price", 3000, "party_count", 2, "total_price", 6000),
                900001,
                "independent",
                true);
        amusementParkService.completeFromDialogue(
                learnerId,
                visitId,
                "snack_split",
                Map.of("snack_total", 6000, "payer_count", 3, "per_person", 2000),
                900002,
                "supported",
                true);
        amusementParkService.completeFromDialogue(
                learnerId,
                visitId,
                "pass_break_even",
                Map.of(
                        "single_ride_price", 2000,
                        "day_pass_price", 10000,
                        "break_even_rides", 5,
                        "benefit_from_rides", 6),
                900003,
                "supported",
                false);
    }

    private String learnerToken(String name, String code) throws Exception {
        return "Bearer " + AuthTestSupport.signupLearner(mockMvc, objectMapper, name, code)
                .get("access_token").asString();
    }

    /** 필수 세션 5개 → 카페 4단계 → 카페 완료까지 밀어 놀이동산을 연다. */
    private String unlockedParkToken(String name, String code) throws Exception {
        String token = learnerToken(name, code);
        for (String sessionKey : CurriculumCatalog.CAFE_REQUIRED_SESSION_IDS) {
            completeSession(token, sessionKey);
        }
        completeCafe(token);
        return token;
    }

    private void completeCafe(String token) throws Exception {
        String visitId = completeCafeStages(token);

        mockMvc.perform(post("/v1/cafe-visits/{id}/complete", visitId).header("Authorization", token))
                .andExpect(status().isOk());
    }

    private String completeCafeStages(String token) throws Exception {
        String visitBody = mockMvc.perform(post("/v1/cafe-visits").header("Authorization", token))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String visitId = objectMapper.readTree(visitBody).get("cafe_visit_id").asString();

        mockMvc.perform(post("/v1/cafe-visits/{id}/queue", visitId)
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "left_count", 4, "right_count", 2, "chosen_count", 2,
                                "scaffold_used", false, "attempt_no", 1))))
                .andExpect(jsonPath("$.is_correct").value(true));

        mockMvc.perform(post("/v1/cafe-visits/{id}/menu", visitId)
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "menu_ids", List.of("americano", "cookie"),
                                "budget", 8000, "attempt_no", 1))))
                .andExpect(jsonPath("$.is_correct").value(true));

        mockMvc.perform(post("/v1/cafe-visits/{id}/payments", visitId)
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "menu_ids", List.of("americano", "cookie"),
                                "answer_amount", 5000, "attempt_no", 1))))
                .andExpect(jsonPath("$.is_correct").value(true));

        mockMvc.perform(post("/v1/cafe-visits/{id}/change", visitId)
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "menu_id", "americano",
                                "counts", Map.of("1000", 7), "attempt_no", 1))))
                .andExpect(jsonPath("$.is_correct").value(true));

        return visitId;
    }

    private void completeSession(String token, String curriculumSessionId) throws Exception {
        String body = mockMvc.perform(post("/v1/learning-sessions")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "curriculum_session_id", curriculumSessionId,
                                "variant_seed", 7))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String sessionId = objectMapper.readTree(body).get("learning_session_id").asString();

        for (int index = 0; index < CurriculumCatalog.MASTERY_TARGET; index++) {
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
}
