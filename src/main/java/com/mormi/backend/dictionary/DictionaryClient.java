package com.mormi.backend.dictionary;

import com.mormi.backend.common.ApiException;
import java.time.Duration;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 궁금해사전 카드를 Mormi-AI에서 읽어오는 클라이언트.
 *
 * <p>대화 호출과 같은 AI 서버를 부르지만 {@code DialogueClient} 와 분리한다.
 * 사전은 승인된 정적 카탈로그를 꺼내오는 읽기 요청이라 LLM 대기시간이 필요 없고,
 * 재시도해도 안전하며, 404 의 뜻도 "대화 없음"이 아니라 "카드 미등록"으로 다르다.
 * 세 정책이 모두 다르므로 한 클래스에 합치면 한쪽 정책이 다른 쪽을 망친다.
 */
@Component
public class DictionaryClient {

    private static final Logger log = LoggerFactory.getLogger(DictionaryClient.class);
    private static final JsonMapper DIAGNOSTIC_JSON = JsonMapper.builder().build();

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);

    /**
     * 사전 조회는 LLM을 거치지 않으므로 대화의 45초 대기시간을 쓰지 않는다.
     * 환경마다 조절할 이유가 없어 설정값이 아니라 상수로 둔다.
     */
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

    /** 최초 호출 1회 + 재시도 1회. 읽기 요청이라 같은 결과가 돌아온다. */
    private static final int MAX_ATTEMPTS = 2;

    /** 즉시 재시도하면 방금 실패한 원인이 그대로 남아 있으므로 짧게 기다린다. */
    private static final long RETRY_BACKOFF_MILLIS = 200;

    private final RestClient restClient;
    private final String serviceKey;
    private final boolean enabled;

    // 대화와 같은 AI 서버라 주소·서비스키 설정을 함께 쓴다. 별도 환경변수를 만들면
    // 배포에서 한쪽만 빠뜨렸을 때 사전만 조용히 죽는다.
    public DictionaryClient(
            @Value("${mormi.dialogue.base-url:}") String baseUrl,
            @Value("${mormi.dialogue.service-key:}") String serviceKey) {
        this.enabled = baseUrl != null && !baseUrl.isBlank();
        this.serviceKey = serviceKey == null ? "" : serviceKey;
        this.restClient = enabled ? buildClient(baseUrl) : null;
    }

    private RestClient buildClient(String baseUrl) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(CONNECT_TIMEOUT);
        requestFactory.setReadTimeout(READ_TIMEOUT);
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
    }

    /**
     * 현재 승인된 카드를 읽는다.
     *
     * @param expectedContentVersion 호출자가 기대하는 콘텐츠 버전. 넘기면 AI가 다를 때 409로 거절한다.
     */
    public JsonNode fetchCurrentCard(String curriculumSessionId, Integer expectedContentVersion) {
        requireConfigured();
        return getWithRetry("사전 카드 조회", () -> restClient.get()
                .uri(builder -> {
                    builder.path("/v1/content/dictionary-cards/{curriculumSessionId}");
                    if (expectedContentVersion != null) {
                        builder.queryParam("expected_content_version", expectedContentVersion);
                    }
                    return builder.build(curriculumSessionId);
                })
                .header("X-Mormi-Service-Key", serviceKey)
                .retrieve()
                .body(JsonNode.class));
    }

    /**
     * 대화에 고정된 카드 스냅샷을 읽는다. 진행 중인 대화는 카탈로그가 새로 배포되어도
     * 시작 시점의 카드를 그대로 본다.
     */
    public JsonNode fetchConversationCard(String conversationId) {
        requireConfigured();
        return getWithRetry("대화 고정 사전 카드 조회", () -> restClient.get()
                .uri("/v1/conversations/{conversationId}/dictionary-card", conversationId)
                .header("X-Mormi-Service-Key", serviceKey)
                .retrieve()
                .body(JsonNode.class));
    }

    /**
     * 읽기 요청이라 몇 번을 보내도 결과가 같다(멱등). 연결 실패·시간초과·AI 5xx 만 재시도하고,
     * 4xx 는 같은 요청을 다시 보내도 같은 거절이 오므로 즉시 포기한다.
     */
    private JsonNode getWithRetry(String action, Supplier<JsonNode> call) {
        ApiException lastError = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return call.get();
            } catch (RestClientResponseException error) {
                ApiException translated = translate(error);
                if (error.getStatusCode().value() < 500) {
                    throw translated;
                }
                lastError = translated;
            } catch (Exception error) {
                log.warn("Mormi-AI {} 실패 (시도 {}/{}): {}", action, attempt, MAX_ATTEMPTS, error.getMessage());
                lastError = ApiException.serviceUnavailable(
                        "dictionary_unavailable", "사전을 잠시 불러오지 못했어요. 다시 시도해 주세요.");
            }
            if (attempt < MAX_ATTEMPTS) {
                backoff();
            }
        }
        throw lastError;
    }

    private void backoff() {
        try {
            Thread.sleep(RETRY_BACKOFF_MILLIS);
        } catch (InterruptedException interrupted) {
            // 요청 처리 스레드가 중단되면 재시도를 포기하고 중단 상태를 되돌려 놓는다.
            Thread.currentThread().interrupt();
            throw ApiException.serviceUnavailable(
                    "dictionary_unavailable", "사전을 잠시 불러오지 못했어요. 다시 시도해 주세요.");
        }
    }

    /**
     * AI 오류를 프런트가 화면을 나눌 수 있는 코드로 옮긴다.
     *
     * <p>두 사전 API 의 404 본문 모양이 다르다. 카드 미등록은 {@code detail} 이 객체라
     * {@code detail.code} 가 있고, 대화 없음은 {@code detail} 이 문자열이라 코드가 없다.
     * 그래서 상태코드만 보지 않고 {@code detail.code} 유무로 갈라야 둘이 구분된다.
     */
    private ApiException translate(RestClientResponseException error) {
        int status = error.getStatusCode().value();
        String code = detailCode(error);
        log.warn("Mormi-AI 사전 조회 실패 status={} code={}", status, code);

        if (status == 401 || status == 403) {
            return ApiException.serviceUnavailable(
                    "dictionary_auth_failed", "사전 연결 인증을 확인하고 있어요. 잠시 후 다시 시도해 주세요.");
        }
        if (status == 404) {
            if ("dictionary_card_not_found".equals(code)) {
                return new ApiException(
                        HttpStatus.NOT_FOUND, "dictionary_card_not_found", "등록된 사전 카드가 없습니다.");
            }
            return ApiException.notFound("대화를 찾을 수 없습니다.");
        }
        if (status == 409) {
            if ("dictionary_snapshot_unavailable".equals(code)) {
                return ApiException.conflict(
                        "dictionary_snapshot_unavailable", "이 대화에는 고정된 사전 카드가 없습니다.");
            }
            return ApiException.conflict(
                    "dictionary_version_mismatch", "사전 내용이 갱신되었습니다. 다시 불러와 주세요.");
        }
        if (status == 429) {
            return ApiException.serviceUnavailable(
                    "dictionary_rate_limited", "사전을 정리하고 있어요. 잠시 후 다시 시도해 주세요.");
        }
        if (status >= 500) {
            return ApiException.serviceUnavailable(
                    "dictionary_ai_error", "사전 서버에서 문제가 생겼어요. 잠시 후 다시 시도해 주세요.");
        }
        return ApiException.serviceUnavailable(
                "dictionary_upstream_error", "사전을 불러오지 못했습니다.");
    }

    /** AI 오류 본문의 {@code detail.code} 만 읽는다. 그 밖의 본문은 로그에도 남기지 않는다. */
    private String detailCode(RestClientResponseException error) {
        try {
            String code = DIAGNOSTIC_JSON
                    .readTree(error.getResponseBodyAsString())
                    .path("detail")
                    .path("code")
                    .asString("");
            if (code == null || code.isBlank() || !code.matches("[a-z0-9_]{1,60}")) {
                return null;
            }
            return code;
        } catch (Exception ignored) {
            // detail 이 문자열이거나 JSON 이 아닌 오류 본문은 코드가 없는 것으로 본다.
            return null;
        }
    }

    private void requireConfigured() {
        if (!enabled) {
            throw ApiException.serviceUnavailable(
                    "dictionary_not_configured", "사전 서버 주소가 아직 설정되지 않았습니다.");
        }
        if (serviceKey.isBlank()) {
            throw ApiException.serviceUnavailable(
                    "dictionary_key_not_configured", "사전 서버 인증키가 아직 설정되지 않았습니다.");
        }
    }
}
