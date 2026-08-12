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
            throw ApiException.forbidden("아직 카페가 열리지 않았습니다. 집에서 필수 학습 4개를 먼저 마쳐 주세요.");
        }
        CafeVisit visit = visitRepository
                .findFirstByLearnerIdAndCompletedAtIsNullOrderByIdDesc(learnerId)
                .orElseGet(() -> visitRepository.save(CafeVisit.start(learnerId)));
        return view(learnerId, visit.getPublicId());
    }

    /** 줄 서기: 사람이 적은 쪽을 골라야 통과. */
    @Transactional
    public StageResultResponse submitQueue(Long learnerId, String publicId, QueueRequest request) {
        CafeVisit visit = requireOwned(learnerId, publicId);
        requireStageReached(visit, CafeStage.QUEUE);

        boolean correct = CurriculumCatalog.QUEUE_CORRECT_CHOICE.equals(request.choiceId());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("choice_id", request.choiceId());
        payload.put("scaffold_used", request.scaffoldUsed());

        return recordStage(
                visit, CafeStage.QUEUE, request.attemptNo(), correct, request.elapsedMs(), payload,
                null, null, correct ? "queue_correct" : "queue_longer_line");
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
        int orderTotal = CurriculumCatalog.orderTotal(menuIds);
        boolean withinBudget = orderTotal <= visit.getTargetAmount();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("menu_ids", menuIds);
        payload.put("order_total", orderTotal);

        if (withinBudget) {
            visit.setOrderTotal(orderTotal);
        }
        return recordStage(
                visit, CafeStage.MENU, request.attemptNo(), withinBudget, request.elapsedMs(), payload,
                visit.getTargetAmount(), orderTotal,
                withinBudget ? "menu_selected" : "menu_over_budget");
    }

    /** 계산: 소지금과 정확히 일치해야 통과. */
    @Transactional
    public StageResultResponse submitPayment(Long learnerId, String publicId, PaymentRequest request) {
        CafeVisit visit = requireOwned(learnerId, publicId);
        requireStageReached(visit, CafeStage.CALCULATE);
        if (visit.getOrderTotal() == null) {
            throw ApiException.conflict("menu_required", "메뉴를 먼저 골라야 합니다.");
        }

        int paid = totalOf(request.counts(), CurriculumCatalog.PAYMENT_DENOMINATIONS);
        boolean correct = paid == visit.getTargetAmount();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("counts", normalizeCounts(request.counts()));
        payload.put("paid_amount", paid);
        payload.put("target_amount", visit.getTargetAmount());
        payload.put("order_total", visit.getOrderTotal());

        if (correct) {
            visit.setPaidAmount(paid);
        }
        return recordStage(
                visit, CafeStage.CALCULATE, request.attemptNo(), correct, request.elapsedMs(), payload,
                visit.getTargetAmount(), paid,
                correct ? "payment_exact" : (paid < visit.getTargetAmount() ? "payment_short" : "payment_over"));
    }

    /** 거스름돈: 낸 돈 − 메뉴값과 일치해야 통과. 500·1,000원으로만 구성한다. */
    @Transactional
    public StageResultResponse submitChange(Long learnerId, String publicId, ChangeRequest request) {
        CafeVisit visit = requireOwned(learnerId, publicId);
        requireStageReached(visit, CafeStage.CHANGE);
        if (visit.getPaidAmount() == null) {
            throw ApiException.conflict("payment_required", "결제를 먼저 마쳐야 합니다.");
        }

        int expected = visit.getPaidAmount() - visit.getOrderTotal();
        int submitted = totalOf(request.counts(), CurriculumCatalog.CHANGE_DENOMINATIONS);
        boolean correct = submitted == expected;

        Map<String, Object> payload = new LinkedHashMap<>();
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
