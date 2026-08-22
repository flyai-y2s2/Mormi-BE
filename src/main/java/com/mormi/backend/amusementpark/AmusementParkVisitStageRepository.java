package com.mormi.backend.amusementpark;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AmusementParkVisitStageRepository
        extends JpaRepository<AmusementParkVisitStage, Long> {

    Optional<AmusementParkVisitStage> findByParkVisitIdAndStageAndAttemptNo(
            Long parkVisitId, String stage, int attemptNo);

    List<AmusementParkVisitStage> findByParkVisitIdOrderByIdAsc(Long parkVisitId);

    int countByParkVisitIdAndStage(Long parkVisitId, String stage);
}
