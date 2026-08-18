package com.mormi.backend.report;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReportSnapshotRepository extends JpaRepository<ReportSnapshot, Long> {

    List<ReportSnapshot> findByLearnerIdOrderByIdDesc(Long learnerId);

    List<ReportSnapshot> findByCohortIdOrderByIdDesc(Long cohortId);
}
