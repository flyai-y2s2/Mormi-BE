package com.mormi.backend.amusementpark;

import com.mormi.backend.curriculum.AmusementParkCatalog;
import com.mormi.backend.curriculum.AmusementParkCatalog.Fact;
import com.mormi.backend.curriculum.AmusementParkCatalog.StageContent;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotEmpty;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

public final class AmusementParkDtos {

    private AmusementParkDtos() {
    }

    /**
     * 스테이지 답 제출. answers 는 아이가 직접 구한 값만 담는다
     * (ticket 이면 total_price, pass_break_even 이면 break_even_rides·benefit_from_rides).
     *
     * <p>주어진 값(입장료·인원)은 받지 않는다. 방문 행에 고정 저장돼 있고 서버가 그것으로만 판정한다.
     */
    public record StageAttemptRequest(
            @NotEmpty Map<String, Integer> answers,
            @Min(1) int attemptNo,
            @Min(0) @Max(600000) Integer elapsedMs) {
    }

    /** 모든 스테이지 제출의 공통 응답. 다음 단계 해금은 서버 판정 결과다. */
    public record StageResultResponse(
            String visitId,
            String stage,
            boolean isCorrect,
            String nextStage,
            boolean nextStageUnlocked,
            int attempts,
            Map<String, Integer> expectedAnswers,
            Map<String, Integer> submittedAnswers,
            String feedbackCode) {
    }

    public record FactView(String key, String label, int value, String unit) {

        public static FactView of(Fact fact, Map<String, Integer> visitFacts) {
            // 라벨·단위는 카탈로그, 값은 방문에 뽑혀 고정된 것을 쓴다. 없으면 기본값으로
            // 얼버무리지 않고 막는다(화면과 판정이 다른 숫자를 보게 되는 게 더 나쁘다).
            return new FactView(
                    fact.key(),
                    fact.label(),
                    AmusementParkCatalog.factValue(visitFacts, fact.key()),
                    fact.unit());
        }
    }

    public record TransferView(String prompt, String equation, String conclusion) {

        public static TransferView of(AmusementParkCatalog.Transfer transfer) {
            return new TransferView(transfer.prompt(), transfer.equation(), transfer.conclusion());
        }
    }

    public record StageView(
            String stageId,
            String scenarioId,
            String title,
            String mission,
            String skill,
            String strategy,
            String mormiMisconception,
            String prompt,
            List<FactView> facts,
            Map<String, Integer> verifiedFacts,
            TransferView transfer) {

        public static StageView of(StageContent content, Map<String, Integer> visitFacts) {
            return new StageView(
                    content.stageId(),
                    content.scenarioId(),
                    content.title(),
                    content.mission(),
                    content.skill(),
                    content.strategy(),
                    content.mormiMisconception(),
                    content.prompt(),
                    content.facts().stream().map(fact -> FactView.of(fact, visitFacts)).toList(),
                    AmusementParkCatalog.verifiedFacts(content.stageId(), visitFacts),
                    TransferView.of(AmusementParkCatalog.transfer(content.stageId(), visitFacts)));
        }
    }

    public record StageAttemptView(
            String stage,
            int attemptNo,
            boolean isCorrect,
            Integer elapsedMs,
            Map<String, Object> payload,
            OffsetDateTime createdAt) {

        public static StageAttemptView of(AmusementParkVisitStage stage) {
            return new StageAttemptView(
                    stage.getStage(),
                    stage.getAttemptNo(),
                    stage.isCorrect(),
                    stage.getElapsedMs(),
                    stage.getPayload(),
                    stage.getCreatedAt());
        }
    }

    /**
     * 방문 조회 응답. 이슈의 방문 계약 그대로다.
     *
     * @param stageProgress 스테이지 id → locked / available / completed
     */
    public record ParkVisitView(
            String themeId,
            String visitId,
            List<String> stageOrder,
            Map<String, String> stageProgress,
            List<StageView> stages,
            OffsetDateTime startedAt,
            OffsetDateTime completedAt,
            List<StageAttemptView> attempts) {
    }
}
