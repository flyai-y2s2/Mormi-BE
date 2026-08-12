package com.mormi.backend.report;

import com.mormi.backend.common.ApiException;
import com.mormi.backend.curriculum.CurriculumCatalog;
import com.mormi.backend.learner.Learner;
import com.mormi.backend.learner.LearnerService;
import com.mormi.backend.report.ReportDtos.ReportSummary;
import com.mormi.backend.reward.RewardService;
import com.mormi.backend.reward.RewardSource;
import com.mormi.backend.session.Attempt;
import com.mormi.backend.session.AttemptRepository;
import com.mormi.backend.session.LearningSession;
import com.mormi.backend.session.LearningSessionRepository;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReportService {

    private static final DateTimeFormatter KOREAN_DATE =
            DateTimeFormatter.ofPattern("M월 d일", Locale.KOREAN);
    private static final String ACTIVITY_DRILL = "drill";

    private final LearningSessionRepository sessionRepository;
    private final AttemptRepository attemptRepository;
    private final RewardService rewardService;
    private final LearnerService learnerService;

    public ReportService(
            LearningSessionRepository sessionRepository,
            AttemptRepository attemptRepository,
            RewardService rewardService,
            LearnerService learnerService) {
        this.sessionRepository = sessionRepository;
        this.attemptRepository = attemptRepository;
        this.rewardService = rewardService;
        this.learnerService = learnerService;
    }

    @Transactional(readOnly = true)
    public ReportSummary latest(Long learnerId) {
        LearningSession session = sessionRepository
                .findFirstByLearnerIdAndCompletedAtIsNotNullOrderByCompletedAtDesc(learnerId)
                .orElseThrow(() -> ApiException.notFound("완료된 세션이 아직 없습니다."));
        return toSummary(learnerService.require(learnerId), session);
    }

    @Transactional(readOnly = true)
    public List<ReportSummary> history(Long learnerId, int limit) {
        Learner learner = learnerService.require(learnerId);
        return sessionRepository.findByLearnerIdAndCompletedAtIsNotNullOrderByCompletedAtDesc(learnerId)
                .stream()
                .limit(Math.max(1, Math.min(limit, 50)))
                .map(session -> toSummary(learner, session))
                .toList();
    }

    private ReportSummary toSummary(Learner learner, LearningSession session) {
        List<Attempt> attempts = attemptRepository.findByLearningSessionIdOrderByIdAsc(session.getId());
        List<Attempt> drills = attempts.stream()
                .filter(attempt -> ACTIVITY_DRILL.equals(attempt.getActivity()))
                .toList();

        int wrongCount = (int) drills.stream().filter(attempt -> !attempt.isCorrect()).count();
        int firstTryCorrect = (int) drills.stream()
                .filter(Attempt::isCorrect)
                .filter(attempt -> asInt(attempt.getAnswerMeta().get("wrong_count_before")) == 0)
                .count();

        int drillCoins = rewardService.sessionReward(session.getId(), RewardSource.DRILL);
        int teachCoins = rewardService.sessionReward(session.getId(), RewardSource.TEACH);

        return new ReportSummary(
                session.getCompletedAt() == null ? null : KOREAN_DATE.format(session.getCompletedAt()),
                session.getPublicId(),
                session.getCurriculumSessionId(),
                CurriculumCatalog.MASTERY_TARGET,
                drills.size(),
                session.getElapsedSeconds() == null ? 0 : session.getElapsedSeconds(),
                wrongCount > 0,
                session.isTransferSolved(),
                session.getScaffoldLevel() == null ? 0 : session.getScaffoldLevel(),
                session.isTimedOut(),
                learner.getId(),
                learner.getDisplayName(),
                drillCoins + teachCoins,
                drillCoins,
                teachCoins,
                rewardService.walletBalance(learner.getId()),
                wrongCount,
                firstTryCorrect);
    }

    private int asInt(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }
}
