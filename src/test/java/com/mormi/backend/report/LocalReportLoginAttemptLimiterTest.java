package com.mormi.backend.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class LocalReportLoginAttemptLimiterTest {

    @Test
    void blocksOnlyTheClientThatReachedFiveFailures() {
        var limiter = new LocalReportLoginAttemptLimiter(
                Clock.fixed(Instant.parse("2026-08-20T00:00:00Z"), ZoneOffset.UTC));

        for (int attempt = 0; attempt < 5; attempt++) {
            assertThat(limiter.evaluate("client-a", false)).isEqualTo(LocalReportLoginAttemptLimiter.Decision.REJECTED);
        }
        assertThat(limiter.evaluate("client-a", false)).isEqualTo(LocalReportLoginAttemptLimiter.Decision.BLOCKED);
        assertThat(limiter.evaluate("client-a", true)).isEqualTo(LocalReportLoginAttemptLimiter.Decision.BLOCKED);
        assertThat(limiter.evaluate("client-b", true)).isEqualTo(LocalReportLoginAttemptLimiter.Decision.ACCEPTED);
    }

    @Test
    void successfulLoginClearsFailuresBeforeTheLimit() {
        var limiter = new LocalReportLoginAttemptLimiter(
                Clock.fixed(Instant.parse("2026-08-20T00:00:00Z"), ZoneOffset.UTC));

        assertThat(limiter.evaluate("client-a", false)).isEqualTo(LocalReportLoginAttemptLimiter.Decision.REJECTED);
        assertThat(limiter.evaluate("client-a", true)).isEqualTo(LocalReportLoginAttemptLimiter.Decision.ACCEPTED);
        assertThat(limiter.evaluate("client-a", false)).isEqualTo(LocalReportLoginAttemptLimiter.Decision.REJECTED);
    }

    @Test
    void boundsTheNumberOfRememberedClientBuckets() {
        var limiter = new LocalReportLoginAttemptLimiter(
                Clock.fixed(Instant.parse("2026-08-20T00:00:00Z"), ZoneOffset.UTC));

        for (int client = 0; client <= LocalReportLoginAttemptLimiter.MAX_CLIENTS; client++) {
            limiter.evaluate("client-" + client, false);
        }

        assertThat(limiter.trackedClients()).isEqualTo(LocalReportLoginAttemptLimiter.MAX_CLIENTS);
    }
}
