package com.mormi.backend.session;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AttemptRepository extends JpaRepository<Attempt, Long> {

    Optional<Attempt> findByLearningSessionIdAndActivityAndAttemptNo(
            Long learningSessionId, String activity, int attemptNo);

    List<Attempt> findByLearningSessionIdOrderByIdAsc(Long learningSessionId);

    int countByLearningSessionIdAndActivity(Long learningSessionId, String activity);

    /** 해당 문제에서 이 시도 이전까지 쌓인 오답 수. 보상 등급을 서버가 계산할 때 쓴다. */
    @Query("""
            SELECT COUNT(a) FROM Attempt a
            WHERE a.learningSessionId = :sessionId
              AND a.activity = :activity
              AND a.questionIndex = :questionIndex
              AND a.correct = false
            """)
    int countWrongForQuestion(
            @Param("sessionId") Long sessionId,
            @Param("activity") String activity,
            @Param("questionIndex") Integer questionIndex);

    @Query("""
            SELECT COUNT(a) FROM Attempt a
            WHERE a.learningSessionId = :sessionId AND a.activity = :activity AND a.correct = true
            """)
    int countCorrect(@Param("sessionId") Long sessionId, @Param("activity") String activity);
}
