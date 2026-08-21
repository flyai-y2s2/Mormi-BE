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
 * 사전 발급된 참여 번호 1건. 아이가 이 번호로 가입하면 발급된 학급에 자동 재적된다.
 * 번호 자체는 학습자의 research_code 가 되므로 전역 유니크다.
 */
@Entity
@Table(name = "cohort_research_codes")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CohortResearchCode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "cohort_id", nullable = false, updatable = false)
    private Long cohortId;

    @Column(name = "code", nullable = false, length = 40, updatable = false)
    private String code;

    @Column(name = "issued_by", nullable = false, updatable = false)
    private Long issuedBy;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private OffsetDateTime createdAt;

    public static CohortResearchCode issue(Long cohortId, String code, Long issuedBy) {
        CohortResearchCode researchCode = new CohortResearchCode();
        researchCode.cohortId = cohortId;
        researchCode.code = code;
        researchCode.issuedBy = issuedBy;
        return researchCode;
    }
}
