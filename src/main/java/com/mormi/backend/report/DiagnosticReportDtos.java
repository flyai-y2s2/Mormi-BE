package com.mormi.backend.report;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * API response shapes and normalized, persistence-independent evidence used by the diagnostic report.
 *
 * <p>The normalized records deliberately contain only data already owned by the source systems. The
 * metrics calculator consumes these records directly and never receives JPA entities.
 */
public final class DiagnosticReportDtos {

    private DiagnosticReportDtos() {
    }

    public record DiagnosticReport(
            LearnerHeader learner,
            ReportPeriod period,
            DataRange dataRange,
            CurrentSummary currentSummary,
            List<ModeReport> modes,
            List<DomainStatus> domains,
            Highlight improvedPoint,
            Highlight observePoint,
            EvidenceCounts evidenceCounts,
            boolean narrativeFallback,
            List<LadderRecommendation> ladderRecommendations) {
    }

    public enum Mode { HOME, LIFE }

    public enum StatusLabel { STABLE, DEVELOPING, SUPPORT_NEEDED, OBSERVING }

    public enum FactCategory { CONCEPT, EXPLANATION, LIFE, IMPROVED, OBSERVE }

    public record LearnerHeader(long learnerId, String displayName) {
    }

    public record ReportPeriod(
            LocalDate weekStart,
            LocalDate weekEnd,
            String timezone,
            LocalDate earliestWeekStart,
            LocalDate latestWeekStart) {
    }

    public record DataRange(
            OffsetDateTime firstAt,
            OffsetDateTime lastAt,
            int totalHomeSessions,
            int totalLifeVisits) {
    }

    public record EvidenceText(String text, List<String> evidenceRefs) {
    }

    public record CurrentSummary(
            EvidenceText conceptPerformance,
            EvidenceText explanationChange,
            EvidenceText lifeTransfer) {
    }

    public record Highlight(String text, List<String> evidenceRefs) {
    }

    public record EvidenceCounts(
            int homeSessions,
            int drillAttempts,
            int teachConversations,
            int lifeVisits,
            int speechSamples) {
    }

    public record TrendPoint(
            String evidenceId,
            String label,
            OffsetDateTime occurredAt,
            double independentScore,
            double supportedScore,
            Integer attemptCount,
            Integer questionCount,
            String expressionLevel,
            boolean recent) {

        public TrendPoint(
                String evidenceId,
                String label,
                OffsetDateTime occurredAt,
                double independentScore,
                double supportedScore,
                boolean recent) {
            this(evidenceId, label, occurredAt, independentScore, supportedScore, null, null, null, recent);
        }
    }

    public record DomainTrend(
            String domainId,
            String label,
            List<TrendPoint> points,
            int totalCount,
            int recentCount) {
    }

    public record ModeReport(Mode mode, List<DomainTrend> domains) {
    }

    public record DomainStatus(
            String domainId,
            String label,
            StatusLabel status,
            String direction,
            int totalCount,
            int recentCount) {
    }

    public record SpeechSample(
            String evidenceId,
            String utterance,
            String hintLevel,
            String expressionLevel,
            OffsetDateTime occurredAt) {
    }

    public record SpeechEvidence(
            String domainId,
            boolean available,
            String message,
            SpeechSample past,
            SpeechSample recent,
            List<String> verifiedElements,
            String changeSummary) {
    }

    public record LadderRecommendation(
            String analysisId,
            long learnerId,
            String skillId,
            String triggerSessionId,
            List<String> sessionIds,
            String currentLevel,
            String recommendedLevel,
            String action,
            Double currentAccuracy,
            int evidenceCount,
            String reasonCode,
            List<Map<String, Object>> recentPredictions,
            String modelVersion,
            int recommendationVersion,
            boolean approved,
            OffsetDateTime analyzedAt) {
    }

    public record LadderApprovalResponse(String analysisId, String status) {
    }

    public record ReportFact(String evidenceId, FactCategory category, String statement) {
    }

    record AttemptEvidence(
            String domainId,
            String itemId,
            Integer questionIndex,
            int attemptNo,
            boolean correct,
            Integer elapsedMs,
            OffsetDateTime occurredAt,
            Map<String, Object> answerMeta) {
    }

    record HomeEvidence(
            String sessionPublicId,
            String domainId,
            OffsetDateTime completedAt,
            List<AttemptEvidence> attempts) {
    }

    record TeachEvidence(
            String conversationId,
            String domainId,
            String taskId,
            String outcome,
            String maxHint,
            String expressionLevel,
            Map<String, Object> verifiedSlots,
            OffsetDateTime occurredAt) {
    }

    record LifeEvidence(
            String visitPublicId,
            String domainId,
            int attemptNo,
            boolean correct,
            boolean scaffoldUsed,
            OffsetDateTime occurredAt,
            Map<String, Object> payload) {
    }

    record AiReportEvidence(
            long learnerId,
            List<AiConversationEvidence> conversations,
            List<AiSkillEvidence> skills,
            List<AiNoteEvidence> notes,
            List<AiLadderRecommendation> ladderRecommendations) {

        AiReportEvidence(
                long learnerId,
                List<AiConversationEvidence> conversations,
                List<AiSkillEvidence> skills,
                List<AiNoteEvidence> notes) {
            this(learnerId, conversations, skills, notes, List.of());
        }
    }

    record AiLadderRecommendation(
            String analysisId,
            long learnerId,
            String skillId,
            String triggerSessionId,
            List<String> sessionIds,
            String currentLevel,
            String recommendedLevel,
            String action,
            Double currentAccuracy,
            int evidenceCount,
            String reasonCode,
            List<Map<String, Object>> recentPredictions,
            String modelVersion,
            int recommendationVersion,
            String status,
            boolean approved,
            OffsetDateTime analyzedAt) {
    }

    record AiConversationEvidence(
            String conversationId,
            String learningSessionId,
            String scene,
            String scenarioId,
            String status,
            String completionOutcome,
            boolean teachRewardEligible,
            Map<String, Object> verifiedSlots,
            String taskMaxHint,
            List<AiTurnEvidence> turns,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {
    }

    record AiTurnEvidence(
            String turnId,
            String taskId,
            String response,
            String responseType,
            String responseCategory,
            String expressionLevel,
            String hintLevel,
            Map<String, Object> pedagogy,
            OffsetDateTime createdAt) {
    }

    record AiSkillEvidence(
            String skillId,
            String highestStableExpressionLevel,
            int h0SuccessStreak,
            String recentMaxHint,
            double conceptMastery,
            double expressionIndependence,
            String lastBottleneck) {
    }

    record AiNoteEvidence(
            String noteId,
            String skillId,
            String text,
            String attribution,
            String evidence,
            String attributionLabel) {
    }

    record AiNarrative(String text, List<String> evidenceRefs) {
    }

    record AiSummary(
            AiNarrative conceptPerformance,
            AiNarrative explanationChange,
            AiNarrative lifeTransfer,
            AiNarrative improvedPoint,
            AiNarrative observePoint) {
    }

    record DrillMetric(
            int questionCount,
            int attemptCount,
            int firstTryCorrectCount,
            int incorrectAttemptCount,
            int correctedQuestionCount,
            int incorrectQuestionCount,
            double firstTryAccuracy,
            double attemptErrorRate,
            double correctionRate,
            double averageElapsedMs,
            double medianElapsedMs) {
    }

    record DiagnosticAnalysis(
            List<DomainTrend> homeDrillTrends,
            List<DomainTrend> teachTrends,
            List<DomainTrend> lifeTrends,
            List<DomainStatus> domainStatuses,
            Map<String, DrillMetric> drillMetrics,
            List<ReportFact> facts) {
    }
}
