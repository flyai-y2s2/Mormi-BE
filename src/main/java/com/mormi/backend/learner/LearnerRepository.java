package com.mormi.backend.learner;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;

public interface LearnerRepository extends JpaRepository<Learner, Long> {

    Optional<Learner> findByResearchCode(String researchCode);

    Optional<Learner> findByLoginId(String loginId);

    boolean existsByLoginId(String loginId);

    boolean existsByResearchCode(String researchCode);

    List<Learner> findByDisplayNameContainingIgnoreCaseOrderByDisplayNameAscIdAsc(
            String displayName, Pageable pageable);
}
