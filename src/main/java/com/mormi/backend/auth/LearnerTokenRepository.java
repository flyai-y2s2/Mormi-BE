package com.mormi.backend.auth;

import java.time.OffsetDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LearnerTokenRepository extends JpaRepository<LearnerToken, Long> {

    /** 매 요청 인증 경로. token_hash UNIQUE 인덱스를 탄다. */
    Optional<LearnerToken> findByTokenHash(String tokenHash);

    /**
     * 전체 기기 로그아웃. 기기 수만큼 UPDATE 를 보내지 않도록 한 번에 폐기한다.
     * 이미 폐기된 토큰은 최초 폐기 시각을 유지한다.
     */
    @Modifying
    @Query("update LearnerToken t set t.revokedAt = :now "
            + "where t.learnerId = :learnerId and t.revokedAt is null")
    int revokeAllByLearnerId(@Param("learnerId") Long learnerId, @Param("now") OffsetDateTime now);
}
