package com.mormi.backend.amusementpark;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mormi.backend.AuthTestSupport;
import com.mormi.backend.curriculum.CurriculumCatalog;
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
import tools.jackson.databind.ObjectMapper;

/**
 * 놀이동산 3스테이지 방문 계약(#29)을 실제 PostgreSQL 로 검증한다.
 * 1) 카페를 마쳐야 열린다
 * 2) 단계는 서버 판정으로만 해금된다
 * 3) 정답은 방문에 고정된 값으로 서버가 계산한다
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

        mockMvc.perform(post("/v1/amusement-park-visits").header("Authorization", token))
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
                .andExpect(jsonPath("$.stages[0].facts[0].value").value(3000))
                .andExpect(jsonPath("$.stages[0].facts[1].value").value(2))
                .andExpect(jsonPath("$.stages[0].verified_facts.total_price").value(6000))
                .andExpect(jsonPath("$.stages[0].transfer.equation").value("3,500 × 4 = 14,000"))
                .andExpect(jsonPath("$.stages[2].verified_facts.break_even_rides").value(5))
                .andExpect(jsonPath("$.stages[2].verified_facts.benefit_from_rides").value(6));
    }

    @Test
    void 앞_단계를_통과해야_다음_단계가_열리고_완료까지_기록이_남는다() throws Exception {
        String token = unlockedParkToken("나은", "MORMI-P03");
        String visitId = startParkVisit(token);

        // 아직 열리지 않은 단계는 제출 자체가 막힌다.
        mockMvc.perform(submit(token, visitId, "snack_split", Map.of("per_person", 2000), 1))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("stage_locked"));

        // 매표소: 3,000 × 2 = 6,000. 먼저 한 장 값만 내는 오개념으로 틀린다.
        mockMvc.perform(submit(token, visitId, "ticket", Map.of("total_price", 3000), 1))
                .andExpect(jsonPath("$.is_correct").value(false))
                .andExpect(jsonPath("$.feedback_code").value("ticket_short"))
                .andExpect(jsonPath("$.expected_answers.total_price").value(6000))
                .andExpect(jsonPath("$.next_stage").value("ticket"));

        mockMvc.perform(submit(token, visitId, "ticket", Map.of("total_price", 6000), 2))
                .andExpect(jsonPath("$.is_correct").value(true))
                .andExpect(jsonPath("$.next_stage").value("snack_split"));

        // 간식가게: 6,000 ÷ 3 = 2,000.
        mockMvc.perform(submit(token, visitId, "snack_split", Map.of("per_person", 2000), 1))
                .andExpect(jsonPath("$.is_correct").value(true))
                .andExpect(jsonPath("$.next_stage").value("pass_break_even"));

        // 자유이용권: 10,000 ÷ 2,000 = 5회가 본전, 6회부터 이득.
        mockMvc.perform(submit(token, visitId, "pass_break_even",
                        Map.of("break_even_rides", 5, "benefit_from_rides", 5), 1))
                .andExpect(jsonPath("$.is_correct").value(false))
                .andExpect(jsonPath("$.feedback_code").value("pass_break_even_wrong"));

        // 세 단계를 다 마치기 전에는 완료할 수 없다.
        mockMvc.perform(post("/v1/amusement-park-visits/{id}/complete", visitId)
                        .header("Authorization", token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("stage_incomplete"));

        mockMvc.perform(submit(token, visitId, "pass_break_even",
                        Map.of("break_even_rides", 5, "benefit_from_rides", 6), 2))
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
                .andExpect(jsonPath("$.attempts[0].payload.answers.total_price").value(3000))
                .andExpect(jsonPath("$.attempts[0].payload.expected_answers.total_price").value(6000))
                .andExpect(jsonPath("$.attempts[0].payload.given_facts.ticket_price").value(3000));
    }

    @Test
    void 계약에_없는_단계나_답은_판정_전에_거절한다() throws Exception {
        String token = unlockedParkToken("유진", "MORMI-P04");
        String visitId = startParkVisit(token);

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
        String visitId = startParkVisit(owner);
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

    private String startParkVisit(String token) throws Exception {
        String body = mockMvc.perform(post("/v1/amusement-park-visits").header("Authorization", token))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("visit_id").asText();
    }

    private String learnerToken(String name, String code) throws Exception {
        return "Bearer " + AuthTestSupport.signupLearner(mockMvc, objectMapper, name, code)
                .get("access_token").asText();
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
        String visitId = objectMapper.readTree(visitBody).get("cafe_visit_id").asText();

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
        String sessionId = objectMapper.readTree(body).get("learning_session_id").asText();

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
