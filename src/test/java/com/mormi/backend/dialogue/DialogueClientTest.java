package com.mormi.backend.dialogue;

import static org.assertj.core.api.Assertions.assertThat;

import com.mormi.backend.common.ApiException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.HttpClientErrorException;

class DialogueClientTest {

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

        DialogueClient client = new DialogueClient("", "");
        ApiException translated = ReflectionTestUtils.invokeMethod(
                client, "translate", upstream, "fallback");

        assertThat(translated).isNotNull();
        assertThat(translated.getCode()).isEqualTo(
                "dialogue_invalid_request.request_validation_failed."
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

        DialogueClient client = new DialogueClient("", "");
        ApiException translated = ReflectionTestUtils.invokeMethod(
                client, "translate", upstream, "fallback");

        assertThat(translated).isNotNull();
        assertThat(translated.getCode()).isEqualTo("dialogue_invalid_request");
    }
}
