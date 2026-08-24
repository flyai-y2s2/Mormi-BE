package com.mormi.backend.report;

import com.mormi.backend.report.DiagnosticReportDtos.AiReportEvidence;
import com.mormi.backend.report.DiagnosticReportDtos.AiSummary;
import com.mormi.backend.report.DiagnosticReportDtos.ReportFact;
import com.mormi.backend.session.LadderAnalysisTrigger;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
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

    /**
     * Reads only ladder metadata from the two owned teaching sessions. Raw child speech is never
     * requested. Production drill attempts are choice records and therefore do not carry an
     * expression level; the latest teaching turn is the authoritative fallback.
     */
    public Optional<String> latestExpressionLevel(
            long learnerId, String skillId, List<String> learningSessionIds) {
        Set<String> ownedSessions = Set.copyOf(learningSessionIds == null ? List.of() : learningSessionIds);
        Optional<AiReportEvidence> evidence = evidence(learnerId, false);
        if (evidence.isEmpty()) {
            return Optional.empty();
        }
        Optional<String> fromTurns = evidence.orElseThrow().conversations().stream()
                .filter(item -> item != null && ownedSessions.contains(item.learningSessionId()))
                .flatMap(item -> item.turns().stream())
                .filter(item -> item != null
                        && item.expressionLevel() != null
                        && !item.expressionLevel().isBlank())
                .max(Comparator.comparing(
                        DiagnosticReportDtos.AiTurnEvidence::createdAt,
                        Comparator.nullsFirst(Comparator.naturalOrder())))
                .map(item -> canonicalLevel(item.expressionLevel()));
        if (fromTurns.isPresent()) {
            return fromTurns;
        }
        return evidence.orElseThrow().skills().stream()
                .filter(item -> item != null && skillId.equals(item.skillId()))
                .map(DiagnosticReportDtos.AiSkillEvidence::highestStableExpressionLevel)
                .filter(value -> value != null && !value.isBlank())
                .map(ReportAiClient::canonicalLevel)
                .findFirst();
    }

    private static String canonicalLevel(String value) {
        String normalized = value.toUpperCase(Locale.ROOT);
        return "L1".equals(normalized) ? "L2" : normalized;
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

    public LadderRegistrationResult registerLadderAnalysis(LadderAnalysisTrigger.Request request) {
        if (!enabled) {
            return LadderRegistrationResult.RETRY;
        }
        try {
            restClient.post()
                    .uri("/v1/internal/ladder-analyses")
                    .header("X-Mormi-Service-Key", serviceKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(JSON.writeValueAsString(request))
                    .retrieve()
                    .toBodilessEntity();
            return LadderRegistrationResult.ACCEPTED;
        } catch (RestClientResponseException error) {
            int status = error.getStatusCode().value();
            log.warn("Mormi-AI ladder registration failed status={}", status);
            if (status == 409) {
                return LadderRegistrationResult.ACCEPTED;
            }
            if (status == 408 || status == 425 || status == 429 || status >= 500) {
                return LadderRegistrationResult.RETRY;
            }
            return LadderRegistrationResult.REJECTED;
        } catch (Exception error) {
            log.warn("Mormi-AI ladder registration unavailable type={}", error.getClass().getSimpleName());
            return LadderRegistrationResult.RETRY;
        }
    }

    public enum LadderRegistrationResult {
        ACCEPTED,
        RETRY,
        REJECTED
    }

    public boolean approveLadderAnalysis(String analysisId, long learnerId, int recommendationVersion) {
        if (!enabled) {
            return false;
        }
        try {
            restClient.post()
                    .uri("/v1/internal/ladder-analyses/{analysisId}/approve", analysisId)
                    .header("X-Mormi-Service-Key", serviceKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(JSON.writeValueAsString(new ApprovalRequest(learnerId, recommendationVersion)))
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (RestClientResponseException error) {
            log.warn("Mormi-AI ladder approval failed status={}", error.getStatusCode().value());
            return false;
        } catch (Exception error) {
            log.warn("Mormi-AI ladder approval unavailable type={}", error.getClass().getSimpleName());
            return false;
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

    private record ApprovalRequest(long learnerId, int recommendationVersion) {
    }
}
