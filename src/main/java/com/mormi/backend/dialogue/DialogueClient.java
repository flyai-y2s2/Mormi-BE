package com.mormi.backend.dialogue;

import com.mormi.backend.common.ApiException;
import java.time.Duration;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Spring BE 전용 Mormi-AI 클라이언트.
 *
 * <p>브라우저는 AI 서비스 키와 learner_id 를 직접 다루지 않는다. 인증된 학습자와
 * 대화의 소유권은 Spring BE가 확인하고, 이 클라이언트만 서비스 키로 FastAPI를 호출한다.
 */
@Component
public class DialogueClient {

    private static final Logger log = LoggerFactory.getLogger(DialogueClient.class);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(12);
    private static final JsonMapper DIAGNOSTIC_JSON = JsonMapper.builder().build();

    private final RestClient restClient;
    private final String serviceKey;
    private final boolean enabled;

    public DialogueClient(
            @Value("${mormi.dialogue.base-url:}") String baseUrl,
            @Value("${mormi.dialogue.service-key:}") String serviceKey) {
        this.enabled = baseUrl != null && !baseUrl.isBlank();
        this.serviceKey = serviceKey == null ? "" : serviceKey;
        this.restClient = enabled ? buildClient(baseUrl) : null;
    }

    private RestClient buildClient(String baseUrl) {
        // AI 컨테이너의 HTTP/1.1 서버와 통신할 때 h2c 업그레이드나
        // chunked 재전송 여지를 없애기 위해 단순 HTTP/1.1 팩토리를 쓴다.
        SimpleClientHttpRequestFactory requestFactory =
                new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(CONNECT_TIMEOUT);
        requestFactory.setReadTimeout(READ_TIMEOUT);
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
    }

    public JsonNode createConversation(Map<String, Object> request) {
        requireConfigured();
        try {
            return restClient.post()
                    .uri("/v1/conversations")
                    .header("X-Mormi-Service-Key", serviceKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    // 운영 컨테이너 사이 호출에서 자동 message converter가 빈 본문을
                    // 보낸 사례가 있어 JSON을 명시적으로 직렬화한다.
                    .body(jsonBody(request))
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientResponseException error) {
            throw translate(error, "대화를 시작하지 못했습니다.");
        } catch (Exception error) {
            throw unavailable("대화 시작", error);
        }
    }

    public JsonNode respond(String conversationId, JsonNode request) {
        requireConfigured();
        try {
            return restClient.post()
                    .uri("/v1/conversations/{id}/responses", conversationId)
                    .header("X-Mormi-Service-Key", serviceKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(jsonBody(request))
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientResponseException error) {
            throw translate(error, "아이의 말을 처리하지 못했습니다.");
        } catch (Exception error) {
            throw unavailable("대화 응답", error);
        }
    }

    public JsonNode getConversation(String conversationId) {
        requireConfigured();
        try {
            return restClient.get()
                    .uri("/v1/conversations/{id}", conversationId)
                    .header("X-Mormi-Service-Key", serviceKey)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientResponseException error) {
            throw translate(error, "대화를 불러오지 못했습니다.");
        } catch (Exception error) {
            throw unavailable("대화 조회", error);
        }
    }

    /** BE가 소유권을 확인해 저장한 대화만 보상 판정에 사용한다. */
    public boolean isTeachRewardEligible(String conversationId) {
        if (!enabled || conversationId == null || conversationId.isBlank()) {
            return false;
        }
        try {
            JsonNode envelope = getConversation(conversationId);
            if (envelope == null) {
                return false;
            }
            JsonNode turn = envelope.path("turn");
            return "completed".equals(turn.path("status").asText())
                    && turn.path("completion").path("teach_reward_eligible").asBoolean(false);
        } catch (ApiException error) {
            log.warn("대화 완료 확인 실패 conversationId={} : {}", conversationId, error.getMessage());
            return false;
        }
    }

    private void requireConfigured() {
        if (!enabled) {
            throw ApiException.serviceUnavailable(
                    "dialogue_not_configured", "대화 서버 주소가 아직 설정되지 않았습니다.");
        }
        if (serviceKey.isBlank()) {
            throw ApiException.serviceUnavailable(
                    "dialogue_key_not_configured", "대화 서버 인증키가 아직 설정되지 않았습니다.");
        }
    }

    private String jsonBody(Object request) throws Exception {
        return DIAGNOSTIC_JSON.writeValueAsString(request);
    }

    private ApiException translate(RestClientResponseException error, String fallback) {
        int status = error.getStatusCode().value();
        String diagnosticCode = diagnosticHeader(error, "X-Mormi-Error-Code");
        String diagnosticPath = diagnosticHeader(error, "X-Mormi-Error-Path");
        if (diagnosticCode == null) {
            diagnosticCode = diagnosticBodyValue(error, "code");
        }
        if (diagnosticPath == null) {
            diagnosticPath = diagnosticBodyValue(error, "location");
        }
        // FastAPI 검증 오류 본문에는 요청 입력 일부가 포함될 수 있으므로
        // 아이 원문이 운영 로그에 남지 않게 상태와 정제된 진단 헤더만 기록한다.
        log.warn(
                "Mormi-AI 호출 실패 status={} diagnosticCode={} diagnosticPath={}",
                status,
                diagnosticCode,
                diagnosticPath);
        if (status == 401 || status == 403) {
            return ApiException.serviceUnavailable(
                    "dialogue_auth_failed",
                    "모르미 연결 인증을 확인하고 있어요. 잠시 후 다시 시도해 주세요.");
        }
        if (status == 404) {
            return ApiException.notFound("대화를 찾을 수 없습니다.");
        }
        if (status == 409) {
            return ApiException.conflict(
                    "dialogue_turn_conflict", "이미 처리된 응답이거나 이전 질문에 대한 응답입니다. 최신 대화를 불러와 주세요.");
        }
        if (status == 400 || status == 422) {
            String errorCode = "dialogue_invalid_request.upstream_" + status;
            errorCode += "." + diagnosticBodyShape(error);
            if (diagnosticCode != null) {
                errorCode += "." + diagnosticCode;
            }
            if (diagnosticPath != null) {
                errorCode += "." + diagnosticPath;
            }
            return ApiException.badRequest(
                    errorCode,
                    "반복 학습 기록과 가르치기 정보를 다시 확인해 주세요.");
        }
        if (status == 429) {
            return ApiException.serviceUnavailable(
                    "dialogue_rate_limited",
                    "모르미가 다른 말을 정리하고 있어요. 잠시 후 다시 시도해 주세요.");
        }
        if (status >= 500) {
            return ApiException.serviceUnavailable(
                    "dialogue_ai_error",
                    "모르미 대화 서버에서 문제가 생겼어요. 잠시 후 다시 시도해 주세요.");
        }
        return ApiException.serviceUnavailable("dialogue_upstream_error", fallback);
    }

    private String diagnosticHeader(RestClientResponseException error, String name) {
        if (error.getResponseHeaders() == null) {
            return null;
        }
        String value = error.getResponseHeaders().getFirst(name);
        if (value == null || value.isBlank() || value.length() > 160) {
            return null;
        }
        // AI가 생성한 코드·필드 경로만 허용한다. 응답 본문이나 사용자 입력은 전달하지 않는다.
        return safeDiagnosticValue(value);
    }

    private String diagnosticBodyValue(RestClientResponseException error, String field) {
        try {
            JsonNode detail = DIAGNOSTIC_JSON.readTree(error.getResponseBodyAsString()).path("detail");
            String value = "code".equals(field)
                    ? detail.path("code").asText()
                    : detail.path("issues").path(0).path("location").asText();
            return safeDiagnosticValue(value);
        } catch (Exception ignored) {
            // 이전 AI 버전의 문자열 detail 또는 JSON이 아닌 오류 본문은 사용하지 않는다.
            return null;
        }
    }

    private String diagnosticBodyShape(RestClientResponseException error) {
        if (error.getResponseBodyAsByteArray().length == 0) {
            return "empty_body";
        }
        try {
            JsonNode detail = DIAGNOSTIC_JSON.readTree(error.getResponseBodyAsString()).path("detail");
            if (detail.isObject()) {
                return "detail_object";
            }
            if (detail.isArray()) {
                return "detail_array";
            }
            if (detail.isTextual()) {
                return "detail_text";
            }
            return "detail_missing";
        } catch (Exception ignored) {
            return "non_json_body";
        }
    }

    private String safeDiagnosticValue(String value) {
        if (value == null || value.isBlank() || value.length() > 160) {
            return null;
        }
        return value.matches("[A-Za-z0-9_.\\[\\]-]+") ? value : null;
    }

    private ApiException unavailable(String action, Exception error) {
        log.warn("Mormi-AI {} 실패: {}", action, error.getMessage());
        return ApiException.serviceUnavailable(
                "dialogue_unavailable", "모르미가 잠시 대답을 준비하지 못했습니다. 다시 시도해 주세요.");
    }

}
