package com.mormi.backend.report;

import com.mormi.backend.common.ExpressionLevels;
import com.mormi.backend.common.ApiException;
import static com.mormi.backend.report.DiagnosticReportDtos.FactCategory.CONCEPT;
import static com.mormi.backend.report.DiagnosticReportDtos.FactCategory.EXPLANATION;
import static com.mormi.backend.report.DiagnosticReportDtos.FactCategory.IMPROVED;
import static com.mormi.backend.report.DiagnosticReportDtos.FactCategory.OBSERVE;
import static com.mormi.backend.report.DiagnosticReportDtos.Mode.HOME;
import static com.mormi.backend.report.DiagnosticReportDtos.Mode.LIFE;

import com.mormi.backend.cafe.CafeVisit;
import com.mormi.backend.cafe.CafeVisitRepository;
import com.mormi.backend.cafe.CafeVisitStage;
import com.mormi.backend.cafe.CafeVisitStageRepository;
import com.mormi.backend.curriculum.CurriculumCatalog;
import com.mormi.backend.dialogue.DialogueConversation;
import com.mormi.backend.dialogue.DialogueConversationRepository;
import com.mormi.backend.learner.Learner;
import com.mormi.backend.learner.LearnerService;
import com.mormi.backend.report.DiagnosticReportDtos.AiConversationEvidence;
import com.mormi.backend.report.DiagnosticReportDtos.AiLadderRecommendation;
import com.mormi.backend.report.DiagnosticReportDtos.AiNarrative;
import com.mormi.backend.report.DiagnosticReportDtos.AiReportEvidence;
import com.mormi.backend.report.DiagnosticReportDtos.AiSummary;
import com.mormi.backend.report.DiagnosticReportDtos.AiTurnEvidence;
import com.mormi.backend.report.DiagnosticReportDtos.AttemptEvidence;
import com.mormi.backend.report.DiagnosticReportDtos.CurrentSummary;
import com.mormi.backend.report.DiagnosticReportDtos.DataRange;
import com.mormi.backend.report.DiagnosticReportDtos.DiagnosticAnalysis;
import com.mormi.backend.report.DiagnosticReportDtos.DiagnosticReport;
import com.mormi.backend.report.DiagnosticReportDtos.DomainStatus;
import com.mormi.backend.report.DiagnosticReportDtos.DomainTrend;
import com.mormi.backend.report.DiagnosticReportDtos.EvidenceCounts;
import com.mormi.backend.report.DiagnosticReportDtos.EvidenceText;
import com.mormi.backend.report.DiagnosticReportDtos.FactCategory;
import com.mormi.backend.report.DiagnosticReportDtos.Highlight;
import com.mormi.backend.report.DiagnosticReportDtos.HomeEvidence;
import com.mormi.backend.report.DiagnosticReportDtos.LearnerHeader;
import com.mormi.backend.report.DiagnosticReportDtos.LadderApprovalResponse;
import com.mormi.backend.report.DiagnosticReportDtos.LadderRecommendation;
import com.mormi.backend.report.DiagnosticReportDtos.LifeEvidence;
import com.mormi.backend.report.DiagnosticReportDtos.ModeReport;
import com.mormi.backend.report.DiagnosticReportDtos.ReportFact;
import com.mormi.backend.report.DiagnosticReportDtos.ReportPeriod;
import com.mormi.backend.report.DiagnosticReportDtos.SpeechEvidence;
import com.mormi.backend.report.DiagnosticReportDtos.SpeechSample;
import com.mormi.backend.report.DiagnosticReportDtos.StatusLabel;
import com.mormi.backend.report.DiagnosticReportDtos.TeachEvidence;
import com.mormi.backend.report.DiagnosticReportDtos.TrendPoint;
import com.mormi.backend.session.Attempt;
import com.mormi.backend.session.AttemptRepository;
import com.mormi.backend.session.LearningSession;
import com.mormi.backend.session.LearningSessionRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Aggregates learner-owned records into a current, non-persisted diagnostic report. */
@Service
public class DiagnosticReportService {

    private static final Logger log = LoggerFactory.getLogger(DiagnosticReportService.class);
    private static final String ACTIVITY_DRILL = "drill";
    private static final String SPEECH_UNAVAILABLE = "비교 가능한 발화 근거가 부족합니다.";
    private static final Set<String> COMPLETION_OUTCOMES = Set.of("taught", "supported", "bright_exit");
    private static final Set<String> VERIFIED_RESPONSE_CATEGORIES =
            Set.of("correct_full", "correct_partial", "self_correction");
    private static final String CHILD_SPEECH_RESPONSE_TYPE = "text";
    private static final List<String> REQUIRED_LIFE_STAGES =
            List.of("queue", "menu", "calculate", "change");
    private static final Map<String, String> HOME_LABELS = CurriculumCatalog.SESSION_REPORT_LABELS;
    private static final Map<String, String> LIFE_LABELS = Map.of(
            "queue", "줄 서기",
            "menu", "메뉴 고르기",
            "calculate", "메뉴 값 계산하기",
            "change", "거스름돈 받기",
            "complete", "카페 완료");
    private static final Map<String, String> SCENARIO_DOMAINS = Map.of(
            "cafe_queue", "queue",
            "cafe_budget_menu", "menu",
            "cafe_menu_total", "calculate",
            "cafe_change", "change");
    private static final List<String> REPORT_DOMAINS = Stream.concat(
                    HOME_LABELS.keySet().stream(),
                    List.of("queue", "menu", "calculate", "change", "complete").stream())
            .toList();

    private final ReportAiClient aiClient;
    private final LearnerService learnerService;
    private final LearningSessionRepository sessionRepository;
    private final AttemptRepository attemptRepository;
    private final CafeVisitRepository cafeVisitRepository;
    private final CafeVisitStageRepository cafeVisitStageRepository;
    private final DialogueConversationRepository dialogueRepository;
    private final Clock reportClock;

    public DiagnosticReportService(
            ReportAiClient aiClient,
            LearnerService learnerService,
            LearningSessionRepository sessionRepository,
            AttemptRepository attemptRepository,
            CafeVisitRepository cafeVisitRepository,
            CafeVisitStageRepository cafeVisitStageRepository,
            DialogueConversationRepository dialogueRepository,
            Clock reportClock) {
        this.aiClient = aiClient;
        this.learnerService = learnerService;
        this.sessionRepository = sessionRepository;
        this.attemptRepository = attemptRepository;
        this.cafeVisitRepository = cafeVisitRepository;
        this.cafeVisitStageRepository = cafeVisitStageRepository;
        this.dialogueRepository = dialogueRepository;
        this.reportClock = reportClock;
    }

    @Transactional(readOnly = true)
    public DiagnosticReport current(long learnerId, LocalDate weekStart) {
        Learner learner = learnerService.require(learnerId);
        WeeklyReportPeriod period = WeeklyReportPeriod.resolve(
                weekStart,
                learner.getCreatedAt(),
                reportClock,
                availableReportWeeks(learnerId));
        RecordContext records = loadRecords(learnerId, true, period);
        boolean includeRaw = rawEvidencePermitted(learner);
        Optional<AiReportEvidence> aiEvidence = safeEvidence(learnerId, includeRaw);
        List<TeachEvidence> teach = normalizeTeach(records, aiEvidence.orElse(null), period);
        DiagnosticAnalysis analysis = DiagnosticMetrics.analyze(records.home(), teach, records.life());

        List<DomainTrend> homeTrends = new ArrayList<>();
        homeTrends.addAll(presentTrends(analysis.homeDrillTrends(), "drill"));
        List<DomainTrend> presentedTeachTrends = presentTrends(analysis.teachTrends(), "teach");
        homeTrends.addAll(presentedTeachTrends.stream()
                .filter(trend -> HOME_LABELS.containsKey(trend.domainId()))
                .toList());
        List<DomainTrend> lifeTrends = new ArrayList<>(presentTrends(analysis.lifeTrends(), "life"));
        lifeTrends.addAll(presentedTeachTrends.stream()
                .filter(trend -> LIFE_LABELS.containsKey(trend.domainId()))
                .toList());
        List<DomainStatus> statuses = presentStatuses(analysis.domainStatuses());
        List<ReportFact> facts = presentationFacts(analysis, records, aiEvidence.orElse(null), period);

        NarrativeResult narrative = narrative(learner.getAnalyticsId().toString(), facts);
        List<OffsetDateTime> occurrences = new ArrayList<>();
        records.sessions().values().stream()
                .map(LearningSession::getCompletedAt)
                .filter(Objects::nonNull)
                .forEach(occurrences::add);
        records.visits().values().stream()
                .map(CafeVisit::getCompletedAt)
                .filter(Objects::nonNull)
                .forEach(occurrences::add);
        occurrences.sort(Comparator.naturalOrder());

        int speechSamples = aiEvidence.map(evidence -> speechCandidates(records, evidence, null, period).size()).orElse(0);
        List<LadderRecommendation> ladderRecommendations = aiEvidence
                .map(evidence -> ladderRecommendations(records, evidence))
                .orElseGet(List::of);
        return new DiagnosticReport(
                new LearnerHeader(learnerId, learner.getDisplayName()),
                new ReportPeriod(
                        period.weekStart(),
                        period.weekEnd(),
                        WeeklyReportPeriod.REPORT_ZONE.getId(),
                        period.earliestWeekStart(),
                        period.latestWeekStart(),
                        period.availableWeekStarts()),
                new DataRange(
                        occurrences.isEmpty() ? null : occurrences.getFirst(),
                        occurrences.isEmpty() ? null : occurrences.getLast(),
                        records.sessions().size(),
                        records.visits().size()),
                narrative.currentSummary(),
                List.of(new ModeReport(HOME, List.copyOf(homeTrends)), new ModeReport(LIFE, List.copyOf(lifeTrends))),
                statuses,
                narrative.improvedPoint(),
                narrative.observePoint(),
                new EvidenceCounts(
                        records.sessions().size(),
                        records.home().stream().mapToInt(home -> home.attempts().size()).sum(),
                        teach.size(),
                        records.visits().size(),
                        speechSamples),
                narrative.fallback(),
                ladderRecommendations);
    }

    private List<LocalDate> availableReportWeeks(long learnerId) {
        return Stream.concat(
                        safeList(sessionRepository
                                .findCompletedAtByLearnerIdAndCurriculumSessionIdInOrderByCompletedAtAsc(
                                        learnerId, List.copyOf(HOME_LABELS.keySet())))
                                .stream(),
                        safeList(cafeVisitRepository.findCompletedAtByLearnerIdOrderByCompletedAtAsc(learnerId)).stream())
                .filter(Objects::nonNull)
                .map(completedAt -> completedAt.atZoneSameInstant(WeeklyReportPeriod.REPORT_ZONE)
                        .toLocalDate()
                        .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)))
                .distinct()
                .sorted()
                .toList();
    }

    public LadderApprovalResponse approveLadderRecommendation(
            long learnerId, String analysisId, int recommendationVersion) {
        AiLadderRecommendation recommendation = safeEvidence(learnerId, false)
                .stream()
                .flatMap(evidence -> safeList(evidence.ladderRecommendations()).stream())
                .filter(item -> item != null
                        && item.learnerId() == learnerId
                        && analysisId.equals(item.analysisId())
                        && item.recommendationVersion() == recommendationVersion)
                .findFirst()
                .orElseThrow(() -> ApiException.notFound("발화 사다리 분석을 찾을 수 없습니다."));
        if (!("UPGRADE".equals(recommendation.action())
                || "ADJUST_DOWN".equals(recommendation.action()))) {
            throw ApiException.conflict("ladder_not_applicable", "적용할 단계 변경이 없습니다.");
        }
        if (!aiClient.approveLadderAnalysis(analysisId, learnerId, recommendationVersion)) {
            throw ApiException.conflict("ladder_approval_failed", "단계 적용을 완료하지 못했습니다.");
        }
        return new LadderApprovalResponse(analysisId, "approved");
    }

    @Transactional(readOnly = true)
    public SpeechEvidence speechEvidence(long learnerId, String domainId, LocalDate weekStart) {
        Learner learner = learnerService.require(learnerId);
        if (!HOME_LABELS.containsKey(domainId) && !LIFE_LABELS.containsKey(domainId)) {
            return unavailableSpeech(domainId);
        }
        WeeklyReportPeriod period = WeeklyReportPeriod.resolve(weekStart, learner.getCreatedAt(), reportClock);
        RecordContext records = loadRecords(learnerId, false, period);
        Optional<AiReportEvidence> evidence = safeEvidence(learnerId, rawEvidencePermitted(learner));
        if (evidence.isEmpty()) {
            return unavailableSpeech(domainId);
        }

        List<SpeechCandidate> candidates = speechCandidates(records, evidence.orElseThrow(), domainId, period);
        Optional<SpeechPair> pair = comparablePair(candidates);
        if (pair.isPresent()) {
            SpeechPair selected = pair.orElseThrow();
            return new SpeechEvidence(
                    domainId,
                    true,
                    null,
                    selected.past().sample(),
                    selected.recent().sample(),
                    selected.verifiedElements(),
                    analyzedChangeSummary(
                            domainId,
                            selected.past().sample(),
                            selected.recent().sample()));
        }
        List<SpeechCandidate> fallback = fallbackSpeechCandidates(
                records, evidence.orElseThrow(), domainId, period);
        if (fallback.isEmpty()) {
            return unavailableSpeech(domainId);
        }
        SpeechSample recent = fallback.getLast().sample();
        SpeechSample past = fallback.size() > 1 ? fallback.getFirst().sample() : null;
        return new SpeechEvidence(
                domainId,
                true,
                null,
                past,
                recent,
                List.of(),
                past == null
                        ? "최근 발화 1건을 확인했습니다."
                        : analyzedChangeSummary(domainId, past, recent));
    }

    private List<LadderRecommendation> ladderRecommendations(
            RecordContext records, AiReportEvidence evidence) {
        Set<String> sessionPublicIds = records.sessions().values().stream()
                .map(LearningSession::getPublicId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        return safeList(evidence.ladderRecommendations()).stream()
                .filter(Objects::nonNull)
                .filter(item -> item.learnerId() == evidence.learnerId())
                .filter(item -> HOME_LABELS.containsKey(item.skillId()))
                .filter(item -> sessionPublicIds.contains(item.triggerSessionId()))
                .map(item -> new LadderRecommendation(
                        item.analysisId(),
                        item.learnerId(),
                        item.skillId(),
                        item.triggerSessionId(),
                        List.copyOf(safeList(item.sessionIds())),
                        ExpressionLevels.canonicalForRead(item.currentLevel()),
                        ExpressionLevels.canonicalForRead(item.recommendedLevel()),
                        item.action(),
                        item.currentAccuracy(),
                        item.evidenceCount(),
                        item.reasonCode(),
                        List.copyOf(safeList(item.recentPredictions())),
                        item.modelVersion(),
                        item.recommendationVersion(),
                        item.approved(),
                        item.analyzedAt()))
                .toList();
    }

    private RecordContext loadRecords(long learnerId, boolean loadMetricRows, WeeklyReportPeriod period) {
        Map<Long, LearningSession> sessions = safeList(sessionRepository
                        .findByLearnerIdAndCompletedAtGreaterThanEqualAndCompletedAtLessThanOrderByCompletedAtAsc(
                                learnerId, period.startInclusive(), period.endExclusive()))
                .stream()
                .filter(Objects::nonNull)
                .filter(session -> Objects.equals(session.getLearnerId(), learnerId))
                .filter(session -> session.getId() != null && session.getPublicId() != null)
                .filter(session -> session.getCompletedAt() != null)
                .filter(session -> isWithin(period, session.getCompletedAt()))
                .filter(session -> HOME_LABELS.containsKey(session.getCurriculumSessionId()))
                .collect(Collectors.toMap(
                        LearningSession::getId,
                        Function.identity(),
                        (first, ignored) -> first,
                        LinkedHashMap::new));
        List<Long> sessionIds = sessions.keySet().stream().sorted().toList();
        List<Attempt> attempts = loadMetricRows && !sessionIds.isEmpty()
                ? dedupeAttempts(attemptRepository.findByLearningSessionIdInOrderByCreatedAtAscIdAsc(sessionIds))
                : List.of();
        Map<Long, List<AttemptEvidence>> attemptsBySession = new LinkedHashMap<>();
        for (Attempt attempt : attempts) {
            LearningSession session = sessions.get(attempt.getLearningSessionId());
            if (session == null || !ACTIVITY_DRILL.equals(attempt.getActivity()) || !analyzableAttempt(attempt)) {
                continue;
            }
            attemptsBySession.computeIfAbsent(session.getId(), ignored -> new ArrayList<>()).add(
                    new AttemptEvidence(
                            session.getCurriculumSessionId(),
                            attempt.getItemId(),
                            attempt.getQuestionIndex(),
                            attempt.getAttemptNo(),
                            attempt.isCorrect(),
                            attempt.getElapsedMs(),
                            attempt.getCreatedAt(),
                            safeMap(attempt.getAnswerMeta())));
        }

        List<HomeEvidence> home = new ArrayList<>();
        for (LearningSession session : sessions.values()) {
            List<AttemptEvidence> sessionAttempts = attemptsBySession.getOrDefault(session.getId(), List.of());
            if (!loadMetricRows || !sessionAttempts.isEmpty()) {
                home.add(new HomeEvidence(
                        session.getPublicId(),
                        session.getCurriculumSessionId(),
                        session.getCompletedAt(),
                        sessionAttempts));
            }
        }
        home.sort(Comparator.comparing(HomeEvidence::completedAt).thenComparing(HomeEvidence::sessionPublicId));

        Map<Long, CafeVisit> visits = safeList(cafeVisitRepository
                        .findByLearnerIdAndCompletedAtGreaterThanEqualAndCompletedAtLessThanOrderByCompletedAtAsc(
                                learnerId, period.startInclusive(), period.endExclusive()))
                .stream()
                .filter(Objects::nonNull)
                .filter(visit -> Objects.equals(visit.getLearnerId(), learnerId))
                .filter(visit -> visit.getId() != null && visit.getPublicId() != null && visit.getCompletedAt() != null)
                .filter(visit -> isWithin(period, visit.getCompletedAt()))
                .collect(Collectors.toMap(
                        CafeVisit::getId,
                        Function.identity(),
                        (first, ignored) -> first,
                        LinkedHashMap::new));
        List<Long> visitIds = visits.keySet().stream().sorted().toList();
        List<CafeVisitStage> stageRows = loadMetricRows && !visitIds.isEmpty()
                ? dedupeStages(cafeVisitStageRepository.findByCafeVisitIdInOrderByCreatedAtAscIdAsc(visitIds))
                : List.of();
        List<CafeVisitStage> weekStageRows = stageRows.stream()
                .filter(stage -> isWithin(period, stage.getCreatedAt()))
                .toList();
        List<LifeEvidence> life = new ArrayList<>(weekStageRows.stream()
                .filter(stage -> visits.containsKey(stage.getCafeVisitId()))
                .filter(stage -> LIFE_LABELS.containsKey(stage.getStage()))
                .map(stage -> new LifeEvidence(
                        visits.get(stage.getCafeVisitId()).getPublicId(),
                        stage.getStage(),
                        stage.getAttemptNo(),
                        stage.isCorrect(),
                        scaffoldUsed(stage),
                        stage.getCreatedAt(),
                        safeMap(stage.getPayload())))
                .toList());
        if (loadMetricRows) {
            for (CafeVisit visit : visits.values()) {
                life.add(completionEvidence(visit, weekStageRows));
            }
        }

        Map<String, DialogueConversation> dialogues = safeList(
                        dialogueRepository.findByLearnerIdOrderByCreatedAtAsc(learnerId))
                .stream()
                .filter(Objects::nonNull)
                .filter(dialogue -> Objects.equals(dialogue.getLearnerId(), learnerId))
                .filter(dialogue -> dialogue.getConversationId() != null && !dialogue.getConversationId().isBlank())
                .filter(dialogue -> dialogueOwnedBySelectedRecord(dialogue, sessions, visits))
                .collect(Collectors.toMap(
                        DialogueConversation::getConversationId,
                        Function.identity(),
                        (first, ignored) -> first,
                        LinkedHashMap::new));
        return new RecordContext(sessions, visits, dialogues, List.copyOf(home), List.copyOf(life));
    }

    private boolean isWithin(WeeklyReportPeriod period, OffsetDateTime occurredAt) {
        return occurredAt != null
                && !occurredAt.isBefore(period.startInclusive())
                && occurredAt.isBefore(period.endExclusive());
    }

    private boolean scaffoldUsed(CafeVisitStage stage) {
        return stage.getAttemptNo() >= 900_000
                || Boolean.TRUE.equals(safeMap(stage.getPayload()).get("scaffold_used"));
    }

    private LifeEvidence completionEvidence(CafeVisit visit, List<CafeVisitStage> stageRows) {
        Map<String, CafeVisitStage> firstByStage = safeList(stageRows).stream()
                .filter(Objects::nonNull)
                .filter(stage -> Objects.equals(stage.getCafeVisitId(), visit.getId()))
                .filter(stage -> REQUIRED_LIFE_STAGES.contains(stage.getStage()))
                .sorted(Comparator
                        .comparing(CafeVisitStage::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparingInt(CafeVisitStage::getAttemptNo)
                        .thenComparing(CafeVisitStage::getId, Comparator.nullsLast(Comparator.naturalOrder())))
                .collect(Collectors.toMap(
                        CafeVisitStage::getStage,
                        Function.identity(),
                        (first, ignored) -> first,
                        LinkedHashMap::new));
        boolean independent = REQUIRED_LIFE_STAGES.stream()
                .map(firstByStage::get)
                .allMatch(stage -> stage != null && stage.isCorrect() && !scaffoldUsed(stage));
        return new LifeEvidence(
                visit.getPublicId(),
                "complete",
                1,
                true,
                !independent,
                visit.getCompletedAt(),
                Map.of());
    }

    private boolean dialogueOwnedBySelectedRecord(
            DialogueConversation dialogue,
            Map<Long, LearningSession> sessions,
            Map<Long, CafeVisit> visits) {
        boolean homeOwned = dialogue.getLearningSessionId() != null
                && dialogue.getCafeVisitId() == null
                && sessions.containsKey(dialogue.getLearningSessionId());
        boolean lifeOwned = dialogue.getCafeVisitId() != null
                && dialogue.getLearningSessionId() == null
                && visits.containsKey(dialogue.getCafeVisitId())
                && SCENARIO_DOMAINS.containsKey(dialogue.getScenarioId());
        return homeOwned || lifeOwned;
    }

    private List<TeachEvidence> normalizeTeach(
            RecordContext records, AiReportEvidence evidence, WeeklyReportPeriod period) {
        if (!validAiEnvelope(evidence)) {
            return List.of();
        }
        Map<String, AiConversationEvidence> unique = uniqueAiConversations(evidence);
        List<TeachEvidence> teach = new ArrayList<>();
        for (Map.Entry<String, AiConversationEvidence> entry : unique.entrySet()) {
            DialogueConversation owner = records.dialogues().get(entry.getKey());
            AiConversationEvidence conversation = entry.getValue();
            if (owner == null || owner.getLearningSessionId() == null || owner.getCafeVisitId() != null) {
                continue;
            }
            String domainId = ownedDomain(records, owner, conversation);
            if (domainId == null || !completedEvidence(conversation) || !isWithin(period, conversation.updatedAt())) {
                continue;
            }
            AiTurnEvidence representative = safeList(conversation.turns()).stream()
                    .filter(Objects::nonNull)
                    .filter(turn -> turn.taskId() != null && !turn.taskId().isBlank())
                    .filter(turn -> isWithin(period, turn.createdAt()))
                    .sorted(Comparator.comparing(
                            AiTurnEvidence::createdAt, Comparator.nullsLast(Comparator.naturalOrder())))
                    .reduce((first, second) -> second)
                    .orElse(null);
            if (representative == null) {
                continue;
            }
            teach.add(new TeachEvidence(
                    conversation.conversationId(),
                    domainId,
                    representative.taskId(),
                    conversation.completionOutcome(),
                    conversation.taskMaxHint(),
                    ExpressionLevels.canonicalForRead(representative.expressionLevel()),
                    safeMap(conversation.verifiedSlots()),
                    conversation.updatedAt()));
        }
        return teach.stream()
                .sorted(Comparator.comparing(TeachEvidence::occurredAt, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(TeachEvidence::conversationId))
                .toList();
    }

    private Optional<AiReportEvidence> safeEvidence(long learnerId, boolean includeRaw) {
        try {
            return aiClient.evidence(learnerId, includeRaw)
                    .filter(evidence -> validAiEnvelope(evidence) && evidence.learnerId() == learnerId);
        } catch (RuntimeException error) {
            log.warn("Mormi-AI report evidence fallback type={}", error.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private boolean validAiEnvelope(AiReportEvidence evidence) {
        return evidence != null && evidence.learnerId() > 0;
    }

    private Map<String, AiConversationEvidence> uniqueAiConversations(AiReportEvidence evidence) {
        if (evidence == null) {
            return Map.of();
        }
        return safeList(evidence.conversations()).stream()
                .filter(Objects::nonNull)
                .filter(conversation -> conversation.conversationId() != null
                        && !conversation.conversationId().isBlank())
                .collect(Collectors.toMap(
                        AiConversationEvidence::conversationId,
                        Function.identity(),
                        (first, ignored) -> first,
                        LinkedHashMap::new));
    }

    private String ownedDomain(
            RecordContext records,
            DialogueConversation owner,
            AiConversationEvidence conversation) {
        if (owner == null || conversation == null || !Objects.equals(
                owner.getConversationId(), conversation.conversationId())) {
            return null;
        }
        if (owner.getLearningSessionId() != null && owner.getCafeVisitId() == null) {
            LearningSession session = records.sessions().get(owner.getLearningSessionId());
            if (session == null
                    || !Objects.equals(session.getPublicId(), conversation.learningSessionId())
                    || !Objects.equals(owner.getScenarioId(), conversation.scenarioId())) {
                return null;
            }
            return HOME_LABELS.containsKey(session.getCurriculumSessionId())
                    ? session.getCurriculumSessionId()
                    : null;
        }
        if (owner.getCafeVisitId() != null && owner.getLearningSessionId() == null) {
            CafeVisit visit = records.visits().get(owner.getCafeVisitId());
            if (visit == null
                    || conversation.learningSessionId() != null
                    || !Objects.equals(owner.getScenarioId(), conversation.scenarioId())) {
                return null;
            }
            return SCENARIO_DOMAINS.get(owner.getScenarioId());
        }
        return null;
    }

    private boolean completedEvidence(AiConversationEvidence conversation) {
        return "completed".equalsIgnoreCase(conversation.status())
                && conversation.completionOutcome() != null
                && COMPLETION_OUTCOMES.contains(conversation.completionOutcome().toLowerCase())
                && !safeMap(conversation.verifiedSlots()).isEmpty();
    }

    private List<SpeechCandidate> speechCandidates(
            RecordContext records,
            AiReportEvidence evidence,
            String requestedDomain,
            WeeklyReportPeriod period) {
        if (!validAiEnvelope(evidence)) {
            return List.of();
        }
        List<SpeechCandidate> candidates = new ArrayList<>();
        for (AiConversationEvidence conversation : uniqueAiConversations(evidence).values()) {
            DialogueConversation owner = records.dialogues().get(conversation.conversationId());
            String domainId = ownedDomain(records, owner, conversation);
            if (domainId == null
                    || (requestedDomain != null && !requestedDomain.equals(domainId))
                    || !completedEvidence(conversation)
                    || !isWithin(period, conversation.updatedAt())) {
                continue;
            }
            Set<String> seenTurnIds = new HashSet<>();
            List<AiTurnEvidence> orderedTurns = safeList(conversation.turns()).stream()
                    .filter(Objects::nonNull)
                    .filter(turn -> turn.turnId() != null && seenTurnIds.add(turn.turnId()))
                    .filter(turn -> isWithin(period, turn.createdAt()))
                    .sorted(Comparator
                            .comparing(AiTurnEvidence::createdAt, Comparator.nullsLast(Comparator.naturalOrder()))
                            .thenComparing(AiTurnEvidence::turnId))
                    .toList();
            for (int index = 0; index + 1 < orderedTurns.size(); index++) {
                AiTurnEvidence turn = orderedTurns.get(index);
                if (turn == null
                        || turn.taskId() == null
                        || turn.taskId().isBlank()
                        || turn.response() == null
                        || turn.response().isBlank()
                        || turn.createdAt() == null
                        || !CHILD_SPEECH_RESPONSE_TYPE.equals(normalize(turn.responseType()))
                        || !VERIFIED_RESPONSE_CATEGORIES.contains(normalize(turn.responseCategory()))) {
                    continue;
                }
                Optional<Map<String, Object>> preResponse = verifiedTurnSlots(turn);
                Optional<Map<String, Object>> postResponse = verifiedTurnSlots(orderedTurns.get(index + 1));
                if (preResponse.isEmpty() || postResponse.isEmpty()) {
                    continue;
                }
                Set<String> newlyVerified = postResponse.orElseThrow().entrySet().stream()
                        .filter(entry -> entry.getKey() != null
                                && !entry.getKey().isBlank()
                                && entry.getValue() != null
                                && !preResponse.orElseThrow().containsKey(entry.getKey()))
                        .map(Map.Entry::getKey)
                        .collect(Collectors.toCollection(LinkedHashSet::new));
                if (newlyVerified.isEmpty()) {
                    continue;
                }
                candidates.add(new SpeechCandidate(
                        domainId,
                        turn.taskId(),
                        new SpeechSample(
                                "conversation:" + conversation.conversationId() + ":turn:" + turn.turnId(),
                                turn.response(),
                                turn.hintLevel(),
                                ExpressionLevels.canonicalForRead(turn.expressionLevel()),
                                turn.createdAt()),
                        Set.copyOf(newlyVerified)));
            }
        }
        return candidates.stream()
                .sorted(speechCandidateOrder())
                .toList();
    }

    private List<SpeechCandidate> fallbackSpeechCandidates(
            RecordContext records,
            AiReportEvidence evidence,
            String requestedDomain,
            WeeklyReportPeriod period) {
        if (!validAiEnvelope(evidence)) {
            return List.of();
        }
        List<SpeechCandidate> candidates = new ArrayList<>();
        for (AiConversationEvidence conversation : uniqueAiConversations(evidence).values()) {
            DialogueConversation owner = records.dialogues().get(conversation.conversationId());
            String domainId = ownedDomain(records, owner, conversation);
            if (domainId == null
                    || (requestedDomain != null && !requestedDomain.equals(domainId))
                    || !completedFallbackEvidence(conversation)
                    || !isWithin(period, conversation.updatedAt())) {
                continue;
            }
            Set<String> seenTurnIds = new HashSet<>();
            for (AiTurnEvidence turn : safeList(conversation.turns()).stream()
                    .filter(Objects::nonNull)
                    .filter(item -> item.turnId() != null && seenTurnIds.add(item.turnId()))
                    .filter(item -> item.response() != null && !item.response().isBlank())
                    .filter(item -> item.createdAt() != null && isWithin(period, item.createdAt()))
                    .filter(item -> CHILD_SPEECH_RESPONSE_TYPE.equals(normalize(item.responseType())))
                    .filter(item -> VERIFIED_RESPONSE_CATEGORIES.contains(normalize(item.responseCategory())))
                    .sorted(Comparator
                            .comparing(AiTurnEvidence::createdAt)
                            .thenComparing(AiTurnEvidence::turnId))
                    .toList()) {
                candidates.add(new SpeechCandidate(
                        domainId,
                        valueOrUnknown(turn.taskId()),
                        new SpeechSample(
                                "conversation:" + conversation.conversationId() + ":turn:" + turn.turnId(),
                                turn.response(),
                                turn.hintLevel(),
                                ExpressionLevels.canonicalForRead(turn.expressionLevel()),
                                turn.createdAt()),
                        Set.of()));
            }
        }
        return candidates.stream().sorted(speechCandidateOrder()).toList();
    }

    private boolean completedFallbackEvidence(AiConversationEvidence conversation) {
        return "completed".equalsIgnoreCase(conversation.status())
                && conversation.completionOutcome() != null
                && COMPLETION_OUTCOMES.contains(conversation.completionOutcome().toLowerCase());
    }

    private Optional<SpeechPair> comparablePair(List<SpeechCandidate> candidates) {
        List<SpeechPair> pairs = new ArrayList<>();
        safeList(candidates).stream()
                .collect(Collectors.groupingBy(SpeechCandidate::taskId, LinkedHashMap::new, Collectors.toList()))
                .entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(Map.Entry::getValue)
                .forEach(group -> {
                    List<SpeechCandidate> ordered = group.stream()
                            .sorted(speechCandidateOrder())
                            .toList();
                    for (int recentIndex = ordered.size() - 1; recentIndex > 0; recentIndex--) {
                        SpeechCandidate recent = ordered.get(recentIndex);
                        for (int pastIndex = 0; pastIndex < recentIndex; pastIndex++) {
                            SpeechCandidate past = ordered.get(pastIndex);
                            List<String> shared = past.verifiedElements().stream()
                                    .filter(recent.verifiedElements()::contains)
                                    .sorted()
                                    .toList();
                            if (!shared.isEmpty()) {
                                pairs.add(new SpeechPair(past, recent, shared));
                                recentIndex = -1;
                                break;
                            }
                        }
                    }
                });
        return pairs.stream().max(Comparator
                .comparing((SpeechPair pair) -> pair.recent().sample().occurredAt())
                .thenComparing(pair -> pair.recent().sample().evidenceId())
                .thenComparing(pair -> pair.past().sample().occurredAt(), Comparator.reverseOrder())
                .thenComparing(pair -> pair.past().sample().evidenceId(), Comparator.reverseOrder()));
    }

    private Comparator<SpeechCandidate> speechCandidateOrder() {
        return Comparator
                .comparing((SpeechCandidate candidate) -> candidate.sample().occurredAt())
                .thenComparing(candidate -> candidate.sample().evidenceId());
    }

    private Optional<Map<String, Object>> verifiedTurnSlots(AiTurnEvidence turn) {
        Object raw = safeMap(turn.pedagogy()).get("verified_slots");
        if (!(raw instanceof Map<?, ?> slots)) {
            return Optional.empty();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        slots.forEach((key, value) -> {
            if (key != null && value != null && !String.valueOf(key).isBlank()) {
                result.put(String.valueOf(key), value);
            }
        });
        return Optional.of(Map.copyOf(result));
    }

    private List<DomainTrend> presentTrends(List<DomainTrend> trends, String kind) {
        return safeList(trends).stream()
                .filter(trend -> labelFor(trend.domainId()) != null)
                .map(trend -> new DomainTrend(
                        trend.domainId(),
                        switch (kind) {
                            case "drill" -> unitLabel(trend.domainId()) + " · 반복학습";
                            case "teach" -> unitLabel(trend.domainId()) + " · 모르미 가르치기";
                            default -> unitLabel(trend.domainId());
                        },
                        trend.points(),
                        trend.totalCount(),
                        trend.recentCount()))
                .toList();
    }

    private List<DomainStatus> presentStatuses(List<DomainStatus> statuses) {
        return safeList(statuses).stream()
                .filter(status -> labelFor(status.domainId()) != null)
                .map(status -> new DomainStatus(
                        status.domainId(),
                        switch (status.label()) {
                            case "drill" -> unitLabel(status.domainId()) + " · 반복학습";
                            case "teach" -> unitLabel(status.domainId()) + " · 모르미 가르치기";
                            default -> unitLabel(status.domainId());
                        },
                        status.status(),
                        status.direction(),
                        status.totalCount(),
                        status.recentCount()))
                .toList();
    }

    private List<ReportFact> presentationFacts(
            DiagnosticAnalysis analysis,
            RecordContext records,
            AiReportEvidence aiEvidence,
            WeeklyReportPeriod period) {
        Map<String, DomainStatus> statuses = safeList(analysis.domainStatuses()).stream()
                .collect(Collectors.toMap(
                        status -> status.label() + ":" + status.domainId(),
                        Function.identity(),
                        (first, ignored) -> first,
                        LinkedHashMap::new));
        List<ReportFact> facts = new ArrayList<>();
        appendTrendFacts(facts, analysis.homeDrillTrends(), statuses, "drill", CONCEPT);
        facts.addAll(speechFacts(records, aiEvidence, period));
        appendTrendFacts(facts, analysis.teachTrends(), statuses, "teach", EXPLANATION);
        appendTrendFacts(facts, analysis.lifeTrends(), statuses, "life", FactCategory.LIFE);

        Optional<DomainStatus> improving = safeList(analysis.domainStatuses()).stream()
                .filter(status -> "IMPROVING".equals(status.direction()))
                .sorted(evidenceStrengthOrder())
                .findFirst();
        facts.add(improving
                .map(status -> new ReportFact(
                        "improved:" + status.label() + ":" + status.domainId(),
                        IMPROVED,
                        improvedStatement(status)))
                .orElseGet(() -> new ReportFact(
                        "improved:insufficient-history",
                        IMPROVED,
                        "현재 자료에서는 향상을 확정할 장기 근거가 충분하지 않습니다.")));

        Optional<DomainStatus> observe = safeList(analysis.domainStatuses()).stream()
                .filter(status -> status.status() != StatusLabel.STABLE)
                .sorted(statusSalienceOrder())
                .findFirst();
        facts.add(observe
                .map(status -> new ReportFact(
                        "observe:" + status.label() + ":" + status.domainId(),
                        OBSERVE,
                        unitLabel(status.domainId()) + "의 현재 상태는 " + koreanStatus(status.status())
                                + "이므로 계속 관찰합니다."))
                .orElseGet(() -> new ReportFact(
                        "observe:next-records",
                        OBSERVE,
                        analysis.domainStatuses().isEmpty()
                                ? "분석 가능한 완료 기록이 아직 없습니다."
                                : "새 기록에서도 현재 수행이 유지되는지 계속 관찰합니다.")));
        return List.copyOf(facts);
    }

    private List<ReportFact> speechFacts(
            RecordContext records, AiReportEvidence aiEvidence, WeeklyReportPeriod period) {
        if (!validAiEnvelope(aiEvidence)) {
            return List.of();
        }
        List<ReportFact> facts = new ArrayList<>();
        for (String domainId : REPORT_DOMAINS) {
            Optional<SpeechPair> pair = comparablePair(speechCandidates(records, aiEvidence, domainId, period));
            if (pair.isEmpty()) {
                List<SpeechCandidate> fallback = fallbackSpeechCandidates(
                        records, aiEvidence, domainId, period);
                if (!fallback.isEmpty()) {
                    SpeechSample recent = fallback.getLast().sample();
                    facts.add(new ReportFact(
                            "speech:" + domainId,
                            EXPLANATION,
                            unitLabel(domainId) + " 단원의 최근 실제 발화는 “"
                                    + reportQuote(recent.utterance()) + "”이며 발화 단계는 "
                                    + valueOrUnknown(recent.expressionLevel()) + "입니다."));
                }
                continue;
            }
            SpeechPair selected = pair.orElseThrow();
            SpeechSample past = selected.past().sample();
            SpeechSample recent = selected.recent().sample();
            String helpChange = Objects.equals(past.hintLevel(), recent.hintLevel())
                    ? "도움 수준은 두 기록 모두 " + valueOrUnknown(recent.hintLevel()) + "입니다."
                    : "도움 수준은 " + valueOrUnknown(past.hintLevel()) + "에서 "
                            + valueOrUnknown(recent.hintLevel()) + "로 바뀌었습니다.";
            facts.add(new ReportFact(
                    "speech:" + domainId,
                    EXPLANATION,
                    unitLabel(domainId) + " 발화 비교에서 공통 검증 요소 "
                            + selected.verifiedElements().size() + "개가 확인되었고 " + helpChange));
        }
        return List.copyOf(facts);
    }

    private String reportQuote(String utterance) {
        String normalized = utterance == null ? "" : utterance.strip();
        return normalized.length() <= 100 ? normalized : normalized.substring(0, 100);
    }

    private void appendTrendFacts(
            List<ReportFact> facts,
            List<DomainTrend> trends,
            Map<String, DomainStatus> statuses,
            String kind,
            FactCategory category) {
        List<DomainTrend> salientTrends = safeList(trends).stream()
                .filter(trend -> labelFor(trend.domainId()) != null)
                .filter(trend -> statuses.containsKey(kind + ":" + trend.domainId()))
                .sorted((left, right) -> statusSalienceOrder().compare(
                        statuses.get(kind + ":" + left.domainId()),
                        statuses.get(kind + ":" + right.domainId())))
                .toList();
        for (DomainTrend trend : salientTrends) {
            DomainStatus status = statuses.get(kind + ":" + trend.domainId());
            double recentAverage = trend.points().stream()
                    .filter(TrendPoint::recent)
                    .mapToDouble(TrendPoint::independentScore)
                    .average()
                    .orElse(0.0);
            String statement = switch (kind) {
                case "drill" -> unitLabel(trend.domainId()) + " 반복학습 수행은 최근 " + display(recentAverage)
                        + "%이며 상태는 " + koreanStatus(status.status()) + "입니다.";
                case "teach" -> unitLabel(trend.domainId()) + " 모르미 가르치기 수행은 최근 " + display(recentAverage)
                        + "%이며 상태는 " + koreanStatus(status.status()) + "입니다.";
                default -> unitLabel(trend.domainId()) + " 실생활 수행은 최근 " + display(recentAverage)
                        + "%이며 상태는 " + koreanStatus(status.status()) + "입니다.";
            };
            facts.add(new ReportFact(
                    kind + ":" + trend.domainId(),
                    category,
                    statement));
        }
    }

    private String improvedStatement(DomainStatus status) {
        return switch (status.label()) {
            case "drill" -> unitLabel(status.domainId()) + " 반복학습 수행은 이전 기록보다 좋아졌습니다.";
            case "teach" -> unitLabel(status.domainId()) + " 모르미 가르치기 수행은 이전 기록보다 좋아졌습니다.";
            default -> unitLabel(status.domainId()) + " 실생활 수행은 이전 기록보다 좋아졌습니다.";
        };
    }

    private Comparator<DomainStatus> statusSalienceOrder() {
        return Comparator.comparingInt((DomainStatus status) -> statusPriority(status.status()))
                .thenComparingInt(status -> directionPriority(status.direction()))
                .thenComparing(Comparator.comparingInt(DomainStatus::recentCount).reversed())
                .thenComparing(Comparator.comparingInt(DomainStatus::totalCount).reversed())
                .thenComparing(DomainStatus::domainId, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(DomainStatus::label, Comparator.nullsLast(Comparator.naturalOrder()));
    }

    private Comparator<DomainStatus> evidenceStrengthOrder() {
        return Comparator.comparingInt(DomainStatus::recentCount)
                .reversed()
                .thenComparing(Comparator.comparingInt(DomainStatus::totalCount).reversed())
                .thenComparing(DomainStatus::domainId, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(DomainStatus::label, Comparator.nullsLast(Comparator.naturalOrder()));
    }

    private int statusPriority(StatusLabel status) {
        return switch (status) {
            case SUPPORT_NEEDED -> 0;
            case DEVELOPING -> 1;
            case STABLE -> 2;
            case OBSERVING -> 3;
        };
    }

    private int directionPriority(String direction) {
        return "DECLINING".equals(direction) ? 0 : 1;
    }

    private NarrativeResult narrative(String learnerLabel, List<ReportFact> facts) {
        try {
            Optional<AiSummary> summary = aiClient.summarize(learnerLabel, facts);
            if (summary.isPresent() && validSummary(summary.orElseThrow(), facts)) {
                AiSummary value = summary.orElseThrow();
                return new NarrativeResult(
                        new CurrentSummary(
                                evidenceText(value.conceptPerformance()),
                                evidenceText(value.explanationChange()),
                                evidenceText(value.lifeTransfer())),
                        highlight(value.improvedPoint()),
                        highlight(value.observePoint()),
                        false);
            }
        } catch (RuntimeException error) {
            log.warn("Mormi-AI report summary fallback type={}", error.getClass().getSimpleName());
        }
        return fallbackNarrative(facts);
    }

    private boolean validSummary(AiSummary summary, List<ReportFact> facts) {
        if (summary == null) {
            return false;
        }
        Map<String, ReportFact> exactFacts = facts.stream().collect(Collectors.toMap(
                ReportFact::evidenceId,
                Function.identity(),
                (first, ignored) -> first,
                LinkedHashMap::new));
        return exactNarrative(summary.conceptPerformance(), exactFacts, CONCEPT)
                && exactNarrative(summary.explanationChange(), exactFacts, EXPLANATION)
                && exactNarrative(summary.lifeTransfer(), exactFacts, FactCategory.LIFE)
                && exactNarrative(summary.improvedPoint(), exactFacts, IMPROVED)
                && exactNarrative(summary.observePoint(), exactFacts, OBSERVE);
    }

    private boolean exactNarrative(
            AiNarrative narrative,
            Map<String, ReportFact> facts,
            FactCategory expectedCategory) {
        if (narrative == null
                || narrative.text() == null
                || narrative.text().isBlank()
                || narrative.evidenceRefs() == null
                || narrative.evidenceRefs().isEmpty()
                || narrative.evidenceRefs().size() > 5
                || new HashSet<>(narrative.evidenceRefs()).size() != narrative.evidenceRefs().size()
                || narrative.evidenceRefs().stream().anyMatch(ref -> !facts.containsKey(ref))
                || narrative.evidenceRefs().stream()
                        .map(facts::get)
                        .anyMatch(fact -> fact.category() != expectedCategory)) {
            return false;
        }
        List<String> referenced = narrative.evidenceRefs().stream()
                .map(facts::get)
                .map(ReportFact::statement)
                .toList();
        return referenced.contains(narrative.text()) || narrative.text().equals(String.join(" ", referenced));
    }

    private NarrativeResult fallbackNarrative(List<ReportFact> facts) {
        EvidenceText concept = fallbackText(facts, CONCEPT, "분석 가능한 문제 풀이 기록이 아직 없습니다.");
        EvidenceText explanation = fallbackText(facts, EXPLANATION, SPEECH_UNAVAILABLE);
        EvidenceText life = fallbackText(
                facts, FactCategory.LIFE, "분석 가능한 생활 속 문제 해결 기록이 아직 없습니다.");
        EvidenceText improved = fallbackText(
                facts, IMPROVED, "현재 자료에서는 향상을 확정할 장기 근거가 충분하지 않습니다.");
        EvidenceText observe = fallbackText(facts, OBSERVE, "새 기록이 쌓이는 동안 계속 관찰합니다.");
        return new NarrativeResult(
                new CurrentSummary(concept, explanation, life),
                new Highlight(improved.text(), improved.evidenceRefs()),
                new Highlight(observe.text(), observe.evidenceRefs()),
                true);
    }

    private EvidenceText fallbackText(List<ReportFact> facts, FactCategory category, String emptyText) {
        return facts.stream()
                .filter(fact -> fact.category() == category)
                .findFirst()
                .map(fact -> new EvidenceText(fact.statement(), List.of(fact.evidenceId())))
                .orElseGet(() -> new EvidenceText(emptyText, List.of()));
    }

    private EvidenceText evidenceText(AiNarrative narrative) {
        return new EvidenceText(narrative.text(), List.copyOf(narrative.evidenceRefs()));
    }

    private Highlight highlight(AiNarrative narrative) {
        return new Highlight(narrative.text(), List.copyOf(narrative.evidenceRefs()));
    }

    private List<Attempt> dedupeAttempts(List<Attempt> attempts) {
        Map<String, Attempt> unique = new LinkedHashMap<>();
        for (Attempt attempt : safeList(attempts)) {
            if (attempt == null) {
                continue;
            }
            String key = attempt.getId() != null
                    ? "id:" + attempt.getId()
                    : "source:" + attempt.getLearningSessionId() + ":" + attempt.getActivity() + ":"
                            + attempt.getAttemptNo();
            unique.putIfAbsent(key, attempt);
        }
        return List.copyOf(unique.values());
    }

    private List<CafeVisitStage> dedupeStages(List<CafeVisitStage> stages) {
        Map<String, CafeVisitStage> unique = new LinkedHashMap<>();
        for (CafeVisitStage stage : safeList(stages)) {
            if (stage == null) {
                continue;
            }
            String key = stage.getId() != null
                    ? "id:" + stage.getId()
                    : "source:" + stage.getCafeVisitId() + ":" + stage.getStage() + ":" + stage.getAttemptNo();
            unique.putIfAbsent(key, stage);
        }
        return List.copyOf(unique.values());
    }

    private boolean analyzableAttempt(Attempt attempt) {
        return attempt.getQuestionIndex() != null
                || (attempt.getItemId() != null && !attempt.getItemId().isBlank());
    }

    private boolean rawEvidencePermitted(Learner learner) {
        return learner.isConversationStorageConsent()
                && learner.getRetentionPolicy() != null
                && !"no_raw".equalsIgnoreCase(learner.getRetentionPolicy());
    }

    private SpeechEvidence unavailableSpeech(String domainId) {
        return new SpeechEvidence(domainId, false, SPEECH_UNAVAILABLE, null, null, List.of(), null);
    }

    private String changeSummary(SpeechSample past, SpeechSample recent) {
        if (Objects.equals(past.hintLevel(), recent.hintLevel())) {
            return "두 발화 모두 도움 수준은 " + valueOrUnknown(recent.hintLevel()) + "입니다.";
        }
        return "도움 수준이 " + valueOrUnknown(past.hintLevel()) + "에서 "
                + valueOrUnknown(recent.hintLevel()) + "로 바뀌었습니다.";
    }

    private String analyzedChangeSummary(String domainId, SpeechSample past, SpeechSample recent) {
        return aiClient.summarizeSpeechChange(
                        labelFor(domainId),
                        past.utterance(),
                        past.expressionLevel(),
                        past.hintLevel(),
                        recent.utterance(),
                        recent.expressionLevel(),
                        recent.hintLevel())
                .orElseGet(() -> changeSummary(past, recent));
    }

    private String labelFor(String domainId) {
        return HOME_LABELS.containsKey(domainId) ? HOME_LABELS.get(domainId) : LIFE_LABELS.get(domainId);
    }

    private String unitLabel(String domainId) {
        String label = labelFor(domainId);
        return label == null ? null : label + " 단원";
    }

    private String koreanStatus(StatusLabel status) {
        return switch (status) {
            case STABLE -> "안정";
            case DEVELOPING -> "발달 중";
            case SUPPORT_NEEDED -> "지원 필요";
            case OBSERVING -> "관찰 중";
        };
    }

    private String display(double value) {
        return BigDecimal.valueOf(value)
                .setScale(1, RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .toPlainString();
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase();
    }

    private String valueOrUnknown(String value) {
        return value == null || value.isBlank() ? "확인 불가" : value;
    }

    private <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }

    private Map<String, Object> safeMap(Map<String, Object> values) {
        return values == null ? Map.of() : values;
    }

    private record RecordContext(
            Map<Long, LearningSession> sessions,
            Map<Long, CafeVisit> visits,
            Map<String, DialogueConversation> dialogues,
            List<HomeEvidence> home,
            List<LifeEvidence> life) {
    }

    private record NarrativeResult(
            CurrentSummary currentSummary,
            Highlight improvedPoint,
            Highlight observePoint,
            boolean fallback) {
    }

    private record SpeechCandidate(
            String domainId,
            String taskId,
            SpeechSample sample,
            Set<String> verifiedElements) {
    }

    private record SpeechPair(
            SpeechCandidate past,
            SpeechCandidate recent,
            List<String> verifiedElements) {
    }
}
