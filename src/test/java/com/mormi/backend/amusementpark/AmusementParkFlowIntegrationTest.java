package com.mormi.backend.amusementpark;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mormi.backend.AuthTestSupport;
import com.mormi.backend.curriculum.CurriculumCatalog;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 놀이동산 3스테이지 방문 계약(#29)을 실제 PostgreSQL 로 검증한다.
 * 1) 카페를 마쳐야 열린다
 * 2) 단계는 서버 판정으로만 해금된다
 * 3) 정답은 방문에 고정된 값으로 서버가 계산한다
 *
 * <p>문제 숫자는 방문마다 뽑히므로(#31) 테스트도 값을 못 박지 않고 방문 응답에서 읽어 계산한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class AmusementParkFlowIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

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
    void 방문_응답은_스테이지_콘텐츠와_잠금_상태를_함께_내려준다() throws Exception {
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
                .andExpect(jsonPath("$.stages[0].mormi_misconception").isNotEmpty())
                .andExpect(jsonPath("$.stages[0].facts[0].key").value("ticket_price"))
                .andExpect(jsonPath("$.stages[0].facts[1].key").value("party_count"))
                .andReturn().getResponse().getContentAsString();

        // 숫자는 방문마다 다르므로 "값"이 아니라 "값들 사이의 관계"를 검증한다.
        JsonNode root = objectMapper.readTree(body);
        Map<String, Integer> facts = readFacts(root);
        assertThat(facts.get("snack_total") % facts.get("payer_count")).isZero();
        assertThat(facts.get("day_pass_price") % facts.get("single_ride_price")).isZero();

        int breakEven = facts.get("day_pass_price") / facts.get("single_ride_price");
        assertThat(root.get("stages").get(0).get("verified_facts").get("total_price").asInt())
                .isEqualTo(facts.get("ticket_price") * facts.get("party_count"));
        assertThat(root.get("stages").get(1).get("verified_facts").get("per_person").asInt())
                .isEqualTo(facts.get("snack_total") / facts.get("payer_count"));
        assertThat(root.get("stages").get(2).get("verified_facts").get("break_even_rides").asInt())
                .isEqualTo(breakEven);
        assertThat(root.get("stages").get(2).get("verified_facts").get("benefit_from_rides").asInt())
                .isEqualTo(breakEven + 1);

        // 전이 턴은 "배운 전략을 새 숫자에 다시 적용"이라 본문제와 같은 식이면 안 된다.
        assertThat(root.get("stages").get(0).get("transfer").get("equation").asString())
                .isNotEqualTo("%,d × %d = %,d".formatted(
                        facts.get("ticket_price"),
                        facts.get("party_count"),
                        facts.get("ticket_price") * facts.get("party_count")));
    }

    @Test
    void 앞_단계를_통과해야_다음_단계가_열리고_완료까지_기록이_남는다() throws Exception {
        String token = unlockedParkToken("나은", "MORMI-P03");
        ParkProblem problem = startParkVisit(token);
        String visitId = problem.visitId();

        // 아직 열리지 않은 단계는 제출 자체가 막힌다.
        mockMvc.perform(submit(token, visitId, "snack_split",
                        Map.of("per_person", problem.perPerson()), 1))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("stage_locked"));

        // 매표소: 먼저 한 장 값만 내는 오개념으로 틀린다(일행이 2명 이상이라 늘 모자란 답이다).
        int oneTicket = problem.fact("ticket_price");
        mockMvc.perform(submit(token, visitId, "ticket", Map.of("total_price", oneTicket), 1))
                .andExpect(jsonPath("$.is_correct").value(false))
                .andExpect(jsonPath("$.feedback_code").value("ticket_short"))
                .andExpect(jsonPath("$.expected_answers.total_price").value(problem.totalPrice()))
                .andExpect(jsonPath("$.next_stage").value("ticket"));

        mockMvc.perform(submit(token, visitId, "ticket",
                        Map.of("total_price", problem.totalPrice()), 2))
                .andExpect(jsonPath("$.is_correct").value(true))
                .andExpect(jsonPath("$.next_stage").value("snack_split"));

        mockMvc.perform(submit(token, visitId, "snack_split",
                        Map.of("per_person", problem.perPerson()), 1))
                .andExpect(jsonPath("$.is_correct").value(true))
                .andExpect(jsonPath("$.next_stage").value("pass_break_even"));

        // 자유이용권: 본전 횟수는 맞혔지만 "그 다음 한 번부터 이득"을 놓쳐 틀린다.
        mockMvc.perform(submit(token, visitId, "pass_break_even",
                        Map.of("break_even_rides", problem.breakEvenRides(),
                                "benefit_from_rides", problem.breakEvenRides()), 1))
                .andExpect(jsonPath("$.is_correct").value(false))
                .andExpect(jsonPath("$.feedback_code").value("pass_break_even_wrong"));

        // 세 단계를 다 마치기 전에는 완료할 수 없다.
        mockMvc.perform(post("/v1/amusement-park-visits/{id}/complete", visitId)
                        .header("Authorization", token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("stage_incomplete"));

        mockMvc.perform(submit(token, visitId, "pass_break_even",
                        Map.of("break_even_rides", problem.breakEvenRides(),
                                "benefit_from_rides", problem.breakEvenRides() + 1), 2))
                .andExpect(jsonPath("$.is_correct").value(true))
                .andExpect(jsonPath("$.next_stage").value("complete"));

        mockMvc.perform(post("/v1/amusement-park-visits/{id}/complete", visitId)
                        .header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.completed_at").isNotEmpty())
                .andExpect(jsonPath("$.stage_progress.pass_break_even").value("completed"));

        // 새로고침해도 복구되고 틀린 시도까지 남는다.
        mockMvc.perform(get("/v1/amusement-park-visits/{id}", visitId).header("Authorization", token))
                .andExpect(jsonPath("$.attempts.length()").value(5))
                .andExpect(jsonPath("$.attempts[0].is_correct").value(false))
                .andExpect(jsonPath("$.attempts[0].payload.answers.total_price").value(oneTicket))
                .andExpect(jsonPath("$.attempts[0].payload.expected_answers.total_price")
                        .value(problem.totalPrice()))
                .andExpect(jsonPath("$.attempts[0].payload.given_facts.ticket_price").value(oneTicket));
    }

    @Test
    void 방문을_새로_시작하면_문제_숫자를_다시_뽑는다() throws Exception {
        // 같은 문제만 반복되면 아이가 계산 대신 답을 외워 통과할 수 있다(#31).
        java.util.Set<Map<String, Integer>> drawn = new java.util.LinkedHashSet<>();
        for (int i = 0; i < 3; i++) {
            String token = unlockedParkToken("도윤" + i, "MORMI-R0" + i);
            drawn.add(startParkVisit(token).facts());
        }

        assertThat(drawn).hasSizeGreaterThan(1);
    }

    @Test
    void 계약에_없는_단계나_답은_판정_전에_거절한다() throws Exception {
        String token = unlockedParkToken("유진", "MORMI-P04");
        String visitId = startParkVisit(token).visitId();

        mockMvc.perform(submit(token, visitId, "roller_coaster", Map.of("total_price", 6000), 1))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("stage_unknown"));

        // 주어진 값은 서버가 갖고 있으므로 아이가 덮어쓸 수 없다.
        mockMvc.perform(submit(token, visitId, "ticket",
                        Map.of("total_price", 6000, "ticket_price", 1), 1))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("answer_unknown"));

        mockMvc.perform(submit(token, visitId, "ticket", Map.of("party_count", 2), 1))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("answer_missing"));
    }

    @Test
    void 다른_학습자의_방문은_열어볼_수_없다() throws Exception {
        String owner = unlockedParkToken("건우", "MORMI-P05");
        String visitId = startParkVisit(owner).visitId();
        String stranger = unlockedParkToken("예린", "MORMI-P06");

        mockMvc.perform(get("/v1/amusement-park-visits/{id}", visitId)
                        .header("Authorization", stranger))
                .andExpect(status().isForbidden());
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder submit(
            String token, String visitId, String stageId, Map<String, Integer> answers, int attemptNo)
            throws Exception {
        return post("/v1/amusement-park-visits/{id}/stages/{stage}", visitId, stageId)
                .header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "answers", answers, "attempt_no", attemptNo)));
    }

    /**
     * 이 방문에 뽑힌 문제. 값이 방문마다 달라 테스트도 응답을 읽고 정답을 계산한다.
     * 계산식은 서버와 같은 규칙(곱셈·나눗셈)을 테스트가 독립적으로 다시 세운 것이다.
     */
    private record ParkProblem(String visitId, Map<String, Integer> facts) {

        int fact(String key) {
            return facts.get(key);
        }

        int totalPrice() {
            return fact("ticket_price") * fact("party_count");
        }

        int perPerson() {
            return fact("snack_total") / fact("payer_count");
        }

        int breakEvenRides() {
            return fact("day_pass_price") / fact("single_ride_price");
        }
    }

    private ParkProblem startParkVisit(String token) throws Exception {
        String body = mockMvc.perform(post("/v1/amusement-park-visits").header("Authorization", token))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        JsonNode root = objectMapper.readTree(body);
        return new ParkProblem(root.get("visit_id").asString(), readFacts(root));
    }

    /** 방문 응답의 세 스테이지에 흩어진 주어진 값을 한 장으로 모은다. */
    private static Map<String, Integer> readFacts(JsonNode visit) {
        Map<String, Integer> facts = new LinkedHashMap<>();
        JsonNode stages = visit.get("stages");
        for (int i = 0; i < stages.size(); i++) {
            JsonNode stageFacts = stages.get(i).get("facts");
            for (int j = 0; j < stageFacts.size(); j++) {
                JsonNode fact = stageFacts.get(j);
                facts.put(fact.get("key").asString(), fact.get("value").asInt());
            }
        }
        return facts;
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

        mockMvc.perform(post("/v1/cafe-visits/{id}/complete", visitId).header("Authorization", token))
                .andExpect(status().isOk());
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
