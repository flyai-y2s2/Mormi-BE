package com.mormi.backend.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mormi.backend.report.ReportAiClient;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class LadderAnalysisTriggerServiceTest {

    @Mock LearningSessionRepository sessionRepository;
    @Mock AttemptRepository attemptRepository;
    @Mock ReportAiClient reportAiClient;
    @Mock ApplicationEventPublisher publisher;

    LadderAnalysisTriggerService service;

    @BeforeEach
    void setUp() {
        service = new LadderAnalysisTriggerService(
                sessionRepository, attemptRepository, reportAiClient, publisher);
    }

    @Test
    void registersOnlyWhenTheLatestTwoCompletedSessionsUseTheSameSubunit() {
        LearningSession older = completed(11L, 7L, "money-count", "session-old", 1);
        LearningSession latest = completed(12L, 7L, "money-count", "session-new", 2);
        when(sessionRepository.findTop2ByLearnerIdAndCompletedAtIsNotNullOrderByCompletedAtDesc(7L))
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
        when(sessionRepository.findTop2ByLearnerIdAndCompletedAtIsNotNullOrderByCompletedAtDesc(7L))
                .thenReturn(List.of(latest));
        service.evaluate(new LadderAnalysisTrigger(7L, "session-new"));

        LearningSession other = completed(11L, 7L, "number-count", "session-old", 1);
        when(sessionRepository.findTop2ByLearnerIdAndCompletedAtIsNotNullOrderByCompletedAtDesc(7L))
                .thenReturn(List.of(latest, other));
        service.evaluate(new LadderAnalysisTrigger(7L, "session-new"));

        when(sessionRepository.findTop2ByLearnerIdAndCompletedAtIsNotNullOrderByCompletedAtDesc(7L))
                .thenReturn(List.of(latest, completed(10L, 7L, "money-count", "session-prior", 1)));
        service.evaluate(new LadderAnalysisTrigger(7L, "session-prior"));

        verify(reportAiClient, never()).registerLadderAnalysis(org.mockito.ArgumentMatchers.any());
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
