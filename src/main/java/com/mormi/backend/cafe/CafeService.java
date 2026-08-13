package com.mormi.backend.cafe;

import com.mormi.backend.cafe.CafeDtos.CafeVisitView;
import com.mormi.backend.cafe.CafeDtos.ChangeRequest;
import com.mormi.backend.cafe.CafeDtos.MenuRequest;
import com.mormi.backend.cafe.CafeDtos.PaymentRequest;
import com.mormi.backend.cafe.CafeDtos.QueueRequest;
import com.mormi.backend.cafe.CafeDtos.StageAttemptView;
import com.mormi.backend.cafe.CafeDtos.StageResultResponse;
import com.mormi.backend.common.ApiException;
import com.mormi.backend.curriculum.CurriculumCatalog;
import com.mormi.backend.progress.ThemeProgressService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CafeService {

    private final CafeVisitRepository visitRepository;
    private final CafeVisitStageRepository stageRepository;
    private final ThemeProgressService themeProgressService;

    public CafeService(
            CafeVisitRepository visitRepository,
            CafeVisitStageRepository stageRepository,
            ThemeProgressService themeProgressService) {
        this.visitRepository = visitRepository;
        this.stageRepository = stageRepository;
        this.themeProgressService = themeProgressService;
    }

    /** 해금되지 않았으면 방문 자체를 만들지 않는다. */
    @Transactional
    public CafeVisitView start(Long learnerId) {
        if (!themeProgressService.isCafeUnlocked(learnerId)) {
            throw ApiException.forbidden("아직 카페가 열리지 않았습니다. 집에서 필수 학습 %d개를 먼저 마쳐 주세요."
                    .formatted(CurriculumCatalog.CAFE_REQUIRED_SESSION_IDS.size()));
        }
        CafeVisit visit = visitRepository
                .findFirstByLearnerIdAndCompletedAtIsNullOrderByIdDesc(learnerId)
                .orElseGet(() -> visitRepository.save(CafeVisit.start(learnerId)));
        return view(learnerId, visit.getPublicId());
    }

    /** 줄 서기: 더 짧은 줄의 인원수를 맞혀야 통과. */
    @Transactional
    public StageResultResponse submitQueue(Long learnerId, String publicId, QueueRequest request) {
        CafeVisit visit = requireOwned(learnerId, publicId);
        requireStageReached(visit, CafeStage.QUEUE);

        int expected = CurriculumCatalog.queueCorrectCount(request.leftCount(), request.rightCount());
        boolean correct = expected == request.chosenCount();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("left_count", request.leftCount());
        payload.put("right_count", request.rightCount());
        payload.put("chosen_count", request.chosenCount());
        payload.put("shorter_side", request.leftCount() <= request.rightCount() ? "left" : "right");
        payload.put("scaffold_used", request.scaffoldUsed());

        return recordStage(
                visit, CafeStage.QUEUE, request.attemptNo(), correct, request.elapsedMs(), payload,
                expected, request.chosenCount(), correct ? "queue_correct" : "queue_longer_line");
    }

    /** 메뉴: 정확히 2개, 합계가 소지금 이내여야 통과. 합계는 서버 가격표로 계산한다. */
    @Transactional
    public StageResultResponse submitMenu(Long learnerId, String publicId, MenuRequest request) {
        CafeVisit visit = requireOwned(learnerId, publicId);
        requireStageReached(visit, CafeStage.MENU);

        List<String> menuIds = request.menuIds();
        if (menuIds.size() != CurriculumCatalog.CAFE_MENU_PICK_COUNT) {
            throw ApiException.badRequest("menu_count", "메뉴는 두 개를 골라야 합니다.");
        }
        int budget = requireKnownBudget(request.budget());
        int orderTotal = CurriculumCatalog.orderTotal(menuIds);
        boolean withinBudget = orderTotal <= budget;

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("menu_ids", menuIds);
        payload.put("order_total", orderTotal);
        payload.put("budget", budget);

        if (withinBudget) {
            visit.setOrderTotal(orderTotal);
        }
        return recordStage(
                visit, CafeStage.MENU, request.attemptNo(), withinBudget, request.elapsedMs(), payload,
                budget, orderTotal,
                withinBudget ? "menu_selected" : "menu_over_budget");
    }

    /** 계산: 이 단계에서 뽑힌 두 메뉴값의 합을 맞혀야 통과. */
    @Transactional
    public StageResultResponse submitPayment(Long learnerId, String publicId, PaymentRequest request) {
        CafeVisit visit = requireOwned(learnerId, publicId);
        requireStageReached(visit, CafeStage.CALCULATE);

        List<String> menuIds = request.menuIds();
        int expected = CurriculumCatalog.orderTotal(menuIds);
        int answer = request.answerAmount();
        boolean correct = answer == expected;

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("menu_ids", menuIds);
        payload.put("answer_amount", answer);
        payload.put("expected_total", expected);

        if (correct) {
            visit.setPaidAmount(expected);
        }
        return recordStage(
                visit, CafeStage.CALCULATE, request.attemptNo(), correct, request.elapsedMs(), payload,
                expected, answer,
                correct ? "payment_exact" : (answer < expected ? "payment_short" : "payment_over"));
    }

    /** 거스름돈: 낸 돈 − 메뉴값과 일치해야 통과. 500·1,000원으로만 구성한다. */
    @Transactional
    public StageResultResponse submitChange(Long learnerId, String publicId, ChangeRequest request) {
        CafeVisit visit = requireOwned(learnerId, publicId);
        requireStageReached(visit, CafeStage.CHANGE);

        int menuPrice = CurriculumCatalog.orderTotal(List.of(request.menuId()));
        int expected = visit.getTargetAmount() - menuPrice;
        int submitted = totalOf(request.counts(), CurriculumCatalog.CHANGE_DENOMINATIONS);
        boolean correct = submitted == expected;

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("menu_id", request.menuId());
        payload.put("menu_price", menuPrice);
        payload.put("counts", normalizeCounts(request.counts()));
        payload.put("change_amount", submitted);
        payload.put("expected_change", expected);

        if (correct) {
            visit.setChangeAmount(submitted);
        }
        return recordStage(
                visit, CafeStage.CHANGE, request.attemptNo(), correct, request.elapsedMs(), payload,
                expected, submitted,
                correct ? "change_exact" : (submitted < expected ? "change_short" : "change_over"));
    }

    @Transactional
    public CafeVisitView complete(Long learnerId, String publicId) {
        CafeVisit visit = requireOwned(learnerId, publicId);
        if (!CafeStage.CHANGE.next().isReachedBy(visit.stage()) && visit.getChangeAmount() == null) {
            throw ApiException.conflict("change_required", "거스름돈까지 마쳐야 완료할 수 있습니다.");
        }
        visit.advanceTo(CafeStage.COMPLETE);
        themeProgressService.markCafeCompleted(learnerId);
        return view(learnerId, publicId);
    }

    @Transactional(readOnly = true)
    public CafeVisitView view(Long learnerId, String publicId) {
        CafeVisit visit = requireOwned(learnerId, publicId);
        List<StageAttemptView> attempts = stageRepository.findByCafeVisitIdOrderByIdAsc(visit.getId())
                .stream().map(StageAttemptView::of).toList();
        Integer changeTarget = visit.getOrderTotal() == null
                ? null
                : visit.getTargetAmount() - visit.getOrderTotal();

        return new CafeVisitView(
                visit.getPublicId(),
                visit.getStage(),
                visit.getTargetAmount(),
                visit.getOrderTotal(),
                visit.getPaidAmount(),
                visit.getChangeAmount(),
                changeTarget,
                visit.getStartedAt(),
                visit.getCompletedAt(),
                CurriculumCatalog.CAFE_MENU_PRICES,
                attempts);
    }

    /**
     * 시도를 저장하고, 정답이면 다음 단계로 진행시킨다.
     * 같은 attempt_no 재전송이면 저장된 첫 결과를 그대로 돌려준다.
     */
    private StageResultResponse recordStage(
            CafeVisit visit,
            CafeStage stage,
            int attemptNo,
            boolean correct,
            Integer elapsedMs,
            Map<String, Object> payload,
            Integer expectedAmount,
            Integer submittedAmount,
            String feedbackCode) {

        var existing = stageRepository.findByCafeVisitIdAndStageAndAttemptNo(
                visit.getId(), stage.value(), attemptNo);
        if (existing.isEmpty()) {
            stageRepository.save(
                    CafeVisitStage.record(visit.getId(), stage, attemptNo, correct, elapsedMs, payload));
        } else {
            correct = existing.get().isCorrect();
        }

        if (correct) {
            visit.advanceTo(stage.next());
        }
        return new StageResultResponse(
                visit.getPublicId(),
                stage.value(),
                correct,
                visit.getStage(),
                correct,
                stageRepository.countByCafeVisitIdAndStage(visit.getId(), stage.value()),
                expectedAmount,
                submittedAmount,
                expectedAmount == null || submittedAmount == null ? null : submittedAmount - expectedAmount,
                feedbackCode);
    }

    /** 예산은 화면이 뽑아 보내지만, 서버는 허용 목록에 있는 값만 인정한다. */
    private int requireKnownBudget(Integer budget) {
        if (budget == null || !CurriculumCatalog.CAFE_MENU_BUDGETS.contains(budget)) {
            throw ApiException.badRequest("budget", "사용할 수 없는 예산입니다: " + budget);
        }
        return budget;
    }

    private int totalOf(Map<Integer, Integer> counts, Set<Integer> allowed) {
        int total = 0;
        for (Map.Entry<Integer, Integer> entry : counts.entrySet()) {
            Integer denomination = entry.getKey();
            int count = entry.getValue() == null ? 0 : entry.getValue();
            if (!allowed.contains(denomination)) {
                throw ApiException.badRequest("denomination", "사용할 수 없는 화폐입니다: " + denomination);
            }
            if (count < 0 || count > 20) {
                throw ApiException.badRequest("count_range", "화폐 개수는 0~20개여야 합니다.");
            }
            total += denomination * count;
        }
        return total;
    }

    private Map<String, Integer> normalizeCounts(Map<Integer, Integer> counts) {
        Map<String, Integer> normalized = new LinkedHashMap<>();
        counts.forEach((denomination, count) ->
                normalized.put(String.valueOf(denomination), count == null ? 0 : count));
        return normalized;
    }

    private void requireStageReached(CafeVisit visit, CafeStage stage) {
        if (visit.isCompleted()) {
            throw ApiException.conflict("visit_completed", "이미 완료된 카페 방문입니다.");
        }
        if (!stage.isReachedBy(visit.stage())) {
            throw ApiException.conflict("stage_locked", "아직 열리지 않은 단계입니다.");
        }
    }

    @Transactional(readOnly = true)
    public CafeVisit requireOwned(Long learnerId, String publicId) {
        CafeVisit visit = visitRepository.findByPublicId(publicId)
                .orElseThrow(() -> ApiException.notFound("카페 방문을 찾을 수 없습니다."));
        if (!visit.getLearnerId().equals(learnerId)) {
            throw ApiException.forbidden("다른 학습자의 카페 방문입니다.");
        }
        return visit;
    }
}
