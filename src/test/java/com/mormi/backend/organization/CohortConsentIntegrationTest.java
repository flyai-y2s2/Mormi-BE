package com.mormi.backend.organization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mormi.backend.learner.ConsentRecord;
import com.mormi.backend.learner.ConsentRecordRepository;
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
import com.mormi.backend.AuthTestSupport;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 학급 묶음 조회와 동의 이력이 지켜야 하는 것들을 실제 PostgreSQL 로 검증한다.
 * 1) 학급 단위로 아이들을 묶어 조회할 수 있다
 * 2) 학습자를 만들면 동의 기준선 이력이 생긴다
 * 3) 동의 철회가 이전 행을 지우지 않고 withdrawn_at 으로 남는다
 * 4) 같은 상태 재전송은 장부를 늘리지 않는다
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class CohortConsentIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    OrganizationRepository organizationRepository;

    @Autowired
    CohortRepository cohortRepository;

    @Autowired
    LearnerEnrollmentRepository enrollmentRepository;

    @Autowired
    ConsentRecordRepository consentRecordRepository;

    @Test
    void 학급_단위로_아이들을_묶어_조회할_수_있다() throws Exception {
        Organization org = organizationRepository.save(Organization.of("모르미 초등학교"));
        Cohort cohort = cohortRepository.save(Cohort.of(org.getId(), "2반 1차수", "CLASS-2-1"));

        long first = 학습자("MORMI-CO-01").get("id").asLong();
        long second = 학습자("MORMI-CO-02").get("id").asLong();
        long other = 학습자("MORMI-CO-03").get("id").asLong();

        enrollmentRepository.save(LearnerEnrollment.of(first, cohort.getId()));
        enrollmentRepository.save(LearnerEnrollment.of(second, cohort.getId()));
        LearnerEnrollment left = enrollmentRepository.save(LearnerEnrollment.of(other, cohort.getId()));
        left.leave();
        enrollmentRepository.save(left);

        List<Long> active = enrollmentRepository.findActiveLearnerIds(cohort.getId());
        assertThat(active).containsExactlyInAnyOrder(first, second);
        assertThat(cohortRepository.findByClassCode("CLASS-2-1")).isPresent();
    }

    @Test
    void 학습자를_만들면_동의_기준선_이력이_생긴다() throws Exception {
        long learnerId = 학습자("MORMI-CO-04").get("id").asLong();

        List<ConsentRecord> records = consentRecordRepository
                .findByLearnerIdAndScopeOrderByIdAsc(learnerId, ConsentRecord.SCOPE_CONVERSATION_STORAGE);
        assertThat(records).hasSize(1);
        assertThat(records.get(0).isGranted()).isTrue();
        assertThat(records.get(0).getPolicyVersion()).isNotBlank();
        assertThat(records.get(0).isActive()).isTrue();
    }

    @Test
    void 동의_철회가_이전_행을_지우지_않고_withdrawn_at_으로_남는다() throws Exception {
        JsonNode learner = 학습자("MORMI-CO-05");
        long learnerId = learner.get("id").asLong();

        mockMvc.perform(patch("/v1/learners/me/conversation-consent")
                        .header("Authorization", "Bearer " + learner.get("access_token").asString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "conversation_storage_consent", false,
                                "retention_policy", "no_raw"))))
                .andExpect(status().isOk());

        List<ConsentRecord> records = consentRecordRepository
                .findByLearnerIdAndScopeOrderByIdAsc(learnerId, ConsentRecord.SCOPE_CONVERSATION_STORAGE);
        assertThat(records).hasSize(2);
        // 기준선 행은 삭제되지 않고 철회 시각만 얻는다.
        assertThat(records.get(0).isGranted()).isTrue();
        assertThat(records.get(0).getWithdrawnAt()).isNotNull();
        // 새 행이 현재 상태를 말한다.
        assertThat(records.get(1).isGranted()).isFalse();
        assertThat(records.get(1).isActive()).isTrue();
    }

    @Test
    void 같은_상태_재전송은_장부를_늘리지_않는다() throws Exception {
        JsonNode learner = 학습자("MORMI-CO-06");
        long learnerId = learner.get("id").asLong();

        mockMvc.perform(patch("/v1/learners/me/conversation-consent")
                        .header("Authorization", "Bearer " + learner.get("access_token").asString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "conversation_storage_consent", true,
                                "retention_policy", "permanent"))))
                .andExpect(status().isOk());

        List<ConsentRecord> records = consentRecordRepository
                .findByLearnerIdAndScopeOrderByIdAsc(learnerId, ConsentRecord.SCOPE_CONVERSATION_STORAGE);
        assertThat(records).hasSize(1);
    }

    private JsonNode 학습자(String researchCode) throws Exception {
        return AuthTestSupport.signupLearner(mockMvc, objectMapper, "학급", researchCode);
    }
}
