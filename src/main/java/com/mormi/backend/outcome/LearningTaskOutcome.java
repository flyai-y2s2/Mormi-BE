package com.mormi.backend.outcome;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.List;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 문제 1개에 대한 수행 결과 집계.
 *
 * <p>원본은 attempts 와 learning_observations 이고 이 행은 파생값이다.
 * 관찰 이벤트가 늦게 도착할 수 있으므로 언제든 다시 계산해 덮어쓸 수 있어야 한다.
 */
@Entity
@Table(name = "learning_task_outcomes")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LearningTaskOutcome {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "learner_id", nullable = false, updatable = false)
    private Long learnerId;

    @Column(name = "learning_session_id", nullable = false, updatable = false)
    private Long learningSessionId;

    @Column(name = "task_key", nullable = false, length = 120, updatable = false)
    private String taskKey;

    @Column(name = "activity", length = 20)
    private String activity;

    @Column(name = "attempt_count")
    private Integer attemptCount;

    @Column(name = "wrong_attempt_count")
    private Integer wrongAttemptCount;

    @Column(name = "first_try_success")
    private Boolean firstTrySuccess;

    @Column(name = "retry_success")
    private Boolean retrySuccess;

    @Column(name = "success_after_help")
    private Boolean successAfterHelp;

    @Column(name = "expression_start", length = 4)
    private String expressionStart;

    @Column(name = "expression_end", length = 4)
    private String expressionEnd;

    /** 가장 많이 내려간 발화 단계. 지원이 최대로 필요했던 순간이다. */
    @Column(name = "expression_lowest", length = 4)
    private String expressionLowest;

    @Column(name = "hint_start", length = 4)
    private String hintStart;

    @Column(name = "hint_end", length = 4)
    private String hintEnd;

    @Column(name = "hint_max", length = 4)
    private String hintMax;

    @Column(name = "completion_outcome", length = 30)
    private String completionOutcome;

    @Column(name = "system_failure")
    private Boolean systemFailure;

    @Column(name = "bottleneck_candidate", length = 60)
    private String bottleneckCandidate;

    /** 1이면 한 번만 관찰된 것이다. 반복 관찰과 구분해 오개념으로 단정하지 않는다. */
    @Column(name = "bottleneck_evidence_count")
    private Integer bottleneckEvidenceCount;

    /** 별노트는 Mormi-AI 가 보유한다. 집계에서 파생하지 않으므로 재계산이 건드리지 않는다. */
    @Column(name = "star_note_id", length = 100)
    private String starNoteId;

    @Column(name = "star_note_attribution", length = 20)
    private String starNoteAttribution;

    @Column(name = "star_note_evidence", length = 40)
    private String starNoteEvidence;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "source_attempt_ids", nullable = false, columnDefinition = "bigint[]")
    private Long[] sourceAttemptIds = new Long[0];

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "source_observation_ids", nullable = false, columnDefinition = "bigint[]")
    private Long[] sourceObservationIds = new Long[0];

    @Column(name = "aggregation_rule_version", nullable = false, length = 20)
    private String aggregationRuleVersion;

    @Column(name = "computed_at", nullable = false)
    private OffsetDateTime computedAt;

    private LearningTaskOutcome(Long learnerId, Long learningSessionId, String taskKey) {
        this.learnerId = learnerId;
        this.learningSessionId = learningSessionId;
        this.taskKey = taskKey;
    }

    public static LearningTaskOutcome of(Long learnerId, Long learningSessionId, String taskKey) {
        return new LearningTaskOutcome(learnerId, learningSessionId, taskKey);
    }

    /** 파생값 전체를 다시 쓴다. 별노트처럼 파생이 아닌 값은 그대로 둔다. */
    public void apply(TaskOutcomeFields fields, String ruleVersion) {
        this.activity = fields.activity();
        this.attemptCount = fields.attemptCount();
        this.wrongAttemptCount = fields.wrongAttemptCount();
        this.firstTrySuccess = fields.firstTrySuccess();
        this.retrySuccess = fields.retrySuccess();
        this.successAfterHelp = fields.successAfterHelp();
        this.expressionStart = fields.expressionStart();
        this.expressionEnd = fields.expressionEnd();
        this.expressionLowest = fields.expressionLowest();
        this.hintStart = fields.hintStart();
        this.hintEnd = fields.hintEnd();
        this.hintMax = fields.hintMax();
        this.completionOutcome = fields.completionOutcome();
        this.systemFailure = fields.systemFailure();
        this.bottleneckCandidate = fields.bottleneckCandidate();
        this.bottleneckEvidenceCount = fields.bottleneckEvidenceCount();
        this.sourceAttemptIds = toArray(fields.sourceAttemptIds());
        this.sourceObservationIds = toArray(fields.sourceObservationIds());
        this.aggregationRuleVersion = ruleVersion;
        this.computedAt = OffsetDateTime.now();
    }

    private Long[] toArray(List<Long> values) {
        return values == null ? new Long[0] : values.toArray(new Long[0]);
    }
}
