package com.mormi.backend;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Locale;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 통합 테스트 공용 학습자 픽스처. 구 연구 코드 온보딩(POST /v1/learners)이 제거되어
 * 학습자는 아이디·비밀번호 가입(POST /v1/auth/signup)으로만 만들 수 있다.
 */
public final class AuthTestSupport {

    /** 픽스처 공용 비밀번호. 재로그인 테스트가 같은 값으로 로그인할 수 있게 공개한다. */
    public static final String PASSWORD = "password123";

    private AuthTestSupport() {
    }

    /** 학습자를 가입시키고 생성 응답 JSON(access_token 포함)을 돌려준다. */
    public static JsonNode signupLearner(
            MockMvc mockMvc, ObjectMapper objectMapper, String displayName, String researchCode)
            throws Exception {
        String body = mockMvc.perform(post("/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "display_name", displayName,
                                "research_code", researchCode,
                                "login_id", loginId(researchCode),
                                "password", PASSWORD))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    /** login_id 는 영숫자만 허용되므로 연구 코드에서 나머지 문자를 걷어내 파생시킨다. */
    public static String loginId(String researchCode) {
        return researchCode.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
    }
}
