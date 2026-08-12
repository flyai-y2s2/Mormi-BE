package com.mormi.backend.session;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

public final class SessionDtos {

    private SessionDtos() {
    }

    public record StartSessionRequest(
            @NotBlank @Size(max = 60) String curriculumSessionId,
            int variantSeed) {
    }

    public record StartSessionResponse(
            String learningSessionId,
            String curriculumSessionId,
            int variantSeed,
            OffsetDateTime startedAt,
            int timeLimitSeconds,
            int masteryTarget) {
    }

    /**
     * 문제 시도 1건. 오답도 반드시 보낸다.
     *
     * <p>answerMeta 에는 선택한 보기 id, 잠긴 보기 목록, 오개념 태그 같은 구조 데이터만 넣는다.
     */
    public record RecordAttemptRequest(
            @NotBlank @Pattern(regexp = "drill|teach|transfer") String activity,
            @Min(1) int attemptNo,
            @Size(max = 120) String itemId,
            @NotNull @Min(0) @Max(50) Integer questionIndex,
            @NotNull Boolean isCorrect,
            @Min(0) @Max(600000) Integer elapsedMs,
            Map<String, Object> answerMeta) {
    }

    public record RecordAttemptResponse(
            Long attemptId,
            boolean duplicate,
            int rewardGranted,
            int sessionRewardSubtotal,
            int correctCount,
            int masteryTarget,
            boolean drillCompleted) {
    }

    public record CompleteSessionRequest(
            @Size(max = 100) String conversationId,
            boolean transferSolved,
            boolean timedOut,
            @Min(0) Integer scaffoldLevel,
            @Min(0) @Max(86400) Integer elapsedSeconds) {
    }

    public record CompleteSessionResponse(
            String learningSessionId,
            String practiceResultId,
            int drillReward,
            int teachReward,
            int totalReward,
            int walletBalance,
            boolean teachRewardEligible,
            List<String> completedSessionIds,
            boolean cafeUnlocked,
            OffsetDateTime completedAt) {
    }

    public record AttemptView(
            Long attemptId,
            String activity,
            int attemptNo,
            String itemId,
            Integer questionIndex,
            boolean isCorrect,
            Integer elapsedMs,
            int rewardGranted,
            Map<String, Object> answerMeta,
            OffsetDateTime createdAt) {

        public static AttemptView of(Attempt attempt) {
            return new AttemptView(
                    attempt.getId(),
                    attempt.getActivity(),
                    attempt.getAttemptNo(),
                    attempt.getItemId(),
                    attempt.getQuestionIndex(),
                    attempt.isCorrect(),
                    attempt.getElapsedMs(),
                    attempt.getRewardGranted(),
                    attempt.getAnswerMeta(),
                    attempt.getCreatedAt());
        }
    }

    /** 새로고침 후 진행 중 세션을 복구할 때 쓴다. */
    public record SessionView(
            String learningSessionId,
            String curriculumSessionId,
            int variantSeed,
            OffsetDateTime startedAt,
            OffsetDateTime completedAt,
            boolean timedOut,
            boolean transferSolved,
            Integer scaffoldLevel,
            String conversationId,
            int correctCount,
            int masteryTarget,
            int drillRewardSubtotal,
            List<AttemptView> attempts) {
    }
}
