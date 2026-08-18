package com.mormi.backend.organization;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 아이와 학급의 연결 1건. 직접 FK 대신 연결 테이블을 둬서
 * 차수 이동·반 이동이 삭제가 아니라 이력으로 남는다.
 */
@Entity
@Table(name = "learner_enrollments")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LearnerEnrollment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "learner_id", nullable = false, updatable = false)
    private Long learnerId;

    @Column(name = "cohort_id", nullable = false, updatable = false)
    private Long cohortId;

    @Column(name = "enrolled_at", nullable = false, updatable = false, insertable = false)
    private OffsetDateTime enrolledAt;

    @Column(name = "left_at")
    private OffsetDateTime leftAt;

    public static LearnerEnrollment of(Long learnerId, Long cohortId) {
        LearnerEnrollment enrollment = new LearnerEnrollment();
        enrollment.learnerId = learnerId;
        enrollment.cohortId = cohortId;
        return enrollment;
    }

    public void leave() {
        if (this.leftAt == null) {
            this.leftAt = OffsetDateTime.now();
        }
    }
}
