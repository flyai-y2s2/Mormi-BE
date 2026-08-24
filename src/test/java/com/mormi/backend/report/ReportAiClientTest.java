package com.mormi.backend.report;

import static com.mormi.backend.report.DiagnosticReportDtos.FactCategory.CONCEPT;
import static org.assertj.core.api.Assertions.assertThat;

import com.mormi.backend.report.DiagnosticReportDtos.ReportFact;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import com.mormi.backend.session.LadderAnalysisTrigger;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ReportAiClientTest {

    @Test
    void ladderRegistrationUsesSharedKeyAndMetadataOnlySnakeCaseContract() throws Exception {
        AtomicReference<String> key = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/internal/ladder-analyses", exchange -> {
            key.set(exchange.getRequestHeaders().getFirst("X-Mormi-Service-Key"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 202, "{\"analysis_id\":\"ladder-1\",\"status\":\"pending\"}");
        });
        server.start();
        try {
            ReportAiClient client = new ReportAiClient(
                    "http://127.0.0.1:" + server.getAddress().getPort(), "shared-secret", 45);
            boolean accepted = client.registerLadderAnalysis(new LadderAnalysisTrigger.Request(
                    "key", 7L, "money-count", "session-2", List.of("session-1", "session-2"),
                    "L2", Map.of("L2", new LadderAnalysisTrigger.Performance(9, 10)), 0));

            assertThat(accepted).isTrue();
            assertThat(key.get()).isEqualTo("shared-secret");
            assertThat(body.get()).contains("\"idempotency_key\":\"key\"");
            assertThat(body.get()).contains("\"performance_by_level\":{\"L2\":");
            assertThat(body.get()).doesNotContain("utterance", "response_raw", "speech");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void disabledConfigurationReturnsEmptyWithoutCallingAnUpstream() {
        ReportAiClient client = new ReportAiClient("", "", 45);

        assertThat(client.evidence(7L, true)).isEmpty();
        assertThat(client.summarize("민서", List.of(
                new ReportFact("drill:money-count", CONCEPT, "돈 세기 상태는 관찰 중입니다."))))
                .isEmpty();
        assertThat(client.registerLadderAnalysis(new LadderAnalysisTrigger.Request(
                "key", 7L, "money-count", "session-2", List.of("session-1", "session-2"),
                "L2", Map.of("L2", new LadderAnalysisTrigger.Performance(9, 10)), 0)))
                .isFalse();
    }

    @Test
    void evidenceAndSummaryUseTheSharedKeyAndSnakeCaseContracts() throws Exception {
        AtomicReference<String> evidenceQuery = new AtomicReference<>();
        AtomicReference<String> evidenceKey = new AtomicReference<>();
        AtomicReference<String> summaryKey = new AtomicReference<>();
        AtomicReference<String> summaryBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/internal/learners/7/report-evidence", exchange -> {
            evidenceQuery.set(exchange.getRequestURI().getRawQuery());
            evidenceKey.set(exchange.getRequestHeaders().getFirst("X-Mormi-Service-Key"));
            respond(exchange, 200, """
                    {
                      "learner_id": 7,
                      "conversations": [{
                        "conversation_id": "conversation-1",
                        "learning_session_id": "session-1",
                        "scene": "home_teach",
                        "scenario_id": "home_teach",
                        "status": "completed",
                        "completion_outcome": "taught",
                        "teach_reward_eligible": true,
                        "verified_slots": {"amount": 600},
                        "task_max_hint": "H1",
                        "turns": [{
                          "turn_id": "turn-1",
                          "task_id": "explain-money",
                          "response": "500원과 100원을 더했어",
                          "response_type": "text",
                          "response_category": "correct_full",
                          "expression_level": "L3",
                          "hint_level": "H1",
                          "pedagogy": {"verified_slots": {"amount": 600}},
                          "created_at": "2026-01-10T09:00:00+09:00"
                        }],
                        "created_at": "2026-01-10T08:55:00+09:00",
                        "updated_at": "2026-01-10T09:00:00+09:00"
                      }],
                      "skills": [{
                        "skill_id": "addition",
                        "highest_stable_expression_level": "L3",
                        "h0_success_streak": 2,
                        "recent_max_hint": "H1",
                        "frequent_hint_types": ["attention"],
                        "concept_mastery": 0.8,
                        "expression_independence": 0.7,
                        "last_bottleneck": "unknown"
                      }],
                      "notes": [{
                        "note_id": "note-1",
                        "skill_id": "addition",
                        "text": "돈의 값을 더해서 셌어요.",
                        "attribution": "child",
                        "evidence": "direct_explanation",
                        "attribution_label": "아이의 직접 설명"
                      }]
                    }
                    """);
        });
        server.createContext("/v1/internal/report-summaries", exchange -> {
            summaryKey.set(exchange.getRequestHeaders().getFirst("X-Mormi-Service-Key"));
            summaryBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, """
                    {
                      "concept_performance": {"text": "돈 세기 상태는 관찰 중입니다.", "evidence_refs": ["drill:money-count"]},
                      "explanation_change": {"text": "돈 세기 상태는 관찰 중입니다.", "evidence_refs": ["drill:money-count"]},
                      "life_transfer": {"text": "돈 세기 상태는 관찰 중입니다.", "evidence_refs": ["drill:money-count"]},
                      "improved_point": {"text": "돈 세기 상태는 관찰 중입니다.", "evidence_refs": ["drill:money-count"]},
                      "observe_point": {"text": "돈 세기 상태는 관찰 중입니다.", "evidence_refs": ["drill:money-count"]}
                    }
                    """);
        });
        server.start();

        try {
            ReportAiClient client = new ReportAiClient(
                    "http://127.0.0.1:" + server.getAddress().getPort(), "shared-secret", 45);

            var evidence = client.evidence(7L, true);
            var summary = client.summarize("민서", List.of(
                    new ReportFact("drill:money-count", CONCEPT, "돈 세기 상태는 관찰 중입니다.")));

            assertThat(evidence).isPresent();
            assertThat(evidence.orElseThrow().conversations().getFirst().turns().getFirst().response())
                    .isEqualTo("500원과 100원을 더했어");
            assertThat(evidence.orElseThrow().skills().getFirst().skillId()).isEqualTo("addition");
            assertThat(summary).isPresent();
            assertThat(evidenceQuery.get()).isEqualTo("include_raw=true");
            assertThat(evidenceKey.get()).isEqualTo("shared-secret");
            assertThat(summaryKey.get()).isEqualTo("shared-secret");
            assertThat(summaryBody.get()).contains("\"learner_label\":\"민서\"");
            assertThat(summaryBody.get()).contains("\"evidence_id\":\"drill:money-count\"");
            assertThat(summaryBody.get()).contains("\"category\":\"concept\"");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void connectionAndServerFailuresReturnEmpty() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/internal/learners/7/report-evidence", exchange ->
                respond(exchange, 503, "sensitive response body"));
        server.start();

        try {
            ReportAiClient serverFailure = new ReportAiClient(
                    "http://127.0.0.1:" + server.getAddress().getPort(), "shared-secret", 45);
            ReportAiClient connectionFailure = new ReportAiClient(
                    "http://127.0.0.1:1", "shared-secret", 15);

            assertThat(serverFailure.evidence(7L, false)).isEmpty();
            assertThat(connectionFailure.evidence(7L, false)).isEmpty();
        } finally {
            server.stop(0);
        }
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
            throws java.io.IOException {
        byte[] response = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }
}
