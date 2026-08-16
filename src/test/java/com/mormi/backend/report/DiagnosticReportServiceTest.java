package com.mormi.backend.report;

import static com.mormi.backend.report.DiagnosticReportDtos.Mode.HOME;
import static com.mormi.backend.report.DiagnosticReportDtos.Mode.LIFE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mormi.backend.cafe.CafeStage;
import com.mormi.backend.cafe.CafeVisit;
import com.mormi.backend.cafe.CafeVisitRepository;
import com.mormi.backend.cafe.CafeVisitStage;
import com.mormi.backend.cafe.CafeVisitStageRepository;
import com.mormi.backend.common.ApiException;
import com.mormi.backend.dialogue.DialogueConversation;
import com.mormi.backend.dialogue.DialogueConversationRepository;
import com.mormi.backend.learner.Learner;
import com.mormi.backend.learner.LearnerService;
import com.mormi.backend.report.DiagnosticReportDtos.AiConversationEvidence;
import com.mormi.backend.report.DiagnosticReportDtos.AiNarrative;
import com.mormi.backend.report.DiagnosticReportDtos.AiReportEvidence;
import com.mormi.backend.report.DiagnosticReportDtos.AiSummary;
import com.mormi.backend.report.DiagnosticReportDtos.AiTurnEvidence;
import com.mormi.backend.report.DiagnosticReportDtos.DiagnosticReport;
import com.mormi.backend.report.DiagnosticReportDtos.DomainTrend;
import com.mormi.backend.report.DiagnosticReportDtos.FactCategory;
import com.mormi.backend.report.DiagnosticReportDtos.ModeReport;
import com.mormi.backend.report.DiagnosticReportDtos.ReportFact;
import com.mormi.backend.report.DiagnosticReportDtos.SpeechEvidence;
import com.mormi.backend.session.Attempt;
import com.mormi.backend.session.AttemptRepository;
import com.mormi.backend.session.LearningSession;
import com.mormi.backend.session.LearningSessionRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

class DiagnosticReportServiceTest {

    private static final long LEARNER_ID = 7L;
    private static final OffsetDateTime JANUARY = OffsetDateTime.parse("2026-01-10T09:00:00+09:00");
    private static final OffsetDateTime FEBRUARY = OffsetDateTime.parse("2026-02-10T09:00:00+09:00");

    private ReportAiClient aiClient;
    private LearnerService learnerService;
    private LearningSessionRepository sessionRepository;
    private AttemptRepository attemptRepository;
    private CafeVisitRepository cafeVisitRepository;
    private CafeVisitStageRepository cafeVisitStageRepository;
    private DialogueConversationRepository dialogueRepository;
    private DiagnosticReportService service;
    private Learner learner;

    @BeforeEach
    void setUp() {
        aiClient = mock(ReportAiClient.class);
        learnerService = mock(LearnerService.class);
        sessionRepository = mock(LearningSessionRepository.class);
        attemptRepository = mock(AttemptRepository.class);
        cafeVisitRepository = mock(CafeVisitRepository.class);
        cafeVisitStageRepository = mock(CafeVisitStageRepository.class);
        dialogueRepository = mock(DialogueConversationRepository.class);
        service = new DiagnosticReportService(
                aiClient,
                learnerService,
                sessionRepository,
                attemptRepository,
                cafeVisitRepository,
                cafeVisitStageRepository,
                dialogueRepository);

        learner = Learner.create("민서", "R-007", "hash");
        ReflectionTestUtils.setField(learner, "id", LEARNER_ID);
        when(learnerService.require(LEARNER_ID)).thenReturn(learner);
        when(sessionRepository.findByLearnerIdAndCompletedAtIsNotNullOrderByCompletedAtDesc(LEARNER_ID))
                .thenReturn(List.of());
        when(cafeVisitRepository.findByLearnerIdAndCompletedAtIsNotNullOrderByCompletedAtAsc(LEARNER_ID))
                .thenReturn(List.of());
        when(dialogueRepository.findByLearnerIdOrderByCreatedAtAsc(LEARNER_ID)).thenReturn(List.of());
        when(aiClient.evidence(LEARNER_ID, true)).thenReturn(Optional.empty());
        when(aiClient.summarize(anyString(), anyList())).thenReturn(Optional.empty());
    }

    @Test
    void currentCombinesAllCompletedAnalyzableSessionsAndCafeVisitsWithBatchReads() {
        LearningSession oldMoneySession = session(11L, LEARNER_ID, "money-count", JANUARY);
        LearningSession recentMoneySession = session(12L, LEARNER_ID, "money-count", FEBRUARY);
        CafeVisit cafeVisit = visit(21L, LEARNER_ID, FEBRUARY.plusDays(1));
        when(sessionRepository.findByLearnerIdAndCompletedAtIsNotNullOrderByCompletedAtDesc(LEARNER_ID))
                .thenReturn(List.of(recentMoneySession, oldMoneySession));
        when(attemptRepository.findByLearningSessionIdInOrderByCreatedAtAscIdAsc(List.of(11L, 12L)))
                .thenAnswer(ignored -> {
                    Attempt retransmitted = attempt(101L, 11L, 1, false, JANUARY);
                    return List.of(
                            retransmitted,
                            retransmitted,
                            attempt(102L, 11L, 2, true, JANUARY.plusMinutes(1)),
                            attempt(103L, 12L, 1, true, FEBRUARY));
                });
        when(cafeVisitRepository.findByLearnerIdAndCompletedAtIsNotNullOrderByCompletedAtAsc(LEARNER_ID))
                .thenReturn(List.of(cafeVisit));
        when(cafeVisitStageRepository.findByCafeVisitIdInOrderByCreatedAtAscIdAsc(List.of(21L)))
                .thenReturn(List.of(cafeStage(201L, 21L, CafeStage.CALCULATE, 1, true, FEBRUARY.plusDays(1))));
        when(aiClient.evidence(LEARNER_ID, true)).thenReturn(Optional.of(emptyAiEvidence()));

        DiagnosticReport report = service.current(LEARNER_ID);

        assertThat(report.dataRange().totalHomeSessions()).isEqualTo(2);
        assertThat(report.dataRange().totalLifeVisits()).isEqualTo(1);
        assertThat(report.modes()).extracting(ModeReport::mode).containsExactly(HOME, LIFE);
        assertThat(report.modes().getFirst().domains()).extracting(domain -> domain.label())
                .contains("돈 세기 · 반복학습");
        assertThat(report.modes().getLast().domains()).extracting(domain -> domain.label())
                .contains("메뉴 값 계산하기");
        assertThat(report.evidenceCounts().drillAttempts()).isEqualTo(3);
        verify(attemptRepository).findByLearningSessionIdInOrderByCreatedAtAscIdAsc(List.of(11L, 12L));
        verify(cafeVisitStageRepository).findByCafeVisitIdInOrderByCreatedAtAscIdAsc(List.of(21L));
    }

    @Test
    void currentCountsCompletedTeachOnlyHomeSessionWithoutFabricatingDrillEvidence() {
        LearningSession teachOnly = session(11L, LEARNER_ID, "money-count", JANUARY);
        when(sessionRepository.findByLearnerIdAndCompletedAtIsNotNullOrderByCompletedAtDesc(LEARNER_ID))
                .thenReturn(List.of(teachOnly));
        when(attemptRepository.findByLearningSessionIdInOrderByCreatedAtAscIdAsc(List.of(11L)))
                .thenReturn(List.of());

        DiagnosticReport report = service.current(LEARNER_ID);

        assertThat(report.dataRange().firstAt()).isEqualTo(JANUARY);
        assertThat(report.dataRange().lastAt()).isEqualTo(JANUARY);
        assertThat(report.dataRange().totalHomeSessions()).isEqualTo(1);
        assertThat(report.evidenceCounts().homeSessions()).isEqualTo(1);
        assertThat(report.evidenceCounts().drillAttempts()).isZero();
        assertThat(report.modes().getFirst().domains())
                .noneMatch(domain -> domain.label().contains("반복학습"));
    }

    @Test
    void currentKeepsDeterministicMetricsWhenAiIsUnavailable() {
        LearningSession moneySession = session(11L, LEARNER_ID, "money-count", JANUARY);
        when(sessionRepository.findByLearnerIdAndCompletedAtIsNotNullOrderByCompletedAtDesc(LEARNER_ID))
                .thenReturn(List.of(moneySession));
        when(attemptRepository.findByLearningSessionIdInOrderByCreatedAtAscIdAsc(List.of(11L)))
                .thenReturn(List.of(attempt(101L, 11L, 1, true, JANUARY)));
        when(aiClient.evidence(LEARNER_ID, true))
                .thenThrow(ApiException.serviceUnavailable("ai", "offline"));

        DiagnosticReport report = service.current(LEARNER_ID);

        assertThat(report.narrativeFallback()).isTrue();
        assertThat(report.modes()).isNotEmpty();
        assertThat(report.currentSummary().conceptPerformance().text()).contains("돈 세기");
        assertThat(report.currentSummary().explanationChange().text()).contains("발화 근거");
    }

    @Test
    void currentRequestsRawEvidenceOnlyWhenStoredConsentAndRetentionPermitIt() {
        learner.applyConsent(false, null);

        service.current(LEARNER_ID);

        verify(aiClient).evidence(LEARNER_ID, false);
    }

    @Test
    void currentDoesNotRequestRawEvidenceForAnInconsistentNoRawRetentionRecord() {
        ReflectionTestUtils.setField(learner, "retentionPolicy", "no_raw");

        service.current(LEARNER_ID);

        verify(aiClient).evidence(LEARNER_ID, false);
    }

    @Test
    void currentUsesOnlyCompletedAiConversationsOwnedByTheSameSpringSession() {
        LearningSession moneySession = session(11L, LEARNER_ID, "money-count", JANUARY);
        when(sessionRepository.findByLearnerIdAndCompletedAtIsNotNullOrderByCompletedAtDesc(LEARNER_ID))
                .thenReturn(List.of(moneySession));
        when(attemptRepository.findByLearningSessionIdInOrderByCreatedAtAscIdAsc(List.of(11L)))
                .thenReturn(List.of(attempt(101L, 11L, 1, true, JANUARY)));
        when(dialogueRepository.findByLearnerIdOrderByCreatedAtAsc(LEARNER_ID))
                .thenReturn(List.of(DialogueConversation.forLearningSession(
                        "conversation-owned", LEARNER_ID, 11L)));
        AiConversationEvidence active = completedConversation(
                "conversation-owned", moneySession.getPublicId(), "같은 과제", "설명했어", "H0", JANUARY);
        active = new AiConversationEvidence(
                active.conversationId(),
                active.learningSessionId(),
                active.scene(),
                active.scenarioId(),
                "active",
                active.completionOutcome(),
                active.teachRewardEligible(),
                active.verifiedSlots(),
                active.taskMaxHint(),
                active.turns(),
                active.createdAt(),
                active.updatedAt());
        when(aiClient.evidence(LEARNER_ID, true)).thenReturn(Optional.of(new AiReportEvidence(
                LEARNER_ID,
                List.of(
                        completedConversation(
                                "conversation-owned", moneySession.getPublicId(), "같은 과제", "설명했어", "H0", JANUARY),
                        completedConversation(
                                "conversation-not-spring-owned", moneySession.getPublicId(), "같은 과제", "섞이면 안 돼", "H0", JANUARY),
                        active),
                List.of(),
                List.of())));

        DiagnosticReport report = service.current(LEARNER_ID);

        assertThat(report.evidenceCounts().teachConversations()).isEqualTo(1);
        assertThat(report.modes().getFirst().domains()).extracting(domain -> domain.label())
                .contains("돈 세기 · 설명 독립성");
    }

    @Test
    void currentDoesNotTreatCompletedCafeDialogueAsHomeTeachEvidence() {
        CafeVisit cafeVisit = visit(21L, LEARNER_ID, FEBRUARY);
        DialogueConversation cafeDialogue = DialogueConversation.forCafeVisit(
                "conversation-cafe", LEARNER_ID, 21L, "cafe_menu_total", 1, Map.of());
        when(cafeVisitRepository.findByLearnerIdAndCompletedAtIsNotNullOrderByCompletedAtAsc(LEARNER_ID))
                .thenReturn(List.of(cafeVisit));
        when(cafeVisitStageRepository.findByCafeVisitIdInOrderByCreatedAtAscIdAsc(List.of(21L)))
                .thenReturn(List.of());
        when(dialogueRepository.findByLearnerIdOrderByCreatedAtAsc(LEARNER_ID))
                .thenReturn(List.of(cafeDialogue));
        when(aiClient.evidence(LEARNER_ID, true)).thenReturn(Optional.of(new AiReportEvidence(
                LEARNER_ID,
                List.of(completedCafeConversation(
                        "conversation-cafe", "cafe_menu_total", "카페 계산", "합계를 설명했어", FEBRUARY)),
                List.of(),
                List.of())));

        DiagnosticReport report = service.current(LEARNER_ID);

        assertThat(report.evidenceCounts().teachConversations()).isZero();
        assertThat(report.modes().getFirst().domains())
                .noneMatch(domain -> domain.label().contains("설명 독립성"));
    }

    @Test
    void currentAcceptsOnlyExactAiSelectionsOfDeterministicFacts() {
        LearningSession moneySession = session(11L, LEARNER_ID, "money-count", JANUARY);
        when(sessionRepository.findByLearnerIdAndCompletedAtIsNotNullOrderByCompletedAtDesc(LEARNER_ID))
                .thenReturn(List.of(moneySession));
        when(attemptRepository.findByLearningSessionIdInOrderByCreatedAtAscIdAsc(List.of(11L)))
                .thenReturn(List.of(attempt(101L, 11L, 1, true, JANUARY)));
        givenTeachAndLifeEvidence(moneySession);
        when(aiClient.summarize(anyString(), anyList())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<com.mormi.backend.report.DiagnosticReportDtos.ReportFact> facts = invocation.getArgument(1);
            return Optional.of(exactSummary(facts));
        });

        DiagnosticReport report = service.current(LEARNER_ID);

        assertThat(report.narrativeFallback()).isFalse();
        assertThat(report.currentSummary().conceptPerformance().text())
                .isEqualTo("돈 세기 반복학습의 최근 독립 수행률은 100%이며 상태는 관찰 중입니다.");
    }

    @Test
    void currentRejectsExactFactWhenAiUsesItInTheWrongNarrativeCategory() {
        LearningSession moneySession = session(11L, LEARNER_ID, "money-count", JANUARY);
        when(sessionRepository.findByLearnerIdAndCompletedAtIsNotNullOrderByCompletedAtDesc(LEARNER_ID))
                .thenReturn(List.of(moneySession));
        when(attemptRepository.findByLearningSessionIdInOrderByCreatedAtAscIdAsc(List.of(11L)))
                .thenReturn(List.of(attempt(101L, 11L, 1, true, JANUARY)));
        givenTeachAndLifeEvidence(moneySession);
        when(aiClient.summarize(anyString(), anyList())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<ReportFact> facts = invocation.getArgument(1);
            AiSummary exact = exactSummary(facts);
            return Optional.of(new AiSummary(
                    exact.improvedPoint(),
                    exact.explanationChange(),
                    exact.lifeTransfer(),
                    exact.improvedPoint(),
                    exact.observePoint()));
        });

        DiagnosticReport report = service.current(LEARNER_ID);

        assertThat(report.narrativeFallback()).isTrue();
    }

    @Test
    void currentUsesAnalyticsIdForAiAndKeepsDisplayNameOnlyInLocalHeader() {
        DiagnosticReport report = service.current(LEARNER_ID);

        verify(aiClient).summarize(org.mockito.ArgumentMatchers.eq(learner.getAnalyticsId().toString()), anyList());
        assertThat(report.learner().displayName()).isEqualTo("민서");
    }

    @Test
    void currentClassifiesAiAssistedCafeStagesAsScaffoldedAndAddsVisitCompletionEvidence() {
        CafeVisit directVisit = visit(21L, LEARNER_ID, JANUARY);
        CafeVisit aiVisit = visit(22L, LEARNER_ID, FEBRUARY);
        when(cafeVisitRepository.findByLearnerIdAndCompletedAtIsNotNullOrderByCompletedAtAsc(LEARNER_ID))
                .thenReturn(List.of(directVisit, aiVisit));
        when(cafeVisitStageRepository.findByCafeVisitIdInOrderByCreatedAtAscIdAsc(List.of(21L, 22L)))
                .thenReturn(List.of(
                        cafeStage(201L, 21L, CafeStage.QUEUE, 1, true, JANUARY),
                        cafeStage(202L, 21L, CafeStage.MENU, 1, true, JANUARY.plusMinutes(1)),
                        cafeStage(203L, 21L, CafeStage.CALCULATE, 1, true, JANUARY.plusMinutes(2)),
                        cafeStage(204L, 21L, CafeStage.CHANGE, 1, true, JANUARY.plusMinutes(3)),
                        cafeStage(205L, 22L, CafeStage.QUEUE, 900_001, true, FEBRUARY),
                        cafeStage(206L, 22L, CafeStage.MENU, 900_002, true, FEBRUARY.plusMinutes(1)),
                        cafeStage(207L, 22L, CafeStage.CALCULATE, 900_003, true, FEBRUARY.plusMinutes(2)),
                        cafeStage(208L, 22L, CafeStage.CHANGE, 900_004, true, FEBRUARY.plusMinutes(3))));

        DiagnosticReport report = service.current(LEARNER_ID);

        DomainTrend menu = lifeTrend(report, "menu");
        DomainTrend calculate = lifeTrend(report, "calculate");
        DomainTrend change = lifeTrend(report, "change");
        DomainTrend complete = lifeTrend(report, "complete");
        assertThat(menu.points()).extracting(point -> point.independentScore()).containsExactly(100.0, 0.0);
        assertThat(calculate.points()).extracting(point -> point.independentScore()).containsExactly(100.0, 0.0);
        assertThat(change.points()).extracting(point -> point.independentScore()).containsExactly(100.0, 0.0);
        assertThat(complete.points()).extracting(point -> point.independentScore()).containsExactly(100.0, 0.0);
        assertThat(complete.points()).extracting(point -> point.supportedScore()).containsExactly(100.0, 100.0);
        assertThat(complete.points()).extracting(point -> point.occurredAt()).containsExactly(JANUARY, FEBRUARY);
    }

    @Test
    void currentRejectsUnknownDomainsAndRecordsOutsideEveryOwnedJoin() {
        LearningSession owned = session(11L, LEARNER_ID, "money-count", JANUARY);
        LearningSession otherLearner = session(12L, 99L, "money-count", JANUARY);
        LearningSession unknown = session(13L, LEARNER_ID, "unknown-domain", JANUARY);
        when(sessionRepository.findByLearnerIdAndCompletedAtIsNotNullOrderByCompletedAtDesc(LEARNER_ID))
                .thenReturn(List.of(owned, otherLearner, unknown));
        when(attemptRepository.findByLearningSessionIdInOrderByCreatedAtAscIdAsc(List.of(11L)))
                .thenReturn(List.of(
                        attempt(101L, 11L, 1, true, JANUARY),
                        attempt(102L, 12L, 1, true, JANUARY)));

        DiagnosticReport report = service.current(LEARNER_ID);

        assertThat(report.dataRange().totalHomeSessions()).isEqualTo(1);
        assertThat(report.evidenceCounts().drillAttempts()).isEqualTo(1);
        assertThat(report.domains()).extracting(domain -> domain.domainId())
                .containsOnly("money-count");
    }

    @Test
    void currentRejectsAiSummaryThatDoesNotSelectExactKnownFacts() {
        LearningSession moneySession = session(11L, LEARNER_ID, "money-count", JANUARY);
        when(sessionRepository.findByLearnerIdAndCompletedAtIsNotNullOrderByCompletedAtDesc(LEARNER_ID))
                .thenReturn(List.of(moneySession));
        when(attemptRepository.findByLearningSessionIdInOrderByCreatedAtAscIdAsc(List.of(11L)))
                .thenReturn(List.of(attempt(101L, 11L, 1, true, JANUARY)));
        AiNarrative invented = new AiNarrative("근거에 없는 해석입니다.", List.of("missing:fact"));
        when(aiClient.summarize(anyString(), anyList()))
                .thenReturn(Optional.of(new AiSummary(invented, invented, invented, invented, invented)));

        DiagnosticReport report = service.current(LEARNER_ID);

        assertThat(report.narrativeFallback()).isTrue();
        assertThat(report.currentSummary().conceptPerformance().text()).doesNotContain("근거에 없는");
    }

    @Test
    void speechEvidenceReturnsExactEarliestAndLatestComparableStoredUtterances() {
        LearningSession pastSession = session(11L, LEARNER_ID, "money-count", JANUARY);
        LearningSession recentSession = session(12L, LEARNER_ID, "money-count", FEBRUARY);
        DialogueConversation pastOwner = DialogueConversation.forLearningSession("conversation-past", LEARNER_ID, 11L);
        DialogueConversation recentOwner = DialogueConversation.forLearningSession("conversation-recent", LEARNER_ID, 12L);
        when(sessionRepository.findByLearnerIdAndCompletedAtIsNotNullOrderByCompletedAtDesc(LEARNER_ID))
                .thenReturn(List.of(recentSession, pastSession));
        when(dialogueRepository.findByLearnerIdOrderByCreatedAtAsc(LEARNER_ID))
                .thenReturn(List.of(pastOwner, recentOwner));
        when(aiClient.evidence(LEARNER_ID, true)).thenReturn(Optional.of(new AiReportEvidence(
                LEARNER_ID,
                List.of(
                        completedConversation(
                                "conversation-past", pastSession.getPublicId(), "같은 과제", "500원만 세었어", "H3", JANUARY),
                        completedConversation(
                                "conversation-recent", recentSession.getPublicId(), "같은 과제", "500원과 100원을 더해서 600원이야", "H0", FEBRUARY)),
                List.of(),
                List.of())));

        SpeechEvidence evidence = service.speechEvidence(LEARNER_ID, "money-count");

        assertThat(evidence.available()).isTrue();
        assertThat(evidence.past().utterance()).isEqualTo("500원만 세었어");
        assertThat(evidence.recent().utterance()).isEqualTo("500원과 100원을 더해서 600원이야");
        assertThat(evidence.past().hintLevel()).isEqualTo("H3");
        assertThat(evidence.recent().hintLevel()).isEqualTo("H0");
        assertThat(evidence.verifiedElements()).containsExactly("amount");
    }

    @Test
    void speechEvidenceDoesNotUseConversationFinalSlotsWithoutAdjacentTurnSnapshots() {
        LearningSession pastSession = session(11L, LEARNER_ID, "money-count", JANUARY);
        LearningSession recentSession = session(12L, LEARNER_ID, "money-count", FEBRUARY);
        when(sessionRepository.findByLearnerIdAndCompletedAtIsNotNullOrderByCompletedAtDesc(LEARNER_ID))
                .thenReturn(List.of(recentSession, pastSession));
        when(dialogueRepository.findByLearnerIdOrderByCreatedAtAsc(LEARNER_ID))
                .thenReturn(List.of(
                        DialogueConversation.forLearningSession("conversation-past", LEARNER_ID, 11L),
                        DialogueConversation.forLearningSession("conversation-recent", LEARNER_ID, 12L)));
        when(aiClient.evidence(LEARNER_ID, true)).thenReturn(Optional.of(new AiReportEvidence(
                LEARNER_ID,
                List.of(
                        completedConversationWithoutSnapshots(
                                "conversation-past", pastSession.getPublicId(), "같은 과제", "과거 발화", "H2", JANUARY),
                        completedConversationWithoutSnapshots(
                                "conversation-recent", recentSession.getPublicId(), "같은 과제", "최근 발화", "H0", FEBRUARY)),
                List.of(),
                List.of())));

        SpeechEvidence evidence = service.speechEvidence(LEARNER_ID, "money-count");

        assertThat(evidence.available()).isFalse();
        assertThat(evidence.message()).isEqualTo("비교 가능한 발화 근거가 부족합니다.");
    }

    @Test
    void speechEvidenceUsesEvidenceIdAsTieBreakerWhenComparableTurnsShareATimestamp() {
        LearningSession firstSession = session(11L, LEARNER_ID, "money-count", JANUARY);
        LearningSession secondSession = session(12L, LEARNER_ID, "money-count", JANUARY.plusMinutes(1));
        when(sessionRepository.findByLearnerIdAndCompletedAtIsNotNullOrderByCompletedAtDesc(LEARNER_ID))
                .thenReturn(List.of(secondSession, firstSession));
        when(dialogueRepository.findByLearnerIdOrderByCreatedAtAsc(LEARNER_ID))
                .thenReturn(List.of(
                        DialogueConversation.forLearningSession("conversation-z", LEARNER_ID, 12L),
                        DialogueConversation.forLearningSession("conversation-a", LEARNER_ID, 11L)));
        when(aiClient.evidence(LEARNER_ID, true)).thenReturn(Optional.of(new AiReportEvidence(
                LEARNER_ID,
                List.of(
                        completedConversation(
                                "conversation-z", secondSession.getPublicId(), "같은 과제", "증거 ID가 뒤인 발화", "H0", JANUARY),
                        completedConversation(
                                "conversation-a", firstSession.getPublicId(), "같은 과제", "증거 ID가 앞인 발화", "H1", JANUARY)),
                List.of(),
                List.of())));

        SpeechEvidence evidence = service.speechEvidence(LEARNER_ID, "money-count");

        assertThat(evidence.past().utterance()).isEqualTo("증거 ID가 앞인 발화");
        assertThat(evidence.recent().utterance()).isEqualTo("증거 ID가 뒤인 발화");
    }

    @Test
    void currentOffersAiOnlyPresentationReadyFactsIncludingComparableSpeech() {
        LearningSession pastSession = session(11L, LEARNER_ID, "money-count", JANUARY);
        LearningSession recentSession = session(12L, LEARNER_ID, "money-count", FEBRUARY);
        when(sessionRepository.findByLearnerIdAndCompletedAtIsNotNullOrderByCompletedAtDesc(LEARNER_ID))
                .thenReturn(List.of(recentSession, pastSession));
        when(attemptRepository.findByLearningSessionIdInOrderByCreatedAtAscIdAsc(List.of(11L, 12L)))
                .thenReturn(List.of(
                        attempt(101L, 11L, 1, true, JANUARY),
                        attempt(102L, 12L, 1, true, FEBRUARY)));
        when(dialogueRepository.findByLearnerIdOrderByCreatedAtAsc(LEARNER_ID))
                .thenReturn(List.of(
                        DialogueConversation.forLearningSession("conversation-past", LEARNER_ID, 11L),
                        DialogueConversation.forLearningSession("conversation-recent", LEARNER_ID, 12L)));
        when(aiClient.evidence(LEARNER_ID, true)).thenReturn(Optional.of(new AiReportEvidence(
                LEARNER_ID,
                List.of(
                        completedConversation(
                                "conversation-past", pastSession.getPublicId(), "같은 과제", "과거 발화", "H3", JANUARY),
                        completedConversation(
                                "conversation-recent", recentSession.getPublicId(), "같은 과제", "최근 발화", "H0", FEBRUARY)),
                List.of(),
                List.of())));

        service.current(LEARNER_ID);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ReportFact>> facts = ArgumentCaptor.forClass(List.class);
        verify(aiClient).summarize(
                org.mockito.ArgumentMatchers.eq(learner.getAnalyticsId().toString()), facts.capture());
        assertThat(facts.getValue())
                .filteredOn(fact -> fact.evidenceId().equals("speech:money-count"))
                .extracting(ReportFact::statement)
                .containsExactly("돈 세기 발화 비교에서 공통 검증 요소 1개가 확인되었고 도움 수준은 H3에서 H0로 바뀌었습니다.");
        assertThat(facts.getValue()).allMatch(fact -> !fact.statement().contains(" recent "));
    }

    @Test
    void speechEvidenceDoesNotCompareDifferentTasksOrUnownedAiConversations() {
        LearningSession pastSession = session(11L, LEARNER_ID, "money-count", JANUARY);
        LearningSession recentSession = session(12L, LEARNER_ID, "money-count", FEBRUARY);
        when(sessionRepository.findByLearnerIdAndCompletedAtIsNotNullOrderByCompletedAtDesc(LEARNER_ID))
                .thenReturn(List.of(recentSession, pastSession));
        when(dialogueRepository.findByLearnerIdOrderByCreatedAtAsc(LEARNER_ID))
                .thenReturn(List.of(
                        DialogueConversation.forLearningSession("conversation-past", LEARNER_ID, 11L),
                        DialogueConversation.forLearningSession("conversation-recent", LEARNER_ID, 12L)));
        when(aiClient.evidence(LEARNER_ID, true)).thenReturn(Optional.of(new AiReportEvidence(
                LEARNER_ID,
                List.of(
                        completedConversation(
                                "conversation-past", pastSession.getPublicId(), "과제-A", "과거 발화", "H2", JANUARY),
                        completedConversation(
                                "conversation-recent", recentSession.getPublicId(), "과제-B", "최근 발화", "H0", FEBRUARY),
                        completedConversation(
                                "conversation-unowned", recentSession.getPublicId(), "과제-A", "섞이면 안 되는 발화", "H0", FEBRUARY)),
                List.of(),
                List.of())));

        SpeechEvidence evidence = service.speechEvidence(LEARNER_ID, "money-count");

        assertThat(evidence.available()).isFalse();
        assertThat(evidence.message()).isEqualTo("비교 가능한 발화 근거가 부족합니다.");
        assertThat(evidence.past()).isNull();
        assertThat(evidence.recent()).isNull();
    }

    private AiReportEvidence emptyAiEvidence() {
        return new AiReportEvidence(LEARNER_ID, List.of(), List.of(), List.of());
    }

    private void givenTeachAndLifeEvidence(LearningSession moneySession) {
        CafeVisit cafeVisit = visit(21L, LEARNER_ID, FEBRUARY);
        when(cafeVisitRepository.findByLearnerIdAndCompletedAtIsNotNullOrderByCompletedAtAsc(LEARNER_ID))
                .thenReturn(List.of(cafeVisit));
        when(cafeVisitStageRepository.findByCafeVisitIdInOrderByCreatedAtAscIdAsc(List.of(21L)))
                .thenReturn(List.of(cafeStage(201L, 21L, CafeStage.CALCULATE, 1, true, FEBRUARY)));
        when(dialogueRepository.findByLearnerIdOrderByCreatedAtAsc(LEARNER_ID))
                .thenReturn(List.of(DialogueConversation.forLearningSession(
                        "conversation-owned", LEARNER_ID, 11L)));
        when(aiClient.evidence(LEARNER_ID, true)).thenReturn(Optional.of(new AiReportEvidence(
                LEARNER_ID,
                List.of(completedConversation(
                        "conversation-owned", moneySession.getPublicId(), "같은 과제", "설명했어", "H0", JANUARY)),
                List.of(),
                List.of())));
    }

    private DomainTrend lifeTrend(DiagnosticReport report, String domainId) {
        return report.modes().stream()
                .filter(mode -> mode.mode() == LIFE)
                .flatMap(mode -> mode.domains().stream())
                .filter(domain -> domain.domainId().equals(domainId))
                .findFirst()
                .orElseThrow();
    }

    private AiSummary exactSummary(List<ReportFact> facts) {
        return new AiSummary(
                exactFact(facts, FactCategory.CONCEPT),
                exactFact(facts, FactCategory.EXPLANATION),
                exactFact(facts, FactCategory.LIFE),
                exactFact(facts, FactCategory.IMPROVED),
                exactFact(facts, FactCategory.OBSERVE));
    }

    private AiNarrative exactFact(List<ReportFact> facts, FactCategory category) {
        return facts.stream()
                .filter(fact -> fact.category() == category)
                .findFirst()
                .map(fact -> new AiNarrative(fact.statement(), List.of(fact.evidenceId())))
                .orElseGet(() -> new AiNarrative("근거 없음", List.of("missing:" + category.name().toLowerCase())));
    }

    private LearningSession session(long id, long learnerId, String domainId, OffsetDateTime completedAt) {
        LearningSession session = LearningSession.start(learnerId, domainId, 17);
        ReflectionTestUtils.setField(session, "id", id);
        ReflectionTestUtils.setField(session, "startedAt", completedAt.minusMinutes(10));
        session.setCompletedAt(completedAt);
        return session;
    }

    private Attempt attempt(
            long id, long sessionId, int attemptNo, boolean correct, OffsetDateTime createdAt) {
        Attempt attempt = Attempt.record(
                sessionId,
                "drill",
                attemptNo,
                "money-count:" + (attemptNo - 1),
                attemptNo - 1,
                correct,
                1_000,
                Map.of());
        ReflectionTestUtils.setField(attempt, "id", id);
        ReflectionTestUtils.setField(attempt, "createdAt", createdAt);
        return attempt;
    }

    private CafeVisit visit(long id, long learnerId, OffsetDateTime completedAt) {
        CafeVisit visit = CafeVisit.start(learnerId);
        ReflectionTestUtils.setField(visit, "id", id);
        ReflectionTestUtils.setField(visit, "startedAt", completedAt.minusHours(1));
        visit.setCompletedAt(completedAt);
        return visit;
    }

    private CafeVisitStage cafeStage(
            long id,
            long visitId,
            CafeStage stage,
            int attemptNo,
            boolean correct,
            OffsetDateTime createdAt) {
        CafeVisitStage evidence = CafeVisitStage.record(
                visitId, stage, attemptNo, correct, 2_000, Map.of());
        ReflectionTestUtils.setField(evidence, "id", id);
        ReflectionTestUtils.setField(evidence, "createdAt", createdAt);
        return evidence;
    }

    private AiConversationEvidence completedConversation(
            String conversationId,
            String learningSessionId,
            String taskId,
            String response,
            String hintLevel,
            OffsetDateTime occurredAt) {
        AiTurnEvidence responseTurn = new AiTurnEvidence(
                "turn-" + conversationId,
                taskId,
                response,
                "text",
                "correct_full",
                "L3",
                hintLevel,
                Map.of("verified_slots", Map.of()),
                occurredAt);
        AiTurnEvidence postResponseSnapshot = new AiTurnEvidence(
                "turn-" + conversationId + "-post",
                taskId,
                null,
                "text",
                null,
                "L3",
                hintLevel,
                Map.of("verified_slots", Map.of("amount", 600)),
                occurredAt.plusSeconds(1));
        return new AiConversationEvidence(
                conversationId,
                learningSessionId,
                "home_teach",
                "home_teach",
                "completed",
                "taught",
                true,
                Map.of("amount", 600),
                hintLevel,
                List.of(responseTurn, postResponseSnapshot),
                occurredAt.minusMinutes(5),
                occurredAt.plusSeconds(1));
    }

    private AiConversationEvidence completedConversationWithoutSnapshots(
            String conversationId,
            String learningSessionId,
            String taskId,
            String response,
            String hintLevel,
            OffsetDateTime occurredAt) {
        AiTurnEvidence turn = new AiTurnEvidence(
                "turn-" + conversationId,
                taskId,
                response,
                "text",
                "correct_full",
                "L3",
                hintLevel,
                Map.of(),
                occurredAt);
        return new AiConversationEvidence(
                conversationId,
                learningSessionId,
                "home_teach",
                "home_teach",
                "completed",
                "taught",
                true,
                Map.of("amount", 600),
                hintLevel,
                List.of(turn),
                occurredAt.minusMinutes(5),
                occurredAt);
    }

    private AiConversationEvidence completedCafeConversation(
            String conversationId,
            String scenarioId,
            String taskId,
            String response,
            OffsetDateTime occurredAt) {
        AiConversationEvidence base = completedConversation(
                conversationId, null, taskId, response, "H0", occurredAt);
        return new AiConversationEvidence(
                base.conversationId(),
                null,
                "cafe",
                scenarioId,
                base.status(),
                base.completionOutcome(),
                base.teachRewardEligible(),
                base.verifiedSlots(),
                base.taskMaxHint(),
                base.turns(),
                base.createdAt(),
                base.updatedAt());
    }
}
