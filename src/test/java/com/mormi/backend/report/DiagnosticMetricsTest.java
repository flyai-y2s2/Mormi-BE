package com.mormi.backend.report;

import static com.mormi.backend.report.DiagnosticReportDtos.StatusLabel.DEVELOPING;
import static com.mormi.backend.report.DiagnosticReportDtos.StatusLabel.OBSERVING;
import static com.mormi.backend.report.DiagnosticReportDtos.StatusLabel.STABLE;
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
        assertThat(DiagnosticMetrics.status(points(49.9, 49.9, 49.9)))
                .isEqualTo(DiagnosticReportDtos.StatusLabel.SUPPORT_NEEDED);
    }

    @Test
    void statusDoesNotCallADecliningRecentWindowStable() {
        assertThat(DiagnosticMetrics.status(points(100.0, 90.0, 70.0))).isEqualTo(OBSERVING);
    }

    @Test
    void directionRequiresTwoHistoricalRecordsBeforeComparingRecentWindow() {
        assertThat(DiagnosticMetrics.direction(points(30, 40, 90, 90, 90, 90, 90))).isEqualTo("IMPROVING");
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
