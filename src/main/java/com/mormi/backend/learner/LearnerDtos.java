package com.mormi.backend.learner;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.UUID;

public final class LearnerDtos {

    private LearnerDtos() {
    }

    /** 온보딩 제출. 연구 코드는 연구자가 미리 발급해 아이에게 전달한다. */
    public record CreateLearnerRequest(
            @NotBlank @Size(max = 12) String displayName,
            @NotBlank @Size(max = 40) @Pattern(regexp = "[A-Za-z0-9._-]+") String researchCode) {
    }

    /** 기기 교체·캐시 삭제 후 복구. 코드만으로 기존 학습자를 되찾는다. */
    public record LearnerAuthRequest(
            @NotBlank @Size(max = 40) @Pattern(regexp = "[A-Za-z0-9._-]+") String researchCode) {
    }

    /**
     * access_token 은 생성·복구 응답에만 실린다. 조회 응답에는 넣지 않는다.
     * display_name 은 화면 표시 전용이고, PostHog 에는 analytics_id 만 쓴다.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record LearnerResponse(
            Long id,
            String displayName,
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
                    learner.getResearchCode(),
                    learner.getAnalyticsId(),
                    learner.isConversationStorageConsent(),
                    learner.getRetentionPolicy(),
                    learner.getCreatedAt(),
                    accessToken);
        }
    }
}
