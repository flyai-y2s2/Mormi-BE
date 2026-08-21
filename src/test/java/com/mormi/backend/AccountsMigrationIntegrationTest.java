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

/**
 * V12 가 운영 데이터를 옮기는 마이그레이션이라, 빈 스키마가 아니라
 * "V11 까지 적용된 DB 에 기존 학습자가 있는" 상태를 만들어 놓고 검증한다.
 * 1) 아이디가 있는 학습자는 계정으로 이관되고 로그인 세션이 끊기지 않는다
 * 2) 구 방식(연구 코드 온보딩) 학습자도 행이 남고 account_id 가 채워진다
 */
@Testcontainers
class AccountsMigrationIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private Connection connect() throws Exception {
        return DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    @Test
    void 기존_학습자의_계정과_로그인_세션이_끊기지_않고_이관된다() throws Exception {
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .target("11")
                .load()
                .migrate();

        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            // V5 방식: 아이디·비밀번호가 있는 학습자와 살아 있는 로그인 세션
            statement.execute("""
                    INSERT INTO learners (display_name, research_code, analytics_id, login_id, password_hash)
                    VALUES ('아이디있음', 'MIG-NEW', gen_random_uuid(), 'signup01', '$2a$10$0123456789012345678901uuuuuuuuuuuuuuuuuuuuuuuuuuuuuuu')
                    """);
            statement.execute("""
                    INSERT INTO learner_tokens (learner_id, token_hash, expires_at)
                    SELECT id, 'live-session-hash', NOW() + INTERVAL '10 days'
                    FROM learners WHERE research_code = 'MIG-NEW'
                    """);
            // V5 이전 방식: 연구 코드로만 온보딩해 아이디가 없는 학습자
            statement.execute("""
                    INSERT INTO learners (display_name, research_code, analytics_id)
                    VALUES ('레거시', 'MIG-OLD', gen_random_uuid())
                    """);
        }

        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .load()
                .migrate();

        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            // 아이디가 있던 학습자: 같은 아이디의 learner 계정으로 이관
            try (ResultSet rs = statement.executeQuery("""
                    SELECT a.role FROM accounts a
                    JOIN learners l ON l.account_id = a.id
                    WHERE a.login_id = 'signup01' AND l.research_code = 'MIG-NEW'
                    """)) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("role")).isEqualTo("learner");
            }

            // 로그인 세션: token_hash 그대로 auth_tokens 로 복사돼 인증이 이어진다
            try (ResultSet rs = statement.executeQuery("""
                    SELECT COUNT(*) FROM auth_tokens t
                    JOIN accounts a ON a.id = t.account_id
                    WHERE t.token_hash = 'live-session-hash'
                      AND a.login_id = 'signup01' AND t.revoked_at IS NULL
                    """)) {
                rs.next();
                assertThat(rs.getInt(1)).isEqualTo(1);
            }

            // 구 방식 학습자: 행은 남고 로그인 불가능한 계정이 채워진다
            try (ResultSet rs = statement.executeQuery("""
                    SELECT a.login_id, a.password_hash FROM accounts a
                    JOIN learners l ON l.account_id = a.id
                    WHERE l.research_code = 'MIG-OLD'
                    """)) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("login_id")).isEqualTo("legacy:MIG-OLD");
                assertThat(rs.getString("password_hash")).isEqualTo("!disabled");
            }

            // account_id 없는 학습자는 있을 수 없다
            try (ResultSet rs = statement.executeQuery(
                    "SELECT COUNT(*) FROM learners WHERE account_id IS NULL")) {
                rs.next();
                assertThat(rs.getInt(1)).isZero();
            }

            // learner_tokens 는 auth_tokens 로 대체되어 사라진다
            try (ResultSet rs = statement.executeQuery("""
                    SELECT COUNT(*) FROM information_schema.tables
                    WHERE table_name = 'learner_tokens'
                    """)) {
                rs.next();
                assertThat(rs.getInt(1)).isZero();
            }
        }
    }
}
