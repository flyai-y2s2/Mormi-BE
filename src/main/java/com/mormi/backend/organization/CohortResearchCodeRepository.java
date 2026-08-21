package com.mormi.backend.organization;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CohortResearchCodeRepository extends JpaRepository<CohortResearchCode, Long> {

    /** 학생 가입 경로. 입력한 참여 번호가 사전 발급된 것인지 찾는다. */
    Optional<CohortResearchCode> findByCode(String code);

    boolean existsByCode(String code);

    List<CohortResearchCode> findByCohortIdOrderByIdAsc(Long cohortId);
}
