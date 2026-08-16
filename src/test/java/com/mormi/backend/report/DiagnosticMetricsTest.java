package com.mormi.backend.report;

import static com.mormi.backend.report.DiagnosticReportDtos.StatusLabel.DEVELOPING;
import static com.mormi.backend.report.DiagnosticReportDtos.StatusLabel.OBSERVING;
import static com.mormi.backend.report.DiagnosticReportDtos.StatusLabel.STABLE;
import static com.mormi.backend.report.DiagnosticReportDtos.StatusLabel.SUPPORT_NEEDED;
import static org.assertj.core.api.Assertions.assertThat;

import com.mormi.backend.report.DiagnosticReportDtos.AttemptEvidence;
import com.mormi.backend.report.DiagnosticReportDtos.DiagnosticAnalysis;
import com.mormi.backend.report.DiagnosticReportDtos.DomainTrend;
import com.mormi.backend.report.DiagnosticReportDtos.TrendPoint;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DiagnosticMetricsTest {

    private static final OffsetDateTime START = OffsetDateTime.of(2026, 8, 1, 9, 0, 0, 0, ZoneOffset.UTC);

    @Test
    void drillMetricsUseFirstAttemptPerQuestionAndCountLaterCorrection() {
        var attempts = List.of(
                drill("money-count", "money-count:0", 0, 1, false, 3000, 0),
                drill("money-count", "money-count:0", 0, 2, true, 2200, 1),
                drill("money-count", "money-count:1", 1, 1, true, 1800, 2));

        var metric = DiagnosticMetrics.drill(attempts);

        assertThat(metric.firstTryAccuracy()).isEqualTo(50.0);
        assertThat(metric.attemptErrorRate()).isEqualTo(33.3);
        assertThat(metric.correctionRate()).isEqualTo(100.0);
        assertThat(metric.averageElapsedMs()).isEqualTo(2333.3);
        assertThat(metric.medianElapsedMs()).isEqualTo(2200.0);
    }

    @Test
    void drillMetricsUseItemIdWhenQuestionIndexIsMissingAndIgnoreNullElapsedTime() {
        var attempts = List.of(
                drill("money-count", "money-count:0", null, 1, false, null, 0),
                drill("money-count", "money-count:0", null, 2, true, 1200, 1),
                drill("money-count", "money-count:1", null, 1, true, 1800, 2));

        var metric = DiagnosticMetrics.drill(attempts);

        assertThat(metric.firstTryAccuracy()).isEqualTo(50.0);
        assertThat(metric.averageElapsedMs()).isEqualTo(1500.0);
        assertThat(metric.medianElapsedMs()).isEqualTo(1500.0);
    }

    @Test
    void currentWindowUsesNewestFiveButNeedsAtLeastThreeComparableRecords() {
        assertThat(DiagnosticMetrics.status(points(90, 80))).isEqualTo(OBSERVING);
        assertThat(DiagnosticMetrics.status(points(30, 85, 90, 80, 95, 100))).isEqualTo(STABLE);

        assertThat(DiagnosticMetrics.markRecent(points(10, 20, 30, 40, 50, 60)))
                .extracting(TrendPoint::recent)
                .containsExactly(false, true, true, true, true, true);
    }

    @Test
    void statusUsesInclusiveEightyAndFiftyPercentBoundaries() {
        assertThat(DiagnosticMetrics.status(points(80.0, 80.0, 80.0))).isEqualTo(STABLE);
        assertThat(DiagnosticMetrics.status(points(79.9, 79.9, 79.9))).isEqualTo(DEVELOPING);
        assertThat(DiagnosticMetrics.status(points(50.0, 50.0, 50.0))).isEqualTo(DEVELOPING);
        assertThat(DiagnosticMetrics.status(points(49.9, 49.9, 49.9))).isEqualTo(OBSERVING);
    }

    @Test
    void statusUsesRawRecentAverageWithoutConflictOrDeclineOverrides() {
        assertThat(DiagnosticMetrics.status(points(100, 100, 100, 100, 0))).isEqualTo(STABLE);
        assertThat(DiagnosticMetrics.status(points(100, 100, 100, 0, 0))).isEqualTo(DEVELOPING);
        assertThat(DiagnosticMetrics.status(points(79.95, 79.95, 79.95))).isEqualTo(DEVELOPING);
        assertThat(DiagnosticMetrics.status(points(80.0, 80.0, 80.0))).isEqualTo(STABLE);
    }

    @Test
    void homeTrendStatusUsesRawSessionScoresInsteadOfRoundedDisplayMetrics() {
        var home = List.of(
                homeWithFirstTryAccuracy("session-1", 1599, START),
                homeWithFirstTryAccuracy("session-2", 1599, START.plusDays(1)),
                homeWithFirstTryAccuracy("session-3", 1599, START.plusDays(2)));

        var trend = DiagnosticMetrics.analyze(home, List.of(), List.of()).homeDrillTrends().getFirst();

        assertThat(trend.points()).extracting(TrendPoint::independentScore).containsOnly(79.95);
        assertThat(DiagnosticMetrics.status(trend.points())).isEqualTo(DEVELOPING);
    }

    @Test
    void belowFiftyNeedsAnExplicitRepeatedRecentBottleneck() {
        assertThat(DiagnosticMetrics.status(points(0, 0, 0))).isEqualTo(OBSERVING);
        assertThat(DiagnosticMetrics.status(
                        points(0, 0, 0),
                        Map.of("evidence-0", "counting-skip", "evidence-1", "counting-skip")))
                .isEqualTo(SUPPORT_NEEDED);
        assertThat(DiagnosticMetrics.status(
                        points(0, 0, 0),
                        Map.of("evidence-0", "counting-skip", "evidence-1", "payment-change")))
                .isEqualTo(OBSERVING);
    }

    @Test
    void analyzeUsesPersistedMisconceptionTagOnlyWhenItRepeatsAcrossRecentHomeSessions() {
        var repeated = List.of(
                homeWithAttempt("session-1", false, Map.of("misconception_tag", "skip-count"), 0),
                homeWithAttempt("session-2", false, Map.of("misconception_tag", "skip-count"), 1),
                homeWithAttempt("session-3", false, Map.of("misconception_tag", "skip-count"), 2));
        var mixed = List.of(
                homeWithAttempt("session-1", false, Map.of("misconception_tag", "skip-count"), 0),
                homeWithAttempt("session-2", false, Map.of("misconception_tag", "amount-order"), 1),
                homeWithAttempt("session-3", false, Map.of(), 2));

        assertThat(DiagnosticMetrics.analyze(repeated, List.of(), List.of()).domainStatuses())
                .extracting(DiagnosticReportDtos.DomainStatus::status)
                .containsExactly(SUPPORT_NEEDED);
        assertThat(DiagnosticMetrics.analyze(mixed, List.of(), List.of()).domainStatuses())
                .extracting(DiagnosticReportDtos.DomainStatus::status)
                .containsExactly(OBSERVING);
    }

    @Test
    void teachScoresRequireARealVerifiedConceptSlotRatherThanOnlyMetadata() {
        var teach = List.of(
                new DiagnosticReportDtos.TeachEvidence(
                        "conversation-1",
                        "money-count",
                        "explain",
                        "taught",
                        "H0",
                        "L2",
                        Map.of("misconception_tag", "skip-count"),
                        START),
                new DiagnosticReportDtos.TeachEvidence(
                        "conversation-2",
                        "money-count",
                        "explain",
                        "taught",
                        "H0",
                        "L2",
                        Map.of("quantity", "five"),
                        START.plusDays(1)));

        var trend = DiagnosticMetrics.analyze(List.of(), teach, List.of()).teachTrends().getFirst();

        assertThat(trend.points()).extracting(TrendPoint::independentScore).containsExactly(0.0, 100.0);
        assertThat(trend.points()).extracting(TrendPoint::supportedScore).containsExactly(0.0, 100.0);
    }

    @Test
    void lifeFactDisplaysHalfUpRoundedRecentIndependentPercentage() {
        var life = List.of(
                life("visit-1", "calculate", 1, true, false, 0, Map.of()),
                life("visit-2", "calculate", 1, true, false, 1, Map.of()),
                life("visit-3", "calculate", 1, true, true, 2, Map.of()));

        var fact = DiagnosticMetrics.analyze(List.of(), List.of(), life).facts().stream()
                .filter(candidate -> candidate.evidenceId().equals("life:calculate"))
                .findFirst()
                .orElseThrow();

        assertThat(fact.statement()).contains("66.7%");
    }

    @Test
    void directionUsesTheSignOfRawRecentAndHistoricalAverages() {
        assertThat(DiagnosticMetrics.direction(points(50, 50, 50.1, 50.1, 50.1, 50.1, 50.1)))
                .isEqualTo("IMPROVING");
        assertThat(DiagnosticMetrics.direction(points(50.1, 50.1, 50, 50, 50, 50, 50)))
                .isEqualTo("DECLINING");
        assertThat(DiagnosticMetrics.direction(points(50, 50, 50, 50, 50, 50, 50)))
                .isEqualTo("MAINTAINING");
        assertThat(DiagnosticMetrics.direction(points(30, 90, 90, 90))).isEqualTo("INSUFFICIENT_HISTORY");
    }

    @Test
    void analyzeKeepsDifferentDomainsInSeparateTrends() {
        var home = List.of(
                new DiagnosticReportDtos.HomeEvidence("session-1", "money-count", START,
                        List.of(drill("money-count", "money-count:0", 0, 1, true, 1000, 0))),
                new DiagnosticReportDtos.HomeEvidence("session-2", "money-price", START.plusDays(1),
                        List.of(drill("money-price", "money-price:0", 0, 1, false, 1000, 1))));

        DiagnosticAnalysis analysis = DiagnosticMetrics.analyze(home, List.of(), List.of());

        assertThat(analysis.homeDrillTrends())
                .extracting(DomainTrend::domainId)
                .containsExactly("money-count", "money-price");
        assertThat(analysis.homeDrillTrends())
                .extracting(DomainTrend::totalCount)
                .containsExactly(1, 1);
    }

    @Test
    void cumulativeHomeDrillMetricsScopeQuestionKeysBySession() {
        var home = List.of(
                new DiagnosticReportDtos.HomeEvidence("session-1", "money-count", START,
                        List.of(drill("money-count", "money-count:0", 0, 1, true, 1000, 0))),
                new DiagnosticReportDtos.HomeEvidence("session-2", "money-count", START.plusDays(1), List.of(
                        drill("money-count", "money-count:0", 0, 1, false, 1000, 1),
                        drill("money-count", "money-count:0", 0, 2, true, 1000, 2))));

        var metric = DiagnosticMetrics.analyze(home, List.of(), List.of()).drillMetrics().get("money-count");

        assertThat(metric.questionCount()).isEqualTo(2);
        assertThat(metric.attemptCount()).isEqualTo(3);
        assertThat(metric.firstTryAccuracy()).isEqualTo(50.0);
        assertThat(metric.attemptErrorRate()).isEqualTo(33.3);
        assertThat(metric.correctionRate()).isEqualTo(100.0);
    }

    @Test
    void lifeRetriesForOneVisitAndDomainBecomeOneComparablePoint() {
        var life = List.of(
                life("visit-1", "change", 1, false, false, 0, Map.of()),
                life("visit-1", "change", 2, true, true, 1, Map.of()));

        var trend = DiagnosticMetrics.analyze(List.of(), List.of(), life).lifeTrends().getFirst();

        assertThat(trend.totalCount()).isEqualTo(1);
        assertThat(trend.recentCount()).isEqualTo(1);
        assertThat(trend.points().getFirst().independentScore()).isEqualTo(0.0);
        assertThat(trend.points().getFirst().supportedScore()).isEqualTo(100.0);
    }

    @Test
    void analyzeKeepsTeachAndLifeDomainsSeparateFromHomeDomains() {
        var home = List.of(new DiagnosticReportDtos.HomeEvidence("session-1", "money-count", START,
                List.of(drill("money-count", "money-count:0", 0, 1, true, 1000, 0))));
        var teach = List.of(new DiagnosticReportDtos.TeachEvidence(
                "conversation-1", "money-count", "explain", "taught", "H0", "L2", Map.of("slot", true), START));
        var life = List.of(life("visit-1", "calculate", 1, true, false, 0, Map.of()));

        var analysis = DiagnosticMetrics.analyze(home, teach, life);

        assertThat(analysis.homeDrillTrends()).extracting(DomainTrend::domainId).containsExactly("money-count");
        assertThat(analysis.teachTrends()).extracting(DomainTrend::domainId).containsExactly("money-count");
        assertThat(analysis.lifeTrends()).extracting(DomainTrend::domainId).containsExactly("calculate");
    }

    private static AttemptEvidence drill(
            String domainId,
            String itemId,
            Integer questionIndex,
            int attemptNo,
            boolean correct,
            Integer elapsedMs,
            int minuteOffset) {
        return new AttemptEvidence(
                domainId,
                itemId,
                questionIndex,
                attemptNo,
                correct,
                elapsedMs,
                START.plusMinutes(minuteOffset),
                Map.of());
    }

    private static DiagnosticReportDtos.LifeEvidence life(
            String visitId,
            String domainId,
            int attemptNo,
            boolean correct,
            boolean scaffoldUsed,
            int minuteOffset,
            Map<String, Object> payload) {
        return new DiagnosticReportDtos.LifeEvidence(
                visitId, domainId, attemptNo, correct, scaffoldUsed, START.plusMinutes(minuteOffset), payload);
    }

    private static DiagnosticReportDtos.HomeEvidence homeWithFirstTryAccuracy(
            String sessionId,
            int firstTryCorrectCount,
            OffsetDateTime completedAt) {
        var attempts = java.util.stream.IntStream.range(0, 2000)
                .mapToObj(index -> new AttemptEvidence(
                        "money-count",
                        "money-count:" + index,
                        index,
                        1,
                        index < firstTryCorrectCount,
                        1000,
                        completedAt,
                        Map.of()))
                .toList();
        return new DiagnosticReportDtos.HomeEvidence(sessionId, "money-count", completedAt, attempts);
    }

    private static DiagnosticReportDtos.HomeEvidence homeWithAttempt(
            String sessionId,
            boolean correct,
            Map<String, Object> answerMeta,
            int dayOffset) {
        return new DiagnosticReportDtos.HomeEvidence(
                sessionId,
                "money-count",
                START.plusDays(dayOffset),
                List.of(new AttemptEvidence(
                        "money-count",
                        "money-count:0",
                        0,
                        1,
                        correct,
                        1000,
                        START.plusDays(dayOffset),
                        answerMeta)));
    }

    private static List<TrendPoint> points(double... scores) {
        return java.util.stream.IntStream.range(0, scores.length)
                .mapToObj(index -> new TrendPoint(
                        "evidence-" + index,
                        "money-count",
                        START.plusDays(index),
                        scores[index],
                        scores[index],
                        false))
                .toList();
    }
}
