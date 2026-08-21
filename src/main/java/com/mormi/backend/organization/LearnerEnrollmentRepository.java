package com.mormi.backend.organization;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LearnerEnrollmentRepository extends JpaRepository<LearnerEnrollment, Long> {

    List<LearnerEnrollment> findByCohortIdAndLeftAtIsNull(Long cohortId);

    List<LearnerEnrollment> findByLearnerIdOrderByIdAsc(Long learnerId);

    /** 소급 재적 시 중복 행을 막는다. 나갔다 돌아온 아이는 새 행으로 재적된다. */
    boolean existsByLearnerIdAndCohortIdAndLeftAtIsNull(Long learnerId, Long cohortId);

    /** 학급 단위 파일럿 조회의 기본 질의. 나간 아이는 제외한다. */
    @Query("""
            SELECT e.learnerId FROM LearnerEnrollment e
            WHERE e.cohortId = :cohortId AND e.leftAt IS NULL
            """)
    List<Long> findActiveLearnerIds(@Param("cohortId") Long cohortId);
}
