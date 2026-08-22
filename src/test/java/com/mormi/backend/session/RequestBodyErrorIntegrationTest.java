package com.mormi.backend.session;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mormi.backend.AuthTestSupport;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
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
 * 잘못된 요청 본문이 500 이 아니라 클라이언트 오류로 나가는지 검증한다.
 * 1) 깨진 JSON·타입 불일치·본문 누락은 400 invalid_request
 * 2) 오류 메시지에 내부 클래스명·스택이 새지 않는다
 * 3) 필수인 variant_seed 누락은 어느 필드가 빠졌는지 알려주는 422 validation_failed
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class RequestBodyErrorIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    /** 테스트마다 새 학습자를 만든다. 연구 코드는 한 번만 쓸 수 있으므로 겹치지 않게 센다. */
    private static final AtomicInteger 코드번호 = new AtomicInteger();

    private String token;

    @BeforeEach
    void 학습자를_만든다() throws Exception {
        String code = "MORMI-BODY-%02d".formatted(코드번호.incrementAndGet());
        token = "Bearer " + AuthTestSupport.signupLearner(mockMvc, objectMapper, "본문", code)
                .get("access_token").asString();
    }

    @Test
    void 필수인_variant_seed_가_빠지면_어느_필드인지_알려주는_422_다() throws Exception {
        mockMvc.perform(post("/v1/learning-sessions")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("curriculum_session_id", "number-count"))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("validation_failed"))
                .andExpect(jsonPath("$.fields.variantSeed").isNotEmpty());
    }

    @Test
    void 깨진_JSON_은_400_invalid_request_다() throws Exception {
        mockMvc.perform(post("/v1/learning-sessions")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"curriculum_session_id\": \"number-count\","))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_request"));
    }

    @Test
    void 본문이_아예_없으면_400_invalid_request_다() throws Exception {
        mockMvc.perform(post("/v1/learning-sessions")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_request"));
    }

    @Test
    void 타입이_맞지_않는_값도_400_이고_내부_정보를_흘리지_않는다() throws Exception {
        mockMvc.perform(post("/v1/learning-sessions")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "curriculum_session_id", "number-count",
                                "variant_seed", "일이삼사"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_request"))
                .andExpect(jsonPath("$.message").value("요청 본문을 읽을 수 없습니다."));
    }

    @Test
    void 원시_필드가_빠진_다른_엔드포인트도_500_이_아니다() throws Exception {
        String started = mockMvc.perform(post("/v1/learning-sessions")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("curriculum_session_id", "number-count", "variant_seed", 7))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String sessionId = objectMapper.readTree(started).get("learning_session_id").asString();

        // attempt_no 가 원시 int 라 누락되면 본문 파싱이 깨진다. 클라이언트 잘못이므로 400 이다.
        mockMvc.perform(post("/v1/learning-sessions/{id}/attempts", sessionId)
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "activity", "drill",
                                "question_index", 0,
                                "is_correct", true))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_request"));
    }
}
