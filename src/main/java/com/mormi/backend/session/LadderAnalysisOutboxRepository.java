package com.mormi.backend.session;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LadderAnalysisOutboxRepository extends JpaRepository<LadderAnalysisOutbox, Long> {

    Optional<LadderAnalysisOutbox> findByTriggerSessionId(String triggerSessionId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT row FROM LadderAnalysisOutbox row WHERE row.id = :id")
    Optional<LadderAnalysisOutbox> findByIdForUpdate(@Param("id") Long id);

    @Query(value = """
            SELECT id
            FROM ladder_analysis_outbox
            WHERE (status = 'pending' AND available_at <= :now)
               OR (status = 'processing' AND lease_until <= :now)
            ORDER BY id
            FOR UPDATE SKIP LOCKED
            LIMIT :batchSize
            """, nativeQuery = true)
    List<Long> lockClaimableIds(
            @Param("now") OffsetDateTime now,
            @Param("batchSize") int batchSize);
}
