package com.mormi.backend.dialogue;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Spring BE가 인증한 학습자와 AI 대화의 소유권 연결. */
@Entity
@Table(name = "dialogue_conversations")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DialogueConversation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "conversation_id", nullable = false, length = 100, updatable = false)
    private String conversationId;

    @Column(name = "learner_id", nullable = false, updatable = false)
    private Long learnerId;

    @Column(name = "learning_session_id", updatable = false)
    private Long learningSessionId;

    @Column(name = "cafe_visit_id", updatable = false)
    private Long cafeVisitId;

    @Column(name = "scenario_id", nullable = false, length = 100, updatable = false)
    private String scenarioId;

    /** 새로고침 뒤에도 화면 문제와 AI가 기억하는 문제를 같게 복구하는 구조 데이터. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "scenario_context", nullable = false, columnDefinition = "jsonb", updatable = false)
    private Map<String, Object> scenarioContext = new LinkedHashMap<>();

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private OffsetDateTime createdAt;

    private DialogueConversation(
            String conversationId,
            Long learnerId,
            Long learningSessionId,
            Long cafeVisitId,
            String scenarioId,
            Map<String, Object> scenarioContext) {
        this.conversationId = conversationId;
        this.learnerId = learnerId;
        this.learningSessionId = learningSessionId;
        this.cafeVisitId = cafeVisitId;
        this.scenarioId = scenarioId;
        this.scenarioContext = scenarioContext == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(scenarioContext);
    }

    public static DialogueConversation forLearningSession(
            String conversationId, Long learnerId, Long learningSessionId) {
        return new DialogueConversation(
                conversationId, learnerId, learningSessionId, null, "home_teach", Map.of());
    }

    public static DialogueConversation forCafeVisit(
            String conversationId,
            Long learnerId,
            Long cafeVisitId,
            String scenarioId,
            Map<String, Object> scenarioContext) {
        return new DialogueConversation(
                conversationId, learnerId, null, cafeVisitId, scenarioId, scenarioContext);
    }
}
