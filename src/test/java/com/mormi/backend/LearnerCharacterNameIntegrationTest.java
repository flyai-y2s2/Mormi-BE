package com.mormi.backend;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Collections;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

/**
 * 아이가 지은 캐릭터 이름이 기기를 바꿔도 살아남는지 실제 PostgreSQL 로 검증한다.
 * 1) 저장한 이름이 앱 부팅(/v1/progress)과 재로그인 응답에 실린다
 * 2) 아직 안 지었으면 NULL 이라 FE 가 이름 짓기 화면을 띄울 수 있다
 * 3) 공백뿐이거나 12자를 넘으면 저장하지 않는다
 * 4) 남의 토큰 없이는 부를 수 없고, 한 아이의 이름이 다른 아이에게 새지 않는다
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class LearnerCharacterNameIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    private String signup(String name, String code, String loginId) throws Exception {
        String body = mockMvc.perform(post("/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "display_name", name,
                                "research_code", code,
                                "login_id", loginId,
                                "password", "pilot1234"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("access_token").asText();
    }

    private String login(String loginId) throws Exception {
        String body = mockMvc.perform(post("/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("login_id", loginId, "password", "pilot1234"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("access_token").asText();
    }

    private org.springframework.test.web.servlet.ResultActions nameCharacter(String token, String name)
            throws Exception {
        // 공백·null 도 그대로 보내야 검증을 확인할 수 있어 Map.of 대신 singletonMap 을 쓴다.
        return mockMvc.perform(patch("/v1/learners/me/character-name")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                        Collections.singletonMap("character_name", name))));
    }

    @Test
    void 저장한_캐릭터_이름은_앱_부팅과_재로그인_응답에_실린다() throws Exception {
        String token = signup("민준", "CHAR-A01", "charminjun");

        nameCharacter(token, "몽이")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.character_name").value("몽이"));

        mockMvc.perform(get("/v1/progress").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.character_name").value("몽이"));

        // 기기를 바꿔 다시 로그인해도 아이가 지은 이름이 그대로 돌아와야 한다.
        String reissued = login("charminjun");
        mockMvc.perform(get("/v1/progress").header(HttpHeaders.AUTHORIZATION, "Bearer " + reissued))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.character_name").value("몽이"));
    }

    /** NULL 이 FE 의 이름 짓기 화면 신호다. 서버가 '모르미' 를 채워 넣으면 안 된다. */
    @Test
    void 아직_이름을_안_지었으면_비어_있다() throws Exception {
        String token = signup("지우", "CHAR-B01", "charjiwoo");

        mockMvc.perform(get("/v1/progress").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.character_name").doesNotExist());
    }

    @Test
    void 앞뒤_공백은_털어서_저장한다() throws Exception {
        String token = signup("서연", "CHAR-C01", "charseoyeon");

        nameCharacter(token, "  콩콩  ")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.character_name").value("콩콩"));
    }

    @Test
    void 공백뿐이거나_12자를_넘으면_저장하지_않는다() throws Exception {
        String token = signup("하준", "CHAR-D01", "charhajun");

        nameCharacter(token, "   ").andExpect(status().isBadRequest());
        nameCharacter(token, "가".repeat(13)).andExpect(status().isBadRequest());
        nameCharacter(token, null).andExpect(status().isBadRequest());

        mockMvc.perform(get("/v1/progress").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(jsonPath("$.character_name").doesNotExist());
    }

    @Test
    void 다시_부르면_덮어쓴다() throws Exception {
        String token = signup("수아", "CHAR-E01", "charsua");

        nameCharacter(token, "몽이").andExpect(status().isOk());
        nameCharacter(token, "별이")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.character_name").value("별이"));

        mockMvc.perform(get("/v1/progress").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(jsonPath("$.character_name").value("별이"));
    }

    @Test
    void 토큰_없이는_이름을_지을_수_없다() throws Exception {
        mockMvc.perform(patch("/v1/learners/me/character-name")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("character_name", "몽이"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 한_아이가_지은_이름은_다른_아이에게_보이지_않는다() throws Exception {
        String doyun = signup("도윤", "CHAR-F01", "chardoyun");
        String yerin = signup("예린", "CHAR-F02", "charyerin");

        nameCharacter(doyun, "몽이").andExpect(status().isOk());

        mockMvc.perform(get("/v1/progress").header(HttpHeaders.AUTHORIZATION, "Bearer " + yerin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.character_name").doesNotExist());
    }
}
