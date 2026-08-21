package com.mormi.backend.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.OffsetDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 로그인 세션 한 건. 계정당 여러 행이 존재할 수 있어 다기기 로그인을 지원한다.
 * 학생·교사 구분 없이 이 테이블 하나가 토큰을 관리하고, 역할은 계정이 갖는다.
 * 토큰 평문은 발급 응답에만 실리고 여기에는 SHA-256 해시만 남는다.
 */
@Entity
@Table(name = "auth_tokens")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AuthToken {

    /** 파일럿은 리프레시 토큰 쌍 없이 만료 30일 + 슬라이딩 갱신으로 운영한다. */
    public static final Duration TTL = Duration.ofDays(30);

    /**
     * 요청마다 만료를 밀면 읽기 전용 요청이 전부 쓰기가 된다.
     * 남은 수명이 하루 이상 줄었을 때만 갱신해 UPDATE 를 하루 한 번으로 묶는다.
     */
    private static final Duration RENEW_AFTER = Duration.ofDays(1);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id", nullable = false, updatable = false)
    private Long accountId;

    @Column(name = "token_hash", nullable = false, length = 64, updatable = false)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "revoked_at")
    private OffsetDateTime revokedAt;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private OffsetDateTime createdAt;

    private AuthToken(Long accountId, String tokenHash, OffsetDateTime expiresAt) {
        this.accountId = accountId;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
    }

    public static AuthToken issue(Long accountId, String tokenHash, OffsetDateTime now) {
        return new AuthToken(accountId, tokenHash, now.plus(TTL));
    }

    /** 폐기되지 않았고 만료 전이어야 인증에 쓸 수 있다. */
    public boolean isValid(OffsetDateTime now) {
        return revokedAt == null && expiresAt.isAfter(now);
    }

    /**
     * 로그아웃. 행을 지우지 않고 시각만 남겨 "언제 끊었는지"를 추적할 수 있게 한다.
     * 이미 폐기된 토큰은 최초 폐기 시각을 덮어쓰지 않는다.
     */
    public void revoke(OffsetDateTime now) {
        if (revokedAt == null) {
            revokedAt = now;
        }
    }

    /**
     * 슬라이딩 갱신. 계속 쓰는 기기는 30일마다 다시 로그인하지 않아도 되고,
     * 방치된 토큰은 그대로 만료된다.
     * 갱신할 게 없으면 만료 시각을 건드리지 않아 UPDATE 도 나가지 않는다.
     */
    public void extendIfStale(OffsetDateTime now) {
        if (expiresAt.isBefore(now.plus(TTL).minus(RENEW_AFTER))) {
            expiresAt = now.plus(TTL);
        }
    }
}
