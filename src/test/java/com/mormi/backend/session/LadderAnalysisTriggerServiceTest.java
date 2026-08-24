package com.mormi.backend.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mormi.backend.report.ReportAiClient;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class LadderAnalysisTriggerServiceTest {

    @Mock LearningSessionRepository sessionRepository;
    @Mock AttemptRepository attemptRepository;
    @Mock ReportAiClient reportAiClient;
    @Mock LadderAnalysisOutboxRepository outboxRepository;
    @Mock LadderAnalysisOutboxClaimService claimService;

    LadderAnalysisTriggerService service;

    @BeforeEach
    void setUp() {
        service = new LadderAnalysisTriggerService(
                sessionRepository, attemptRepository, reportAiClient, outboxRepository, claimService);
    }

    @Test
    void registersOnlyWhenTheLatestTwoCompletedSessionsUseTheSameSubunit() {
        LearningSession older = completed(11L, 7L, "money-count", "session-old", 1);
        LearningSession latest = completed(12L, 7L, "money-count", "session-new", 2);
        when(sessionRepository.findByPublicId("session-new")).thenReturn(java.util.Optional.of(latest));
        when(sessionRepository.findTop2ByLearnerIdAndCurriculumSessionIdAndCompletedAtIsNotNullOrderByCompletedAtDescIdDesc(
                        7L, "money-count"))
                .thenReturn(List.of(latest, older));
        when(attemptRepository.findByLearningSessionIdInOrderByCreatedAtAscIdAsc(List.of(11L, 12L)))
                .thenReturn(List.of(
                        attempt(11L, 1, true, "L2"),
                        attempt(11L, 2, false, "L1"),
                        attempt(12L, 1, true, "L2"),
                        attempt(12L, 2, true, "L3")));

        service.evaluate(new LadderAnalysisTrigger(7L, "session-new"));

        ArgumentCaptor<LadderAnalysisTrigger.Request> request =
                ArgumentCaptor.forClass(LadderAnalysisTrigger.Request.class);
        verify(reportAiClient).registerLadderAnalysis(request.capture());
        assertThat(request.getValue().sessionIds()).containsExactly("session-old", "session-new");
        assertThat(request.getValue().currentLevel()).isEqualTo("L3");
        assertThat(request.getValue().performanceByLevel().get("L2"))
                .isEqualTo(new LadderAnalysisTrigger.Performance(2, 3));
        assertThat(request.getValue().performanceByLevel().get("L3"))
                .isEqualTo(new LadderAnalysisTrigger.Performance(1, 1));
    }

    @Test
    void ignoresOneSessionDifferentSubunitsAndReplayedOlderCompletion() {
        LearningSession latest = completed(12L, 7L, "money-count", "session-new", 2);
        when(sessionRepository.findByPublicId("session-new")).thenReturn(java.util.Optional.of(latest));
        when(sessionRepository.findTop2ByLearnerIdAndCurriculumSessionIdAndCompletedAtIsNotNullOrderByCompletedAtDescIdDesc(
                        7L, "money-count"))
                .thenReturn(List.of(latest));
        service.evaluate(new LadderAnalysisTrigger(7L, "session-new"));

        LearningSession prior = completed(10L, 7L, "money-count", "session-prior", 1);
        when(sessionRepository.findByPublicId("session-prior")).thenReturn(java.util.Optional.of(prior));
        service.evaluate(new LadderAnalysisTrigger(7L, "session-prior"));

        verify(reportAiClient, never()).registerLadderAnalysis(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void findsTwoCompletionsOfTheTriggeredSubunitEvenWhenAnotherSubunitFinishedBetweenThem() {
        LearningSession older = completed(11L, 7L, "money-count", "session-old", 1);
        LearningSession latest = completed(13L, 7L, "money-count", "session-new", 3);
        when(sessionRepository.findByPublicId("session-new")).thenReturn(java.util.Optional.of(latest));
        when(sessionRepository.findTop2ByLearnerIdAndCurriculumSessionIdAndCompletedAtIsNotNullOrderByCompletedAtDescIdDesc(
                        7L, "money-count"))
                .thenReturn(List.of(latest, older));
        when(attemptRepository.findByLearningSessionIdInOrderByCreatedAtAscIdAsc(List.of(11L, 13L)))
                .thenReturn(List.of(attempt(11L, 1, true, "L2"), attempt(13L, 1, true, "L3")));

        service.evaluate(new LadderAnalysisTrigger(7L, "session-new"));

        verify(reportAiClient).registerLadderAnalysis(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void storesACompletionInTheDurableOutbox() {
        when(outboxRepository.findByTriggerSessionId("session-new")).thenReturn(Optional.empty());

        service.schedule(7L, "session-new");

        ArgumentCaptor<LadderAnalysisOutbox> row = ArgumentCaptor.forClass(LadderAnalysisOutbox.class);
        verify(outboxRepository).save(row.capture());
        assertThat(row.getValue().getLearnerId()).isEqualTo(7L);
        assertThat(row.getValue().getTriggerSessionId()).isEqualTo("session-new");
        assertThat(row.getValue().getStatus()).isEqualTo("pending");
    }

    @Test
    void retriesUnavailableAiAndMarksAcceptedDeliveryAsSent() {
        LearningSession older = completed(11L, 7L, "money-count", "session-old", 1);
        LearningSession latest = completed(12L, 7L, "money-count", "session-new", 2);
        var claim = new LadderAnalysisOutboxClaimService.Claim(31L, 7L, "session-new", "claim-1");
        when(claimService.claim()).thenReturn(List.of(claim));
        when(sessionRepository.findByPublicId("session-new")).thenReturn(Optional.of(latest));
        when(sessionRepository.findTop2ByLearnerIdAndCurriculumSessionIdAndCompletedAtIsNotNullOrderByCompletedAtDescIdDesc(
                        7L, "money-count"))
                .thenReturn(List.of(latest, older));
        when(attemptRepository.findByLearningSessionIdInOrderByCreatedAtAscIdAsc(List.of(11L, 12L)))
                .thenReturn(List.of(attempt(11L, 1, true, "L2"), attempt(12L, 1, true, "L3")));
        when(reportAiClient.registerLadderAnalysis(org.mockito.ArgumentMatchers.any()))
                .thenReturn(
                        ReportAiClient.LadderRegistrationResult.RETRY,
                        ReportAiClient.LadderRegistrationResult.ACCEPTED);

        service.dispatchPending();
        verify(claimService).retry(31L, "claim-1");

        service.dispatchPending();
        verify(claimService).markSent(31L, "claim-1");
    }

    @Test
    void rejectsPermanentAiContractErrorsWithoutRetryingForever() {
        var claim = new LadderAnalysisOutboxClaimService.Claim(31L, 7L, "session-new", "claim-1");
        LearningSession older = completed(11L, 7L, "money-count", "session-old", 1);
        LearningSession latest = completed(12L, 7L, "money-count", "session-new", 2);
        when(claimService.claim()).thenReturn(List.of(claim));
        when(sessionRepository.findByPublicId("session-new")).thenReturn(Optional.of(latest));
        when(sessionRepository.findTop2ByLearnerIdAndCurriculumSessionIdAndCompletedAtIsNotNullOrderByCompletedAtDescIdDesc(
                        7L, "money-count"))
                .thenReturn(List.of(latest, older));
        when(attemptRepository.findByLearningSessionIdInOrderByCreatedAtAscIdAsc(List.of(11L, 12L)))
                .thenReturn(List.of(attempt(11L, 1, true, "L2"), attempt(12L, 1, true, "L3")));
        when(reportAiClient.registerLadderAnalysis(org.mockito.ArgumentMatchers.any()))
                .thenReturn(ReportAiClient.LadderRegistrationResult.REJECTED);

        service.dispatchPending();

        verify(claimService).reject(31L, "claim-1");
        verify(claimService, never()).retry(31L, "claim-1");
    }

    @Test
    void usesSessionIdAsTieBreakerWhenCompletionTimesAreEqual() {
        LearningSession older = completed(11L, 7L, "money-count", "session-old", 1);
        LearningSession latest = completed(13L, 7L, "money-count", "session-new", 1);
        when(sessionRepository.findByPublicId("session-new")).thenReturn(Optional.of(latest));
        when(sessionRepository.findTop2ByLearnerIdAndCurriculumSessionIdAndCompletedAtIsNotNullOrderByCompletedAtDescIdDesc(
                        7L, "money-count"))
                .thenReturn(List.of(latest, older));
        when(attemptRepository.findByLearningSessionIdInOrderByCreatedAtAscIdAsc(List.of(11L, 13L)))
                .thenReturn(List.of(attempt(11L, 1, true, "L2"), attempt(13L, 1, true, "L3")));

        service.evaluate(new LadderAnalysisTrigger(7L, "session-new"));

        verify(reportAiClient).registerLadderAnalysis(org.mockito.ArgumentMatchers.any());
    }

    private LearningSession completed(
            Long id, Long learnerId, String skill, String publicId, int minute) {
        LearningSession session = LearningSession.start(learnerId, skill, 1);
        ReflectionTestUtils.setField(session, "id", id);
        ReflectionTestUtils.setField(session, "publicId", publicId);
        session.setCompletedAt(OffsetDateTime.parse("2026-08-23T10:0" + minute + ":00+09:00"));
        return session;
    }

    private Attempt attempt(Long sessionId, int no, boolean correct, String level) {
        return Attempt.record(
                sessionId,
                "drill",
                no,
                "item-" + no,
                no,
                correct,
                100,
                null,
                null,
                Map.of("expression_level", level));
    }
}
