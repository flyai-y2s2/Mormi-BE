package com.mormi.backend.organization;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrganizationRepository extends JpaRepository<Organization, Long> {

    /** 교사 가입 시 같은 이름의 기관에 합류시키기 위한 조회. */
    Optional<Organization> findByName(String name);
}
