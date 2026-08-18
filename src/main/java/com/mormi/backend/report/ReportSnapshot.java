package com.mormi.backend.report;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 리포트 1회 생성분. 만들어진 뒤에는 근거·본문을 바꾸지 않는다.
 *
 * <p>집계가 나중에 다시 계산돼도 이 스냅샷은 생성 당시의 근거 ID 를 그대로 갖는다.
 * 교사 수정 문장과 승인 상태만 갱신할 수 있다.
 */
@Entity
@Table(name = "report_snapshots")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReportSnapshot {

    public static final String STATUS_DRAFT = "draft";
    public static final String STATUS_EDITED = "edited";
    public static final String STATUS_APPROVED = "approved";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "learner_id", updatable = false)
    private Long learnerId;

    @Column(name = "cohort_id", updatable = false)
    private Long cohortId;

    @Column(name = "period_start", nullable = false, updatable = false)
    private OffsetDateTime periodStart;

    @Column(name = "period_end", nullable = false, updatable = false)
    private OffsetDateTime periodEnd;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "body", nullable = false, columnDefinition = "jsonb", updatable = false)
    private Map<String, Object> body = new LinkedHashMap<>();

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "source_observation_ids", nullable = false, columnDefinition = "bigint[]", updatable = false)
    private Long[] sourceObservationIds = new Long[0];

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "source_attempt_ids", nullable = false, columnDefinition = "bigint[]", updatable = false)
    private Long[] sourceAttemptIds = new Long[0];

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "source_outcome_ids", nullable = false, columnDefinition = "bigint[]", updatable = false)
    private Long[] sourceOutcomeIds = new Long[0];

    @Column(name = "aggregation_rule_version", nullable = false, length = 20, updatable = false)
    private String aggregationRuleVersion;

    /** NULL 이면 LLM 없이 만든 스냅샷이다. */
    @Column(name = "llm_model", length = 60, updatable = false)
    private String llmModel;

    @Column(name = "llm_prompt_version", length = 40, updatable = false)
    private String llmPromptVersion;

    @Column(name = "generated_text", updatable = false)
    private String generatedText;

    @Column(name = "teacher_edited_text")
    private String teacherEditedText;

    /** draft | edited | approved */
    @Column(name = "approval_status", nullable = false, length = 20)
    private String approvalStatus = STATUS_DRAFT;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private OffsetDateTime createdAt;

    private ReportSnapshot(
            Long learnerId,
            Long cohortId,
            OffsetDateTime periodStart,
            OffsetDateTime periodEnd,
            Map<String, Object> body,
            List<Long> sourceObservationIds,
            List<Long> sourceAttemptIds,
            List<Long> sourceOutcomeIds,
            String aggregationRuleVersion) {
        this.learnerId = learnerId;
        this.cohortId = cohortId;
        this.periodStart = periodStart;
        this.periodEnd = periodEnd;
        this.body = new LinkedHashMap<>(body);
        this.sourceObservationIds = sourceObservationIds.toArray(new Long[0]);
        this.sourceAttemptIds = sourceAttemptIds.toArray(new Long[0]);
        this.sourceOutcomeIds = sourceOutcomeIds.toArray(new Long[0]);
        this.aggregationRuleVersion = aggregationRuleVersion;
    }

    public static ReportSnapshot forLearner(
            Long learnerId,
            OffsetDateTime periodStart,
            OffsetDateTime periodEnd,
            Map<String, Object> body,
            List<Long> sourceObservationIds,
            List<Long> sourceAttemptIds,
            List<Long> sourceOutcomeIds,
            String aggregationRuleVersion) {
        return new ReportSnapshot(learnerId, null, periodStart, periodEnd, body,
                sourceObservationIds, sourceAttemptIds, sourceOutcomeIds, aggregationRuleVersion);
    }

    public static ReportSnapshot forCohort(
            Long cohortId,
            OffsetDateTime periodStart,
            OffsetDateTime periodEnd,
            Map<String, Object> body,
            List<Long> sourceObservationIds,
            List<Long> sourceAttemptIds,
            List<Long> sourceOutcomeIds,
            String aggregationRuleVersion) {
        return new ReportSnapshot(null, cohortId, periodStart, periodEnd, body,
                sourceObservationIds, sourceAttemptIds, sourceOutcomeIds, aggregationRuleVersion);
    }

    public void editByTeacher(String editedText) {
        this.teacherEditedText = editedText;
        if (STATUS_DRAFT.equals(this.approvalStatus)) {
            this.approvalStatus = STATUS_EDITED;
        }
    }

    public void approve() {
        this.approvalStatus = STATUS_APPROVED;
    }
}
