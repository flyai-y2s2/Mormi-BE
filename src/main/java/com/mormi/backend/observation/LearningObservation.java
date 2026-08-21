package com.mormi.backend.observation;

import com.mormi.backend.dialogue.DialogueConversation;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 리포트용 구조화 관찰 1건. AI 관찰 하나당 한 행을 유지한다.
 *
 * <p>같은 관찰의 이벤트가 여러 번 도착해도 행은 늘지 않고 최신 관측만 반영한다.
 * 어느 이벤트에서 왔는지는 observationEventId 로, AI 원본은 aiObservationId 로 추적한다.
 * 아이 원문 발화는 저장하지 않으며, 인용이 필요하면 AI 조회 경로를 따로 쓴다.
 */
@Entity
@Table(name = "learning_observations")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LearningObservation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "observation_event_id", nullable = false)
    private Long observationEventId;

    @Column(name = "ai_observation_id", nullable = false, length = 100, updatable = false)
    private String aiObservationId;

    @Column(name = "ai_observation_version", length = 20)
    private String aiObservationVersion;

    /** 소유권은 이벤트가 보낸 값이 아니라 대화 기록에서 끌어온다. */
    @Column(name = "learner_id", nullable = false, updatable = false)
    private Long learnerId;

    @Column(name = "learning_session_id", updatable = false)
    private Long learningSessionId;

    @Column(name = "cafe_visit_id", updatable = false)
    private Long cafeVisitId;

    @Column(name = "conversation_id", length = 100, updatable = false)
    private String conversationId;

    @Column(name = "scenario_id", length = 100)
    private String scenarioId;

    @Column(name = "task_id", length = 120)
    private String taskId;

    @Column(name = "stage", length = 40)
    private String stage;

    @Column(name = "turn_index")
    private Integer turnIndex;

    @Column(name = "response_category", length = 40)
    private String responseCategory;

    @Column(name = "difficulty_class", length = 40)
    private String difficultyClass;

    @Column(name = "concept_result", length = 40)
    private String conceptResult;

    /** 후보일 뿐이다. 한 번 관찰된 값을 확정 오개념으로 쓰지 않는다. */
    @Column(name = "bottleneck_candidate", length = 60)
    private String bottleneckCandidate;

    /** 발화사다리 원본. 신규 값은 L4/L3/L2/L0이며 과거 L1도 원본 그대로 보존한다. */
    @Column(name = "expression_before", length = 4)
    private String expressionBefore;

    @Column(name = "expression_after", length = 4)
    private String expressionAfter;

    /** 힌트사다리 H0~H3. */
    @Column(name = "hint_before", length = 4)
    private String hintBefore;

    @Column(name = "hint_after", length = 4)
    private String hintAfter;

    @Column(name = "transition_reason", length = 60)
    private String transitionReason;

    @Column(name = "help_used")
    private Boolean helpUsed;

    @Column(name = "fallback_occurred")
    private Boolean fallbackOccurred;

    @Column(name = "system_error")
    private Boolean systemError;

    @Column(name = "completion_outcome", length = 30)
    private String completionOutcome;

    @Column(name = "classifier_confidence", precision = 4, scale = 3)
    private BigDecimal classifierConfidence;

    @Column(name = "evidence_strength", length = 20)
    private String evidenceStrength;

    @Column(name = "observed_at")
    private OffsetDateTime observedAt;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private OffsetDateTime createdAt;

    private LearningObservation(
            Long observationEventId,
            String aiObservationId,
            DialogueConversation conversation,
            ObservationFields fields) {
        this.observationEventId = observationEventId;
        this.aiObservationId = aiObservationId;
        this.learnerId = conversation.getLearnerId();
        this.learningSessionId = conversation.getLearningSessionId();
        this.cafeVisitId = conversation.getCafeVisitId();
        this.conversationId = conversation.getConversationId();
        apply(observationEventId, fields);
    }

    public static LearningObservation from(
            Long observationEventId,
            String aiObservationId,
            DialogueConversation conversation,
            ObservationFields fields) {
        return new LearningObservation(observationEventId, aiObservationId, conversation, fields);
    }

    /** 더 최신 관측이 도착했을 때만 호출한다. 순서 역전 판단은 서비스가 한다. */
    public void apply(Long observationEventId, ObservationFields fields) {
        this.observationEventId = observationEventId;
        this.aiObservationVersion = fields.aiObservationVersion();
        this.scenarioId = fields.scenarioId();
        this.taskId = fields.taskId();
        this.stage = fields.stage();
        this.turnIndex = fields.turnIndex();
        this.responseCategory = fields.responseCategory();
        this.difficultyClass = fields.difficultyClass();
        this.conceptResult = fields.conceptResult();
        this.bottleneckCandidate = fields.bottleneckCandidate();
        this.expressionBefore = fields.expressionBefore();
        this.expressionAfter = fields.expressionAfter();
        this.hintBefore = fields.hintBefore();
        this.hintAfter = fields.hintAfter();
        this.transitionReason = fields.transitionReason();
        this.helpUsed = fields.helpUsed();
        this.fallbackOccurred = fields.fallbackOccurred();
        this.systemError = fields.systemError();
        this.completionOutcome = fields.completionOutcome();
        this.classifierConfidence = fields.classifierConfidence();
        this.evidenceStrength = fields.evidenceStrength();
        this.observedAt = fields.observedAt();
    }
}
