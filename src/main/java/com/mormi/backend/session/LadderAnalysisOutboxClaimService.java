package com.mormi.backend.session;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Atomically leases outbox rows so only one backend instance can deliver each request. */
@Service
public class LadderAnalysisOutboxClaimService {

    private static final int BATCH_SIZE = 1;
    // Longer than ReportAiClient's maximum read timeout, preventing a second
    // instance from reclaiming a request while the first delivery is in flight.
    private static final long LEASE_SECONDS = 180;

    private final LadderAnalysisOutboxRepository repository;

    public LadderAnalysisOutboxClaimService(LadderAnalysisOutboxRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public List<Claim> claim() {
        OffsetDateTime now = OffsetDateTime.now();
        List<Long> ids = repository.lockClaimableIds(now, BATCH_SIZE);
        List<LadderAnalysisOutbox> rows = repository.findAllById(ids);
        OffsetDateTime leaseUntil = now.plusSeconds(LEASE_SECONDS);
        rows.forEach(row -> row.markProcessing(leaseUntil, UUID.randomUUID().toString()));
        return rows.stream()
                .map(row -> new Claim(
                        row.getId(), row.getLearnerId(), row.getTriggerSessionId(), row.getClaimToken()))
                .toList();
    }

    @Transactional
    public void markSent(long id, String claimToken) {
        repository.findByIdForUpdate(id)
                .filter(row -> ownsClaim(row, claimToken))
                .ifPresent(LadderAnalysisOutbox::markSent);
    }

    @Transactional
    public void retry(long id, String claimToken) {
        repository.findByIdForUpdate(id)
                .filter(row -> ownsClaim(row, claimToken))
                .ifPresent(LadderAnalysisOutbox::retryLater);
    }

    @Transactional
    public void reject(long id, String claimToken) {
        repository.findByIdForUpdate(id)
                .filter(row -> ownsClaim(row, claimToken))
                .ifPresent(LadderAnalysisOutbox::markRejected);
    }

    private boolean ownsClaim(LadderAnalysisOutbox row, String claimToken) {
        return "processing".equals(row.getStatus()) && claimToken.equals(row.getClaimToken());
    }

    public record Claim(long id, long learnerId, String triggerSessionId, String claimToken) {
    }
}
