package com.mormi.backend.session;

import com.mormi.backend.report.ReportAiClient;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Service
public class LadderAnalysisTriggerService {

    private final LearningSessionRepository sessionRepository;
    private final AttemptRepository attemptRepository;
    private final ReportAiClient reportAiClient;
    private final ApplicationEventPublisher publisher;

    public LadderAnalysisTriggerService(
            LearningSessionRepository sessionRepository,
            AttemptRepository attemptRepository,
            ReportAiClient reportAiClient,
            ApplicationEventPublisher publisher) {
        this.sessionRepository = sessionRepository;
        this.attemptRepository = attemptRepository;
        this.reportAiClient = reportAiClient;
        this.publisher = publisher;
    }

    public void schedule(long learnerId, String triggerSessionId) {
        publisher.publishEvent(new LadderAnalysisTrigger(learnerId, triggerSessionId));
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void afterCommit(LadderAnalysisTrigger trigger) {
        evaluate(trigger);
    }

    void evaluate(LadderAnalysisTrigger trigger) {
        List<LearningSession> latest = sessionRepository
                .findTop2ByLearnerIdAndCompletedAtIsNotNullOrderByCompletedAtDesc(
                        trigger.learnerId());
        if (latest.size() != 2) {
            return;
        }
        LearningSession newest = latest.get(0);
        LearningSession older = latest.get(1);
        if (!newest.getPublicId().equals(trigger.triggerSessionId())
                || !newest.getCurriculumSessionId().equals(older.getCurriculumSessionId())) {
            return;
        }

        List<Attempt> attempts = attemptRepository
                .findByLearningSessionIdInOrderByCreatedAtAscIdAsc(
                        List.of(older.getId(), newest.getId()))
                .stream()
                .filter(attempt -> "drill".equals(attempt.getActivity()))
                .toList();
        Map<String, int[]> counts = new LinkedHashMap<>();
        String currentLevel = null;
        int lowerRuleEvidence = 0;
        for (Attempt attempt : attempts) {
            String level = canonicalLevel(attempt.getAnswerMeta().get("expression_level"));
            if (level == null) {
                continue;
            }
            currentLevel = level;
            int[] value = counts.computeIfAbsent(level, ignored -> new int[2]);
            value[1] += 1;
            if (attempt.isCorrect()) {
                value[0] += 1;
            }
            if ("L0".equals(level)) {
                lowerRuleEvidence += 1;
            }
        }
        if (currentLevel == null || counts.isEmpty()) {
            return;
        }
        Map<String, LadderAnalysisTrigger.Performance> performance = new LinkedHashMap<>();
        counts.forEach((level, value) -> performance.put(
                level, new LadderAnalysisTrigger.Performance(value[0], value[1])));
        reportAiClient.registerLadderAnalysis(new LadderAnalysisTrigger.Request(
                trigger.learnerId() + ":" + newest.getCurriculumSessionId() + ":" + newest.getPublicId(),
                trigger.learnerId(),
                newest.getCurriculumSessionId(),
                newest.getPublicId(),
                List.of(older.getPublicId(), newest.getPublicId()),
                currentLevel,
                performance,
                lowerRuleEvidence));
    }

    private String canonicalLevel(Object raw) {
        if (!(raw instanceof String value)) {
            return null;
        }
        String normalized = value.toUpperCase();
        if ("L1".equals(normalized)) {
            return "L2";
        }
        return switch (normalized) {
            case "L0", "L2", "L3", "L4" -> normalized;
            default -> null;
        };
    }
}
