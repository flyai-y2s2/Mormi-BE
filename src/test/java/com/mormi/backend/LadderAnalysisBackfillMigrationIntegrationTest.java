package com.mormi.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class LadderAnalysisBackfillMigrationIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private Connection connect() throws Exception {
        return DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    @Test
    void newestEligibleCompletionAfterInitialOutboxMigrationIsBackfilled() throws Exception {
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .target("19")
                .load()
                .migrate();

        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO accounts (login_id, password_hash, role)
                    VALUES ('ladder-backfill', '!disabled', 'learner')
                    """);
            statement.execute("""
                    INSERT INTO learners (
                        display_name, research_code, analytics_id, account_id,
                        conversation_storage_consent, retention_policy
                    )
                    SELECT '백필 학습자', 'LADDER-BACKFILL', gen_random_uuid(), id,
                           FALSE, 'no_raw'
                    FROM accounts WHERE login_id = 'ladder-backfill'
                    """);
            statement.execute("""
                    INSERT INTO learning_sessions (
                        public_id, learner_id, curriculum_session_id, variant_seed, completed_at
                    )
                    SELECT 'session-old-1', id, 'number-count', 1, NOW() - INTERVAL '3 days'
                    FROM learners WHERE research_code = 'LADDER-BACKFILL'
                    UNION ALL
                    SELECT 'session-old-2', id, 'number-count', 2, NOW() - INTERVAL '2 days'
                    FROM learners WHERE research_code = 'LADDER-BACKFILL'
                    """);
            statement.execute("""
                    INSERT INTO ladder_analysis_outbox (
                        learner_id, trigger_session_id, status, sent_at
                    )
                    SELECT id, 'session-old-2', 'sent', NOW()
                    FROM learners WHERE research_code = 'LADDER-BACKFILL'
                    """);

            // A newer completion arrives after V18/V19 have already run in production.
            statement.execute("""
                    INSERT INTO learning_sessions (
                        public_id, learner_id, curriculum_session_id, variant_seed, completed_at
                    )
                    SELECT 'session-newest', id, 'number-count', 3, NOW() - INTERVAL '1 day'
                    FROM learners WHERE research_code = 'LADDER-BACKFILL'
                    """);
        }

        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .load()
                .migrate();

        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            try (ResultSet rs = statement.executeQuery("""
                    SELECT trigger_session_id, status
                    FROM ladder_analysis_outbox
                    WHERE trigger_session_id = 'session-newest'
                    """)) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("status")).isEqualTo("pending");
            }
            try (ResultSet rs = statement.executeQuery("""
                    SELECT status, sent_at
                    FROM ladder_analysis_outbox
                    WHERE trigger_session_id = 'session-old-2'
                    """)) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("status")).isEqualTo("sent");
                assertThat(rs.getTimestamp("sent_at")).isNotNull();
                assertThat(rs.next()).isFalse();
            }
        }
    }
}
