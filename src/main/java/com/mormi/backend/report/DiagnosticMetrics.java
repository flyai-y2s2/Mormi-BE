package com.mormi.backend.report;

import static com.mormi.backend.report.DiagnosticReportDtos.FactCategory.CONCEPT;
import static com.mormi.backend.report.DiagnosticReportDtos.FactCategory.EXPLANATION;
import static com.mormi.backend.report.DiagnosticReportDtos.FactCategory.LIFE;
import static com.mormi.backend.report.DiagnosticReportDtos.StatusLabel.DEVELOPING;
import static com.mormi.backend.report.DiagnosticReportDtos.StatusLabel.OBSERVING;
import static com.mormi.backend.report.DiagnosticReportDtos.StatusLabel.STABLE;
import static com.mormi.backend.report.DiagnosticReportDtos.StatusLabel.SUPPORT_NEEDED;

import com.mormi.backend.report.DiagnosticReportDtos.AttemptEvidence;
import com.mormi.backend.report.DiagnosticReportDtos.DiagnosticAnalysis;
import com.mormi.backend.report.DiagnosticReportDtos.DomainStatus;
import com.mormi.backend.report.DiagnosticReportDtos.DomainTrend;
import com.mormi.backend.report.DiagnosticReportDtos.DrillMetric;
import com.mormi.backend.report.DiagnosticReportDtos.HomeEvidence;
import com.mormi.backend.report.DiagnosticReportDtos.LifeEvidence;
import com.mormi.backend.report.DiagnosticReportDtos.ReportFact;
import com.mormi.backend.report.DiagnosticReportDtos.StatusLabel;
import com.mormi.backend.report.DiagnosticReportDtos.TeachEvidence;
import com.mormi.backend.report.DiagnosticReportDtos.TrendPoint;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Pure, deterministic calculations for normalized diagnostic-report evidence. */
final class DiagnosticMetrics {

    private static final int MAX_RECENT_RECORDS = 5;
    private static final int MIN_RECENT_RECORDS = 3;
    private static final int MIN_HISTORICAL_RECORDS = 2;
    private static final double STABLE_THRESHOLD = 80.0;
    private static final double DEVELOPING_THRESHOLD = 50.0;

    /**
     * The existing persisted {@code answer_meta.misconception_tag} key accepted as explicit bottleneck
     * evidence. No value is derived from an answer, response, score, or free-text field when it is absent.
     */
    private static final List<String> BOTTLENECK_METADATA_KEYS =
            List.of("misconception_tag");

    private DiagnosticMetrics() {
    }

    static DrillMetric drill(List<AttemptEvidence> attempts) {
        List<AttemptEvidence> ordered = attempts.stream()
                .filter(Objects::nonNull)
                .sorted(attemptOrder())
                .toList();
        Map<String, List<AttemptEvidence>> attemptsByQuestion = ordered.stream()
                .collect(Collectors.groupingBy(
                        DiagnosticMetrics::questionKey,
                        LinkedHashMap::new,
                        Collectors.toList()));

        int firstTryCorrect = 0;
        int incorrectQuestions = 0;
        int correctedQuestions = 0;
        for (List<AttemptEvidence> questionAttempts : attemptsByQuestion.values()) {
            if (questionAttempts.getFirst().correct()) {
                firstTryCorrect++;
            }
            if (questionAttempts.stream().anyMatch(attempt -> !attempt.correct())) {
                incorrectQuestions++;
                if (questionAttempts.getLast().correct()) {
                    correctedQuestions++;
                }
            }
        }

        List<Integer> elapsed = ordered.stream()
                .map(AttemptEvidence::elapsedMs)
                .filter(Objects::nonNull)
                .sorted()
                .toList();
        int incorrectAttempts = (int) ordered.stream().filter(attempt -> !attempt.correct()).count();

        return new DrillMetric(
                attemptsByQuestion.size(),
                ordered.size(),
                firstTryCorrect,
                incorrectAttempts,
                correctedQuestions,
                incorrectQuestions,
                percent(firstTryCorrect, attemptsByQuestion.size()),
                percent(incorrectAttempts, ordered.size()),
                percent(correctedQuestions, incorrectQuestions),
                average(elapsed),
                median(elapsed));
    }

    static List<TrendPoint> markRecent(List<TrendPoint> chronological) {
        int recentStart = Math.max(0, chronological.size() - MAX_RECENT_RECORDS);
        List<TrendPoint> marked = new ArrayList<>(chronological.size());
        for (int index = 0; index < chronological.size(); index++) {
            TrendPoint point = chronological.get(index);
            marked.add(new TrendPoint(
                    point.evidenceId(),
                    point.label(),
                    point.occurredAt(),
                    point.independentScore(),
                    point.supportedScore(),
                    point.attemptCount(),
                    point.questionCount(),
                    point.expressionLevel(),
                    index >= recentStart));
        }
        return List.copyOf(marked);
    }

    static StatusLabel status(List<TrendPoint> chronological) {
        return status(chronological, Map.of());
    }

    static StatusLabel status(List<TrendPoint> chronological, Map<String, String> bottleneckByEvidenceId) {
        List<TrendPoint> recent = recentPoints(chronological);
        if (recent.size() < MIN_RECENT_RECORDS) {
            return OBSERVING;
        }

        double independentAverage = rawAverage(recent, TrendPoint::independentScore);
        if (independentAverage >= STABLE_THRESHOLD) {
            return STABLE;
        }
        if (independentAverage >= DEVELOPING_THRESHOLD) {
            return DEVELOPING;
        }
        return hasRepeatedBottleneck(recent, bottleneckByEvidenceId) ? SUPPORT_NEEDED : OBSERVING;
    }

    static String direction(List<TrendPoint> chronological) {
        List<TrendPoint> recent = recentPoints(chronological);
        int pastEnd = chronological.size() - recent.size();
        if (recent.size() < MIN_RECENT_RECORDS || pastEnd < MIN_HISTORICAL_RECORDS) {
            return "INSUFFICIENT_HISTORY";
        }

        double recentAverage = rawAverage(recent, TrendPoint::independentScore);
        double pastAverage = rawAverage(chronological.subList(0, pastEnd), TrendPoint::independentScore);
        if (recentAverage > pastAverage) {
            return "IMPROVING";
        }
        if (recentAverage < pastAverage) {
            return "DECLINING";
        }
        return "MAINTAINING";
    }

    static DiagnosticAnalysis analyze(
            List<HomeEvidence> home,
            List<TeachEvidence> teach,
            List<LifeEvidence> life) {
        List<DomainTrend> homeTrends = homeDrillTrends(home);
        List<DomainTrend> teachTrends = teachTrends(teach);
        List<DomainTrend> lifeTrends = lifeTrends(life);
        List<DomainStatus> statuses = new ArrayList<>();
        List<ReportFact> facts = new ArrayList<>();
        appendStatusesAndFacts(homeTrends, CONCEPT, homeBottlenecks(home), statuses, facts);
        appendStatusesAndFacts(teachTrends, EXPLANATION, teachBottlenecks(teach), statuses, facts);
        appendStatusesAndFacts(lifeTrends, LIFE, lifeBottlenecks(life), statuses, facts);

        Map<String, DrillMetric> drillMetrics = cumulativeHomeDrillMetrics(home);

        return new DiagnosticAnalysis(
                homeTrends,
                teachTrends,
                lifeTrends,
                List.copyOf(statuses),
                java.util.Collections.unmodifiableMap(new LinkedHashMap<>(drillMetrics)),
                List.copyOf(facts));
    }

    private static List<DomainTrend> homeDrillTrends(List<HomeEvidence> home) {
        return safeList(home).stream()
                .filter(Objects::nonNull)
                .collect(Collectors.groupingBy(HomeEvidence::domainId, LinkedHashMap::new, Collectors.toList()))
                .entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> trend(
                        entry.getKey(),
                        "drill",
                        entry.getValue().stream()
                                .map(evidence -> homePoint(evidence, drill(safeList(evidence.attempts()))))
                                .toList()))
                .toList();
    }

    private static List<DomainTrend> teachTrends(List<TeachEvidence> teach) {
        return groupedTrends(
                teach,
                TeachEvidence::domainId,
                "teach",
                evidence -> new TrendPoint(
                        evidence.conversationId(),
                        "teach",
                        evidence.occurredAt(),
                        independentTeachScore(evidence),
                        supportedTeachScore(evidence),
                        null,
                        null,
                        evidence.expressionLevel(),
                        false));
    }

    private static List<DomainTrend> lifeTrends(List<LifeEvidence> life) {
        return safeList(life).stream()
                .filter(Objects::nonNull)
                .collect(Collectors.groupingBy(LifeEvidence::domainId, LinkedHashMap::new, Collectors.toList()))
                .entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> trend(
                        entry.getKey(),
                        "life",
                        entry.getValue().stream()
                                .collect(Collectors.groupingBy(
                                        LifeEvidence::visitPublicId, LinkedHashMap::new, Collectors.toList()))
                                .values().stream()
                                .map(DiagnosticMetrics::lifePoint)
                                .toList()))
                .toList();
    }

    private static <T> List<DomainTrend> groupedTrends(
            List<T> evidence,
            Function<T, String> domainId,
            String label,
            Function<T, TrendPoint> point) {
        return safeList(evidence).stream()
                .filter(Objects::nonNull)
                .collect(Collectors.groupingBy(domainId, LinkedHashMap::new, Collectors.toList()))
                .entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> trend(entry.getKey(), label, entry.getValue().stream().map(point).toList()))
                .toList();
    }

    private static TrendPoint homePoint(HomeEvidence evidence, DrillMetric metric) {
        double rawFirstTryAccuracy = rawPercent(metric.firstTryCorrectCount(), metric.questionCount());
        double rawSupportedCompletion = 100.0 - rawPercent(metric.incorrectAttemptCount(), metric.attemptCount());
        AttemptsToCorrect attemptsToCorrect = attemptsToCorrect(safeList(evidence.attempts()));
        return new TrendPoint(
                evidence.sessionPublicId(),
                "drill",
                evidence.completedAt(),
                rawFirstTryAccuracy,
                rawSupportedCompletion,
                attemptsToCorrect.attemptCount(),
                attemptsToCorrect.questionCount(),
                null,
                false);
    }

    private static TrendPoint lifePoint(List<LifeEvidence> visitAttempts) {
        List<LifeEvidence> ordered = visitAttempts.stream()
                .sorted(Comparator.comparing(LifeEvidence::occurredAt, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparingInt(LifeEvidence::attemptNo))
                .toList();
        LifeEvidence first = ordered.getFirst();
        boolean completed = ordered.stream().anyMatch(LifeEvidence::correct);
        int attemptsToCorrect = java.util.stream.IntStream.range(0, ordered.size())
                .filter(index -> ordered.get(index).correct())
                .findFirst()
                .orElse(-1) + 1;
        return new TrendPoint(
                first.visitPublicId(),
                "life",
                first.occurredAt(),
                first.correct() && !first.scaffoldUsed() ? 100.0 : 0.0,
                completed ? 100.0 : 0.0,
                completed ? attemptsToCorrect : null,
                completed ? 1 : null,
                null,
                false);
    }

    private static AttemptsToCorrect attemptsToCorrect(List<AttemptEvidence> attempts) {
        Map<String, List<AttemptEvidence>> byQuestion = safeList(attempts).stream()
                .filter(Objects::nonNull)
                .sorted(attemptOrder())
                .collect(Collectors.groupingBy(
                        DiagnosticMetrics::questionKey,
                        LinkedHashMap::new,
                        Collectors.toList()));
        int attemptCount = 0;
        int questionCount = 0;
        for (List<AttemptEvidence> questionAttempts : byQuestion.values()) {
            for (int index = 0; index < questionAttempts.size(); index++) {
                if (questionAttempts.get(index).correct()) {
                    attemptCount += index + 1;
                    questionCount++;
                    break;
                }
            }
        }
        return new AttemptsToCorrect(attemptCount, questionCount);
    }

    private record AttemptsToCorrect(int attemptCount, int questionCount) {
    }

    private static Map<String, DrillMetric> cumulativeHomeDrillMetrics(List<HomeEvidence> home) {
        return safeList(home).stream()
                .filter(Objects::nonNull)
                .collect(Collectors.groupingBy(HomeEvidence::domainId, LinkedHashMap::new, Collectors.toList()))
                .entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> cumulativeDrillMetric(entry.getValue()),
                        (left, right) -> left,
                        LinkedHashMap::new));
    }

    private static DrillMetric cumulativeDrillMetric(List<HomeEvidence> sessions) {
        List<DrillMetric> perSession = sessions.stream()
                .map(session -> drill(safeList(session.attempts())))
                .toList();
        int questionCount = perSession.stream().mapToInt(DrillMetric::questionCount).sum();
        int attemptCount = perSession.stream().mapToInt(DrillMetric::attemptCount).sum();
        int firstTryCorrectCount = perSession.stream().mapToInt(DrillMetric::firstTryCorrectCount).sum();
        int incorrectAttemptCount = perSession.stream().mapToInt(DrillMetric::incorrectAttemptCount).sum();
        int correctedQuestionCount = perSession.stream().mapToInt(DrillMetric::correctedQuestionCount).sum();
        int incorrectQuestionCount = perSession.stream().mapToInt(DrillMetric::incorrectQuestionCount).sum();
        List<Integer> elapsed = sessions.stream()
                .flatMap(session -> safeList(session.attempts()).stream())
                .map(AttemptEvidence::elapsedMs)
                .filter(Objects::nonNull)
                .sorted()
                .toList();
        return new DrillMetric(
                questionCount,
                attemptCount,
                firstTryCorrectCount,
                incorrectAttemptCount,
                correctedQuestionCount,
                incorrectQuestionCount,
                percent(firstTryCorrectCount, questionCount),
                percent(incorrectAttemptCount, attemptCount),
                percent(correctedQuestionCount, incorrectQuestionCount),
                average(elapsed),
                median(elapsed));
    }

    private static Map<String, String> homeBottlenecks(List<HomeEvidence> home) {
        Map<String, String> bottlenecks = new LinkedHashMap<>();
        for (HomeEvidence evidence : safeList(home)) {
            if (evidence == null) {
                continue;
            }
            explicitCommonBottleneck(safeList(evidence.attempts()).stream()
                    .map(AttemptEvidence::answerMeta)
                    .toList()).ifPresent(key -> bottlenecks.put(evidenceKey(evidence.domainId(), evidence.sessionPublicId()), key));
        }
        return bottlenecks;
    }

    private static Map<String, String> teachBottlenecks(List<TeachEvidence> teach) {
        Map<String, String> bottlenecks = new LinkedHashMap<>();
        for (TeachEvidence evidence : safeList(teach)) {
            if (evidence == null) {
                continue;
            }
            String bottleneck = explicitBottleneck(evidence.verifiedSlots());
            if (bottleneck != null) {
                bottlenecks.put(evidenceKey(evidence.domainId(), evidence.conversationId()), bottleneck);
            }
        }
        return bottlenecks;
    }

    private static Map<String, String> lifeBottlenecks(List<LifeEvidence> life) {
        Map<String, String> bottlenecks = new LinkedHashMap<>();
        safeList(life).stream()
                .filter(Objects::nonNull)
                .collect(Collectors.groupingBy(
                        evidence -> evidenceKey(evidence.domainId(), evidence.visitPublicId()),
                        LinkedHashMap::new,
                        Collectors.toList()))
                .forEach((visitKey, attempts) -> explicitCommonBottleneck(attempts.stream()
                                .map(LifeEvidence::payload)
                                .toList())
                        .ifPresent(key -> bottlenecks.put(visitKey, key)));
        return bottlenecks;
    }

    private static java.util.Optional<String> explicitCommonBottleneck(List<Map<String, Object>> metadata) {
        Set<String> keys = metadata.stream()
                .map(DiagnosticMetrics::explicitBottleneck)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(HashSet::new));
        return keys.size() == 1 ? java.util.Optional.of(keys.iterator().next()) : java.util.Optional.empty();
    }

    private static String explicitBottleneck(Map<String, Object> metadata) {
        Map<String, Object> values = safeMap(metadata);
        for (String key : BOTTLENECK_METADATA_KEYS) {
            Object value = values.get(key);
            if (value instanceof String text && !text.isBlank()) {
                return text.trim();
            }
        }
        return null;
    }

    private static boolean hasRepeatedBottleneck(List<TrendPoint> recent, Map<String, String> bottleneckByEvidenceId) {
        Map<String, Long> counts = recent.stream()
                .map(TrendPoint::evidenceId)
                .map(bottleneckByEvidenceId::get)
                .filter(value -> value != null && !value.isBlank())
                .collect(Collectors.groupingBy(value -> value, Collectors.counting()));
        return counts.values().stream().anyMatch(count -> count >= 2);
    }

    private static Map<String, String> bottlenecksFor(DomainTrend trend, Map<String, String> allBottlenecks) {
        return trend.points().stream()
                .map(TrendPoint::evidenceId)
                .filter(evidenceId -> allBottlenecks.containsKey(evidenceKey(trend.domainId(), evidenceId)))
                .collect(Collectors.toMap(
                        Function.identity(),
                        evidenceId -> allBottlenecks.get(evidenceKey(trend.domainId(), evidenceId)),
                        (left, right) -> left,
                        LinkedHashMap::new));
    }

    private static String evidenceKey(String domainId, String evidenceId) {
        return domainId + "\u0000" + evidenceId;
    }

    private static DomainTrend trend(String domainId, String label, List<TrendPoint> points) {
        List<TrendPoint> chronological = points.stream()
                .sorted(Comparator.comparing(TrendPoint::occurredAt, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(TrendPoint::evidenceId, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        List<TrendPoint> marked = markRecent(chronological);
        return new DomainTrend(domainId, label, marked, marked.size(), (int) marked.stream().filter(TrendPoint::recent).count());
    }

    private static void appendStatusesAndFacts(
            List<DomainTrend> trends,
            DiagnosticReportDtos.FactCategory category,
            Map<String, String> bottleneckByEvidenceId,
            List<DomainStatus> statuses,
            List<ReportFact> facts) {
        for (DomainTrend trend : trends) {
            StatusLabel label = status(trend.points(), bottlenecksFor(trend, bottleneckByEvidenceId));
            String direction = direction(trend.points());
            statuses.add(new DomainStatus(
                    trend.domainId(), trend.label(), label, direction, trend.totalCount(), trend.recentCount()));
            facts.add(new ReportFact(
                    trend.label() + ":" + trend.domainId(),
                    category,
                    trend.domainId() + " recent independent score is "
                            + display(rawAverage(recentPoints(trend.points()), TrendPoint::independentScore))
                            + "% (" + label + ")."));
        }
    }

    private static double independentTeachScore(TeachEvidence evidence) {
        return "H0".equalsIgnoreCase(evidence.maxHint()) && hasVerifiedConceptSlot(evidence.verifiedSlots()) ? 100.0 : 0.0;
    }

    private static double supportedTeachScore(TeachEvidence evidence) {
        return ("taught".equalsIgnoreCase(evidence.outcome()) || "supported".equalsIgnoreCase(evidence.outcome()))
                        && hasVerifiedConceptSlot(evidence.verifiedSlots())
                ? 100.0
                : 0.0;
    }

    private static boolean hasVerifiedConceptSlot(Map<String, Object> verifiedSlots) {
        return safeMap(verifiedSlots).entrySet().stream()
                .anyMatch(entry -> !BOTTLENECK_METADATA_KEYS.contains(entry.getKey()) && entry.getValue() != null);
    }

    private static List<TrendPoint> recentPoints(List<TrendPoint> chronological) {
        return markRecent(chronological).stream().filter(TrendPoint::recent).toList();
    }

    private static Comparator<AttemptEvidence> attemptOrder() {
        return Comparator.comparing(AttemptEvidence::occurredAt, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparingInt(AttemptEvidence::attemptNo)
                .thenComparing(AttemptEvidence::itemId, Comparator.nullsLast(Comparator.naturalOrder()));
    }

    private static String questionKey(AttemptEvidence attempt) {
        return attempt.questionIndex() == null ? "item:" + attempt.itemId() : "question:" + attempt.questionIndex();
    }

    private static double percent(int numerator, int denominator) {
        return round(rawPercent(numerator, denominator));
    }

    private static double rawPercent(int numerator, int denominator) {
        return denominator == 0 ? 0.0 : numerator * 100.0 / denominator;
    }

    private static double average(List<Integer> values) {
        return values.isEmpty() ? 0.0 : round(values.stream().mapToDouble(Integer::doubleValue).average().orElse(0.0));
    }

    private static double median(List<Integer> values) {
        if (values.isEmpty()) {
            return 0.0;
        }
        int middle = values.size() / 2;
        double median = values.size() % 2 == 0
                ? (values.get(middle - 1) + values.get(middle)) / 2.0
                : values.get(middle);
        return round(median);
    }

    private static double rawAverage(List<TrendPoint> points, Function<TrendPoint, Double> score) {
        return points.isEmpty()
                ? 0.0
                : points.stream().mapToDouble(point -> score.apply(point)).average().orElse(0.0);
    }

    private static String display(double value) {
        return BigDecimal.valueOf(round(value)).stripTrailingZeros().toPlainString();
    }

    private static double round(double value) {
        return BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP).doubleValue();
    }

    private static <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }

    private static Map<String, Object> safeMap(Map<String, Object> values) {
        return values == null ? Map.of() : values;
    }
}
