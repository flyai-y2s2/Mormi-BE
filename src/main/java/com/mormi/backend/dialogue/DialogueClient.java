package com.mormi.backend.dialogue;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Mormi-AI 대화 서비스 조회 클라이언트.
 *
 * <p>가르치기 500원은 프런트가 사다리 단계나 별노트 유무로 추론하지 않고,
 * 대화 서비스가 완료로 판정한 경우에만 지급한다. 그 판정을 여기서 확인한다.
 */
@Component
public class DialogueClient {

    private static final Logger log = LoggerFactory.getLogger(DialogueClient.class);

    private final RestClient restClient;
    private final String serviceKey;
    private final boolean enabled;

    public DialogueClient(
            @Value("${mormi.dialogue.base-url:}") String baseUrl,
            @Value("${mormi.dialogue.service-key:}") String serviceKey) {
        this.enabled = baseUrl != null && !baseUrl.isBlank();
        this.serviceKey = serviceKey;
        this.restClient = enabled ? RestClient.builder().baseUrl(baseUrl).build() : null;
    }

    /**
     * 대화가 완료되었고 보상 대상인지 확인한다.
     * 대화 서비스 주소가 설정되지 않았거나 조회에 실패하면 지급하지 않는다
     * (없는 보상을 주는 것보다 안전한 쪽으로 실패한다).
     */
    public boolean isTeachRewardEligible(String conversationId) {
        if (!enabled || conversationId == null || conversationId.isBlank()) {
            return false;
        }
        try {
            SessionEnvelope envelope = restClient.get()
                    .uri("/v1/conversations/{id}", conversationId)
                    .header("X-Mormi-Service-Key", serviceKey)
                    .retrieve()
                    .body(SessionEnvelope.class);

            if (envelope == null || envelope.turn() == null) {
                return false;
            }
            Turn turn = envelope.turn();
            Completion completion = turn.completion();
            return "completed".equals(turn.status())
                    && completion != null
                    && completion.teachRewardEligible();
        } catch (Exception error) {
            log.warn("대화 완료 확인 실패 conversationId={} : {}", conversationId, error.getMessage());
            return false;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SessionEnvelope(@JsonProperty("conversation_id") String conversationId, Turn turn) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Turn(
            @JsonProperty("turn_id") String turnId,
            String status,
            @JsonProperty("state_version") Integer stateVersion,
            Completion completion) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Completion(
            String outcome,
            @JsonProperty("teach_reward_eligible") boolean teachRewardEligible) {
    }
}
