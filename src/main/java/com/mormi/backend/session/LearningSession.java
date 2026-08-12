package com.mormi.backend.session;

import com.mormi.backend.curriculum.CurriculumCatalog;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 집에서 진행하는 학습 세션 한 회. 커리큘럼 세션 id 는 문자열 그대로 쓴다
 * (배열 인덱스는 영역 순서에 따라 바뀌므로 식별자로 쓰지 않는다).
 */
@Entity
@Table(name = "learning_sessions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LearningSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_id", nullable = false, length = 60, updatable = false)
    private String publicId;

    @Column(name = "learner_id", nullable = false, updatable = false)
    private Long learnerId;

    @Column(name = "curriculum_session_id", nullable = false, length = 60, updatable = false)
    private String curriculumSessionId;

    /**
     * 프런트가 varyProblem() 으로 문제를 런타임 생성하므로,
     * 이 seed 가 없으면 아이가 실제로 본 문제를 나중에 재구성할 수 없다.
     */
    @Column(name = "variant_seed", nullable = false, updatable = false)
    private int variantSeed;

    @Column(name = "scaffold_level")
    @Setter
    private Integer scaffoldLevel;

    @Column(name = "elapsed_seconds")
    @Setter
    private Integer elapsedSeconds;

    @Column(name = "transfer_solved", nullable = false)
    @Setter
    private boolean transferSolved;

    @Column(name = "timed_out", nullable = false)
    @Setter
    private boolean timedOut;

    @Column(name = "conversation_id", length = 100)
    @Setter
    private String conversationId;

    @Column(name = "practice_result_id", length = 100)
    @Setter
    private String practiceResultId;

    @Column(name = "started_at", nullable = false, updatable = false, insertable = false)
    private OffsetDateTime startedAt;

    @Column(name = "completed_at")
    @Setter
    private OffsetDateTime completedAt;

    private LearningSession(Long learnerId, String curriculumSessionId, int variantSeed) {
        this.publicId = "session_" + UUID.randomUUID().toString().replace("-", "");
        this.learnerId = learnerId;
        this.curriculumSessionId = curriculumSessionId;
        this.variantSeed = variantSeed;
    }

    public static LearningSession start(Long learnerId, String curriculumSessionId, int variantSeed) {
        return new LearningSession(learnerId, curriculumSessionId, variantSeed);
    }

    public boolean isCompleted() {
        return completedAt != null;
    }

    /** 서버 기준 경과 시간. 클라이언트가 보낸 값을 그대로 믿지 않기 위한 상한 계산에 쓴다. */
    public int serverElapsedSeconds() {
        OffsetDateTime from = startedAt != null ? startedAt : OffsetDateTime.now();
        return (int) Duration.between(from, OffsetDateTime.now()).toSeconds();
    }

    public boolean exceededTimeLimit() {
        return serverElapsedSeconds() >= CurriculumCatalog.SESSION_TIME_LIMIT_SECONDS;
    }
}
