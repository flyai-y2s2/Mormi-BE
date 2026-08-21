package com.mormi.backend.organization;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CohortRepository extends JpaRepository<Cohort, Long> {

    Optional<Cohort> findByClassCode(String classCode);

    /** 교사 화면의 학급 목록. 파일럿은 기관 단위로 학급을 공유한다. */
    List<Cohort> findByOrganizationIdOrderByIdAsc(Long organizationId);
}
