package com.mormi.backend.learner;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConsentRecordRepository extends JpaRepository<ConsentRecord, Long> {

    List<ConsentRecord> findByLearnerIdAndScopeOrderByIdAsc(Long learnerId, String scope);

    Optional<ConsentRecord> findFirstByLearnerIdAndScopeAndWithdrawnAtIsNullOrderByIdDesc(
            Long learnerId, String scope);
}
