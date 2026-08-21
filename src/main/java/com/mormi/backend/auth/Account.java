package com.mormi.backend.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.Locale;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 로그인 계정 한 개. 학생·교사 공용이라 login_id 가 전역 유니크다.
 * 역할별 프로필(learners·educators)이 account_id 로 이 행을 가리킨다.
 */
@Entity
@Table(name = "accounts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Account {

    public static final String ROLE_LEARNER = "learner";
    public static final String ROLE_EDUCATOR = "educator";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "login_id", nullable = false, length = 60, updatable = false)
    private String loginId;

    /** BCrypt 해시는 항상 60자다. 평문은 어디에도 남기지 않는다. */
    @Column(name = "password_hash", nullable = false, length = 60)
    private String passwordHash;

    @Column(name = "role", nullable = false, length = 20, updatable = false)
    private String role;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private OffsetDateTime createdAt;

    private Account(String loginId, String passwordHash, String role) {
        this.loginId = loginId;
        this.passwordHash = passwordHash;
        this.role = role;
    }

    public static Account register(String loginId, String passwordHash, String role) {
        return new Account(loginId, passwordHash, role);
    }

    public boolean isLearner() {
        return ROLE_LEARNER.equals(role);
    }

    /** Spring Security 권한 문자열. hasRole("LEARNER") 검사와 짝이 맞는다. */
    public String authority() {
        return "ROLE_" + role.toUpperCase(Locale.ROOT);
    }
}
