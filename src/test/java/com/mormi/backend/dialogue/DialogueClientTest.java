package com.mormi.backend.dialogue;

import static org.assertj.core.api.Assertions.assertThat;

import com.mormi.backend.common.ApiException;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.HttpClientErrorException;

class DialogueClientTest {

    @Test
    void createConversationSendsJsonRequestBody() throws Exception {
        AtomicReference<String> capturedBody = new AtomicReference<>();
        AtomicReference<String> capturedContentType = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/conversations", exchange -> {
            capturedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            capturedContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            byte[] response = "{\"conversation_id\":\"test\",\"turn\":{}}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        try {
            DialogueClient client = new DialogueClient(
                    "http://127.0.0.1:" + server.getAddress().getPort(), "test-key", 45);

            client.createConversation(Map.of(
                    "learner_id", 1L,
                    "scene", "home_teach",
                    "scenario_id", "home_teach"));

            assertThat(capturedContentType.get()).startsWith("application/json");
            assertThat(capturedBody.get()).contains("\"learner_id\":1");
            assertThat(capturedBody.get()).contains("\"scenario_id\":\"home_teach\"");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void validationFailureKeepsOnlySanitizedDiagnosticHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Mormi-Error-Code", "request_validation_failed");
        headers.add("X-Mormi-Error-Path", "body.practice_summary.attempts.0.latency_ms");
        HttpClientErrorException upstream = HttpClientErrorException.create(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "unprocessable",
                headers,
                "sensitive body must not be forwarded".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);

        DialogueClient client = new DialogueClient("", "", 45);
        ApiException translated = ReflectionTestUtils.invokeMethod(
                client, "translate", upstream, "fallback");

        assertThat(translated).isNotNull();
        assertThat(translated.getCode()).isEqualTo(
                "dialogue_invalid_request.upstream_422.non_json_body.request_validation_failed."
                        + "body.practice_summary.attempts.0.latency_ms");
        assertThat(translated.getMessage()).doesNotContain("sensitive");
    }

    @Test
    void unsafeDiagnosticHeaderIsDiscarded() {
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Mormi-Error-Code", "request value=아이 원문");
        HttpClientErrorException upstream = HttpClientErrorException.create(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "unprocessable",
                headers,
                new byte[0],
                StandardCharsets.UTF_8);

        DialogueClient client = new DialogueClient("", "", 45);
        ApiException translated = ReflectionTestUtils.invokeMethod(
                client, "translate", upstream, "fallback");

        assertThat(translated).isNotNull();
        assertThat(translated.getCode()).isEqualTo(
                "dialogue_invalid_request.upstream_422.empty_body");
    }

    @Test
    void sanitizedJsonDetailIsUsedWhenProxyDropsDiagnosticHeaders() {
        String body = """
                {"detail":{"code":"home_practice_result_missing","issues":[
                  {"location":"body","type":"value_error"}
                ]}}
                """;
        HttpClientErrorException upstream = HttpClientErrorException.create(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "unprocessable",
                new HttpHeaders(),
                body.getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);

        DialogueClient client = new DialogueClient("", "", 45);
        ApiException translated = ReflectionTestUtils.invokeMethod(
                client, "translate", upstream, "fallback");

        assertThat(translated).isNotNull();
        assertThat(translated.getCode()).isEqualTo(
                "dialogue_invalid_request.upstream_422.detail_object."
                        + "home_practice_result_missing.body");
    }

    @Test
    void modelFailureKeepsOnlyTheSafeProviderCode() {
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Mormi-Error-Code", "structured_schema_too_complex");
        HttpClientErrorException upstream = HttpClientErrorException.create(
                HttpStatus.SERVICE_UNAVAILABLE,
                "unavailable",
                headers,
                new byte[0],
                StandardCharsets.UTF_8);

        DialogueClient client = new DialogueClient("", "", 45);
        ApiException translated = ReflectionTestUtils.invokeMethod(
                client, "translate", upstream, "fallback");

        assertThat(translated).isNotNull();
        assertThat(translated.getCode()).isEqualTo(
                "dialogue_ai_error.structured_schema_too_complex");
    }

    @Test
    void responseBodyInputAndMessagesAreNeverUsedAsDiagnostics() {
        String body = """
                {"detail":{"code":"request value=아이 원문","issues":[
                  {"location":"body[아이 원문]","type":"value_error","input":"민감 정보"}
                ]}}
                """;
        HttpClientErrorException upstream = HttpClientErrorException.create(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "unprocessable",
                new HttpHeaders(),
                body.getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);

        DialogueClient client = new DialogueClient("", "", 45);
        ApiException translated = ReflectionTestUtils.invokeMethod(
                client, "translate", upstream, "fallback");

        assertThat(translated).isNotNull();
        assertThat(translated.getCode()).isEqualTo(
                "dialogue_invalid_request.upstream_422.detail_object");
        assertThat(translated.getMessage()).doesNotContain("민감", "아이 원문");
    }
}
