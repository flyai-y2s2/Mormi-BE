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

    @Column(name = "park_visit_id", updatable = false)
    private Long parkVisitId;

    @Column(name = "scenario_id", nullable = false, length = 100, updatable = false)
    private String scenarioId;

    /**
     * 같은 (방문, 시나리오)를 다시 연습한 회차. 1부터 시작한다.
     * 회차마다 대화가 따로 생겨야 이미 끝낸 스테이지를 새 문제로 다시 풀 수 있다.
     */
    @Column(name = "round", nullable = false, updatable = false)
    private int round = 1;

    /** 새로고침 뒤에도 화면 문제와 AI가 기억하는 문제를 같게 복구하는 구조 데이터. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "scenario_context", nullable = false, columnDefinition = "jsonb", updatable = false)
    private Map<String, Object> scenarioContext = new LinkedHashMap<>();

    /**
     * FE가 요청마다 새로 뽑는 멱등키. 같은 요청이 네트워크 재시도로 중복 도착하면
     * (learner_id, request_id) 유니크 제약과 재조회로 같은 회차를 돌려준다.
     */
    @Column(name = "request_id", length = 100, updatable = false)
    private String requestId;

    /** 이 회차가 스테이지를 통과시킨 시각. 같은 회차를 다시 읽을 때 재검증을 건너뛰는 기준. */
    @Column(name = "cleared_at")
    private OffsetDateTime clearedAt;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private OffsetDateTime createdAt;

    private DialogueConversation(
            String conversationId,
            Long learnerId,
            Long learningSessionId,
            Long cafeVisitId,
            Long parkVisitId,
            String scenarioId,
            int round,
            Map<String, Object> scenarioContext,
            String requestId) {
        this.conversationId = conversationId;
        this.learnerId = learnerId;
        this.learningSessionId = learningSessionId;
        this.cafeVisitId = cafeVisitId;
        this.parkVisitId = parkVisitId;
        this.scenarioId = scenarioId;
        this.round = round;
        this.scenarioContext = scenarioContext == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(scenarioContext);
        this.requestId = requestId;
    }

    public static DialogueConversation forLearningSession(
            String conversationId, Long learnerId, Long learningSessionId) {
        return forLearningSession(conversationId, learnerId, learningSessionId, 1, null);
    }

    public static DialogueConversation forLearningSession(
            String conversationId, Long learnerId, Long learningSessionId,
            int round, String requestId) {
        return new DialogueConversation(
                conversationId, learnerId, learningSessionId, null, null,
                "home_teach", round, Map.of(), requestId);
    }

    public static DialogueConversation forCafeVisit(
            String conversationId,
            Long learnerId,
            Long cafeVisitId,
            String scenarioId,
            int round,
            Map<String, Object> scenarioContext) {
        return forCafeVisit(
                conversationId, learnerId, cafeVisitId, scenarioId, round, scenarioContext, null);
    }

    public static DialogueConversation forCafeVisit(
            String conversationId,
            Long learnerId,
            Long cafeVisitId,
            String scenarioId,
            int round,
            Map<String, Object> scenarioContext,
            String requestId) {
        return new DialogueConversation(
                conversationId, learnerId, null, cafeVisitId, null, scenarioId, round,
                scenarioContext, requestId);
    }

    /** 놀이동산 방문 회차. 문제 사실은 프런트가 아니라 방문 행에서 끌어와 저장한다. */
    public static DialogueConversation forParkVisit(
            String conversationId,
            Long learnerId,
            Long parkVisitId,
            String scenarioId,
            int round,
            Map<String, Object> scenarioContext,
            String requestId) {
        return new DialogueConversation(
                conversationId, learnerId, null, null, parkVisitId, scenarioId, round,
                scenarioContext, requestId);
    }

    /** 장소 대화인지. 홈 가르치기는 스테이지 진행도를 붙이지 않는다. */
    public boolean isVisitScoped() {
        return cafeVisitId != null || parkVisitId != null;
    }

    /** 이 회차가 스테이지를 통과시켰음을 한 번만 기록한다. */
    public void markCleared() {
        if (clearedAt == null) {
            clearedAt = OffsetDateTime.now();
        }
    }
}
