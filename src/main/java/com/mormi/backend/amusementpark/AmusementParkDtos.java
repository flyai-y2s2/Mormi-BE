package com.mormi.backend.amusementpark;

import com.mormi.backend.curriculum.AmusementParkCatalog;
import com.mormi.backend.curriculum.AmusementParkCatalog.StageContent;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

public final class AmusementParkDtos {

    private AmusementParkDtos() {
    }

    /** AI가 검증한 완료를 방문 진행 상태로 반영한 내부 결과. */
    public record StageResultResponse(
            String visitId,
            String stage,
            boolean isCorrect,
            String nextStage,
            boolean nextStageUnlocked,
            int attempts) {
    }

    /** 지도·해금 UI에 필요한 안정적인 껍데기. 교육 콘텐츠는 AI 첫 턴의 visual에만 있다. */
    public record StageView(
            String stageId,
            String scenarioId,
            String title,
            String mission,
            String skill) {

        public static StageView of(StageContent content) {
            return new StageView(
                    content.stageId(),
                    content.scenarioId(),
                    content.title(),
                    content.mission(),
                    content.skill());
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
