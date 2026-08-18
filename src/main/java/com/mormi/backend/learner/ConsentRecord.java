package com.mormi.backend.learner;

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
 * 동의 장부 1행. 추가만 하고 삭제하지 않는다.
 *
 * <p>learners.conversation_storage_consent 는 현재 상태 캐시일 뿐이고,
 * "언제 어떤 문서 버전으로 누가 받았고 언제 철회했나"의 근거는 이 테이블이 갖는다.
 */
@Entity
@Table(name = "consent_records")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ConsentRecord {

    public static final String SCOPE_CONVERSATION_STORAGE = "conversation_storage";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "learner_id", nullable = false, updatable = false)
    private Long learnerId;

    @Column(name = "scope", nullable = false, length = 40, updatable = false)
    private String scope;

    @Column(name = "policy_version", nullable = false, length = 40, updatable = false)
    private String policyVersion;

    @Column(name = "granted", nullable = false, updatable = false)
    private boolean granted;

    @Column(name = "collected_at", nullable = false, updatable = false, insertable = false)
    private OffsetDateTime collectedAt;

    @Column(name = "collected_by", length = 60, updatable = false)
    private String collectedBy;

    /** 철회 시각. 행을 지우는 대신 여기에 기록해 감사 추적을 유지한다. */
    @Column(name = "withdrawn_at")
    private OffsetDateTime withdrawnAt;

    public static ConsentRecord collect(
            Long learnerId, String scope, String policyVersion, boolean granted, String collectedBy) {
        ConsentRecord record = new ConsentRecord();
        record.learnerId = learnerId;
        record.scope = scope;
        record.policyVersion = policyVersion;
        record.granted = granted;
        record.collectedBy = collectedBy;
        return record;
    }

    public void withdraw() {
        if (this.withdrawnAt == null) {
            this.withdrawnAt = OffsetDateTime.now();
        }
    }

    public boolean isActive() {
        return withdrawnAt == null;
    }
}
