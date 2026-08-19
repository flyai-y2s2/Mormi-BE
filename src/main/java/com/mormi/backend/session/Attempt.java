package com.mormi.backend.session;

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
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 문제 시도 1건. 오답도 그대로 남긴다.
 *
 * <p>answer_meta 에는 선택한 보기 id, 그때까지의 오답 수, 오개념 태그 같은
 * 구조 데이터만 넣는다. 아이 이름, 자유 발화 원문, 음성은 넣지 않는다.
 */
@Entity
@Table(name = "attempts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Attempt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "learning_session_id", nullable = false, updatable = false)
    private Long learningSessionId;

    /** drill | teach | transfer */
    @Column(name = "activity", nullable = false, length = 20, updatable = false)
    private String activity;

    @Column(name = "attempt_no", nullable = false, updatable = false)
    private int attemptNo;

    /** 예: "money-count:2" — 커리큘럼 세션과 문제 번호. */
    @Column(name = "item_id", length = 120)
    private String itemId;

    @Column(name = "question_index")
    private Integer questionIndex;

    @Column(name = "is_correct", nullable = false)
    private boolean correct;

    @Column(name = "elapsed_ms")
    private Integer elapsedMs;

    /** 적용 문제의 맥락. same_form_new_number | new_representation | real_life_context */
    @Column(name = "application_scope", length = 30, updatable = false)
    private String applicationScope;

    /** FE 사다리 0~3. NULL 은 수집 안 됨이며 0(도움 없음)과 다르다. */
    @Column(name = "support_level", updatable = false)
    private Integer supportLevel;

    @Column(name = "reward_granted", nullable = false)
    @Setter
    private int rewardGranted;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "answer_meta", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> answerMeta = new LinkedHashMap<>();

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private OffsetDateTime createdAt;

    private Attempt(
            Long learningSessionId,
            String activity,
            int attemptNo,
            String itemId,
            Integer questionIndex,
            boolean correct,
            Integer elapsedMs,
            String applicationScope,
            Integer supportLevel,
            Map<String, Object> answerMeta) {
        this.learningSessionId = learningSessionId;
        this.activity = activity;
        this.attemptNo = attemptNo;
        this.itemId = itemId;
        this.questionIndex = questionIndex;
        this.correct = correct;
        this.elapsedMs = elapsedMs;
        this.applicationScope = applicationScope;
        this.supportLevel = supportLevel;
        this.answerMeta = answerMeta == null ? new LinkedHashMap<>() : new LinkedHashMap<>(answerMeta);
    }

    public static Attempt record(
            Long learningSessionId,
            String activity,
            int attemptNo,
            String itemId,
            Integer questionIndex,
            boolean correct,
            Integer elapsedMs,
            String applicationScope,
            Integer supportLevel,
            Map<String, Object> answerMeta) {
        return new Attempt(
                learningSessionId, activity, attemptNo, itemId, questionIndex, correct, elapsedMs,
                applicationScope, supportLevel, answerMeta);
    }
}
