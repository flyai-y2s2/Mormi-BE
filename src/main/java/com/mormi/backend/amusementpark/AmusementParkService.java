package com.mormi.backend.amusementpark;

import com.mormi.backend.amusementpark.AmusementParkDtos.ParkVisitView;
import com.mormi.backend.amusementpark.AmusementParkDtos.StageAttemptView;
import com.mormi.backend.amusementpark.AmusementParkDtos.StageResultResponse;
import com.mormi.backend.amusementpark.AmusementParkDtos.StageView;
import com.mormi.backend.common.ApiException;
import com.mormi.backend.curriculum.AmusementParkCatalog;
import com.mormi.backend.curriculum.AmusementParkCatalog.StageContent;
import com.mormi.backend.progress.ThemeProgressService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AmusementParkService {

    private final AmusementParkVisitRepository visitRepository;
    private final AmusementParkVisitStageRepository stageRepository;
    private final ThemeProgressService themeProgressService;

    public AmusementParkService(
            AmusementParkVisitRepository visitRepository,
            AmusementParkVisitStageRepository stageRepository,
            ThemeProgressService themeProgressService) {
        this.visitRepository = visitRepository;
        this.stageRepository = stageRepository;
        this.themeProgressService = themeProgressService;
    }

    /** 해금되지 않았으면 방문 자체를 만들지 않는다. */
    @Transactional
    public ParkVisitView start(Long learnerId) {
        if (!themeProgressService.syncAmusementParkUnlock(learnerId)) {
            throw ApiException.forbidden("아직 놀이동산이 열리지 않았습니다. 카페를 먼저 마쳐 주세요.");
        }
        // 카페와 동일하게 완료한 방문도 연습 모드로 다시 연다. 완료 상태와 과거
        // 시도는 보존하고, 각 스테이지의 새 문제는 대화 round로 구분한다.
        AmusementParkVisit visit = visitRepository
                .findFirstByLearnerIdAndCompletedAtIsNullOrderByIdDesc(learnerId)
                .or(() -> visitRepository.findFirstByLearnerIdOrderByIdDesc(learnerId))
                .orElseGet(() -> visitRepository.save(AmusementParkVisit.start(learnerId)));
        return view(learnerId, visit.getPublicId());
    }

    /**
     * AI 소유 대화의 완료 경로. BE는 문제를 다시 채점하지 않고 검증된 증거와 진행만 기록한다.
     *
     * <p>문제·정답 원장은 Mormi-AI의 대화 스냅샷과 결정적 교육 엔진에 있다. 여기서 BE가
     * 방문용 임시 숫자로 다시 채점하면 서로 다른 문제를 비교하게 되므로, 허용된 사실 키와
     * 값 범위만 검증한 뒤 인증된 AI 완료 이벤트를 스테이지 완료로 기록한다.
     */
    @Transactional
    public StageResultResponse completeFromDialogue(
            Long learnerId,
            String publicId,
            String stageId,
            Map<String, Integer> verifiedFacts,
            int attemptNo,
            String completionOutcome,
            boolean teachRewardEligible) {
        AmusementParkVisit visit = requireOwned(learnerId, publicId);
        AmusementParkStage stage = requirePlayableStage(stageId);
        requireStageReached(visit, stage);

        StageContent content = AmusementParkCatalog.stage(stageId);
        Map<String, Integer> evidence =
                AmusementParkProblemContract.requireVerifiedFacts(content, verifiedFacts);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("verified_facts", evidence);
        payload.put("completion_outcome", completionOutcome);
        payload.put("teach_reward_eligible", teachRewardEligible);
        payload.put("content_owner", "mormi_ai");

        var existing = stageRepository.findByParkVisitIdAndStageAndAttemptNo(
                visit.getId(), stage.value(), attemptNo);
        if (existing.isEmpty()) {
            stageRepository.save(AmusementParkVisitStage.record(
                    visit.getId(), stage, attemptNo, true, null, payload));
        }

        AmusementParkStage next = stage.next();
        visit.advanceTo(next);
        if (next == AmusementParkStage.COMPLETE) {
            themeProgressService.markAmusementParkCompleted(visit.getLearnerId());
        }
        return new StageResultResponse(
                visit.getPublicId(),
                stage.value(),
                true,
                visit.getStage(),
                true,
                stageRepository.countByParkVisitIdAndStage(visit.getId(), stage.value()));
    }

    @Transactional
    public ParkVisitView complete(Long learnerId, String publicId) {
        AmusementParkVisit visit = requireOwned(learnerId, publicId);
        if (!AmusementParkStage.COMPLETE.isReachedBy(visit.stage())) {
            throw ApiException.conflict(
                    "stage_incomplete", "세 단계를 모두 마쳐야 완료할 수 있습니다.");
        }
        visit.advanceTo(AmusementParkStage.COMPLETE);
        themeProgressService.markAmusementParkCompleted(learnerId);
        return view(learnerId, publicId);
    }

    @Transactional(readOnly = true)
    public ParkVisitView view(Long learnerId, String publicId) {
        AmusementParkVisit visit = requireOwned(learnerId, publicId);

        List<StageView> stages = AmusementParkCatalog.stages().stream()
                .map(StageView::of)
                .toList();
        List<StageAttemptView> attempts = stageRepository
                .findByParkVisitIdOrderByIdAsc(visit.getId())
                .stream().map(StageAttemptView::of).toList();

        return new ParkVisitView(
                AmusementParkCatalog.THEME_ID,
                visit.getPublicId(),
                AmusementParkCatalog.stageOrder(),
                stageProgress(visit),
                stages,
                visit.getStartedAt(),
                visit.getCompletedAt(),
                attempts);
    }

    @Transactional(readOnly = true)
    public AmusementParkVisit requireOwned(Long learnerId, String publicId) {
        AmusementParkVisit visit = visitRepository.findByPublicId(publicId)
                .orElseThrow(() -> ApiException.notFound("놀이동산 방문을 찾을 수 없습니다."));
        if (!visit.getLearnerId().equals(learnerId)) {
            throw ApiException.forbidden("다른 학습자의 놀이동산 방문입니다.");
        }
        return visit;
    }


    /**
     * 스테이지별 잠금 상태. 완료된 방문은 세 칸이 모두 completed 다.
     * FE 는 이 값만 보고 화면을 그리고, 스스로 해금을 계산하지 않는다.
     */
    private Map<String, String> stageProgress(AmusementParkVisit visit) {
        AmusementParkStage current = visit.stage();
        Map<String, String> progress = new LinkedHashMap<>();
        for (AmusementParkStage stage : AmusementParkStage.playable()) {
            String state;
            if (visit.isCompleted() || stage.isClearedBy(current)) {
                state = "completed";
            } else if (stage.isReachedBy(current)) {
                state = "available";
            } else {
                state = "locked";
            }
            progress.put(stage.value(), state);
        }
        return progress;
    }

    private AmusementParkStage requirePlayableStage(String stageId) {
        AmusementParkStage stage;
        try {
            stage = AmusementParkStage.from(stageId);
        } catch (IllegalArgumentException error) {
            throw ApiException.badRequest("stage_unknown", "지원하지 않는 단계입니다: " + stageId);
        }
        if (stage == AmusementParkStage.COMPLETE) {
            throw ApiException.badRequest("stage_unknown", "제출할 수 없는 단계입니다: " + stageId);
        }
        return stage;
    }

    private void requireStageReached(AmusementParkVisit visit, AmusementParkStage stage) {
        // 완료된 방문은 세 단계를 모두 지났으므로 어느 단계든 다시 연습할 수 있다.
        // advanceTo 가 전진 전용이라 재연습 제출이 진행도를 되돌리지는 않는다.
        if (visit.isCompleted()) {
            return;
        }
        if (!stage.isReachedBy(visit.stage())) {
            throw ApiException.conflict("stage_locked", "아직 열리지 않은 단계입니다.");
        }
    }
}
