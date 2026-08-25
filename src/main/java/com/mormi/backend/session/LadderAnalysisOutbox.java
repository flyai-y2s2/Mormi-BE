package com.mormi.backend.session;

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

/** A durable request to analyze a completed learning session after its transaction commits. */
@Entity
@Table(name = "ladder_analysis_outbox")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LadderAnalysisOutbox {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "learner_id", nullable = false, updatable = false)
    private Long learnerId;

    @Column(name = "trigger_session_id", nullable = false, length = 60, updatable = false)
    private String triggerSessionId;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "available_at", nullable = false)
    private OffsetDateTime availableAt;

    @Column(name = "lease_until")
    private OffsetDateTime leaseUntil;

    @Column(name = "claim_token", length = 36)
    private String claimToken;

    @Column(name = "sent_at")
    private OffsetDateTime sentAt;

    private LadderAnalysisOutbox(Long learnerId, String triggerSessionId) {
        this.learnerId = learnerId;
        this.triggerSessionId = triggerSessionId;
        this.status = "pending";
        this.availableAt = OffsetDateTime.now();
    }

    public static LadderAnalysisOutbox pending(Long learnerId, String triggerSessionId) {
        return new LadderAnalysisOutbox(learnerId, triggerSessionId);
    }

    public void markProcessing(OffsetDateTime leaseUntil, String claimToken) {
        this.attemptCount += 1;
        this.status = "processing";
        this.leaseUntil = leaseUntil;
        this.claimToken = claimToken;
    }

    public void markSent() {
        this.status = "sent";
        this.leaseUntil = null;
        this.claimToken = null;
        this.sentAt = OffsetDateTime.now();
    }

    public void retryLater() {
        this.status = "pending";
        this.leaseUntil = null;
        this.claimToken = null;
        long delaySeconds = Math.min(300L, 1L << Math.min(attemptCount, 8));
        this.availableAt = OffsetDateTime.now().plusSeconds(delaySeconds);
    }

    public void markRejected() {
        this.status = "rejected";
        this.leaseUntil = null;
        this.claimToken = null;
    }
}
