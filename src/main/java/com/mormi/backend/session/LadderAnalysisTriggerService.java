package com.mormi.backend.session;

import com.mormi.backend.report.ReportAiClient;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class LadderAnalysisTriggerService {

    private final LearningSessionRepository sessionRepository;
    private final AttemptRepository attemptRepository;
    private final ReportAiClient reportAiClient;
    private final LadderAnalysisOutboxRepository outboxRepository;
    private final LadderAnalysisOutboxClaimService claimService;

    public LadderAnalysisTriggerService(
            LearningSessionRepository sessionRepository,
            AttemptRepository attemptRepository,
            ReportAiClient reportAiClient,
            LadderAnalysisOutboxRepository outboxRepository,
            LadderAnalysisOutboxClaimService claimService) {
        this.sessionRepository = sessionRepository;
        this.attemptRepository = attemptRepository;
        this.reportAiClient = reportAiClient;
        this.outboxRepository = outboxRepository;
        this.claimService = claimService;
    }

    public void schedule(long learnerId, String triggerSessionId) {
        if (outboxRepository.findByTriggerSessionId(triggerSessionId).isEmpty()) {
            outboxRepository.save(LadderAnalysisOutbox.pending(learnerId, triggerSessionId));
        }
    }

    @Scheduled(fixedDelayString = "${mormi.ladder-analysis.outbox-poll-ms:2000}")
    public void dispatchPending() {
        List<LadderAnalysisOutboxClaimService.Claim> pending = claimService.claim();
        int sent = 0;
        int retry = 0;
        int rejected = 0;
        for (LadderAnalysisOutboxClaimService.Claim row : pending) {
            ReportAiClient.LadderRegistrationResult result =
                    evaluate(new LadderAnalysisTrigger(row.learnerId(), row.triggerSessionId()));
            switch (result) {
                case ACCEPTED -> {
                    claimService.markSent(row.id(), row.claimToken());
                    sent += 1;
                }
                case RETRY -> {
                    claimService.retry(row.id(), row.claimToken());
                    retry += 1;
                }
                case REJECTED -> {
                    claimService.reject(row.id(), row.claimToken());
                    rejected += 1;
                }
            }
        }
        if (!pending.isEmpty()) {
            log.info("ladder_analysis_outbox_dispatched sent={} retry={} rejected={}", sent, retry, rejected);
        }
    }

    ReportAiClient.LadderRegistrationResult evaluate(LadderAnalysisTrigger trigger) {
        LearningSession triggered = sessionRepository.findByPublicId(trigger.triggerSessionId())
                .filter(session -> session.getLearnerId().equals(trigger.learnerId()))
                .filter(session -> session.getCompletedAt() != null)
                .orElse(null);
        if (triggered == null) {
            return ReportAiClient.LadderRegistrationResult.ACCEPTED;
        }
        List<LearningSession> latest = sessionRepository
                .findTop2ByLearnerIdAndCurriculumSessionIdAndCompletedAtIsNotNullOrderByCompletedAtDescIdDesc(
                        trigger.learnerId(), triggered.getCurriculumSessionId());
        if (latest.size() != 2) {
            return ReportAiClient.LadderRegistrationResult.ACCEPTED;
        }
        LearningSession newest = latest.get(0);
        LearningSession older = latest.get(1);
        if (!newest.getPublicId().equals(trigger.triggerSessionId())) {
            return ReportAiClient.LadderRegistrationResult.ACCEPTED;
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
        int unlevelledCorrect = 0;
        int unlevelledAttempts = 0;
        for (Attempt attempt : attempts) {
            String level = canonicalLevel(attempt.getAnswerMeta().get("expression_level"));
            if (level == null) {
                unlevelledAttempts += 1;
                if (attempt.isCorrect()) {
                    unlevelledCorrect += 1;
                }
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
        List<String> sessionPublicIds = List.of(older.getPublicId(), newest.getPublicId());
        String unlevelledLevel = currentLevel;
        if (unlevelledAttempts > 0 || currentLevel == null) {
            unlevelledLevel = reportAiClient.latestExpressionLevel(
                            trigger.learnerId(), newest.getCurriculumSessionId(), sessionPublicIds)
                    .orElse(null);
            if (unlevelledLevel != null) {
                currentLevel = unlevelledLevel;
            }
        }
        if (currentLevel == null || (unlevelledAttempts > 0 && unlevelledLevel == null)) {
            return ReportAiClient.LadderRegistrationResult.RETRY;
        }
        if (unlevelledAttempts > 0) {
            int[] value = counts.computeIfAbsent(unlevelledLevel, ignored -> new int[2]);
            value[0] += unlevelledCorrect;
            value[1] += unlevelledAttempts;
            if ("L0".equals(unlevelledLevel)) {
                lowerRuleEvidence += unlevelledAttempts;
            }
        }
        if (counts.isEmpty()) {
            return ReportAiClient.LadderRegistrationResult.ACCEPTED;
        }
        Map<String, LadderAnalysisTrigger.Performance> performance = new LinkedHashMap<>();
        counts.forEach((level, value) -> performance.put(
                level, new LadderAnalysisTrigger.Performance(value[0], value[1])));
        return reportAiClient.registerLadderAnalysis(new LadderAnalysisTrigger.Request(
                trigger.learnerId() + ":" + newest.getCurriculumSessionId() + ":" + newest.getPublicId(),
                trigger.learnerId(),
                newest.getCurriculumSessionId(),
                newest.getPublicId(),
                sessionPublicIds,
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
