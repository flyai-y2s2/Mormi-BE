package com.mormi.backend.organization;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 교사·연구자 한 명. V9 에서는 명부였고, account_id 가 연결되면 로그인 주체가 된다.
 * V12 이전에 명부로만 등록된 행은 계정이 없을 수 있다.
 */
@Entity
@Table(name = "educators")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Educator {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private Long organizationId;

    @Column(name = "display_name", nullable = false, length = 40)
    private String displayName;

    /** 직위(교사/연구자). accounts.role(learner/educator)과 다른 값이다. */
    @Column(name = "role", length = 30)
    private String role;

    @Column(name = "account_id", updatable = false)
    private Long accountId;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private OffsetDateTime createdAt;

    public static Educator of(Long organizationId, String displayName, String role) {
        Educator educator = new Educator();
        educator.organizationId = organizationId;
        educator.displayName = displayName;
        educator.role = role;
        return educator;
    }

    /** 교사 회원가입. 계정과 함께 만들어져 로그인할 수 있는 교사가 된다. */
    public static Educator withAccount(
            Long organizationId, String displayName, String role, Long accountId) {
        Educator educator = of(organizationId, displayName, role);
        educator.accountId = accountId;
        return educator;
    }
}
