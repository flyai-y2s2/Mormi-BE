package com.mormi.backend.dialogue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mormi.backend.cafe.CafeVisitRepository;
import com.mormi.backend.cafe.CafeVisit;
import com.mormi.backend.cafe.CafeService;
import com.mormi.backend.cafe.CafeDtos.QueueRequest;
import com.mormi.backend.cafe.CafeDtos.StageResultResponse;
import com.mormi.backend.dialogue.DialogueDtos.StartCafeDialogueRequest;
import com.mormi.backend.learner.Learner;
import com.mormi.backend.learner.LearnerService;
import com.mormi.backend.reward.RewardService;
import com.mormi.backend.reward.RewardSource;
import com.mormi.backend.session.Attempt;
import com.mormi.backend.session.AttemptRepository;
import com.mormi.backend.session.LearningSession;
import com.mormi.backend.session.LearningSessionRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class DialogueServiceTest {

    @Test
    void completedCafeDialogueAdvancesFromVerifiedFactsOnly() throws Exception {
        DialogueClient dialogueClient = mock(DialogueClient.class);
        DialogueConversationRepository dialogueRepository = mock(DialogueConversationRepository.class);
        LearningSessionRepository sessionRepository = mock(LearningSessionRepository.class);
        AttemptRepository attemptRepository = mock(AttemptRepository.class);
        CafeVisitRepository cafeVisitRepository = mock(CafeVisitRepository.class);
        CafeService cafeService = mock(CafeService.class);
        LearnerService learnerService = mock(LearnerService.class);
        RewardService rewardService = mock(RewardService.class);
        DialogueService service = new DialogueService(
                dialogueClient,
                dialogueRepository,
                sessionRepository,
                attemptRepository,
                cafeVisitRepository,
                cafeService,
                learnerService,
                rewardService);

        CafeVisit visit = CafeVisit.start(7L);
        ReflectionTestUtils.setField(visit, "id", 21L);
        DialogueConversation dialogue = DialogueConversation.forCafeVisit(
                "conversation-queue-1",
                7L,
                21L,
                "cafe_queue",
                Map.of("queue_context", Map.of("left_count", 2, "right_count", 5)));
        JsonNode childResponse = new ObjectMapper().readTree("{\"turn_id\":\"turn-1\"}");
        JsonNode envelope = new ObjectMapper().readTree("""
                {
                  "conversation_id": "conversation-queue-1",
                  "turn": {
                    "status": "completed",
                    "state_version": 7,
                    "completion": {
                      "outcome": "supported",
                      "teach_reward_eligible": true,
                      "verified_facts": {"left_count": 2, "right_count": 5}
                    }
                  }
                }
                """);

        when(dialogueRepository.findByConversationId("conversation-queue-1"))
                .thenReturn(Optional.of(dialogue));
        when(cafeVisitRepository.findById(21L)).thenReturn(Optional.of(visit));
        when(dialogueClient.respond("conversation-queue-1", childResponse)).thenReturn(envelope);
        when(cafeService.submitQueue(any(), any(), any())).thenReturn(
                new StageResultResponse(
                        visit.getPublicId(), "queue", true, "menu", true, 2, 2, 2, 0, "queue_correct"));

        Object raw = service.respond(7L, "conversation-queue-1", childResponse);

        @SuppressWarnings("unchecked")
        Map<String, Object> response = (Map<String, Object>) raw;
        @SuppressWarnings("unchecked")
        Map<String, Object> progress = (Map<String, Object>) response.get("stage_progress");
        assertThat(progress)
                .containsEntry("stage", "queue")
                .containsEntry("completed", true)
                .containsEntry("next_stage", "menu")
                .containsEntry("source", "dialogue_verified_facts");

        ArgumentCaptor<QueueRequest> request = ArgumentCaptor.forClass(QueueRequest.class);
        verify(cafeService).submitQueue(any(), any(), request.capture());
        assertThat(request.getValue().chosenCount()).isEqualTo(2);
        assertThat(request.getValue().scaffoldUsed()).isTrue();
    }

    @Test
    void cafeRecoveryReturnsTheOriginallyStoredProblemContext() throws Exception {
        DialogueClient dialogueClient = mock(DialogueClient.class);
        DialogueConversationRepository dialogueRepository = mock(DialogueConversationRepository.class);
        LearningSessionRepository sessionRepository = mock(LearningSessionRepository.class);
        AttemptRepository attemptRepository = mock(AttemptRepository.class);
        CafeVisitRepository cafeVisitRepository = mock(CafeVisitRepository.class);
        CafeService cafeService = mock(CafeService.class);
        LearnerService learnerService = mock(LearnerService.class);
        RewardService rewardService = mock(RewardService.class);
        DialogueService service = new DialogueService(
                dialogueClient,
                dialogueRepository,
                sessionRepository,
                attemptRepository,
                cafeVisitRepository,
                cafeService,
                learnerService,
                rewardService);

        CafeVisit visit = CafeVisit.start(7L);
        ReflectionTestUtils.setField(visit, "id", 21L);
        Map<String, Object> storedContext = Map.of(
                "queue_context", Map.of("left_count", 2, "right_count", 5));
        DialogueConversation stored = DialogueConversation.forCafeVisit(
                "conversation-queue-1", 7L, 21L, "cafe_queue", storedContext);

        JsonNode envelope = new ObjectMapper().readTree("""
                {
                  "conversation_id": "conversation-queue-1",
                  "turn": {"status": "active", "completion": null}
                }
                """);

        when(cafeVisitRepository.findByPublicId(visit.getPublicId())).thenReturn(Optional.of(visit));
        when(cafeVisitRepository.findById(21L)).thenReturn(Optional.of(visit));
        when(dialogueRepository.findByCafeVisitIdAndScenarioId(21L, "cafe_queue"))
                .thenReturn(Optional.of(stored));
        when(dialogueClient.getConversation("conversation-queue-1")).thenReturn(envelope);

        Map<String, Object> result = service.startCafeDialogue(
                7L,
                visit.getPublicId(),
                new StartCafeDialogueRequest(
                        "cafe_queue",
                        Map.of("left_count", 4, "right_count", 1),
                        null));

        assertThat(result.get("conversation_id")).isEqualTo("conversation-queue-1");
        assertThat(result.get("scenario_context")).isEqualTo(storedContext);
        verify(dialogueClient, never()).createConversation(any());
    }

    @Test
    void homeTeachingUsesOnlyServerOwnedPracticeFacts() {
        DialogueClient dialogueClient = mock(DialogueClient.class);
        DialogueConversationRepository dialogueRepository = mock(DialogueConversationRepository.class);
        LearningSessionRepository sessionRepository = mock(LearningSessionRepository.class);
        AttemptRepository attemptRepository = mock(AttemptRepository.class);
        CafeVisitRepository cafeVisitRepository = mock(CafeVisitRepository.class);
        CafeService cafeService = mock(CafeService.class);
        LearnerService learnerService = mock(LearnerService.class);
        RewardService rewardService = mock(RewardService.class);

        DialogueService service = new DialogueService(
                dialogueClient,
                dialogueRepository,
                sessionRepository,
                attemptRepository,
                cafeVisitRepository,
                cafeService,
                learnerService,
                rewardService);

        LearningSession session = LearningSession.start(7L, "number-count", 42);
        ReflectionTestUtils.setField(session, "id", 11L);
        Learner learner = Learner.create("표시 이름", "R-007", "hash");
        ReflectionTestUtils.setField(learner, "id", 7L);

        List<Attempt> attempts = new ArrayList<>();
        for (int index = 0; index < 5; index += 1) {
            Attempt attempt = Attempt.record(
                    11L,
                    "drill",
                    index + 1,
                    "number-count:" + index,
                    index,
                    true,
                    1000 + index,
                    Map.of("selected_choice_id", "choice-" + index));
            ReflectionTestUtils.setField(attempt, "id", (long) index + 1);
            attempts.add(attempt);
        }

        JsonNode envelope = mock(JsonNode.class);
        JsonNode conversationId = mock(JsonNode.class);
        JsonNode turn = mock(JsonNode.class);
        when(envelope.path("conversation_id")).thenReturn(conversationId);
        when(conversationId.asText()).thenReturn("conversation-safe-1");
        when(envelope.path("turn")).thenReturn(turn);
        when(turn.isMissingNode()).thenReturn(false);

        when(sessionRepository.findByPublicId(session.getPublicId())).thenReturn(Optional.of(session));
        when(dialogueRepository.findByLearningSessionId(11L)).thenReturn(Optional.empty());
        when(attemptRepository.countDistinctCorrectQuestions(11L, "drill")).thenReturn(5);
        when(attemptRepository.findByLearningSessionIdOrderByIdAsc(11L)).thenReturn(attempts);
        when(learnerService.require(7L)).thenReturn(learner);
        when(rewardService.sessionReward(11L, RewardSource.DRILL)).thenReturn(1000);
        when(dialogueClient.createConversation(any())).thenReturn(envelope);

        JsonNode result = service.startHomeTeaching(7L, session.getPublicId());

        assertThat(result).isSameAs(envelope);
        assertThat(session.getConversationId()).isEqualTo("conversation-safe-1");
        assertThat(session.getPracticeResultId()).startsWith("practice_");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> requestCaptor = ArgumentCaptor.forClass(Map.class);
        verify(dialogueClient).createConversation(requestCaptor.capture());
        Map<String, Object> request = requestCaptor.getValue();
        assertThat(request)
                .containsEntry("learner_id", 7L)
                .containsEntry("scene", "home_teach")
                .containsEntry("scenario_id", "home_teach")
                .containsEntry("conversation_storage_consent", true)
                .containsEntry("retention_policy", "permanent")
                .doesNotContainKeys("display_name", "child_text", "raw_response");

        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) request.get("practice_summary");
        assertThat(summary)
                .containsEntry("curriculum_session_id", "number-count")
                .containsEntry("question_count", 5)
                .containsEntry("first_try_correct_count", 5);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> compactAttempts =
                (List<Map<String, Object>>) summary.get("attempts");
        assertThat(compactAttempts)
                .hasSize(5)
                .allSatisfy(attempt -> assertThat(attempt)
                        .containsKeys("item_id", "correct", "latency_ms")
                        .doesNotContainKeys("response", "selected_answer", "raw_text"));

        verify(dialogueRepository).save(any(DialogueConversation.class));
    }
}
