package com.mormi.backend.auth;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AccountRepository extends JpaRepository<Account, Long> {

    /** 통합 로그인 경로. login_id UNIQUE 인덱스를 탄다. */
    Optional<Account> findByLoginId(String loginId);

    boolean existsByLoginId(String loginId);
}
