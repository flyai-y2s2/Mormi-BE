package com.mormi.backend.report;

import com.mormi.backend.report.DiagnosticReportDtos.AiReportEvidence;
import com.mormi.backend.report.DiagnosticReportDtos.AiSummary;
import com.mormi.backend.report.DiagnosticReportDtos.ReportFact;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

/** Privacy-preserving internal client for Mormi-AI report evidence and wording. */
@Component
public class ReportAiClient {

    private static final Logger log = LoggerFactory.getLogger(ReportAiClient.class);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final JsonMapper JSON = JsonMapper.builder()
            .findAndAddModules()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .build();

    private final RestClient restClient;
    private final String serviceKey;
    private final boolean enabled;

    public ReportAiClient(
            @Value("${mormi.dialogue.base-url:}") String baseUrl,
            @Value("${mormi.dialogue.service-key:}") String serviceKey,
            @Value("${mormi.dialogue.read-timeout-seconds:45}") long readTimeoutSeconds) {
        this.enabled = baseUrl != null && !baseUrl.isBlank() && serviceKey != null && !serviceKey.isBlank();
        this.serviceKey = serviceKey == null ? "" : serviceKey;
        Duration readTimeout = Duration.ofSeconds(Math.max(15, Math.min(readTimeoutSeconds, 120)));
        this.restClient = enabled ? buildClient(baseUrl, readTimeout) : null;
    }

    public Optional<AiReportEvidence> evidence(long learnerId, boolean includeRaw) {
        if (!enabled) {
            return Optional.empty();
        }
        try {
            String response = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/v1/internal/learners/{learnerId}/report-evidence")
                            .queryParam("include_raw", includeRaw)
                            .build(learnerId))
                    .header("X-Mormi-Service-Key", serviceKey)
                    .retrieve()
                    .body(String.class);
            return response == null || response.isBlank()
                    ? Optional.empty()
                    : Optional.of(JSON.readValue(response, AiReportEvidence.class));
        } catch (RestClientResponseException error) {
            log.warn("Mormi-AI report evidence failed status={}", error.getStatusCode().value());
            return Optional.empty();
        } catch (Exception error) {
            log.warn("Mormi-AI report evidence unavailable type={}", error.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    public Optional<AiSummary> summarize(String learnerLabel, List<ReportFact> facts) {
        if (!enabled || facts == null || facts.isEmpty()) {
            return Optional.empty();
        }
        try {
            SummaryRequest request = new SummaryRequest(
                    learnerLabel,
                    facts.stream()
                            .map(fact -> new SummaryFact(
                                    fact.evidenceId(),
                                    fact.category().name().toLowerCase(Locale.ROOT),
                                    fact.statement()))
                            .toList());
            String response = restClient.post()
                    .uri("/v1/internal/report-summaries")
                    .header("X-Mormi-Service-Key", serviceKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(JSON.writeValueAsString(request))
                    .retrieve()
                    .body(String.class);
            return response == null || response.isBlank()
                    ? Optional.empty()
                    : Optional.of(JSON.readValue(response, AiSummary.class));
        } catch (RestClientResponseException error) {
            log.warn("Mormi-AI report summary failed status={}", error.getStatusCode().value());
            return Optional.empty();
        } catch (Exception error) {
            log.warn("Mormi-AI report summary unavailable type={}", error.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private RestClient buildClient(String baseUrl, Duration readTimeout) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(CONNECT_TIMEOUT);
        requestFactory.setReadTimeout(readTimeout);
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
    }

    private record SummaryRequest(String learnerLabel, List<SummaryFact> facts) {
    }

    private record SummaryFact(String evidenceId, String category, String statement) {
    }
}
