package com.mormi.backend.report;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "mormi.local-report-admin.enabled", havingValue = "true")
final class LocalReportLoginAttemptLimiter {

    enum Decision {
        ACCEPTED,
        REJECTED,
        BLOCKED
    }

    static final Duration WINDOW = Duration.ofMinutes(10);
    static final int MAX_CLIENTS = 1_000;
    private static final int MAX_FAILURES = 5;

    private final Clock clock;
    private final LinkedHashMap<String, AttemptWindow> attempts = new LinkedHashMap<>(16, 0.75f, true);

    LocalReportLoginAttemptLimiter() {
        this(Clock.systemUTC());
    }

    LocalReportLoginAttemptLimiter(Clock clock) {
        this.clock = clock;
    }

    synchronized Decision evaluate(String clientFingerprint, boolean accepted) {
        Instant now = clock.instant();
        String client = clientFingerprint == null || clientFingerprint.isBlank() ? "unknown" : clientFingerprint;
        AttemptWindow window = attempts.get(client);
        if (window == null || !now.isBefore(window.startedAt().plus(WINDOW))) {
            window = new AttemptWindow(now, 0);
        }
        if (window.failures() >= MAX_FAILURES) {
            attempts.put(client, window);
            return Decision.BLOCKED;
        }
        if (accepted) {
            attempts.remove(client);
            return Decision.ACCEPTED;
        }
        attempts.put(client, new AttemptWindow(window.startedAt(), window.failures() + 1));
        evictOldestClients();
        return Decision.REJECTED;
    }

    synchronized int trackedClients() {
        return attempts.size();
    }

    private void evictOldestClients() {
        Iterator<Map.Entry<String, AttemptWindow>> iterator = attempts.entrySet().iterator();
        while (attempts.size() > MAX_CLIENTS && iterator.hasNext()) {
            iterator.next();
            iterator.remove();
        }
    }

    private record AttemptWindow(Instant startedAt, int failures) {}
}
