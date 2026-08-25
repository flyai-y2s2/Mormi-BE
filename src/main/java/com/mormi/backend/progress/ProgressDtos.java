package com.mormi.backend.progress;

import java.util.List;
import java.util.UUID;

public final class ProgressDtos {

    private ProgressDtos() {
    }

    /**
     * 앱 부팅 상태 한 덩어리.
     * level 과 stars 는 MoramiApp.tsx 가 클라이언트에서 계산하던 값을 서버로 옮긴 것이다.
     */
    public record ProgressResponse(
            Long learnerId,
            String displayName,
            String characterName,
            UUID analyticsId,
            boolean onboardingComplete,
            List<String> completedSessionIds,
            int walletBalance,
            int level,
            int stars,
            boolean cafeUnlocked,
            List<String> cafeRequiredSessionIds,
            String activeLearningSessionId,
            String activeCafeVisitId) {
    }

    public record ThemeView(
            String themeId,
            String title,
            boolean unlocked,
            List<String> requiredSessionIds,
            List<String> remainingSessionIds) {
    }
}
