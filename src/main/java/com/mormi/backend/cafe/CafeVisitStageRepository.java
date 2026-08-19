package com.mormi.backend.cafe;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CafeVisitStageRepository extends JpaRepository<CafeVisitStage, Long> {

    Optional<CafeVisitStage> findByCafeVisitIdAndStageAndAttemptNo(
            Long cafeVisitId, String stage, int attemptNo);

    List<CafeVisitStage> findByCafeVisitIdOrderByIdAsc(Long cafeVisitId);

    List<CafeVisitStage> findByCafeVisitIdInOrderByCreatedAtAscIdAsc(List<Long> cafeVisitIds);

    int countByCafeVisitIdAndStage(Long cafeVisitId, String stage);
}
