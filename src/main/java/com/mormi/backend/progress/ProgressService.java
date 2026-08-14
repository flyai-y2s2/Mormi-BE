package com.mormi.backend.progress;

import com.mormi.backend.cafe.CafeVisitRepository;
import com.mormi.backend.curriculum.CurriculumCatalog;
import com.mormi.backend.learner.Learner;
import com.mormi.backend.learner.LearnerService;
import com.mormi.backend.progress.ProgressDtos.ProgressResponse;
import com.mormi.backend.progress.ProgressDtos.ThemeView;
import com.mormi.backend.reward.RewardService;
import com.mormi.backend.session.LearningSession;
import com.mormi.backend.session.LearningSessionRepository;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProgressService {

    private final LearnerService learnerService;
    private final LearningSessionRepository sessionRepository;
    private final CafeVisitRepository cafeVisitRepository;
    private final ThemeProgressService themeProgressService;
    private final RewardService rewardService;

    public ProgressService(
            LearnerService learnerService,
            LearningSessionRepository sessionRepository,
            CafeVisitRepository cafeVisitRepository,
            ThemeProgressService themeProgressService,
            RewardService rewardService) {
        this.learnerService = learnerService;
        this.sessionRepository = sessionRepository;
        this.cafeVisitRepository = cafeVisitRepository;
        this.themeProgressService = themeProgressService;
        this.rewardService = rewardService;
    }

    /**
     * 앱 시작 시 한 번 호출해 화면 상태를 통째로 복구한다.
     * 지금 localStorage 4개 키가 하던 일을 대신한다.
     */
    @Transactional
    public ProgressResponse snapshot(Long learnerId) {
        Learner learner = learnerService.require(learnerId);
        List<String> completed = sessionRepository.findCompletedCurriculumSessionIds(learnerId);
        boolean cafeUnlocked = themeProgressService.syncCafeUnlock(learnerId, Set.copyOf(completed));

        String activeSessionId = sessionRepository
                .findFirstByLearnerIdAndCompletedAtIsNullOrderByIdDesc(learnerId)
                .map(LearningSession::getPublicId)
                .orElse(null);

        // 진행 중 방문이 없으면 마지막 방문을 돌려준다. 끝낸 방문도 그대로 다시 열어
        // 네 단계를 연습할 수 있어야 하므로, 새로고침 뒤에도 같은 방문으로 돌아온다.
        String activeVisitId = cafeVisitRepository
                .findFirstByLearnerIdAndCompletedAtIsNullOrderByIdDesc(learnerId)
                .or(() -> cafeVisitRepository.findFirstByLearnerIdOrderByIdDesc(learnerId))
                .map(visit -> visit.getPublicId())
                .orElse(null);

        return new ProgressResponse(
                learner.getId(),
                learner.getDisplayName(),
                learner.getAnalyticsId(),
                learner.getOnboardingCompletedAt() != null,
                completed,
                rewardService.walletBalance(learnerId),
                completed.size() / 4 + 1,
                completed.size() * 3,
                cafeUnlocked,
                CurriculumCatalog.CAFE_REQUIRED_SESSION_IDS,
                activeSessionId,
                activeVisitId);
    }

    @Transactional
    public List<ThemeView> themes(Long learnerId) {
        List<String> completed = sessionRepository.findCompletedCurriculumSessionIds(learnerId);
        boolean cafeUnlocked = themeProgressService.syncCafeUnlock(learnerId, Set.copyOf(completed));
        List<String> remaining = CurriculumCatalog.CAFE_REQUIRED_SESSION_IDS.stream()
                .filter(id -> !completed.contains(id))
                .toList();

        return List.of(
                new ThemeView("home", "집", true, List.of(), List.of()),
                new ThemeView(
                        CurriculumCatalog.THEME_CAFE,
                        "모르미 카페",
                        cafeUnlocked,
                        CurriculumCatalog.CAFE_REQUIRED_SESSION_IDS,
                        remaining));
    }
}
