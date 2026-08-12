package com.mormi.backend.cafe;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CafeVisitRepository extends JpaRepository<CafeVisit, Long> {

    Optional<CafeVisit> findByPublicId(String publicId);

    Optional<CafeVisit> findFirstByLearnerIdAndCompletedAtIsNullOrderByIdDesc(Long learnerId);

    int countByLearnerIdAndCompletedAtIsNotNull(Long learnerId);
}
