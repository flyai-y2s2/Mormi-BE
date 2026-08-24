package com.mormi.backend.amusementpark;

import com.mormi.backend.amusementpark.AmusementParkDtos.ParkVisitView;
import com.mormi.backend.amusementpark.AmusementParkDtos.StageAttemptRequest;
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
        AmusementParkVisit visit = visitRepository
                .findFirstByLearnerIdAndCompletedAtIsNullOrderByIdDesc(learnerId)
                .orElseGet(() -> visitRepository.save(newVisitAfterLatest(learnerId)));
        return view(learnerId, visit.getPublicId());
    }

    /** 화면에서 직접 답을 제출하는 결정적 경로. 정답은 방문에 고정된 값으로 서버가 계산한다. */
    @Transactional
    public StageResultResponse submit(
            Long learnerId, String publicId, String stageId, StageAttemptRequest request) {
        AmusementParkVisit visit = requireOwned(learnerId, publicId);
        AmusementParkStage stage = requirePlayableStage(stageId);
        requireStageReached(visit, stage);

        StageContent content = AmusementParkCatalog.stage(stageId);
        Map<String, Integer> answers =
                AmusementParkProblemContract.requireDerivedAnswers(content, request.answers());
        return judge(visit, stage, content, answers, request.attemptNo(), request.elapsedMs());
    }

    /**
     * 대화 완료 경로. AI가 돌려준 verified_facts 를 결정적 제출과 같은 길로 흘려보낸다.
     *
     * <p>자유 발화 원문이나 모르미 대사는 절대 단계 정답으로 쓰지 않는다. 여기 들어오는 값은
     * DialogueService 가 완료 턴의 verified_facts 에서 뽑아낸 숫자뿐이다.
     */
    @Transactional
    public StageResultResponse submitVerifiedFacts(
            Long learnerId,
            String publicId,
            String stageId,
            Map<String, Integer> verifiedFacts,
            int attemptNo) {
        AmusementParkVisit visit = requireOwned(learnerId, publicId);
        AmusementParkStage stage = requirePlayableStage(stageId);
        requireStageReached(visit, stage);

        StageContent content = AmusementParkCatalog.stage(stageId);
        AmusementParkProblemContract.requireGivenFactsMatch(content, visit.getFacts(), verifiedFacts);
        Map<String, Integer> answers =
                AmusementParkProblemContract.requireDerivedAnswers(content, derivedOnly(content, verifiedFacts));
        return judge(visit, stage, content, answers, attemptNo, null);
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
        Map<String, Integer> facts = visit.getFacts();

        List<StageView> stages = AmusementParkCatalog.stages().stream()
                .map(content -> StageView.of(content, facts))
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

    private AmusementParkVisit newVisitAfterLatest(Long learnerId) {
        Map<String, Integer> previousFacts = visitRepository.findFirstByLearnerIdOrderByIdDesc(learnerId)
                .map(AmusementParkVisit::getFacts)
                .orElse(null);
        if (previousFacts == null) {
            return AmusementParkVisit.start(learnerId);
        }

        for (int index = 0; index < 20; index++) {
            Map<String, Integer> facts = AmusementParkCatalog.initialFacts();
            if (!facts.equals(previousFacts)) {
                return AmusementParkVisit.start(learnerId, facts);
            }
        }
        Map<String, Integer> facts = new LinkedHashMap<>(previousFacts);
        int ticketPrice = facts.getOrDefault("ticket_price", 2000);
        facts.put("ticket_price", ticketPrice >= 5000 ? 2000 : ticketPrice + 1000);
        return AmusementParkVisit.start(learnerId, facts);
    }

    /** 시도를 저장하고, 정답이면 다음 단계로 진행시킨다. 같은 attempt_no 재전송은 첫 결과를 돌려준다. */
    private StageResultResponse judge(
            AmusementParkVisit visit,
            AmusementParkStage stage,
            StageContent content,
            Map<String, Integer> answers,
            int attemptNo,
            Integer elapsedMs) {

        Map<String, Integer> expected =
                AmusementParkCatalog.expectedAnswers(content.stageId(), visit.getFacts());
        boolean correct = expected.equals(answers);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("given_facts", givenOnly(content, visit.getFacts()));
        payload.put("answers", answers);
        payload.put("expected_answers", expected);

        var existing = stageRepository.findByParkVisitIdAndStageAndAttemptNo(
                visit.getId(), stage.value(), attemptNo);
        if (existing.isEmpty()) {
            stageRepository.save(AmusementParkVisitStage.record(
                    visit.getId(), stage, attemptNo, correct, elapsedMs, payload));
        } else {
            correct = existing.get().isCorrect();
        }

        if (correct) {
            AmusementParkStage next = stage.next();
            visit.advanceTo(next);
            if (next == AmusementParkStage.COMPLETE) {
                themeProgressService.markAmusementParkCompleted(visit.getLearnerId());
            }
        }
        return new StageResultResponse(
                visit.getPublicId(),
                stage.value(),
                correct,
                visit.getStage(),
                correct,
                stageRepository.countByParkVisitIdAndStage(visit.getId(), stage.value()),
                expected,
                answers,
                feedbackCode(content, expected, answers, correct));
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

    private String feedbackCode(
            StageContent content,
            Map<String, Integer> expected,
            Map<String, Integer> answers,
            boolean correct) {
        String stageId = content.stageId();
        if (correct) {
            return stageId + "_correct";
        }
        // 답이 하나뿐인 단계는 모자란지 넘쳤는지까지 알려 주어 모르미 되묻기를 고를 수 있게 한다.
        if (expected.size() == 1) {
            String key = expected.keySet().iterator().next();
            return answers.get(key) < expected.get(key) ? stageId + "_short" : stageId + "_over";
        }
        return stageId + "_wrong";
    }

    private Map<String, Integer> givenOnly(StageContent content, Map<String, Integer> facts) {
        Map<String, Integer> given = new LinkedHashMap<>();
        content.factKeys().forEach(key -> given.put(key, facts.get(key)));
        return given;
    }

    private Map<String, Integer> derivedOnly(StageContent content, Map<String, Integer> facts) {
        Map<String, Integer> derived = new LinkedHashMap<>();
        content.derivedKeys().forEach(key -> {
            if (facts.containsKey(key)) {
                derived.put(key, facts.get(key));
            }
        });
        return derived;
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
