package com.mormi.backend.organization;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.mormi.backend.report.ReportSnapshot;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

public final class CohortDtos {

    private CohortDtos() {
    }

    public record CreateCohortRequest(@NotBlank @Size(max = 80) String name) {
    }

    /** class_code 는 서버가 발급한다. 교사가 이 코드로 학급을 식별해 공유할 수 있다. */
    public record CohortResponse(
            Long id, String name, String classCode, Long organizationId, OffsetDateTime createdAt) {

        public static CohortResponse of(Cohort cohort) {
            return new CohortResponse(
                    cohort.getId(), cohort.getName(), cohort.getClassCode(),
                    cohort.getOrganizationId(), cohort.getCreatedAt());
        }
    }

    /** 참여 번호 사전 발급. 형식은 학생 가입의 research_code 와 같다. */
    public record IssueResearchCodesRequest(
            @NotEmpty @Size(max = 50)
            List<@NotBlank @Size(max = 40) @Pattern(regexp = "[A-Za-z0-9._-]+") String> codes) {
    }

    /** learnerId 가 채워져 있으면 이미 가입한 아이가 소급 재적된 것이다. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record IssuedResearchCodeResponse(String code, Long learnerId) {
    }

    public record CohortLearnerResponse(
            Long id, String displayName, String researchCode, OffsetDateTime enrolledAt) {
    }

    /** 학급 리포트. body 는 ReportSnapshotService 가 집계한 학습자 섹션 모음이다. */
    public record CohortReportResponse(
            Long snapshotId,
            Long cohortId,
            OffsetDateTime periodStart,
            OffsetDateTime periodEnd,
            Map<String, Object> body,
            String aggregationRuleVersion,
            OffsetDateTime createdAt) {

        public static CohortReportResponse of(ReportSnapshot snapshot) {
            return new CohortReportResponse(
                    snapshot.getId(),
                    snapshot.getCohortId(),
                    snapshot.getPeriodStart(),
                    snapshot.getPeriodEnd(),
                    snapshot.getBody(),
                    snapshot.getAggregationRuleVersion(),
                    snapshot.getCreatedAt());
        }
    }
}
