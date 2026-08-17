package com.mormi.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mormi.backend.auth.TokenHasher;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

/**
 * 아이디·비밀번호 인증에서 반드시 성립해야 하는 것들을 실제 PostgreSQL 로 검증한다.
 * 1) 가입한 아이만 자기 데이터에 접근한다
 * 2) 응답이 아이디 존재 여부를 흘리지 않는다
 * 3) 기기를 두 대 써도 서로를 밀어내지 않는다
 * 4) 로그아웃·만료된 토큰은 그 순간부터 통하지 않는다
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class LearnerAuthIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    TokenHasher tokenHasher;

    @Autowired
    JdbcTemplate jdbcTemplate;

    private String signup(String name, String code, String loginId, String password) throws Exception {
        String body = mockMvc.perform(post("/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "display_name", name,
                                "research_code", code,
                                "login_id", loginId,
                                "password", password))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("access_token").asText();
    }

    private String login(String loginId, String password) throws Exception {
        String body = mockMvc.perform(post("/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("login_id", loginId, "password", password))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("access_token").asText();
    }

    private void expectProgress(String token, int expectedStatus) throws Exception {
        mockMvc.perform(get("/v1/progress").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().is(expectedStatus));
    }

    @Test
    void 가입한_학습자는_로그인해서_보호_API_를_쓰고_로그아웃하면_끊긴다() throws Exception {
        signup("민준", "AUTH-A01", "minjun01", "pilot1234");
        String token = login("minjun01", "pilot1234");

        expectProgress(token, 200);

        mockMvc.perform(post("/v1/auth/logout").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isNoContent());

        expectProgress(token, 401);
    }

    @Test
    void 이미_쓰는_아이디로는_가입할_수_없다() throws Exception {
        signup("지우", "AUTH-B01", "jiwoo01", "pilot1234");

        mockMvc.perform(post("/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "display_name", "다른아이",
                                "research_code", "AUTH-B02",
                                "login_id", "jiwoo01",
                                "password", "pilot1234"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("login_id_taken"));
    }

    /** 아이디가 없을 때와 비밀번호가 틀릴 때의 응답이 같아야 가입 여부를 떠볼 수 없다. */
    @Test
    void 로그인_실패는_아이디_존재_여부를_드러내지_않는다() throws Exception {
        signup("서연", "AUTH-C01", "seoyeon01", "pilot1234");

        String wrongPassword = mockMvc.perform(post("/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("login_id", "seoyeon01", "password", "wrongpass1"))))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        String unknownId = mockMvc.perform(post("/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("login_id", "nobody01", "password", "wrongpass1"))))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        assertThat(wrongPassword).isEqualTo(unknownId);
        assertThat(wrongPassword).doesNotContain("seoyeon01");
    }

    @Test
    void 두_기기에서_로그인해도_서로를_밀어내지_않는다() throws Exception {
        signup("하준", "AUTH-D01", "hajun01", "pilot1234");
        String tablet = login("hajun01", "pilot1234");
        String phone = login("hajun01", "pilot1234");

        assertThat(tablet).isNotEqualTo(phone);
        expectProgress(tablet, 200);
        expectProgress(phone, 200);
    }

    @Test
    void 한_기기_로그아웃은_다른_기기의_토큰을_살려둔다() throws Exception {
        signup("수아", "AUTH-E01", "sua01", "pilot1234");
        String tablet = login("sua01", "pilot1234");
        String phone = login("sua01", "pilot1234");

        mockMvc.perform(post("/v1/auth/logout").header(HttpHeaders.AUTHORIZATION, "Bearer " + tablet))
                .andExpect(status().isNoContent());

        expectProgress(tablet, 401);
        expectProgress(phone, 200);
    }

    @Test
    void 전체_로그아웃은_모든_기기의_토큰을_폐기한다() throws Exception {
        signup("도윤", "AUTH-F01", "doyun01", "pilot1234");
        String tablet = login("doyun01", "pilot1234");
        String phone = login("doyun01", "pilot1234");

        mockMvc.perform(post("/v1/auth/logout-all").header(HttpHeaders.AUTHORIZATION, "Bearer " + phone))
                .andExpect(status().isNoContent());

        expectProgress(tablet, 401);
        expectProgress(phone, 401);
    }

    /** 30일을 기다릴 수 없으므로 만료 시각을 과거로 돌려 같은 상황을 만든다. */
    @Test
    void 만료된_토큰은_통하지_않는다() throws Exception {
        signup("예린", "AUTH-G01", "yerin01", "pilot1234");
        String token = login("yerin01", "pilot1234");
        expectProgress(token, 200);

        jdbcTemplate.update(
                "UPDATE learner_tokens SET expires_at = NOW() - INTERVAL '1 day' WHERE token_hash = ?",
                tokenHasher.hash(token));

        expectProgress(token, 401);
    }
}
