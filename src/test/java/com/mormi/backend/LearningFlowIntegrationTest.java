package com.mormi.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mormi.backend.curriculum.CurriculumCatalog;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
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
        return AuthTestSupport.signupLearner(mockMvc, objectMapper, name, code);
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
    void 같은_아이디로_다시_로그인하면_진행도가_이어진다() throws Exception {
        JsonNode created = createLearner("서연", "MORMI-A03");
        long learnerId = created.get("id").asLong();

        // 기기를 바꿔 아이디·비밀번호로 다시 로그인
        String restored = mockMvc.perform(post("/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "login_id", AuthTestSupport.loginId("MORMI-A03"),
                                "password", AuthTestSupport.PASSWORD))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode login = objectMapper.readTree(restored);
        assertThat(login.get("role").asString()).isEqualTo("learner");
        assertThat(login.get("learner").get("id").asLong()).isEqualTo(learnerId);
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
    void 메뉴_예산은_7000원과_8000원이_공식이고_기존_저장분_9000원_10000원도_한시_허용한다() throws Exception {
        String token = "Bearer " + createLearner("소민", "MORMI-C04").get("access_token").asText();
        for (String sessionKey : CurriculumCatalog.CAFE_REQUIRED_SESSION_IDS) {
            completeSession(token, sessionKey);
        }

        String visitBody = mockMvc.perform(post("/v1/cafe-visits").header("Authorization", token))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String visitId = objectMapper.readTree(visitBody).get("cafe_visit_id").asText();

        // 줄 서기를 먼저 통과해 메뉴 단계를 연다.
        mockMvc.perform(post("/v1/cafe-visits/{id}/queue", visitId)
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "left_count", 3, "right_count", 1, "chosen_count", 1,
                                "scaffold_used", false, "attempt_no", 1))))
                .andExpect(jsonPath("$.is_correct").value(true));

        // 예산 7,000원: 우유 2,000 + 딸기주스 4,000 = 6,000 은 예산 안이라 성공.
        mockMvc.perform(post("/v1/cafe-visits/{id}/menu", visitId)
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "menu_ids", List.of("milk", "strawberry-juice"),
                                "budget", 7000, "attempt_no", 1))))
                .andExpect(jsonPath("$.is_correct").value(true))
                .andExpect(jsonPath("$.submitted_amount").value(6000))
                .andExpect(jsonPath("$.feedback_code").value("menu_selected"));

        // 경계값: 쿠키 2,000 + 샌드위치 5,000 = 7,000 은 예산과 같으므로 성공.
        mockMvc.perform(post("/v1/cafe-visits/{id}/menu", visitId)
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "menu_ids", List.of("cookie", "sandwich"),
                                "budget", 7000, "attempt_no", 2))))
                .andExpect(jsonPath("$.is_correct").value(true))
                .andExpect(jsonPath("$.submitted_amount").value(7000));

        // 합계가 예산을 넘을 때만 초과 처리: 아메리카노 3,000 + 케이크 4,500 = 7,500 > 7,000.
        mockMvc.perform(post("/v1/cafe-visits/{id}/menu", visitId)
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "menu_ids", List.of("americano", "strawberry-cake"),
                                "budget", 7000, "attempt_no", 3))))
                .andExpect(jsonPath("$.is_correct").value(false))
                .andExpect(jsonPath("$.feedback_code").value("menu_over_budget"));

        // 허용 목록 밖 예산은 합계 판정 전에 400 으로 거부된다.
        mockMvc.perform(post("/v1/cafe-visits/{id}/menu", visitId)
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "menu_ids", List.of("milk", "cookie"),
                                "budget", 6000, "attempt_no", 4))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("budget"));

        // 배포 전 저장 대화 호환: 9,000원·10,000원은 아직 유효한 예산으로 인정한다.
        mockMvc.perform(post("/v1/cafe-visits/{id}/menu", visitId)
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "menu_ids", List.of("milk", "cookie"),
                                "budget", 9000, "attempt_no", 5))))
                .andExpect(jsonPath("$.is_correct").value(true));

        mockMvc.perform(post("/v1/cafe-visits/{id}/menu", visitId)
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "menu_ids", List.of("milk", "cookie"),
                                "budget", 10000, "attempt_no", 6))))
                .andExpect(jsonPath("$.is_correct").value(true));
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
    void 카페_문제_계약_위반은_4xx로_거절되고_기록이_남지_않는다() throws Exception {
        String token = "Bearer " + createLearner("이든", "MORMI-C03").get("access_token").asText();
        for (String sessionKey : CurriculumCatalog.CAFE_REQUIRED_SESSION_IDS) {
            completeSession(token, sessionKey);
        }
        String visitBody = mockMvc.perform(post("/v1/cafe-visits").header("Authorization", token))
                .andReturn().getResponse().getContentAsString();
        String visitId = objectMapper.readTree(visitBody).get("cafe_visit_id").asText();

        // 좌우 줄 인원이 같으면 "더 짧은 줄" 문제가 성립하지 않는다.
        mockMvc.perform(post("/v1/cafe-visits/{id}/queue", visitId)
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "left_count", 3, "right_count", 3, "chosen_count", 3,
                                "scaffold_used", false, "attempt_no", 1))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("queue_count_equal"));

        // 계약 범위(1~5)를 벗어난 인원은 DTO 검증이 422로 거절한다.
        mockMvc.perform(post("/v1/cafe-visits/{id}/queue", visitId)
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "left_count", 7, "right_count", 2, "chosen_count", 2,
                                "scaffold_used", false, "attempt_no", 1))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("validation_failed"));

        // AI 대화 시작도 같은 계약으로 막혀 대화 자체가 만들어지지 않는다.
        mockMvc.perform(post("/v1/cafe-visits/{id}/dialogues", visitId)
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "scenario_id", "cafe_queue",
                                "queue_context", Map.of("left_count", 4, "right_count", 4),
                                "restart", false))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("queue_count_equal"));

        // 계약을 지킨 문제는 그대로 채점되어 다음 단계가 열린다.
        mockMvc.perform(post("/v1/cafe-visits/{id}/queue", visitId)
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "left_count", 2, "right_count", 5, "chosen_count", 2,
                                "scaffold_used", false, "attempt_no", 1))))
                .andExpect(jsonPath("$.is_correct").value(true))
                .andExpect(jsonPath("$.next_stage").value("menu"));

        // 같은 메뉴 두 개는 제출 단계에서도 거절한다.
        mockMvc.perform(post("/v1/cafe-visits/{id}/menu", visitId)
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "menu_ids", List.of("cookie", "cookie"),
                                "budget", 8000, "attempt_no", 1))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("menu_duplicate"));

        // 카탈로그에 없는 메뉴도 안정적인 코드로 거절한다.
        mockMvc.perform(post("/v1/cafe-visits/{id}/menu", visitId)
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "menu_ids", List.of("cookie", "latte"),
                                "budget", 8000, "attempt_no", 1))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("menu_unknown"));

        // 메뉴 대화 시작은 화면이 보낸 가격을 서버 카탈로그와 대조한다.
        mockMvc.perform(post("/v1/cafe-visits/{id}/dialogues", visitId)
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "scenario_id", "cafe_budget_menu",
                                "cafe_context", Map.of(
                                        "menu_items", List.of(
                                                Map.of("id", "americano", "name", "아메리카노", "price", 3500),
                                                Map.of("id", "cookie", "name", "쿠키", "price", 2000)),
                                        "mormi_menu_id", "americano",
                                        "budget", 8000),
                                "restart", false))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("menu_price_mismatch"));

        // 서로 다른 두 메뉴는 정상 채점된다.
        mockMvc.perform(post("/v1/cafe-visits/{id}/menu", visitId)
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "menu_ids", List.of("americano", "cookie"),
                                "budget", 8000, "attempt_no", 1))))
                .andExpect(jsonPath("$.is_correct").value(true))
                .andExpect(jsonPath("$.next_stage").value("calculate"));

        // 계산 제출도 같은 메뉴 중복을 거절한다.
        mockMvc.perform(post("/v1/cafe-visits/{id}/payments", visitId)
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "menu_ids", List.of("milk", "milk"),
                                "answer_amount", 4000, "attempt_no", 1))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("menu_duplicate"));

        // 거절된 시도는 학습 기록으로 남지 않는다. 남은 것은 정답 두 건뿐이다.
        mockMvc.perform(get("/v1/cafe-visits/{id}", visitId).header("Authorization", token))
                .andExpect(jsonPath("$.stage").value("calculate"))
                .andExpect(jsonPath("$.attempts.length()").value(2));
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

    @Test
    void 진단_리포트는_인증한_학습자의_기록만_읽고_AI_오프라인_폴백을_반환한다() throws Exception {
        JsonNode owner = createLearner("지우", "MORMI-G01");
        JsonNode other = createLearner("수아", "MORMI-G02");
        String ownerToken = "Bearer " + owner.get("access_token").asText();
        String otherToken = "Bearer " + other.get("access_token").asText();
        String sessionId = startSession(ownerToken, "money-count");

        recordDrill(ownerToken, sessionId, 1, false);
        recordDrill(ownerToken, sessionId, 2, true);
        completeSessionByPublicId(ownerToken, sessionId);

        mockMvc.perform(get("/v1/reports/diagnostic")
                        .header("Authorization", ownerToken)
                        .param("learner_id", String.valueOf(other.get("id").asLong())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.learner.learner_id").value(owner.get("id").asLong()))
                .andExpect(jsonPath("$.learner.display_name").value("지우"))
                .andExpect(jsonPath("$.data_range.total_home_sessions").value(1))
                .andExpect(jsonPath("$.evidence_counts.drill_attempts").value(2))
                .andExpect(jsonPath("$.narrative_fallback").value(true));

        mockMvc.perform(get("/v1/reports/diagnostic").header("Authorization", otherToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.learner.learner_id").value(other.get("id").asLong()))
                .andExpect(jsonPath("$.learner.display_name").value("수아"))
                .andExpect(jsonPath("$.data_range.total_home_sessions").value(0))
                .andExpect(jsonPath("$.evidence_counts.drill_attempts").value(0));
    }

    @Test
    void 진단_리포트는_새로_완료한_세션을_저장_없이_다음_조회에_반영한다() throws Exception {
        String token = "Bearer " + createLearner("다온", "MORMI-G03").get("access_token").asText();
        completeSession(token, "money-count");

        mockMvc.perform(get("/v1/reports/diagnostic").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data_range.total_home_sessions").value(1));

        completeSession(token, "money-count");

        mockMvc.perform(get("/v1/reports/diagnostic").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data_range.total_home_sessions").value(2))
                .andExpect(jsonPath("$.modes[0].domains[0].total_count").value(2));
    }

    @Test
    void 진단_리포트는_인증_없이는_읽을_수_없다() throws Exception {
        mockMvc.perform(get("/v1/reports/diagnostic"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 진단_리포트는_서울_월요일_주차를_검증하고_메타데이터를_반환한다() throws Exception {
        String token = "Bearer " + createLearner("하린", "MORMI-G05").get("access_token").asText();
        LocalDate monday = LocalDate.now(ZoneId.of("Asia/Seoul"))
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));

        mockMvc.perform(get("/v1/reports/diagnostic")
                        .header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.period.week_start").value(monday.toString()))
                .andExpect(jsonPath("$.period.week_end").value(monday.plusDays(6).toString()))
                .andExpect(jsonPath("$.period.timezone").value("Asia/Seoul"));

        mockMvc.perform(get("/v1/reports/diagnostic")
                        .header("Authorization", token)
                        .param("week_start", monday.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.period.week_start").value(monday.toString()))
                .andExpect(jsonPath("$.period.week_end").value(monday.plusDays(6).toString()))
                .andExpect(jsonPath("$.period.timezone").value("Asia/Seoul"));

        mockMvc.perform(get("/v1/reports/diagnostic")
                        .header("Authorization", token)
                        .param("week_start", monday.plusDays(1).toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("week_start"));

        mockMvc.perform(get("/v1/reports/diagnostic")
                        .header("Authorization", token)
                        .param("week_start", monday.plusWeeks(1).toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("week_start"));

        mockMvc.perform(get("/v1/reports/diagnostic")
                        .header("Authorization", token)
                        .param("week_start", monday.minusWeeks(1).toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("week_start"));

        mockMvc.perform(get("/v1/reports/diagnostic/speech-evidence")
                        .header("Authorization", token)
                        .param("domain_id", "money-count")
                        .param("week_start", monday.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.domain_id").value("money-count"));
    }

    @Test
    void 발화_근거는_알려진_비어있지_않은_영역만_인증한_학습자에게_반환한다() throws Exception {
        String token = "Bearer " + createLearner("유진", "MORMI-G04").get("access_token").asText();

        mockMvc.perform(get("/v1/reports/diagnostic/speech-evidence")
                        .header("Authorization", token)
                        .param("domain_id", "money-count"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.domain_id").value("money-count"))
                .andExpect(jsonPath("$.available").value(false));

        mockMvc.perform(get("/v1/reports/diagnostic/speech-evidence")
                        .header("Authorization", token)
                        .param("domain_id", "unknown-domain"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("domain_id"));

        mockMvc.perform(get("/v1/reports/diagnostic/speech-evidence")
                        .header("Authorization", token)
                        .param("domain_id", "   "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("domain_id"));
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

    private void recordDrill(String token, String sessionId, int attemptNo, boolean correct) throws Exception {
        mockMvc.perform(post("/v1/learning-sessions/{id}/attempts", sessionId)
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "activity", "drill",
                                "attempt_no", attemptNo,
                                "item_id", "money-count:0",
                                "question_index", 0,
                                "is_correct", correct,
                                "elapsed_ms", 2500,
                                "answer_meta", Map.of("selected_choice_id", correct ? "c1" : "c2")))))
                .andExpect(status().isCreated());
    }

    private void completeSessionByPublicId(String token, String sessionId) throws Exception {
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
