package com.mormi.backend.session;

import java.time.OffsetDateTime;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LearningSessionRepository extends JpaRepository<LearningSession, Long> {

    Optional<LearningSession> findByPublicId(String publicId);

    /** 같은 세션의 집계를 동시에 다시 계산하지 못하게 세션 행을 잠근다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM LearningSession s WHERE s.id = :id")
    Optional<LearningSession> findByIdForUpdate(@Param("id") Long id);

    /** 완료된 커리큘럼 세션 id 목록. 카페 해금 판정의 근거다. */
    @Query("""
            SELECT DISTINCT s.curriculumSessionId FROM LearningSession s
            WHERE s.learnerId = :learnerId AND s.completedAt IS NOT NULL
            """)
    List<String> findCompletedCurriculumSessionIds(@Param("learnerId") Long learnerId);

    Optional<LearningSession> findFirstByLearnerIdAndCompletedAtIsNullOrderByIdDesc(Long learnerId);

    List<LearningSession> findByLearnerIdAndCompletedAtIsNotNullOrderByCompletedAtDesc(Long learnerId);

    List<LearningSession> findByLearnerIdAndCompletedAtGreaterThanEqualAndCompletedAtLessThanOrderByCompletedAtAsc(
            Long learnerId, OffsetDateTime startInclusive, OffsetDateTime endExclusive);

    Optional<LearningSession> findFirstByLearnerIdAndCompletedAtIsNotNullOrderByCompletedAtDesc(Long learnerId);

    List<LearningSession> findTop2ByLearnerIdAndCurriculumSessionIdAndCompletedAtIsNotNullOrderByCompletedAtDescIdDesc(
            Long learnerId, String curriculumSessionId);
}
