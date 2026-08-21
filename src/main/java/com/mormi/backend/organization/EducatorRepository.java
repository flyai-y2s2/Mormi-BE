package com.mormi.backend.organization;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EducatorRepository extends JpaRepository<Educator, Long> {

    /** 인증된 계정에서 교사 프로필을 찾는 매 요청 경로. account_id UNIQUE 를 탄다. */
    Optional<Educator> findByAccountId(Long accountId);
}
