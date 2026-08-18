package com.mormi.backend.organization;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CohortRepository extends JpaRepository<Cohort, Long> {

    Optional<Cohort> findByClassCode(String classCode);
}
