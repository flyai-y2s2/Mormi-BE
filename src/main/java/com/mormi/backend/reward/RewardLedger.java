package com.mormi.backend.reward;

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
 * 보상 원장. 지갑 잔액은 별도 컬럼 없이 이 표의 합계로 도출한다.
 * idempotency_key 가 새로고침·재전송에 의한 중복 지급을 막는다.
 */
@Entity
@Table(name = "reward_ledger")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RewardLedger {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "learner_id", nullable = false)
    private Long learnerId;

    @Column(name = "learning_session_id")
    private Long learningSessionId;

    @Column(name = "source", nullable = false, length = 30)
    private String source;

    @Column(name = "amount", nullable = false)
    private int amount;

    @Column(name = "idempotency_key", nullable = false, length = 160)
    private String idempotencyKey;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private OffsetDateTime createdAt;

    private RewardLedger(Long learnerId, Long learningSessionId, RewardSource source, int amount, String key) {
        this.learnerId = learnerId;
        this.learningSessionId = learningSessionId;
        this.source = source.value();
        this.amount = amount;
        this.idempotencyKey = key;
    }

    public static RewardLedger of(
            Long learnerId, Long learningSessionId, RewardSource source, int amount, String idempotencyKey) {
        return new RewardLedger(learnerId, learningSessionId, source, amount, idempotencyKey);
    }
}
