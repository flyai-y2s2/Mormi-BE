package com.mormi.backend.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mormi.backend.organization.Cohort;
import com.mormi.backend.organization.CohortRepository;
import com.mormi.backend.organization.LearnerEnrollment;
import com.mormi.backend.organization.LearnerEnrollmentRepository;
import com.mormi.backend.organization.Organization;
import com.mormi.backend.organization.OrganizationRepository;
import com.mormi.backend.outcome.TaskOutcomeService;
import java.time.OffsetDateTime;
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
 * 리포트 스냅샷이 지켜야 하는 것들을 실제 PostgreSQL 로 검증한다.
 * 1) LLM 없이도 근거 ID 를 갖춘 구조화 스냅샷이 생성된다
 * 2) 교사 수정·승인이 상태로 남고 본문·근거는 불변이다
 * 3) 학급 스냅샷이 재적 아이들을 모두 담는다
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class ReportSnapshotIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    ReportSnapshotService snapshotService;

    @Autowired
    ReportSnapshotRepository snapshotRepository;

    @Autowired
    OrganizationRepository organizationRepository;

    @Autowired
    CohortRepository cohortRepository;

    @Autowired
    LearnerEnrollmentRepository enrollmentRepository;

    @Test
    void LLM_없이도_근거를_갖춘_구조화_스냅샷이_생성된다() throws Exception {
        long learnerId = 완료된_학습("MORMI-SNAP-01");

        ReportSnapshot snapshot = snapshotService.snapshotForLearner(
                learnerId, OffsetDateTime.now().minusDays(1), OffsetDateTime.now().plusDays(1));

        assertThat(snapshot.getLlmModel()).isNull();
        assertThat(snapshot.getGeneratedText()).isNull();
        assertThat(snapshot.getAggregationRuleVersion()).isEqualTo(TaskOutcomeService.RULE_VERSION);
        assertThat(snapshot.getApprovalStatus()).isEqualTo(ReportSnapshot.STATUS_DRAFT);
        assertThat(snapshot.getSourceAttemptIds()).isNotEmpty();
        assertThat(snapshot.getSourceOutcomeIds()).isNotEmpty();

        Map<String, Object> body = snapshot.getBody();
        assertThat(body.get("disclaimer")).isEqualTo("단일 세션의 수행 관찰이며 진단이 아님");
        @SuppressWarnings("unchecked")
        Map<String, Object> learner = (Map<String, Object>) body.get("learner");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tasks = (List<Map<String, Object>>) learner.get("tasks");
        assertThat(tasks).isNotEmpty();
        assertThat(tasks.get(0).get("task_key")).isNotNull();
        assertThat((List<?>) tasks.get(0).get("evidence_attempt_ids")).isNotEmpty();
    }

    @Test
    void 교사_수정과_승인이_상태로_남는다() throws Exception {
        long learnerId = 완료된_학습("MORMI-SNAP-02");
        ReportSnapshot snapshot = snapshotService.snapshotForLearner(
                learnerId, OffsetDateTime.now().minusDays(1), OffsetDateTime.now().plusDays(1));

        snapshot.editByTeacher("받아올림에서 도움을 받으면 잘 해결했어요.");
        assertThat(snapshot.getApprovalStatus()).isEqualTo(ReportSnapshot.STATUS_EDITED);

        snapshot.approve();
        snapshotRepository.save(snapshot);

        ReportSnapshot reloaded = snapshotRepository.findById(snapshot.getId()).orElseThrow();
        assertThat(reloaded.getApprovalStatus()).isEqualTo(ReportSnapshot.STATUS_APPROVED);
        assertThat(reloaded.getTeacherEditedText()).contains("받아올림");
        // 본문과 근거는 그대로다.
        assertThat(reloaded.getBody().get("disclaimer")).isNotNull();
        assertThat(reloaded.getSourceOutcomeIds()).isNotEmpty();
    }

    @Test
    void 학급_스냅샷이_재적_아이들을_모두_담는다() throws Exception {
        long first = 완료된_학습("MORMI-SNAP-03");
        long second = 완료된_학습("MORMI-SNAP-04");

        Organization org = organizationRepository.save(Organization.of("스냅샷 초등학교"));
        Cohort cohort = cohortRepository.save(Cohort.of(org.getId(), "1반", "CLASS-SNAP-1"));
        enrollmentRepository.save(LearnerEnrollment.of(first, cohort.getId()));
        enrollmentRepository.save(LearnerEnrollment.of(second, cohort.getId()));

        ReportSnapshot snapshot = snapshotService.snapshotForCohort(
                cohort.getId(), OffsetDateTime.now().minusDays(1), OffsetDateTime.now().plusDays(1));

        assertThat(snapshot.getCohortId()).isEqualTo(cohort.getId());
        assertThat(snapshot.getLearnerId()).isNull();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> learners =
                (List<Map<String, Object>>) snapshot.getBody().get("learners");
        assertThat(learners).hasSize(2);
    }

    /** 시도 2건(오답→정답)을 남기고 완료까지 마친 학습자를 만든다. */
    private long 완료된_학습(String researchCode) throws Exception {
        JsonNode learner = AuthTestSupport.signupLearner(mockMvc, objectMapper, "스냅", researchCode);
        String token = learner.get("access_token").asString();

        String sessionBody = mockMvc.perform(post("/v1/learning-sessions")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("curriculum_session_id", "money-count", "variant_seed", 0))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String publicId = objectMapper.readTree(sessionBody).get("learning_session_id").asString();

        for (int attemptNo = 1; attemptNo <= 2; attemptNo++) {
            mockMvc.perform(post("/v1/learning-sessions/" + publicId + "/attempts")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "activity", "drill",
                                    "attempt_no", attemptNo,
                                    "item_id", "money-count:1",
                                    "question_index", 0,
                                    "is_correct", attemptNo == 2,
                                    "elapsed_ms", 1000))))
                    .andExpect(status().isCreated());
        }
        mockMvc.perform(post("/v1/learning-sessions/" + publicId + "/complete")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "transfer_solved", false,
                                "timed_out", false,
                                "scaffold_level", 1,
                                "elapsed_seconds", 60))))
                .andExpect(status().isOk());
        return learner.get("id").asLong();
    }
}
