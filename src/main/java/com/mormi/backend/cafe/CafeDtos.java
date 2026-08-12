package com.mormi.backend.cafe;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

public final class CafeDtos {

    private CafeDtos() {
    }

    public record QueueRequest(
            @NotNull @Size(max = 20) String choiceId,
            boolean scaffoldUsed,
            @Min(1) int attemptNo,
            @Min(0) @Max(600000) Integer elapsedMs) {
    }

    public record MenuRequest(
            @NotEmpty @Size(min = 2, max = 2) List<@Size(max = 40) String> menuIds,
            @Min(1) int attemptNo,
            @Min(0) @Max(600000) Integer elapsedMs) {
    }

    /** counts 는 화폐 액면가 → 개수. 최종 구성만 보낸다. */
    public record PaymentRequest(
            @NotNull Map<Integer, Integer> counts,
            @Min(1) int attemptNo,
            @Min(0) @Max(600000) Integer elapsedMs) {
    }

    public record ChangeRequest(
            @NotNull Map<Integer, Integer> counts,
            @Min(1) int attemptNo,
            @Min(0) @Max(600000) Integer elapsedMs) {
    }

    /** 모든 스테이지 제출의 공통 응답. 다음 단계 해금은 서버 판정 결과다. */
    public record StageResultResponse(
            String cafeVisitId,
            String stage,
            boolean isCorrect,
            String nextStage,
            boolean nextStageUnlocked,
            int attempts,
            Integer expectedAmount,
            Integer submittedAmount,
            Integer difference,
            String feedbackCode) {
    }

    public record StageAttemptView(
            String stage,
            int attemptNo,
            boolean isCorrect,
            Integer elapsedMs,
            Map<String, Object> payload,
            OffsetDateTime createdAt) {

        public static StageAttemptView of(CafeVisitStage stage) {
            return new StageAttemptView(
                    stage.getStage(),
                    stage.getAttemptNo(),
                    stage.isCorrect(),
                    stage.getElapsedMs(),
                    stage.getPayload(),
                    stage.getCreatedAt());
        }
    }

    public record CafeVisitView(
            String cafeVisitId,
            String stage,
            int targetAmount,
            Integer orderTotal,
            Integer paidAmount,
            Integer changeAmount,
            Integer changeTarget,
            OffsetDateTime startedAt,
            OffsetDateTime completedAt,
            Map<String, Integer> menuPrices,
            List<StageAttemptView> attempts) {
    }
}
