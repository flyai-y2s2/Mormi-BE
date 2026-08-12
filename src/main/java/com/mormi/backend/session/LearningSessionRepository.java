package com.mormi.backend.session;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LearningSessionRepository extends JpaRepository<LearningSession, Long> {

    Optional<LearningSession> findByPublicId(String publicId);

    /** 완료된 커리큘럼 세션 id 목록. 카페 해금 판정의 근거다. */
    @Query("""
            SELECT DISTINCT s.curriculumSessionId FROM LearningSession s
            WHERE s.learnerId = :learnerId AND s.completedAt IS NOT NULL
            """)
    List<String> findCompletedCurriculumSessionIds(@Param("learnerId") Long learnerId);

    Optional<LearningSession> findFirstByLearnerIdAndCompletedAtIsNullOrderByIdDesc(Long learnerId);

    List<LearningSession> findByLearnerIdAndCompletedAtIsNotNullOrderByCompletedAtDesc(Long learnerId);

    Optional<LearningSession> findFirstByLearnerIdAndCompletedAtIsNotNullOrderByCompletedAtDesc(Long learnerId);
}
