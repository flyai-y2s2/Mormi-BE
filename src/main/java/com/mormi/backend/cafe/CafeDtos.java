package com.mormi.backend.cafe;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

public final class CafeDtos {

    private CafeDtos() {
    }

    /**
     * 줄 서기. 좌우 인원은 화면이 방문마다 새로 뽑으므로 문제 자체를 함께 보낸다.
     * chosenCount 는 아이가 답한 "더 짧은 줄의 인원수"이고, 정오는 서버가 판정한다.
     * 좌우 인원 범위(1~5)·서로 다름은 CafeProblemContract 가 다시 검증한다.
     * 자유 발화 원문은 이 학습 DB 계약으로 받지 않고, 소유권 검증 뒤 AI로만 전달한다.
     */
    public record QueueRequest(
            @NotNull @Min(1) @Max(5) Integer leftCount,
            @NotNull @Min(1) @Max(5) Integer rightCount,
            @NotNull @Min(0) @Max(99) Integer chosenCount,
            boolean scaffoldUsed,
            @Min(1) int attemptNo,
            @Min(0) @Max(600000) Integer elapsedMs) {
    }

    /** 카페 대화 시작 시 화면이 보내는 줄 서기 문제. 값 규칙은 CafeProblemContract 가 검증한다. */
    public record QueueContext(
            @NotNull Integer leftCount,
            @NotNull Integer rightCount) {
    }

    /** 카페 대화 시작 시 화면이 보내는 메뉴판 한 줄. 가격은 서버 카탈로그와 일치해야 한다. */
    public record CafeMenuItem(
            @NotBlank @Size(max = 40) String id,
            @NotBlank @Size(max = 60) String name,
            @NotNull Integer price) {
    }

    /** 카페 대화 시작 시 화면이 보내는 메뉴 문제. budget 은 cafe_budget_menu 에서만 필수다. */
    public record CafeContext(
            @NotEmpty @Size(max = 10) List<@Valid CafeMenuItem> menuItems,
            @NotBlank @Size(max = 40) String mormiMenuId,
            Integer budget) {
    }

    /** 메뉴 고르기. 예산은 화면이 방문마다 뽑으므로 함께 받되, 서버는 허용 목록의 값만 인정한다. */
    public record MenuRequest(
            @NotEmpty @Size(min = 2, max = 2) List<@Size(max = 40) String> menuIds,
            @NotNull Integer budget,
            @Min(1) int attemptNo,
            @Min(0) @Max(600000) Integer elapsedMs) {
    }

    /**
     * 메뉴값 계산. 이 단계의 두 메뉴는 메뉴 고르기 단계와 별개로 다시 뽑히므로 함께 받는다.
     * answerAmount 는 아이가 적어 낸 합계이고, 정답은 서버 가격표로 계산한다.
     */
    public record PaymentRequest(
            @NotEmpty @Size(min = 2, max = 2) List<@Size(max = 40) String> menuIds,
            @NotNull @Min(0) @Max(1000000) Integer answerAmount,
            @Min(1) int attemptNo,
            @Min(0) @Max(600000) Integer elapsedMs) {
    }

    /**
     * 거스름돈. 이 단계의 메뉴도 따로 뽑히므로 menuId 를 받아
     * 10,000원 − 메뉴값을 기대 거스름돈으로 삼는다. counts 는 화폐 액면가 → 개수.
     */
    public record ChangeRequest(
            @NotNull @Size(max = 40) String menuId,
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
