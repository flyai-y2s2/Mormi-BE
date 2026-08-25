package com.mormi.backend.learner;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.OffsetDateTime;
import java.util.UUID;

public final class LearnerDtos {

    private LearnerDtos() {
    }

    /** 자유 발화 암호화 저장 동의. 동의하지 않으면 정책은 반드시 no_raw 다. */
    public record ConversationConsentRequest(
            @NotNull Boolean conversationStorageConsent,
            @NotBlank @Pattern(regexp = "no_raw|30_days|90_days|permanent") String retentionPolicy) {
    }

    /**
     * 아이가 지은 캐릭터 이름. 공백·길이 검증은 Learner.nameCharacter 가 맡는다.
     * 빈 값과 길이 초과를 한 곳에서 보면 둘 다 같은 400 으로 나간다.
     */
    public record CharacterNameRequest(String characterName) {
    }

    /**
     * access_token 은 생성·복구 응답에만 실린다. 조회 응답에는 넣지 않는다.
     * display_name 은 화면 표시 전용이고, PostHog 에는 analytics_id 만 쓴다.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record LearnerResponse(
            Long id,
            String displayName,
            String characterName,
            String researchCode,
            UUID analyticsId,
            boolean conversationStorageConsent,
            String retentionPolicy,
            OffsetDateTime createdAt,
            String accessToken) {

        public static LearnerResponse of(Learner learner, String accessToken) {
            return new LearnerResponse(
                    learner.getId(),
                    learner.getDisplayName(),
                    learner.getCharacterName(),
                    learner.getResearchCode(),
                    learner.getAnalyticsId(),
                    learner.isConversationStorageConsent(),
                    learner.getRetentionPolicy(),
                    learner.getCreatedAt(),
                    accessToken);
        }
    }
}
