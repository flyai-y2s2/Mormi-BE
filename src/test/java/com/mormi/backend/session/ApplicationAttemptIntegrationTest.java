package com.mormi.backend.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 새 숫자 적용 시도가 지켜야 하는 것들을 실제 PostgreSQL 로 검증한다.
 * 1) 적용 시도가 맥락·지원 수준과 함께 attempt 단위로 남는다
 * 2) 적용 시도 기록이 있으면 transfer_solved 는 FE 값이 아니라 서버 기록으로 정해진다
 * 3) 적용 시도가 없으면 기존 FE 방식이 그대로 동작한다 (하위 호환)
 * 4) 적용 맥락은 transfer 가 아닌 시도에 섞이지 못한다
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class ApplicationAttemptIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    LearningSessionRepository sessionRepository;

    @Autowired
    AttemptRepository attemptRepository;

    @Test
    void 적용_시도가_맥락과_지원_수준과_함께_남는다() throws Exception {
        학습 학습 = 학습을_시작한다("MORMI-APP-01");

        적용_시도(학습, 1, false, "same_form_new_number", 0).andExpect(status().isCreated());
        적용_시도(학습, 2, true, "real_life_context", 2).andExpect(status().isCreated());

        var attempts = attemptRepository.findByLearningSessionIdOrderByIdAsc(세션ID(학습));
        var transfer = attempts.stream().filter(a -> "transfer".equals(a.getActivity())).toList();
        assertThat(transfer).hasSize(2);
        assertThat(transfer.get(0).getApplicationScope()).isEqualTo("same_form_new_number");
        assertThat(transfer.get(0).getSupportLevel()).isZero();
        assertThat(transfer.get(1).getApplicationScope()).isEqualTo("real_life_context");
        assertThat(transfer.get(1).getSupportLevel()).isEqualTo(2);
    }

    @Test
    void 적용_시도_기록이_있으면_서버가_transfer_solved_를_정한다() throws Exception {
        학습 학습 = 학습을_시작한다("MORMI-APP-02");
        적용_시도(학습, 1, false, "same_form_new_number", 1).andExpect(status().isCreated());

        // FE 는 성공이라고 보내지만 저장된 적용 시도는 전부 오답이다.
        완료(학습, true);

        LearningSession session = sessionRepository.findByPublicId(학습.publicId()).orElseThrow();
        assertThat(session.isTransferSolved()).isFalse();
    }

    @Test
    void 적용_시도가_없으면_FE_값을_그대로_쓴다() throws Exception {
        학습 학습 = 학습을_시작한다("MORMI-APP-03");

        완료(학습, true);

        LearningSession session = sessionRepository.findByPublicId(학습.publicId()).orElseThrow();
        assertThat(session.isTransferSolved()).isTrue();
    }

    @Test
    void 적용_맥락은_drill_시도에_섞이지_못한다() throws Exception {
        학습 학습 = 학습을_시작한다("MORMI-APP-04");

        Map<String, Object> body = 시도_본문("drill", 1, true);
        body.put("application_scope", "real_life_context");

        mockMvc.perform(post("/v1/learning-sessions/" + 학습.publicId() + "/attempts")
                        .header("Authorization", "Bearer " + 학습.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("application_scope_not_allowed"));
    }

    @Test
    void 모르는_적용_맥락은_저장을_거절한다() throws Exception {
        학습 학습 = 학습을_시작한다("MORMI-APP-05");

        Map<String, Object> body = 시도_본문("transfer", 1, true);
        body.put("application_scope", "totally_new_scope");

        mockMvc.perform(post("/v1/learning-sessions/" + 학습.publicId() + "/attempts")
                        .header("Authorization", "Bearer " + 학습.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isUnprocessableContent());
    }

    private record 학습(String token, String publicId) {
    }

    private 학습 학습을_시작한다(String researchCode) throws Exception {
        String learnerBody = mockMvc.perform(post("/v1/learners")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("display_name", "적용", "research_code", researchCode))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        JsonNode learner = objectMapper.readTree(learnerBody);
        String token = learner.get("access_token").asString();

        String sessionBody = mockMvc.perform(post("/v1/learning-sessions")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("curriculum_session_id", "money-count", "variant_seed", 0))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return new 학습(token, objectMapper.readTree(sessionBody).get("learning_session_id").asString());
    }

    private Map<String, Object> 시도_본문(String activity, int attemptNo, boolean correct) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("activity", activity);
        body.put("attempt_no", attemptNo);
        body.put("item_id", "money-count:apply:" + attemptNo);
        body.put("question_index", attemptNo);
        body.put("is_correct", correct);
        body.put("elapsed_ms", 1500);
        return body;
    }

    private ResultActions 적용_시도(
            학습 학습, int attemptNo, boolean correct, String scope, int supportLevel) throws Exception {
        Map<String, Object> body = 시도_본문("transfer", attemptNo, correct);
        body.put("application_scope", scope);
        body.put("support_level", supportLevel);
        return mockMvc.perform(post("/v1/learning-sessions/" + 학습.publicId() + "/attempts")
                .header("Authorization", "Bearer " + 학습.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private void 완료(학습 학습, boolean transferSolved) throws Exception {
        mockMvc.perform(post("/v1/learning-sessions/" + 학습.publicId() + "/complete")
                        .header("Authorization", "Bearer " + 학습.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "transfer_solved", transferSolved,
                                "timed_out", false,
                                "scaffold_level", 1,
                                "elapsed_seconds", 60))))
                .andExpect(status().isOk());
    }

    private Long 세션ID(학습 학습) {
        return sessionRepository.findByPublicId(학습.publicId()).orElseThrow().getId();
    }
}
