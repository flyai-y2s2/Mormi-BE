package com.mormi.backend.reward;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RewardLedgerRepository extends JpaRepository<RewardLedger, Long> {

    boolean existsByIdempotencyKey(String idempotencyKey);

    @Query("SELECT COALESCE(SUM(r.amount), 0) FROM RewardLedger r WHERE r.learnerId = :learnerId")
    int sumAmountByLearnerId(@Param("learnerId") Long learnerId);

    @Query("""
            SELECT COALESCE(SUM(r.amount), 0) FROM RewardLedger r
            WHERE r.learningSessionId = :sessionId AND r.source = :source
            """)
    int sumAmountBySessionAndSource(@Param("sessionId") Long sessionId, @Param("source") String source);

    List<RewardLedger> findByLearningSessionId(Long learningSessionId);
}
