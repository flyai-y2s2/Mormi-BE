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

/** 교사·연구자 명부. 로그인 계정이 아니며 "누가 동의를 받았나"를 가리키는 데 쓴다. */
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

    @Column(name = "role", length = 30)
    private String role;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private OffsetDateTime createdAt;

    public static Educator of(Long organizationId, String displayName, String role) {
        Educator educator = new Educator();
        educator.organizationId = organizationId;
        educator.displayName = displayName;
        educator.role = role;
        return educator;
    }
}
