package com.mormi.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 대상자 테스트에서 반드시 성립해야 하는 것들을 실제 PostgreSQL 로 검증한다.
 * 1) 아이들이 한 사람으로 섞이지 않는다
 * 2) 오답 상세가 남는다
 * 3) 카페 진행과 결제 기록이 남는다
 * 4) 새로고침·재전송에도 보상이 중복 지급되지 않는다
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class LearningFlowIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    private JsonNode createLearner(String name, String code) throws Exception {
        String body = mockMvc.perform(post("/v1/learners")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("display_name", name, "research_code", code))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    @Test
    void 서로_다른_아이는_서로_다른_학습자로_분리된다() throws Exception {
        JsonNode first = createLearner("민준", "MORMI-A01");
        JsonNode second = createLearner("지우", "MORMI-A02");

        assertThat(first.get("id").asLong()).isNotEqualTo(second.get("id").asLong());
        assertThat(first.get("access_token").asText()).isNotEqualTo(second.get("access_token").asText());
        assertThat(first.get("analytics_id").asText()).isNotEqualTo(second.get("analytics_id").asText());
        assertThat(first.get("conversation_storage_consent").asBoolean()).isTrue();
        assertThat(first.get("retention_policy").asText()).isEqualTo("permanent");

        // 각자 지갑은 6,000원에서 시작하고 서로 섞이지 않는다.
        mockMvc.perform(get("/v1/progress").header("Authorization", "Bearer " + first.get("access_token").asText()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.wallet_balance").value(6000))
                .andExpect(jsonPath("$.display_name").value("민준"))
                .andExpect(jsonPath("$.completed_session_ids").isEmpty());
    }

    @Test
    void 같은_연구코드로_다시_들어오면_진행도가_이어진다() throws Exception {
        JsonNode created = createLearner("서연", "MORMI-A03");
        long learnerId = created.get("id").asLong();

        // 기기를 바꿔 코드만으로 복구
        String restored = mockMvc.perform(post("/v1/learners/auth")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("research_code", "MORMI-A03"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(objectMapper.readTree(restored).get("id").asLong()).isEqualTo(learnerId);
    }

    @Test
    void 인증_없이는_진행도를_읽을_수_없다() throws Exception {
        mockMvc.perform(get("/v1/progress")).andExpect(status().isUnauthorized());
    }

    @Test
    void 오답_상세가_저장되고_보상은_서버가_등급을_계산한다() throws Exception {
        String token = "Bearer " + createLearner("하준", "MORMI-B01").get("access_token").asText();

        String started = mockMvc.perform(post("/v1/learning-sessions")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("curriculum_session_id", "money-count", "variant_seed", 1284))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String sessionId = objectMapper.readTree(started).get("learning_session_id").asText();

        // 1번 문제: 오답 한 번 뒤 정답 → 150원이어야 한다.
        mockMvc.perform(post("/v1/learning-sessions/{id}/attempts", sessionId)
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "activity", "drill",
                                "attempt_no", 1,
                                "item_id", "money-count:0",
                                "question_index", 0,
                                "is_correct", false,
                                "elapsed_ms", 4200,
                                "answer_meta", Map.of(
                                        "selected_choice_id", "c2",
                                        "misconception_tag", "coin_count_not_value")))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reward_granted").value(0));

        mockMvc.perform(post("/v1/learning-sessions/{id}/attempts", sessionId)
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "activity", "drill",
                                "attempt_no", 2,
                                "item_id", "money-count:0",
                                "question_index", 0,
                                "is_correct", true,
                                "elapsed_ms", 3100,
                                "answer_meta", Map.of("selected_choice_id", "c1")))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reward_granted").value(150));

        // 오답 상세가 실제로 남아 있는지 확인한다.
        mockMvc.perform(get("/v1/learning-sessions/{id}", sessionId).header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.variant_seed").value(1284))
                .andExpect(jsonPath("$.attempts.length()").value(2))
                .andExpect(jsonPath("$.attempts[0].is_correct").value(false))
                .andExpect(jsonPath("$.attempts[0].answer_meta.selected_choice_id").value("c2"))
                .andExpect(jsonPath("$.attempts[0].answer_meta.misconception_tag").value("coin_count_not_value"))
                .andExpect(jsonPath("$.attempts[1].answer_meta.wrong_count_before").value(1));
    }

    @Test
    void 같은_시도를_재전송해도_보상은_한_번만_지급된다() throws Exception {
        String token = "Bearer " + createLearner("시우", "MORMI-B02").get("access_token").asText();
        String sessionId = startSession(token, "money-price");

        Map<String, Object> attempt = Map.of(
                "activity", "drill",
                "attempt_no", 1,
                "item_id", "money-price:0",
                "question_index", 0,
                "is_correct", true,
                "elapsed_ms", 2000,
                "answer_meta", Map.of("selected_choice_id", "c1"));

        mockMvc.perform(post("/v1/learning-sessions/{id}/attempts", sessionId)
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(attempt)))
                .andExpect(jsonPath("$.reward_granted").value(200));

        // 네트워크 재시도로 같은 attempt_no 가 다시 와도 추가 지급이 없어야 한다.
        mockMvc.perform(post("/v1/learning-sessions/{id}/attempts", sessionId)
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(attempt)))
                .andExpect(jsonPath("$.duplicate").value(true))
                .andExpect(jsonPath("$.session_reward_subtotal").value(200));

        mockMvc.perform(get("/v1/progress").header("Authorization", token))
                .andExpect(jsonPath("$.wallet_balance").value(6200));
    }

    @Test
    void 필수_5개를_마쳐야_카페가_열리고_해금_전에는_방문이_막힌다() throws Exception {
        String token = "Bearer " + createLearner("나윤", "MORMI-C01").get("access_token").asText();

        mockMvc.perform(post("/v1/cafe-visits").header("Authorization", token))
                .andExpect(status().isForbidden());

        for (String sessionKey : CurriculumCatalog.CAFE_REQUIRED_SESSION_IDS) {
            completeSession(token, sessionKey);
        }

        mockMvc.perform(get("/v1/progress").header("Authorization", token))
                .andExpect(jsonPath("$.cafe_unlocked").value(true))
                .andExpect(jsonPath("$.completed_session_ids.length()").value(5));

        mockMvc.perform(post("/v1/cafe-visits").header("Authorization", token))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.stage").value("queue"));
    }

    @Test
    void 카페_진행과_결제_기록이_모두_남는다() throws Exception {
        String token = "Bearer " + createLearner("도윤", "MORMI-C02").get("access_token").asText();
        for (String sessionKey : CurriculumCatalog.CAFE_REQUIRED_SESSION_IDS) {
            completeSession(token, sessionKey);
        }

        String visitBody = mockMvc.perform(post("/v1/cafe-visits").header("Authorization", token))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String visitId = objectMapper.readTree(visitBody).get("cafe_visit_id").asText();

        // 줄 서기: 왼쪽 4명, 오른쪽 2명이면 정답은 "2". 먼저 틀리고 그 다음 맞힌다.
        mockMvc.perform(post("/v1/cafe-visits/{id}/queue", visitId)
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "left_count", 4, "right_count", 2, "chosen_count", 4,
                                "counting_answer", "4, 2", "scaffold_used", false, "attempt_no", 1))))
                .andExpect(jsonPath("$.is_correct").value(false))
                .andExpect(jsonPath("$.expected_amount").value(2))
                .andExpect(jsonPath("$.next_stage").value("queue"));

        mockMvc.perform(post("/v1/cafe-visits/{id}/queue", visitId)
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "left_count", 4, "right_count", 2, "chosen_count", 2,
                                "counting_answer", "4, 2", "scaffold_used", true, "attempt_no", 2))))
                .andExpect(jsonPath("$.is_correct").value(true))
                .andExpect(jsonPath("$.next_stage").value("menu"));

        // 메뉴 2개: 예산 8,000원에 케이크 4,500 + 샌드위치 5,000 = 9,500 은 초과.
        mockMvc.perform(post("/v1/cafe-visits/{id}/menu", visitId)
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "menu_ids", List.of("strawberry-cake", "sandwich"),
                                "budget", 8000, "attempt_no", 1))))
                .andExpect(jsonPath("$.is_correct").value(false))
                .andExpect(jsonPath("$.feedback_code").value("menu_over_budget"))
                .andExpect(jsonPath("$.next_stage").value("menu"));

        // 아메리카노 3,000 + 쿠키 2,000 = 5,000 은 예산 안.
        mockMvc.perform(post("/v1/cafe-visits/{id}/menu", visitId)
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "menu_ids", List.of("americano", "cookie"),
                                "budget", 8000, "attempt_no", 2))))
                .andExpect(jsonPath("$.is_correct").value(true))
                .andExpect(jsonPath("$.submitted_amount").value(5000))
                .andExpect(jsonPath("$.next_stage").value("calculate"));

        // 계산: 이 단계 메뉴는 다시 뽑힌다. 케이크 4,500 + 샌드위치 5,000 = 9,500.
        mockMvc.perform(post("/v1/cafe-visits/{id}/payments", visitId)
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "menu_ids", List.of("strawberry-cake", "sandwich"),
                                "answer_amount", 9000, "attempt_no", 1))))
                .andExpect(jsonPath("$.is_correct").value(false))
                .andExpect(jsonPath("$.difference").value(-500));

        mockMvc.perform(post("/v1/cafe-visits/{id}/payments", visitId)
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "menu_ids", List.of("strawberry-cake", "sandwich"),
                                "answer_amount", 9500, "attempt_no", 2))))
                .andExpect(jsonPath("$.is_correct").value(true))
                .andExpect(jsonPath("$.next_stage").value("change"));

        // 거스름돈: 이 단계 메뉴도 따로 뽑힌다. 10,000 − 아메리카노 3,000 = 7,000.
        mockMvc.perform(post("/v1/cafe-visits/{id}/change", visitId)
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "menu_id", "americano",
                                "counts", Map.of("1000", 6, "500", 2), "attempt_no", 1))))
                .andExpect(jsonPath("$.is_correct").value(true))
                .andExpect(jsonPath("$.expected_amount").value(7000));

        mockMvc.perform(post("/v1/cafe-visits/{id}/complete", visitId).header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stage").value("complete"));

        // 새로고침해도 복구되고, 틀린 시도까지 전부 남아 있다.
        mockMvc.perform(get("/v1/cafe-visits/{id}", visitId).header("Authorization", token))
                .andExpect(jsonPath("$.order_total").value(5000))
                .andExpect(jsonPath("$.paid_amount").value(9500))
                .andExpect(jsonPath("$.change_amount").value(7000))
                .andExpect(jsonPath("$.attempts.length()").value(7))
                .andExpect(jsonPath("$.attempts[0].is_correct").value(false))
                .andExpect(jsonPath("$.attempts[0].payload.chosen_count").value(4))
                .andExpect(jsonPath("$.attempts[2].payload.budget").value(8000))
                .andExpect(jsonPath("$.attempts[4].payload.answer_amount").value(9000));

        // 카페를 끝낸 뒤에도 방문은 그대로 남아 네 단계를 다시 연습할 수 있다.
        mockMvc.perform(post("/v1/cafe-visits").header("Authorization", token))
                .andExpect(jsonPath("$.cafe_visit_id").value(visitId))
                .andExpect(jsonPath("$.stage").value("complete"));

        // 줄 서기를 새 시도 번호로 다시 풀어도 409 가 아니라 정상 채점된다.
        mockMvc.perform(post("/v1/cafe-visits/{id}/queue", visitId)
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "left_count", 1, "right_count", 4, "chosen_count", 4,
                                "scaffold_used", false, "attempt_no", 3))))
                .andExpect(jsonPath("$.is_correct").value(false));

        mockMvc.perform(post("/v1/cafe-visits/{id}/queue", visitId)
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "left_count", 1, "right_count", 4, "chosen_count", 1,
                                "scaffold_used", false, "attempt_no", 4))))
                .andExpect(jsonPath("$.is_correct").value(true));

        // 재연습 기록이 쌓이고, 진행도는 complete 에서 되돌아가지 않는다.
        mockMvc.perform(get("/v1/cafe-visits/{id}", visitId).header("Authorization", token))
                .andExpect(jsonPath("$.stage").value("complete"))
                .andExpect(jsonPath("$.completed_at").isNotEmpty())
                .andExpect(jsonPath("$.attempts.length()").value(9));

        // 완료 재호출은 멱등이다.
        mockMvc.perform(post("/v1/cafe-visits/{id}/complete", visitId).header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stage").value("complete"));
    }

    @Test
    void 다른_학습자의_세션에는_접근할_수_없다() throws Exception {
        String ownerToken = "Bearer " + createLearner("은우", "MORMI-D01").get("access_token").asText();
        String otherToken = "Bearer " + createLearner("수아", "MORMI-D02").get("access_token").asText();
        String sessionId = startSession(ownerToken, "money-count");

        mockMvc.perform(get("/v1/learning-sessions/{id}", sessionId).header("Authorization", otherToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void 대화_서비스가_설정되지_않으면_가르치기_500원은_지급되지_않는다() throws Exception {
        String token = "Bearer " + createLearner("주원", "MORMI-E01").get("access_token").asText();
        String sessionId = startSession(token, "money-count");

        mockMvc.perform(post("/v1/learning-sessions/{id}/complete", sessionId)
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "conversation_id", "conversation_x9",
                                "transfer_solved", true,
                                "timed_out", false,
                                "scaffold_level", 3,
                                "elapsed_seconds", 142))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.teach_reward").value(0))
                .andExpect(jsonPath("$.teach_reward_eligible").value(false));
    }

    @Test
    void 리포트는_저장된_시도에서_계산된다() throws Exception {
        String token = "Bearer " + createLearner("예린", "MORMI-F01").get("access_token").asText();
        completeSession(token, "money-count");

        mockMvc.perform(get("/v1/reports/summary").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.session_id").value("money-count"))
                .andExpect(jsonPath("$.learner_name").value("예린"))
                .andExpect(jsonPath("$.mastery_target").value(5))
                .andExpect(jsonPath("$.repetitions").value(5))
                .andExpect(jsonPath("$.first_try_correct_count").value(5))
                .andExpect(jsonPath("$.drill_coins").value(1000));
    }

    private String startSession(String token, String curriculumSessionId) throws Exception {
        String body = mockMvc.perform(post("/v1/learning-sessions")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("curriculum_session_id", curriculumSessionId, "variant_seed", 7))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("learning_session_id").asText();
    }

    /** 5문제를 첫 시도에 모두 맞히고 세션을 끝낸다. 드릴 보상은 1,000원 상한이다. */
    private void completeSession(String token, String curriculumSessionId) throws Exception {
        String sessionId = startSession(token, curriculumSessionId);
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
