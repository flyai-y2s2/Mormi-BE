package com.mormi.backend.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class LadderAnalysisOutboxClaimIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired LadderAnalysisOutboxRepository repository;
    @Autowired LadderAnalysisOutboxClaimService claimService;

    @Test
    void concurrentBackendInstancesCannotClaimTheSameOutboxRow() throws Exception {
        String sessionId = insertClaimableRow();

        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<List<LadderAnalysisOutboxClaimService.Claim>> first =
                    executor.submit(() -> claimAfter(start));
            Future<List<LadderAnalysisOutboxClaimService.Claim>> second =
                    executor.submit(() -> claimAfter(start));
            start.countDown();

            List<Long> claimedIds = java.util.stream.Stream.concat(
                            first.get().stream(), second.get().stream())
                    .map(LadderAnalysisOutboxClaimService.Claim::id)
                    .toList();

            assertThat(claimedIds).containsExactly(repository.findByTriggerSessionId(sessionId)
                    .orElseThrow()
                    .getId());
        }
    }

    @Test
    void staleClaimCannotOverwriteTheResultOfAReclaimedRow() {
        String sessionId = insertClaimableRow();
        LadderAnalysisOutboxClaimService.Claim stale = claimService.claim().getFirst();
        jdbcTemplate.update(
                "UPDATE ladder_analysis_outbox SET lease_until = NOW() - INTERVAL '1 second' WHERE id = ?",
                stale.id());

        LadderAnalysisOutboxClaimService.Claim current = claimService.claim().getFirst();
        assertThat(current.claimToken()).isNotEqualTo(stale.claimToken());

        claimService.markSent(stale.id(), stale.claimToken());
        LadderAnalysisOutbox afterStaleCompletion = repository.findById(stale.id()).orElseThrow();
        assertThat(afterStaleCompletion.getStatus()).isEqualTo("processing");
        assertThat(afterStaleCompletion.getClaimToken()).isEqualTo(current.claimToken());

        claimService.markSent(current.id(), current.claimToken());
        assertThat(repository.findById(current.id()).orElseThrow().getStatus()).isEqualTo("sent");
    }

    private String insertClaimableRow() {
        String suffix = UUID.randomUUID().toString().substring(0, 24);
        Long accountId = jdbcTemplate.queryForObject("""
                INSERT INTO accounts (login_id, password_hash, role)
                VALUES (?, '!disabled', 'learner')
                RETURNING id
                """, Long.class, "claim-" + suffix);
        Long learnerId = jdbcTemplate.queryForObject("""
                INSERT INTO learners (
                    display_name, research_code, analytics_id, account_id,
                    conversation_storage_consent, retention_policy
                ) VALUES (?, ?, ?, ?, FALSE, 'no_raw')
                RETURNING id
                """, Long.class, "claim-test", "claim-" + suffix,
                UUID.randomUUID(), accountId);
        String sessionId = "session-" + UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO learning_sessions (
                    public_id, learner_id, curriculum_session_id, variant_seed, completed_at
                ) VALUES (?, ?, 'money-count', 1, NOW())
                """, sessionId, learnerId);
        repository.saveAndFlush(LadderAnalysisOutbox.pending(learnerId, sessionId));
        return sessionId;
    }

    private List<LadderAnalysisOutboxClaimService.Claim> claimAfter(CountDownLatch start)
            throws InterruptedException {
        start.await();
        return claimService.claim();
    }
}
