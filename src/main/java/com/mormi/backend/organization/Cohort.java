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
 * 학급/차수. 같은 기관·수업의 아이들을 묶어 조회하는 단위다.
 *
 * <p>class_code 는 학급 코드다. 아이 개인의 research_code 와 다른 축이며 섞어 쓰지 않는다.
 */
@Entity
@Table(name = "cohorts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Cohort {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private Long organizationId;

    @Column(name = "name", nullable = false, length = 80)
    private String name;

    @Column(name = "class_code", nullable = false, length = 40, updatable = false)
    private String classCode;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private OffsetDateTime createdAt;

    public static Cohort of(Long organizationId, String name, String classCode) {
        Cohort cohort = new Cohort();
        cohort.organizationId = organizationId;
        cohort.name = name;
        cohort.classCode = classCode;
        return cohort;
    }
}
