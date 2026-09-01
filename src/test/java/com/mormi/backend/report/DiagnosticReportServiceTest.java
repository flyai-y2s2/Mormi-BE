package com.mormi.backend.report;

import static com.mormi.backend.report.DiagnosticReportDtos.Mode.HOME;
import static com.mormi.backend.report.DiagnosticReportDtos.Mode.LIFE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
import com.mormi.backend.report.DiagnosticReportDtos.AiLadderRecommendation;
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
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

class DiagnosticReportServiceTest {

    private static final long LEARNER_ID = 7L;
    private static final OffsetDateTime JANUARY = OffsetDateTime.parse("2026-01-10T09:00:00+09:00");
    private static final OffsetDateTime FEBRUARY = JANUARY.plusHours(1);
    private static final LocalDate REPORT_WEEK = LocalDate.parse("2026-01-05");
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-19T01:00:00Z"), ZoneId.of("Asia/Seoul"));

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
                dialogueRepository,
                CLOCK);

        learner = Learner.register("민서", "R-007", 1L);
        ReflectionTestUtils.setField(learner, "id", LEARNER_ID);
        ReflectionTestUtils.setField(learner, "createdAt", OffsetDateTime.parse("2026-01-01T09:00:00+09:00"));
        when(learnerService.require(LEARNER_ID)).thenReturn(learner);
        when(sessionRepository.findCompletedAtByLearnerIdAndCurriculumSessionIdInOrderByCompletedAtAsc(
                eq(LEARNER_ID), anyList())).thenReturn(List.of());
        when(cafeVisitRepository.findCompletedAtByLearnerIdOrderByCompletedAtAsc(LEARNER_ID)).thenReturn(List.of());
        when(sessionRepository.findByLearnerIdAndCompletedAtGreaterThanEqualAndCompletedAtLessThanOrderByCompletedAtAsc(
                eq(LEARNER_ID), any(), any()))
                .thenReturn(List.of());
        when(cafeVisitRepository.findByLearnerIdAndCompletedAtGreaterThanEqualAndCompletedAtLessThanOrderByCompletedAtAsc(
                eq(LEARNER_ID), any(), any()))
                .thenReturn(List.of());
        when(dialogueRepository.findByLearnerIdOrderByCreatedAtAsc(LEARNER_ID)).thenReturn(List.of());
        when(aiClient.evidence(LEARNER_ID, true)).thenReturn(Optional.empty());
        when(aiClient.summarize(anyString(), anyList())).thenReturn(Optional.empty());
    }

    @Test
    void currentDefaultsToLatestWeekWithCompletedDataAndListsEveryAvailableWeek() {
        when(sessionRepository.findCompletedAtByLearnerIdAndCurriculumSessionIdInOrderByCompletedAtAsc(
                eq(LEARNER_ID), anyList())).thenReturn(List.of(
                OffsetDateTime.parse("2026-08-05T10:00:00+09:00"),
                OffsetDateTime.parse("2026-08-19T10:00:00+09:00")));
        when(cafeVisitRepository.findCompletedAtByLearnerIdOrderByCompletedAtAsc(LEARNER_ID)).thenReturn(List.of(
                OffsetDateTime.parse("2026-08-12T10:00:00+09:00")));

        DiagnosticReport report = service.current(LEARNER_ID, null);

        assertThat(report.period().weekStart()).isEqualTo(LocalDate.parse("2026-08-17"));
        assertThat(report.period().availableWeekStarts()).containsExactly(
                LocalDate.parse("2026-08-03"),
                LocalDate.parse("2026-08-10"),
                LocalDate.parse("2026-08-17"));
    }

    @Test
    void currentIncludesCompletedHomeSessionOutsideCafePrerequisites() {
        LearningSession clockSession = session(
                11L,
                LEARNER_ID,
                "clock-basic",
                OffsetDateTime.parse("2026-08-19T10:00:00+09:00"));
        when(sessionRepository
                .findByLearnerIdAndCompletedAtGreaterThanEqualAndCompletedAtLessThanOrderByCompletedAtAsc(
                        eq(LEARNER_ID), any(), any()))
                .thenReturn(List.of(clockSession));
        when(attemptRepository.findByLearningSessionIdInOrderByCreatedAtAscIdAsc(List.of(11L)))
                .thenReturn(List.of(attempt(
                        101L,
                        11L,
                        1,
                        true,
                        OffsetDateTime.parse("2026-08-19T10:01:00+09:00"))));

        DiagnosticReport report = service.current(LEARNER_ID, LocalDate.parse("2026-08-17"));

        assertThat(report.dataRange().totalHomeSessions()).isEqualTo(1);
        assertThat(homeTrend(report, "clock-basic").label()).isEqualTo("정각과 30분 단원 · 반복학습");
    }

    @Test
    void currentUsesEveryCurriculumSessionWhenFindingAvailableReportWeeks() {
        when(sessionRepository.findCompletedAtByLearnerIdAndCurriculumSessionIdInOrderByCompletedAtAsc(
                        eq(LEARNER_ID), anyList()))
                .thenAnswer(invocation -> {
                    List<String> requestedSessionIds = invocation.getArgument(1);
                    return requestedSessionIds.contains("clock-basic")
                            ? List.of(OffsetDateTime.parse("2026-08-05T10:00:00+09:00"))
                            : List.of();
                });

        DiagnosticReport report = service.current(LEARNER_ID, null);

        assertThat(report.period().weekStart()).isEqualTo(LocalDate.parse("2026-08-03"));
        assertThat(report.period().availableWeekStarts()).containsExactly(LocalDate.parse("2026-08-03"));
    }

    @Test
    void currentUsesOnlyCompletedRecordsInsideRequestedWeek() {
        when(sessionRepository
                .findByLearnerIdAndCompletedAtGreaterThanEqualAndCompletedAtLessThanOrderByCompletedAtAsc(
                        eq(LEARNER_ID), any(), any()))
                .thenReturn(List.of(
                        session(11L, LEARNER_ID, "money-count", OffsetDateTime.parse("2026-08-23T23:59:00+09:00")),
                        session(12L, LEARNER_ID, "money-count", OffsetDateTime.parse("2026-08-24T00:00:00+09:00")),
                        startedSession(13L, "money-count", OffsetDateTime.parse("2026-08-18T10:00:00+09:00"))));
        when(cafeVisitRepository
                .findByLearnerIdAndCompletedAtGreaterThanEqualAndCompletedAtLessThanOrderByCompletedAtAsc(
                        eq(LEARNER_ID), any(), any()))
                .thenReturn(List.of());

        DiagnosticReport report = service.current(LEARNER_ID, LocalDate.parse("2026-08-17"));

        assertThat(report.period().weekStart()).isEqualTo(LocalDate.parse("2026-08-17"));
        assertThat(report.dataRange().totalHomeSessions()).isEqualTo(1);
        assertThat(report.dataRange().totalLifeVisits()).isZero();
        assertThat(report.dataRange().firstAt()).isEqualTo(OffsetDateTime.parse("2026-08-23T23:59:00+09:00"));

        ArgumentCaptor<OffsetDateTime> start = ArgumentCaptor.forClass(OffsetDateTime.class);
        ArgumentCaptor<OffsetDateTime> end = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(sessionRepository)
                .findByLearnerIdAndCompletedAtGreaterThanEqualAndCompletedAtLessThanOrderByCompletedAtAsc(
                        eq(LEARNER_ID), start.capture(), end.capture());
        verify(cafeVisitRepository)
                .findByLearnerIdAndCompletedAtGreaterThanEqualAndCompletedAtLessThanOrderByCompletedAtAsc(
                        eq(LEARNER_ID), eq(start.getValue()), eq(end.getValue()));
        assertThat(start.getValue()).isEqualTo(OffsetDateTime.parse("2026-08-17T00:00:00+09:00"));
        assertThat(end.getValue()).isEqualTo(OffsetDateTime.parse("2026-08-24T00:00:00+09:00"));
    }

    @Test
    void currentExcludesLaterWeekReplayStagesForAVisitCompletedInsideTheWeek() {
        CafeVisit visit = visit(21L, LEARNER_ID, OffsetDateTime.parse("2026-08-23T20:00:00+09:00"));
        when(cafeVisitRepository.findByLearnerIdAndCompletedAtGreaterThanEqualAndCompletedAtLessThanOrderByCompletedAtAsc(
                eq(LEARNER_ID), any(), any())).thenReturn(List.of(visit));
        when(cafeVisitStageRepository.findByCafeVisitIdInOrderByCreatedAtAscIdAsc(List.of(21L))).thenReturn(List.of(
                cafeStage(201L, 21L, CafeStage.QUEUE, 1, true, OffsetDateTime.parse("2026-08-23T23:57:00+09:00")),
                cafeStage(202L, 21L, CafeStage.MENU, 1, true, OffsetDateTime.parse("2026-08-23T23:58:00+09:00")),
                cafeStage(203L, 21L, CafeStage.CALCULATE, 1, true, OffsetDateTime.parse("2026-08-23T23:59:00+09:00")),
                cafeStage(204L, 21L, CafeStage.CHANGE, 1, true, OffsetDateTime.parse("2026-08-24T00:00:00+09:00"))));

        DiagnosticReport report = service.current(LEARNER_ID, LocalDate.parse("2026-08-17"));

        assertThat(lifeTrend(report, "calculate").points()).extracting(point -> point.occurredAt())
                .containsExactly(OffsetDateTime.parse("2026-08-23T23:59:00+09:00"));
        assertThat(lifeTrend(report, "complete").points().getFirst().independentScore()).isZero();
    }

    @Test
    void reportIncludesCurrentWeekRecommendationForEveryCurriculumSessionAndApprovalKeepsLearnerScope() {
        LearningSession session = session(11L, LEARNER_ID, "clock-basic", JANUARY);
        when(sessionRepository.findByLearnerIdAndCompletedAtGreaterThanEqualAndCompletedAtLessThanOrderByCompletedAtAsc(
                eq(LEARNER_ID), any(), any())).thenReturn(List.of(session));
        AiLadderRecommendation recommendation = new AiLadderRecommendation(
                "ladder-1",
                LEARNER_ID,
                "clock-basic",
                session.getPublicId(),
                List.of("session-before", session.getPublicId()),
                "L2",
                "L3",
                "UPGRADE",
                0.9,
                10,
                "MASTERY_AND_HIGHER_PREDICTIONS",
                List.of(Map.of("level", "L3", "confidence", 0.9)),
                "test-v2",
                1,
                "completed",
                false,
                JANUARY);
        when(aiClient.evidence(LEARNER_ID, true)).thenReturn(Optional.of(new AiReportEvidence(
                LEARNER_ID, List.of(), List.of(), List.of(), List.of(recommendation))));
        when(aiClient.evidence(LEARNER_ID, false)).thenReturn(Optional.of(new AiReportEvidence(
                LEARNER_ID, List.of(), List.of(), List.of(), List.of(recommendation))));
        when(aiClient.approveLadderAnalysis("ladder-1", LEARNER_ID, 1)).thenReturn(true);

        DiagnosticReport report = service.current(LEARNER_ID, REPORT_WEEK);
        var approval = service.approveLadderRecommendation(LEARNER_ID, "ladder-1", 1);

        assertThat(report.ladderRecommendations()).singleElement()
                .satisfies(item -> {
                    assertThat(item.skillId()).isEqualTo("clock-basic");
                    assertThat(item.action()).isEqualTo("UPGRADE");
                    assertThat(item.recommendedLevel()).isEqualTo("L3");
                });
        assertThat(approval.status()).isEqualTo("approved");
        verify(aiClient).approveLadderAnalysis("ladder-1", LEARNER_ID, 1);
    }

    @Test
    void speechEvidenceSupportsEveryCurriculumSessionAndUsesTheSameSelectedWeek() {
        service.speechEvidence(LEARNER_ID, "clock-basic", LocalDate.parse("2026-08-17"));

        verify(sessionRepository)
                .findByLearnerIdAndCompletedAtGreaterThanEqualAndCompletedAtLessThanOrderByCompletedAtAsc(
                        eq(LEARNER_ID), any(), any());
    }

    @Test
    void currentAndSpeechEvidenceExcludeOwnedAiEvidenceOutsideTheSelectedWeek() {
        LearningSession first = session(11L, LEARNER_ID, "money-count", OffsetDateTime.parse("2026-08-18T10:00:00+09:00"));
        LearningSession second = session(12L, LEARNER_ID, "money-count", OffsetDateTime.parse("2026-08-19T10:00:00+09:00"));
        when(sessionRepository.findByLearnerIdAndCompletedAtGreaterThanEqualAndCompletedAtLessThanOrderByCompletedAtAsc(
                eq(LEARNER_ID), any(), any())).thenReturn(List.of(first, second));
        when(dialogueRepository.findByLearnerIdOrderByCreatedAtAsc(LEARNER_ID)).thenReturn(List.of(
                DialogueConversation.forLearningSession("conversation-inside", LEARNER_ID, 11L),
                DialogueConversation.forLearningSession("conversation-later", LEARNER_ID, 12L)));
        when(aiClient.evidence(LEARNER_ID, true)).thenReturn(Optional.of(new AiReportEvidence(
                LEARNER_ID,
                List.of(
                        completedConversation("conversation-inside", first.getPublicId(), "같은 과제", "이번 주 발화", "H1",
                                OffsetDateTime.parse("2026-08-18T11:00:00+09:00")),
                        completedConversation("conversation-later", second.getPublicId(), "같은 과제", "다음 주 재생", "H0",
                                OffsetDateTime.parse("2026-08-24T00:00:00+09:00"))),
                List.of(),
                List.of())));

        DiagnosticReport report = service.current(LEARNER_ID, LocalDate.parse("2026-08-17"));
        SpeechEvidence evidence = service.speechEvidence(LEARNER_ID, "money-count", LocalDate.parse("2026-08-17"));

        assertThat(report.evidenceCounts().teachConversations()).isEqualTo(1);
        assertThat(evidence.available()).isTrue();
        assertThat(evidence.past()).isNull();
        assertThat(evidence.recent().utterance()).isEqualTo("이번 주 발화");
    }

    @Test
    void speechEvidenceExcludesOutOfWeekTurnsFromAnOtherwiseSelectedConversation() {
        LearningSession first = session(11L, LEARNER_ID, "money-count", OffsetDateTime.parse("2026-08-18T10:00:00+09:00"));
        LearningSession second = session(12L, LEARNER_ID, "money-count", OffsetDateTime.parse("2026-08-19T10:00:00+09:00"));
        when(sessionRepository.findByLearnerIdAndCompletedAtGreaterThanEqualAndCompletedAtLessThanOrderByCompletedAtAsc(
                eq(LEARNER_ID), any(), any())).thenReturn(List.of(first, second));
        when(dialogueRepository.findByLearnerIdOrderByCreatedAtAsc(LEARNER_ID)).thenReturn(List.of(
                DialogueConversation.forLearningSession("conversation-inside", LEARNER_ID, 11L),
                DialogueConversation.forLearningSession("conversation-later-turn", LEARNER_ID, 12L)));
        AiConversationEvidence laterTurns = completedConversation(
                "conversation-later-turn", second.getPublicId(), "같은 과제", "다음 주 발화", "H0",
                OffsetDateTime.parse("2026-08-24T00:00:00+09:00"));
        when(aiClient.evidence(LEARNER_ID, true)).thenReturn(Optional.of(new AiReportEvidence(
                LEARNER_ID,
                List.of(
                        completedConversation("conversation-inside", first.getPublicId(), "같은 과제", "이번 주 발화", "H1",
                                OffsetDateTime.parse("2026-08-18T11:00:00+09:00")),
                        withUpdatedAt(laterTurns, OffsetDateTime.parse("2026-08-19T11:00:00+09:00"))),
                List.of(),
                List.of())));

        SpeechEvidence evidence = service.speechEvidence(LEARNER_ID, "money-count", LocalDate.parse("2026-08-17"));

        assertThat(evidence.available()).isTrue();
        assertThat(evidence.past()).isNull();
        assertThat(evidence.recent().utterance()).isEqualTo("이번 주 발화");
    }

    @Test
    void currentCombinesAllCompletedAnalyzableSessionsAndCafeVisitsWithBatchReads() {
        LearningSession oldMoneySession = session(11L, LEARNER_ID, "money-count", JANUARY);
        LearningSession recentMoneySession = session(12L, LEARNER_ID, "money-count", FEBRUARY);
        CafeVisit cafeVisit = visit(21L, LEARNER_ID, FEBRUARY.plusDays(1));
        when(sessionRepository.findByLearnerIdAndCompletedAtGreaterThanEqualAndCompletedAtLessThanOrderByCompletedAtAsc(
                eq(LEARNER_ID), any(), any()))
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
        when(cafeVisitRepository.findByLearnerIdAndCompletedAtGreaterThanEqualAndCompletedAtLessThanOrderByCompletedAtAsc(
                eq(LEARNER_ID), any(), any()))
                .thenReturn(List.of(cafeVisit));
        when(cafeVisitStageRepository.findByCafeVisitIdInOrderByCreatedAtAscIdAsc(List.of(21L)))
                .thenReturn(List.of(cafeStage(201L, 21L, CafeStage.CALCULATE, 1, true, FEBRUARY.plusDays(1))));
        when(aiClient.evidence(LEARNER_ID, true)).thenReturn(Optional.of(emptyAiEvidence()));

        DiagnosticReport report = service.current(LEARNER_ID, REPORT_WEEK);

        assertThat(report.dataRange().totalHomeSessions()).isEqualTo(2);
        assertThat(report.dataRange().totalLifeVisits()).isEqualTo(1);
        assertThat(report.modes()).extracting(ModeReport::mode).containsExactly(HOME, LIFE);
        assertThat(report.modes().getFirst().domains()).extracting(domain -> domain.label())
                .contains("돈을 세어요 단원 · 반복학습");
        assertThat(report.modes().getLast().domains()).extracting(domain -> domain.label())
                .contains("메뉴 값 계산하기 단원");
        assertThat(report.evidenceCounts().drillAttempts()).isEqualTo(3);
        verify(attemptRepository).findByLearningSessionIdInOrderByCreatedAtAscIdAsc(List.of(11L, 12L));
        verify(cafeVisitStageRepository).findByCafeVisitIdInOrderByCreatedAtAscIdAsc(List.of(21L));
    }

    @Test
    void currentCountsCompletedTeachOnlyHomeSessionWithoutFabricatingDrillEvidence() {
        LearningSession teachOnly = session(11L, LEARNER_ID, "money-count", JANUARY);
        when(sessionRepository.findByLearnerIdAndCompletedAtGreaterThanEqualAndCompletedAtLessThanOrderByCompletedAtAsc(
                eq(LEARNER_ID), any(), any()))
                .thenReturn(List.of(teachOnly));
        when(attemptRepository.findByLearningSessionIdInOrderByCreatedAtAscIdAsc(List.of(11L)))
                .thenReturn(List.of());

        DiagnosticReport report = service.current(LEARNER_ID, REPORT_WEEK);

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
        when(sessionRepository.findByLearnerIdAndCompletedAtGreaterThanEqualAndCompletedAtLessThanOrderByCompletedAtAsc(
                eq(LEARNER_ID), any(), any()))
                .thenReturn(List.of(moneySession));
        when(attemptRepository.findByLearningSessionIdInOrderByCreatedAtAscIdAsc(List.of(11L)))
                .thenReturn(List.of(attempt(101L, 11L, 1, true, JANUARY)));
        when(aiClient.evidence(LEARNER_ID, true))
                .thenThrow(ApiException.serviceUnavailable("ai", "offline"));

        DiagnosticReport report = service.current(LEARNER_ID, REPORT_WEEK);

        assertThat(report.narrativeFallback()).isTrue();
        assertThat(report.modes()).isNotEmpty();
        assertThat(report.currentSummary().conceptPerformance().text()).contains("돈을 세어요");
        assertThat(report.currentSummary().explanationChange().text()).contains("발화 근거");
    }

    @Test
    void currentRanksDecliningDevelopingConceptAboveSingleObservingConceptInFactsAndFallback() {
        givenObservingBudgetAndDecliningCountEvidence();

        DiagnosticReport report = service.current(LEARNER_ID, REPORT_WEEK);

        assertThat(report.currentSummary().conceptPerformance().text())
                .isEqualTo("돈을 세어요 단원 반복학습 수행은 최근 60%이며 상태는 발달 중입니다.");
        assertThat(report.currentSummary().conceptPerformance().evidenceRefs())
                .containsExactly("drill:money-count");
        assertThat(report.observePoint().text())
                .isEqualTo("돈을 세어요 단원의 현재 상태는 발달 중이므로 계속 관찰합니다.");
        assertThat(report.observePoint().evidenceRefs())
                .containsExactly("observe:drill:money-count");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ReportFact>> facts = ArgumentCaptor.forClass(List.class);
        verify(aiClient).summarize(
                org.mockito.ArgumentMatchers.eq(learner.getAnalyticsId().toString()), facts.capture());
        assertThat(facts.getValue().stream()
                        .filter(fact -> fact.category() == FactCategory.CONCEPT)
                        .map(ReportFact::evidenceId))
                .containsExactly("drill:money-count", "drill:money-budget");
    }

    @Test
    void currentChoosesImprovingFactWithStrongestRecentAndTotalEvidence() {
        givenTwoImprovingDomainsWithDifferentEvidenceStrength();

        DiagnosticReport report = service.current(LEARNER_ID, REPORT_WEEK);

        assertThat(report.improvedPoint().text())
                .isEqualTo("돈을 세어요 단원 반복학습 수행은 이전 기록보다 좋아졌습니다.");
        assertThat(report.improvedPoint().evidenceRefs())
                .containsExactly("improved:drill:money-count");
    }

    @Test
    void currentRequestsRawEvidenceOnlyWhenStoredConsentAndRetentionPermitIt() {
        learner.applyConsent(false, null);

        service.current(LEARNER_ID, REPORT_WEEK);

        verify(aiClient).evidence(LEARNER_ID, false);
    }

    @Test
    void currentDoesNotRequestRawEvidenceForAnInconsistentNoRawRetentionRecord() {
        ReflectionTestUtils.setField(learner, "retentionPolicy", "no_raw");

        service.current(LEARNER_ID, REPORT_WEEK);

        verify(aiClient).evidence(LEARNER_ID, false);
    }

    @Test
    void currentUsesOnlyCompletedAiConversationsOwnedByTheSameSpringSession() {
        LearningSession moneySession = session(11L, LEARNER_ID, "money-count", JANUARY);
        when(sessionRepository.findByLearnerIdAndCompletedAtGreaterThanEqualAndCompletedAtLessThanOrderByCompletedAtAsc(
                eq(LEARNER_ID), any(), any()))
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

        DiagnosticReport report = service.current(LEARNER_ID, REPORT_WEEK);

        assertThat(report.evidenceCounts().teachConversations()).isEqualTo(1);
        assertThat(report.modes().getFirst().domains()).extracting(domain -> domain.label())
                .contains("돈을 세어요 단원 · 모르미 가르치기");
    }

    @Test
    void currentDoesNotTreatCompletedCafeDialogueAsHomeTeachEvidence() {
        CafeVisit cafeVisit = visit(21L, LEARNER_ID, FEBRUARY);
        DialogueConversation cafeDialogue = DialogueConversation.forCafeVisit(
                "conversation-cafe", LEARNER_ID, 21L, "cafe_menu_total", 1, Map.of());
        when(cafeVisitRepository.findByLearnerIdAndCompletedAtGreaterThanEqualAndCompletedAtLessThanOrderByCompletedAtAsc(
                eq(LEARNER_ID), any(), any()))
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

        DiagnosticReport report = service.current(LEARNER_ID, REPORT_WEEK);

        assertThat(report.evidenceCounts().teachConversations()).isZero();
        assertThat(report.modes().getFirst().domains())
                .noneMatch(domain -> domain.label().contains("모르미 가르치기"));
    }

    @Test
    void currentAcceptsOnlyExactAiSelectionsOfDeterministicFacts() {
        LearningSession moneySession = session(11L, LEARNER_ID, "money-count", JANUARY);
        when(sessionRepository.findByLearnerIdAndCompletedAtGreaterThanEqualAndCompletedAtLessThanOrderByCompletedAtAsc(
                eq(LEARNER_ID), any(), any()))
                .thenReturn(List.of(moneySession));
        when(attemptRepository.findByLearningSessionIdInOrderByCreatedAtAscIdAsc(List.of(11L)))
                .thenReturn(List.of(attempt(101L, 11L, 1, true, JANUARY)));
        givenTeachAndLifeEvidence(moneySession);
        when(aiClient.summarize(anyString(), anyList())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<com.mormi.backend.report.DiagnosticReportDtos.ReportFact> facts = invocation.getArgument(1);
            return Optional.of(exactSummary(facts));
        });

        DiagnosticReport report = service.current(LEARNER_ID, REPORT_WEEK);

        assertThat(report.narrativeFallback()).isFalse();
        assertThat(report.currentSummary().conceptPerformance().text())
                .isEqualTo("돈을 세어요 단원 반복학습 수행은 최근 100%이며 상태는 관찰 중입니다.");
    }

    @Test
    void currentRejectsExactFactWhenAiUsesItInTheWrongNarrativeCategory() {
        LearningSession moneySession = session(11L, LEARNER_ID, "money-count", JANUARY);
        when(sessionRepository.findByLearnerIdAndCompletedAtGreaterThanEqualAndCompletedAtLessThanOrderByCompletedAtAsc(
                eq(LEARNER_ID), any(), any()))
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

        DiagnosticReport report = service.current(LEARNER_ID, REPORT_WEEK);

        assertThat(report.narrativeFallback()).isTrue();
    }

    @Test
    void currentUsesAnalyticsIdForAiAndKeepsDisplayNameOnlyInLocalHeader() {
        DiagnosticReport report = service.current(LEARNER_ID, REPORT_WEEK);

        verify(aiClient).summarize(org.mockito.ArgumentMatchers.eq(learner.getAnalyticsId().toString()), anyList());
        assertThat(report.learner().displayName()).isEqualTo("민서");
    }

    @Test
    void currentClassifiesAiAssistedCafeStagesAsScaffoldedAndAddsVisitCompletionEvidence() {
        CafeVisit directVisit = visit(21L, LEARNER_ID, JANUARY);
        CafeVisit aiVisit = visit(22L, LEARNER_ID, FEBRUARY);
        when(cafeVisitRepository.findByLearnerIdAndCompletedAtGreaterThanEqualAndCompletedAtLessThanOrderByCompletedAtAsc(
                eq(LEARNER_ID), any(), any()))
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

        DiagnosticReport report = service.current(LEARNER_ID, REPORT_WEEK);

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
        when(sessionRepository.findByLearnerIdAndCompletedAtGreaterThanEqualAndCompletedAtLessThanOrderByCompletedAtAsc(
                eq(LEARNER_ID), any(), any()))
                .thenReturn(List.of(owned, otherLearner, unknown));
        when(attemptRepository.findByLearningSessionIdInOrderByCreatedAtAscIdAsc(List.of(11L)))
                .thenReturn(List.of(
                        attempt(101L, 11L, 1, true, JANUARY),
                        attempt(102L, 12L, 1, true, JANUARY)));

        DiagnosticReport report = service.current(LEARNER_ID, REPORT_WEEK);

        assertThat(report.dataRange().totalHomeSessions()).isEqualTo(1);
        assertThat(report.evidenceCounts().drillAttempts()).isEqualTo(1);
        assertThat(report.domains()).extracting(domain -> domain.domainId())
                .containsOnly("money-count");
    }

    @Test
    void currentRejectsAiSummaryThatDoesNotSelectExactKnownFacts() {
        LearningSession moneySession = session(11L, LEARNER_ID, "money-count", JANUARY);
        when(sessionRepository.findByLearnerIdAndCompletedAtGreaterThanEqualAndCompletedAtLessThanOrderByCompletedAtAsc(
                eq(LEARNER_ID), any(), any()))
                .thenReturn(List.of(moneySession));
        when(attemptRepository.findByLearningSessionIdInOrderByCreatedAtAscIdAsc(List.of(11L)))
                .thenReturn(List.of(attempt(101L, 11L, 1, true, JANUARY)));
        AiNarrative invented = new AiNarrative("근거에 없는 해석입니다.", List.of("missing:fact"));
        when(aiClient.summarize(anyString(), anyList()))
                .thenReturn(Optional.of(new AiSummary(invented, invented, invented, invented, invented)));

        DiagnosticReport report = service.current(LEARNER_ID, REPORT_WEEK);

        assertThat(report.narrativeFallback()).isTrue();
        assertThat(report.currentSummary().conceptPerformance().text()).doesNotContain("근거에 없는");
    }

    @Test
    void speechEvidenceReturnsExactEarliestAndLatestComparableStoredUtterances() {
        LearningSession pastSession = session(11L, LEARNER_ID, "money-count", JANUARY);
        LearningSession recentSession = session(12L, LEARNER_ID, "money-count", FEBRUARY);
        DialogueConversation pastOwner = DialogueConversation.forLearningSession("conversation-past", LEARNER_ID, 11L);
        DialogueConversation recentOwner = DialogueConversation.forLearningSession("conversation-recent", LEARNER_ID, 12L);
        when(sessionRepository.findByLearnerIdAndCompletedAtGreaterThanEqualAndCompletedAtLessThanOrderByCompletedAtAsc(
                eq(LEARNER_ID), any(), any()))
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

        SpeechEvidence evidence = service.speechEvidence(LEARNER_ID, "money-count", REPORT_WEEK);

        assertThat(evidence.available()).isTrue();
        assertThat(evidence.past().utterance()).isEqualTo("500원만 세었어");
        assertThat(evidence.recent().utterance()).isEqualTo("500원과 100원을 더해서 600원이야");
        assertThat(evidence.past().hintLevel()).isEqualTo("H3");
        assertThat(evidence.recent().hintLevel()).isEqualTo("H0");
        assertThat(evidence.verifiedElements()).containsExactly("amount");
    }

    @Test
    void speechEvidencePrefersAiBeforeAfterAnalysisOverTheFactualFallback() {
        aiClient = mock(ReportAiClient.class, invocation -> {
            if (invocation.getMethod().getName().equals("summarizeSpeechChange")) {
                return Optional.of(
                        "답만 짧게 말하던 모습에서 수를 세는 순서를 말로 표현하는 모습으로 변화했습니다.");
            }
            return Answers.RETURNS_DEFAULTS.answer(invocation);
        });
        service = new DiagnosticReportService(
                aiClient,
                learnerService,
                sessionRepository,
                attemptRepository,
                cafeVisitRepository,
                cafeVisitStageRepository,
                dialogueRepository,
                CLOCK);
        LearningSession pastSession = session(11L, LEARNER_ID, "money-count", JANUARY);
        LearningSession recentSession = session(12L, LEARNER_ID, "money-count", FEBRUARY);
        when(sessionRepository.findByLearnerIdAndCompletedAtGreaterThanEqualAndCompletedAtLessThanOrderByCompletedAtAsc(
                eq(LEARNER_ID), any(), any()))
                .thenReturn(List.of(pastSession, recentSession));
        when(dialogueRepository.findByLearnerIdOrderByCreatedAtAsc(LEARNER_ID))
                .thenReturn(List.of(
                        DialogueConversation.forLearningSession("conversation-past", LEARNER_ID, 11L),
                        DialogueConversation.forLearningSession("conversation-recent", LEARNER_ID, 12L)));
        when(aiClient.evidence(LEARNER_ID, true)).thenReturn(Optional.of(new AiReportEvidence(
                LEARNER_ID,
                List.of(
                        completedConversation(
                                "conversation-past",
                                pastSession.getPublicId(),
                                "같은 과제",
                                "점 3개야",
                                "H0",
                                JANUARY),
                        completedConversation(
                                "conversation-recent",
                                recentSession.getPublicId(),
                                "같은 과제",
                                "하나 둘 셋 3개",
                                "H0",
                                FEBRUARY)),
                List.of(),
                List.of())));

        SpeechEvidence evidence = service.speechEvidence(LEARNER_ID, "money-count", REPORT_WEEK);

        assertThat(evidence.changeSummary()).isEqualTo(
                "답만 짧게 말하던 모습에서 수를 세는 순서를 말로 표현하는 모습으로 변화했습니다.");
    }

    @Test
    void speechEvidenceDoesNotUseConversationFinalSlotsWithoutAdjacentTurnSnapshots() {
        LearningSession pastSession = session(11L, LEARNER_ID, "money-count", JANUARY);
        LearningSession recentSession = session(12L, LEARNER_ID, "money-count", FEBRUARY);
        when(sessionRepository.findByLearnerIdAndCompletedAtGreaterThanEqualAndCompletedAtLessThanOrderByCompletedAtAsc(
                eq(LEARNER_ID), any(), any()))
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

        SpeechEvidence evidence = service.speechEvidence(LEARNER_ID, "money-count", REPORT_WEEK);

        assertThat(evidence.available()).isTrue();
        assertThat(evidence.past().utterance()).isEqualTo("과거 발화");
        assertThat(evidence.recent().utterance()).isEqualTo("최근 발화");
    }

    @Test
    void speechEvidenceSkipsStructuredChoicePayloadsThatAreNotChildSpeech() {
        LearningSession pastSession = session(11L, LEARNER_ID, "money-count", JANUARY);
        LearningSession recentSession = session(12L, LEARNER_ID, "money-count", FEBRUARY);
        when(sessionRepository.findByLearnerIdAndCompletedAtGreaterThanEqualAndCompletedAtLessThanOrderByCompletedAtAsc(
                eq(LEARNER_ID), any(), any()))
                .thenReturn(List.of(recentSession, pastSession));
        when(dialogueRepository.findByLearnerIdOrderByCreatedAtAsc(LEARNER_ID))
                .thenReturn(List.of(
                        DialogueConversation.forLearningSession("conversation-past", LEARNER_ID, 11L),
                        DialogueConversation.forLearningSession("conversation-recent", LEARNER_ID, 12L)));
        AiConversationEvidence structured = withResponseType(
                completedConversationWithoutSnapshots(
                        "conversation-recent",
                        recentSession.getPublicId(),
                        "같은 과제",
                        "{'answer': '3', 'tracking': 'count_each_once'}",
                        "H3",
                        FEBRUARY),
                "choice");
        when(aiClient.evidence(LEARNER_ID, true)).thenReturn(Optional.of(new AiReportEvidence(
                LEARNER_ID,
                List.of(
                        completedConversationWithoutSnapshots(
                                "conversation-past",
                                pastSession.getPublicId(),
                                "같은 과제",
                                "점을 하나씩 세어서 3개야",
                                "H0",
                                JANUARY),
                        structured),
                List.of(),
                List.of())));

        SpeechEvidence evidence = service.speechEvidence(LEARNER_ID, "money-count", REPORT_WEEK);

        assertThat(evidence.available()).isTrue();
        assertThat(evidence.past()).isNull();
        assertThat(evidence.recent().utterance()).isEqualTo("점을 하나씩 세어서 3개야");
    }

    @Test
    void speechEvidenceUsesEvidenceIdAsTieBreakerWhenComparableTurnsShareATimestamp() {
        LearningSession firstSession = session(11L, LEARNER_ID, "money-count", JANUARY);
        LearningSession secondSession = session(12L, LEARNER_ID, "money-count", JANUARY.plusMinutes(1));
        when(sessionRepository.findByLearnerIdAndCompletedAtGreaterThanEqualAndCompletedAtLessThanOrderByCompletedAtAsc(
                eq(LEARNER_ID), any(), any()))
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

        SpeechEvidence evidence = service.speechEvidence(LEARNER_ID, "money-count", REPORT_WEEK);

        assertThat(evidence.past().utterance()).isEqualTo("증거 ID가 앞인 발화");
        assertThat(evidence.recent().utterance()).isEqualTo("증거 ID가 뒤인 발화");
    }

    @Test
    void currentOffersAiOnlyPresentationReadyFactsIncludingComparableSpeech() {
        LearningSession pastSession = session(11L, LEARNER_ID, "money-count", JANUARY);
        LearningSession recentSession = session(12L, LEARNER_ID, "money-count", FEBRUARY);
        when(sessionRepository.findByLearnerIdAndCompletedAtGreaterThanEqualAndCompletedAtLessThanOrderByCompletedAtAsc(
                eq(LEARNER_ID), any(), any()))
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

        service.current(LEARNER_ID, REPORT_WEEK);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ReportFact>> facts = ArgumentCaptor.forClass(List.class);
        verify(aiClient).summarize(
                org.mockito.ArgumentMatchers.eq(learner.getAnalyticsId().toString()), facts.capture());
        assertThat(facts.getValue())
                .filteredOn(fact -> fact.evidenceId().equals("speech:money-count"))
                .extracting(ReportFact::statement)
                .containsExactly("돈을 세어요 단원 발화 비교에서 공통 검증 요소 1개가 확인되었고 도움 수준은 H3에서 H0로 바뀌었습니다.");
        assertThat(facts.getValue()).allMatch(fact -> !fact.statement().contains(" recent "));
    }

    @Test
    void speechEvidenceDoesNotCompareDifferentTasksOrUnownedAiConversations() {
        LearningSession pastSession = session(11L, LEARNER_ID, "money-count", JANUARY);
        LearningSession recentSession = session(12L, LEARNER_ID, "money-count", FEBRUARY);
        when(sessionRepository.findByLearnerIdAndCompletedAtGreaterThanEqualAndCompletedAtLessThanOrderByCompletedAtAsc(
                eq(LEARNER_ID), any(), any()))
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

        SpeechEvidence evidence = service.speechEvidence(LEARNER_ID, "money-count", REPORT_WEEK);

        assertThat(evidence.available()).isTrue();
        assertThat(evidence.past().utterance()).isEqualTo("과거 발화");
        assertThat(evidence.recent().utterance()).isEqualTo("최근 발화");
        assertThat(evidence.recent().utterance()).doesNotContain("섞이면 안 되는 발화");
    }

    private AiReportEvidence emptyAiEvidence() {
        return new AiReportEvidence(LEARNER_ID, List.of(), List.of(), List.of());
    }

    private void givenObservingBudgetAndDecliningCountEvidence() {
        List<LearningSession> sessions = new java.util.ArrayList<>();
        List<Attempt> attempts = new java.util.ArrayList<>();
        sessions.add(session(11L, LEARNER_ID, "money-budget", JANUARY));
        attempts.add(attempt(101L, 11L, 1, true, JANUARY));
        boolean[] countCorrect = {true, true, true, true, true, true, true, false, false};
        for (int index = 0; index < countCorrect.length; index++) {
            long sessionId = 12L + index;
            OffsetDateTime completedAt = JANUARY.plusHours(index + 1L);
            sessions.add(session(sessionId, LEARNER_ID, "money-count", completedAt));
            attempts.add(attempt(102L + index, sessionId, 1, countCorrect[index], completedAt));
        }
        when(sessionRepository.findByLearnerIdAndCompletedAtGreaterThanEqualAndCompletedAtLessThanOrderByCompletedAtAsc(
                eq(LEARNER_ID), any(), any()))
                .thenReturn(sessions.reversed());
        when(attemptRepository.findByLearningSessionIdInOrderByCreatedAtAscIdAsc(
                        java.util.stream.LongStream.rangeClosed(11L, 20L).boxed().toList()))
                .thenReturn(attempts);
    }

    private void givenTwoImprovingDomainsWithDifferentEvidenceStrength() {
        List<LearningSession> sessions = new java.util.ArrayList<>();
        List<Attempt> attempts = new java.util.ArrayList<>();
        boolean[] countCorrect = {false, false, false, false, true, true, true, true, true};
        boolean[] budgetCorrect = {false, false, true, true, true, true, true};
        long sessionId = 11L;
        long attemptId = 101L;
        for (int index = 0; index < countCorrect.length; index++) {
            OffsetDateTime completedAt = JANUARY.plusHours(index);
            sessions.add(session(sessionId, LEARNER_ID, "money-count", completedAt));
            attempts.add(attempt(attemptId, sessionId, 1, countCorrect[index], completedAt));
            sessionId++;
            attemptId++;
        }
        for (int index = 0; index < budgetCorrect.length; index++) {
            OffsetDateTime completedAt = JANUARY.plusHours(10L + index);
            sessions.add(session(sessionId, LEARNER_ID, "money-budget", completedAt));
            attempts.add(attempt(attemptId, sessionId, 1, budgetCorrect[index], completedAt));
            sessionId++;
            attemptId++;
        }
        when(sessionRepository.findByLearnerIdAndCompletedAtGreaterThanEqualAndCompletedAtLessThanOrderByCompletedAtAsc(
                eq(LEARNER_ID), any(), any()))
                .thenReturn(sessions.reversed());
        when(attemptRepository.findByLearningSessionIdInOrderByCreatedAtAscIdAsc(
                        java.util.stream.LongStream.rangeClosed(11L, 26L).boxed().toList()))
                .thenReturn(attempts);
    }

    private void givenTeachAndLifeEvidence(LearningSession moneySession) {
        CafeVisit cafeVisit = visit(21L, LEARNER_ID, FEBRUARY);
        when(cafeVisitRepository.findByLearnerIdAndCompletedAtGreaterThanEqualAndCompletedAtLessThanOrderByCompletedAtAsc(
                eq(LEARNER_ID), any(), any()))
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

    private DomainTrend homeTrend(DiagnosticReport report, String domainId) {
        return report.modes().stream()
                .filter(mode -> mode.mode() == HOME)
                .flatMap(mode -> mode.domains().stream())
                .filter(domain -> domain.domainId().equals(domainId))
                .findFirst()
                .orElseThrow();
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

    private LearningSession completedSession(String domainId, String completedAt) {
        return session(11L, LEARNER_ID, domainId, OffsetDateTime.parse(completedAt));
    }

    private LearningSession startedSession(long id, String domainId, OffsetDateTime startedAt) {
        LearningSession session = LearningSession.start(LEARNER_ID, domainId, 17);
        ReflectionTestUtils.setField(session, "id", id);
        ReflectionTestUtils.setField(session, "startedAt", startedAt);
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
                null,
                null,
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

    private AiConversationEvidence withUpdatedAt(
            AiConversationEvidence conversation, OffsetDateTime updatedAt) {
        return new AiConversationEvidence(
                conversation.conversationId(),
                conversation.learningSessionId(),
                conversation.scene(),
                conversation.scenarioId(),
                conversation.status(),
                conversation.completionOutcome(),
                conversation.teachRewardEligible(),
                conversation.verifiedSlots(),
                conversation.taskMaxHint(),
                conversation.turns(),
                conversation.createdAt(),
                updatedAt);
    }

    private AiConversationEvidence withResponseType(
            AiConversationEvidence conversation, String responseType) {
        AiTurnEvidence turn = conversation.turns().getFirst();
        AiTurnEvidence replaced = new AiTurnEvidence(
                turn.turnId(),
                turn.taskId(),
                turn.response(),
                responseType,
                turn.responseCategory(),
                turn.expressionLevel(),
                turn.hintLevel(),
                turn.pedagogy(),
                turn.createdAt());
        return new AiConversationEvidence(
                conversation.conversationId(),
                conversation.learningSessionId(),
                conversation.scene(),
                conversation.scenarioId(),
                conversation.status(),
                conversation.completionOutcome(),
                conversation.teachRewardEligible(),
                conversation.verifiedSlots(),
                conversation.taskMaxHint(),
                List.of(replaced),
                conversation.createdAt(),
                conversation.updatedAt());
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
