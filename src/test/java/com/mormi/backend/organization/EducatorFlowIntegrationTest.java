package com.mormi.backend.organization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mormi.backend.AuthTestSupport;
import java.util.List;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 교사 흐름에서 반드시 성립해야 하는 것들을 실제 PostgreSQL 로 검증한다.
 * 1) 통합 로그인이 역할을 구분해 응답한다
 * 2) 교사 토큰은 학습 경로에, 학생 토큰은 학급 경로에 통하지 않는다 (403)
 * 3) 참여 번호로 가입한 아이가 학급 명단·리포트에 나타난다
 * 4) 다른 기관의 학급은 조회할 수 없다
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class EducatorFlowIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    private JsonNode educatorSignup(String organization, String name, String loginId)
            throws Exception {
        String body = mockMvc.perform(post("/v1/auth/educators/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "organization_name", organization,
                                "display_name", name,
                                "position", "교사",
                                "login_id", loginId,
                                "password", AuthTestSupport.PASSWORD))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    private JsonNode createCohort(String token, String name) throws Exception {
        String body = mockMvc.perform(post("/v1/cohorts")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", name))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    private void issueCodes(String token, long cohortId, List<String> codes) throws Exception {
        mockMvc.perform(post("/v1/cohorts/" + cohortId + "/research-codes")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("codes", codes))))
                .andExpect(status().isCreated());
    }

    @Test
    void 통합_로그인이_교사와_학생의_역할을_구분해_응답한다() throws Exception {
        educatorSignup("모르미초등학교", "김교사", "teachera1");
        AuthTestSupport.signupLearner(mockMvc, objectMapper, "민준", "EDU-A01");

        mockMvc.perform(post("/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "login_id", "teachera1", "password", AuthTestSupport.PASSWORD))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("educator"))
                .andExpect(jsonPath("$.access_token").isNotEmpty())
                .andExpect(jsonPath("$.educator.organization_name").value("모르미초등학교"))
                .andExpect(jsonPath("$.learner").doesNotExist());

        mockMvc.perform(post("/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "login_id", AuthTestSupport.loginId("EDU-A01"),
                                "password", AuthTestSupport.PASSWORD))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("learner"))
                .andExpect(jsonPath("$.learner.display_name").value("민준"))
                .andExpect(jsonPath("$.educator").doesNotExist());
    }

    @Test
    void 교사_토큰으로_학습_경로를_부르면_403_이다() throws Exception {
        String token = educatorSignup("모르미초등학교", "박교사", "teacherb1")
                .get("access_token").asString();

        mockMvc.perform(get("/v1/progress").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void 학생_토큰으로_학급_경로를_부르면_403_이다() throws Exception {
        String token = AuthTestSupport.signupLearner(mockMvc, objectMapper, "지우", "EDU-B01")
                .get("access_token").asString();

        mockMvc.perform(get("/v1/cohorts").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void 참여_번호로_가입한_아이가_학급_명단과_리포트에_나타난다() throws Exception {
        String token = educatorSignup("모르미초등학교", "이교사", "teacherc1")
                .get("access_token").asString();
        JsonNode cohort = createCohort(token, "1반");
        long cohortId = cohort.get("id").asLong();
        assertThat(cohort.get("class_code").asString()).isNotEmpty();

        issueCodes(token, cohortId, List.of("EDU-C01"));
        AuthTestSupport.signupLearner(mockMvc, objectMapper, "서연", "EDU-C01");

        mockMvc.perform(get("/v1/cohorts/" + cohortId + "/learners")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].research_code").value("EDU-C01"))
                .andExpect(jsonPath("$[0].display_name").value("서연"));

        mockMvc.perform(get("/v1/cohorts/" + cohortId + "/reports")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cohort_id").value(cohortId))
                .andExpect(jsonPath("$.body.learners.length()").value(1));
    }

    @Test
    void 발급_전에_가입한_아이도_참여_번호를_발급하면_소급_재적된다() throws Exception {
        long learnerId = AuthTestSupport.signupLearner(mockMvc, objectMapper, "하준", "EDU-D01")
                .get("id").asLong();
        String token = educatorSignup("모르미초등학교", "최교사", "teacherd1")
                .get("access_token").asString();
        long cohortId = createCohort(token, "2반").get("id").asLong();

        mockMvc.perform(post("/v1/cohorts/" + cohortId + "/research-codes")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("codes", List.of("EDU-D01")))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$[0].learner_id").value(learnerId));

        mockMvc.perform(get("/v1/cohorts/" + cohortId + "/learners")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(learnerId));
    }

    @Test
    void 다른_기관의_교사는_남의_학급을_보지_못한다() throws Exception {
        String owner = educatorSignup("모르미초등학교", "정교사", "teachere1")
                .get("access_token").asString();
        long cohortId = createCohort(owner, "3반").get("id").asLong();

        String outsider = educatorSignup("다른센터", "강연구자", "teachere2")
                .get("access_token").asString();

        mockMvc.perform(get("/v1/cohorts/" + cohortId + "/learners")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + outsider))
                .andExpect(status().isForbidden());
    }
}
