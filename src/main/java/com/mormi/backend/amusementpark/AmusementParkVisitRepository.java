package com.mormi.backend.amusementpark;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AmusementParkVisitRepository extends JpaRepository<AmusementParkVisit, Long> {

    Optional<AmusementParkVisit> findByPublicId(String publicId);

    Optional<AmusementParkVisit> findFirstByLearnerIdAndCompletedAtIsNullOrderByIdDesc(Long learnerId);

    Optional<AmusementParkVisit> findFirstByLearnerIdOrderByIdDesc(Long learnerId);

}
