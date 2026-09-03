package com.mormi.backend.dialogue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mormi.backend.cafe.CafeVisitRepository;
import com.mormi.backend.cafe.CafeVisit;
import com.mormi.backend.amusementpark.AmusementParkService;
import com.mormi.backend.amusementpark.AmusementParkStage;
import com.mormi.backend.amusementpark.AmusementParkVisit;
import com.mormi.backend.amusementpark.AmusementParkVisitRepository;
import com.mormi.backend.cafe.CafeService;
import com.mormi.backend.cafe.CafeStage;
import com.mormi.backend.cafe.CafeDtos.CafeContext;
import com.mormi.backend.cafe.CafeDtos.CafeMenuItem;
import com.mormi.backend.cafe.CafeDtos.PaymentRequest;
import com.mormi.backend.cafe.CafeDtos.QueueContext;
import com.mormi.backend.cafe.CafeDtos.QueueRequest;
import com.mormi.backend.cafe.CafeDtos.StageResultResponse;
import com.mormi.backend.common.ApiException;
import com.mormi.backend.dialogue.DialogueDtos.StartCafeDialogueRequest;
import com.mormi.backend.dialogue.DialogueDtos.StartParkDialogueRequest;
import com.mormi.backend.dialogue.DialogueDtos.StartTeachingRequest;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class DialogueServiceTest {

    /**
     * 단위 테스트에는 DB도 트랜잭션 매니저도 없다. 콜백을 그대로 실행하는 템플릿을 주어
     * applyTurn() 안쪽 로직이 지금까지와 똑같이 검증되도록 한다. 트랜잭션 경계 자체는
     * DialogueTransactionBoundaryTest 가 확인한다.
     */
    private static TransactionTemplate directTransactions() {
        return new TransactionTemplate(new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                return new SimpleTransactionStatus();
            }

            @Override
            public void commit(TransactionStatus status) {
            }

            @Override
            public void rollback(TransactionStatus status) {
            }
        });
    }

    @Test
    void jointParkCompletionUnlocksStageWithoutTeachingReward() throws Exception {
        DialogueClient dialogueClient = mock(DialogueClient.class);
        DialogueConversationRepository dialogueRepository = mock(DialogueConversationRepository.class);
        AmusementParkVisitRepository visitRepository = mock(AmusementParkVisitRepository.class);
        AmusementParkService parkService = mock(AmusementParkService.class);
        DialogueService service = new DialogueService(
                dialogueClient,
                dialogueRepository,
                mock(LearningSessionRepository.class),
                mock(AttemptRepository.class),
                mock(CafeVisitRepository.class),
                mock(CafeService.class),
                visitRepository,
                parkService,
                mock(LearnerService.class),
                mock(RewardService.class),
                directTransactions());

        AmusementParkVisit visit = AmusementParkVisit.start(7L);
        ReflectionTestUtils.setField(visit, "id", 31L);
        DialogueConversation dialogue = DialogueConversation.forParkVisit(
                "conversation-park-1",
                7L,
                31L,
                "amusement_ticket_multiply",
                1,
                Map.of("content_owner", "mormi_ai"),
                null);
        JsonNode childResponse = new ObjectMapper().readTree("{\"turn_id\":\"turn-1\"}");
        JsonNode envelope = new ObjectMapper().readTree("""
                {
                  "conversation_id": "conversation-park-1",
                  "turn": {
                    "status": "completed",
                    "state_version": 5,
                    "completion": {
                      "outcome": "supported",
                      "teach_reward_eligible": false,
                      "stage_completion_eligible": true,
                      "verified_facts": {
                        "ticket_price": 3000,
                        "party_count": 2,
                        "total_price": 6000
                      }
                    }
                  }
                }
                """);

        // applyTurn() 이 트랜잭션을 다시 열고 detached 엔티티를 PK로 재조회한다.
        ReflectionTestUtils.setField(dialogue, "id", 901L);
        when(dialogueRepository.findByConversationId("conversation-park-1"))
                .thenReturn(Optional.of(dialogue));
        when(dialogueRepository.findById(901L)).thenReturn(Optional.of(dialogue));
        when(visitRepository.findById(31L)).thenReturn(Optional.of(visit));
        when(dialogueClient.respond("conversation-park-1", childResponse)).thenReturn(envelope);
        when(parkService.completeFromDialogue(
                        any(), any(), any(), any(), anyInt(), any(), anyBoolean()))
                .thenReturn(new com.mormi.backend.amusementpark.AmusementParkDtos.StageResultResponse(
                        visit.getPublicId(),
                        "ticket",
                        true,
                        "snack_split",
                        true,
                        1));

        @SuppressWarnings("unchecked")
        Map<String, Object> response = (Map<String, Object>)
                service.respond(7L, "conversation-park-1", childResponse);
        @SuppressWarnings("unchecked")
        Map<String, Object> progress = (Map<String, Object>) response.get("stage_progress");
        assertThat(progress)
                .containsEntry("completed", true)
                .containsEntry("next_stage", "snack_split");
        verify(parkService).completeFromDialogue(
                7L,
                visit.getPublicId(),
                "ticket",
                Map.of("ticket_price", 3000, "party_count", 2, "total_price", 6000),
                900_005,
                "supported",
                false);
    }

    @Test
    void supportedCafeCompletionUnlocksStageWithoutTeachingReward() throws Exception {
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
                mock(AmusementParkVisitRepository.class),
                mock(AmusementParkService.class),
                learnerService,
                rewardService,
                directTransactions());

        CafeVisit visit = CafeVisit.start(7L);
        ReflectionTestUtils.setField(visit, "id", 21L);
        DialogueConversation dialogue = DialogueConversation.forCafeVisit(
                "conversation-queue-1",
                7L,
                21L,
                "cafe_queue",
                1,
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
                      "teach_reward_eligible": false,
                      "stage_completion_eligible": true,
                      "verified_facts": {"left_count": 2, "right_count": 5}
                    }
                  }
                }
                """);

        // applyTurn() 이 트랜잭션을 다시 열고 detached 엔티티를 PK로 재조회한다.
        ReflectionTestUtils.setField(dialogue, "id", 902L);
        when(dialogueRepository.findByConversationId("conversation-queue-1"))
                .thenReturn(Optional.of(dialogue));
        when(dialogueRepository.findById(902L)).thenReturn(Optional.of(dialogue));
        when(cafeVisitRepository.findById(21L)).thenReturn(Optional.of(visit));
        when(dialogueClient.respond("conversation-queue-1", childResponse)).thenReturn(envelope);
        when(cafeService.submitQueue(any(), any(), any())).thenReturn(
                new StageResultResponse(
                        visit.getPublicId(), "queue", true, "calculate", true, 2, 2, 2, 0, "queue_correct"));

        Object raw = service.respond(7L, "conversation-queue-1", childResponse);

        @SuppressWarnings("unchecked")
        Map<String, Object> response = (Map<String, Object>) raw;
        @SuppressWarnings("unchecked")
        Map<String, Object> progress = (Map<String, Object>) response.get("stage_progress");
        assertThat(progress)
                .containsEntry("stage", "queue")
                .containsEntry("completed", true)
                .containsEntry("next_stage", "calculate")
                .containsEntry("source", "dialogue_verified_facts");

        ArgumentCaptor<QueueRequest> request = ArgumentCaptor.forClass(QueueRequest.class);
        verify(cafeService).submitQueue(any(), any(), request.capture());
        assertThat(request.getValue().chosenCount()).isEqualTo(2);
        assertThat(request.getValue().scaffoldUsed()).isTrue();
    }

    @Test
    void resumeAfterCompletedCafeRoundOpensAFreshRound() throws Exception {
        DialogueClient dialogueClient = mock(DialogueClient.class);
        DialogueConversationRepository dialogueRepository = mock(DialogueConversationRepository.class);
        CafeVisitRepository cafeVisitRepository = mock(CafeVisitRepository.class);
        LearnerService learnerService = mock(LearnerService.class);
        DialogueService service = new DialogueService(
                dialogueClient,
                dialogueRepository,
                mock(LearningSessionRepository.class),
                mock(AttemptRepository.class),
                cafeVisitRepository,
                mock(CafeService.class),
                mock(AmusementParkVisitRepository.class),
                mock(AmusementParkService.class),
                learnerService,
                mock(RewardService.class),
                directTransactions());

        CafeVisit visit = CafeVisit.start(7L);
        ReflectionTestUtils.setField(visit, "id", 21L);
        Learner learner = Learner.register("표시 이름", "R-007", 1L);
        ReflectionTestUtils.setField(learner, "id", 7L);
        DialogueConversation completed = DialogueConversation.forCafeVisit(
                "conversation-queue-1",
                7L,
                21L,
                "cafe_queue",
                1,
                Map.of("queue_context", Map.of("left_count", 2, "right_count", 5)));
        completed.markCleared();

        JsonNode completedEnvelope = new ObjectMapper().readTree("""
                {
                  "conversation_id": "conversation-queue-1",
                  "turn": {"status": "completed", "completion": null}
                }
                """);
        JsonNode freshEnvelope = new ObjectMapper().readTree("""
                {
                  "conversation_id": "conversation-queue-2",
                  "turn": {"status": "active", "completion": null}
                }
                """);

        when(cafeVisitRepository.findByPublicId(visit.getPublicId())).thenReturn(Optional.of(visit));
        when(cafeVisitRepository.findById(21L)).thenReturn(Optional.of(visit));
        when(dialogueRepository.findFirstByCafeVisitIdAndScenarioIdOrderByRoundDesc(
                        21L, "cafe_queue"))
                .thenReturn(Optional.of(completed));
        when(dialogueClient.getConversation("conversation-queue-1")).thenReturn(completedEnvelope);
        when(learnerService.require(7L)).thenReturn(learner);
        when(dialogueClient.createConversation(any())).thenReturn(freshEnvelope);

        Map<String, Object> result = service.startCafeDialogue(
                7L,
                visit.getPublicId(),
                new StartCafeDialogueRequest(
                        "cafe_queue", new QueueContext(4, 1), null, "resume", null, false));

        assertThat(result.get("conversation_id")).isEqualTo("conversation-queue-2");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> aiRequest = ArgumentCaptor.forClass(Map.class);
        verify(dialogueClient).createConversation(aiRequest.capture());
        assertThat(aiRequest.getValue()).containsEntry("conversation_round", 2);
    }

    @Test
    void resumeAfterCompletedParkRoundOpensAFreshRound() throws Exception {
        DialogueClient dialogueClient = mock(DialogueClient.class);
        DialogueConversationRepository dialogueRepository = mock(DialogueConversationRepository.class);
        AmusementParkVisitRepository visitRepository = mock(AmusementParkVisitRepository.class);
        AmusementParkService parkService = mock(AmusementParkService.class);
        LearnerService learnerService = mock(LearnerService.class);
        DialogueService service = new DialogueService(
                dialogueClient,
                dialogueRepository,
                mock(LearningSessionRepository.class),
                mock(AttemptRepository.class),
                mock(CafeVisitRepository.class),
                mock(CafeService.class),
                visitRepository,
                parkService,
                learnerService,
                mock(RewardService.class),
                directTransactions());

        AmusementParkVisit visit = AmusementParkVisit.start(7L);
        ReflectionTestUtils.setField(visit, "id", 31L);
        Learner learner = Learner.register("표시 이름", "R-007", 1L);
        ReflectionTestUtils.setField(learner, "id", 7L);
        DialogueConversation completed = DialogueConversation.forParkVisit(
                "conversation-park-1",
                7L,
                31L,
                "amusement_ticket_multiply",
                1,
                Map.of("content_owner", "mormi_ai"),
                null);
        completed.markCleared();

        JsonNode completedEnvelope = new ObjectMapper().readTree("""
                {
                  "conversation_id": "conversation-park-1",
                  "turn": {"status": "completed", "completion": null}
                }
                """);
        JsonNode freshEnvelope = new ObjectMapper().readTree("""
                {
                  "conversation_id": "conversation-park-2",
                  "turn": {"status": "active", "completion": null}
                }
                """);

        when(parkService.requireOwned(7L, visit.getPublicId())).thenReturn(visit);
        when(visitRepository.findById(31L)).thenReturn(Optional.of(visit));
        when(dialogueRepository.findFirstByParkVisitIdAndScenarioIdOrderByRoundDesc(
                        31L, "amusement_ticket_multiply"))
                .thenReturn(Optional.of(completed));
        when(dialogueClient.getConversation("conversation-park-1")).thenReturn(completedEnvelope);
        when(learnerService.require(7L)).thenReturn(learner);
        when(dialogueClient.createConversation(any())).thenReturn(freshEnvelope);

        Map<String, Object> result = service.startParkDialogue(
                7L,
                visit.getPublicId(),
                new StartParkDialogueRequest(
                        "amusement_ticket_multiply", "resume", null));

        assertThat(result.get("conversation_id")).isEqualTo("conversation-park-2");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> aiRequest = ArgumentCaptor.forClass(Map.class);
        verify(dialogueClient).createConversation(aiRequest.capture());
        assertThat(aiRequest.getValue()).containsEntry("conversation_round", 2);
    }

    @Test
    void cafeCalculationUsesTheChildMenuPinnedBeforeDialogueAndOnlyAiResultFact() throws Exception {
        DialogueClient dialogueClient = mock(DialogueClient.class);
        DialogueConversationRepository dialogueRepository = mock(DialogueConversationRepository.class);
        CafeVisitRepository cafeVisitRepository = mock(CafeVisitRepository.class);
        CafeService cafeService = mock(CafeService.class);
        DialogueService service = new DialogueService(
                dialogueClient,
                dialogueRepository,
                mock(LearningSessionRepository.class),
                mock(AttemptRepository.class),
                cafeVisitRepository,
                cafeService,
                mock(AmusementParkVisitRepository.class),
                mock(AmusementParkService.class),
                mock(LearnerService.class),
                mock(RewardService.class),
                directTransactions());

        CafeVisit visit = CafeVisit.start(7L);
        ReflectionTestUtils.setField(visit, "id", 21L);
        Map<String, Object> cafeContext = Map.of(
                "menu_items", List.of(
                        Map.of("id", "americano", "name", "아메리카노", "price", 3000),
                        Map.of("id", "cookie", "name", "쿠키", "price", 2000),
                        Map.of("id", "sandwich", "name", "샌드위치", "price", 5000)),
                "mormi_menu_id", "americano",
                "child_menu_id", "sandwich");
        DialogueConversation dialogue = DialogueConversation.forCafeVisit(
                "conversation-total-1",
                7L,
                21L,
                "cafe_menu_total",
                1,
                Map.of("cafe_context", cafeContext));
        JsonNode childResponse = new ObjectMapper().readTree("{\"turn_id\":\"turn-total-1\"}");
        JsonNode envelope = new ObjectMapper().readTree("""
                {
                  "conversation_id": "conversation-total-1",
                  "turn": {
                    "status": "completed",
                    "state_version": 4,
                    "completion": {
                      "outcome": "taught",
                      "teach_reward_eligible": true,
                      "verified_facts": {"result": 8000}
                    }
                  }
                }
                """);

        // applyTurn() 이 트랜잭션을 다시 열고 detached 엔티티를 PK로 재조회한다.
        ReflectionTestUtils.setField(dialogue, "id", 904L);
        when(dialogueRepository.findByConversationId("conversation-total-1"))
                .thenReturn(Optional.of(dialogue));
        when(dialogueRepository.findById(904L)).thenReturn(Optional.of(dialogue));
        when(cafeVisitRepository.findById(21L)).thenReturn(Optional.of(visit));
        when(dialogueClient.respond("conversation-total-1", childResponse)).thenReturn(envelope);
        when(cafeService.submitPayment(any(), any(), any())).thenReturn(
                new StageResultResponse(
                        visit.getPublicId(), "calculate", true, "change", true, 1,
                        8000, 8000, 0, "payment_exact"));

        service.respond(7L, "conversation-total-1", childResponse);

        ArgumentCaptor<PaymentRequest> request = ArgumentCaptor.forClass(PaymentRequest.class);
        verify(cafeService).submitPayment(any(), any(), request.capture());
        assertThat(request.getValue().menuIds()).containsExactly("americano", "sandwich");
        assertThat(request.getValue().answerAmount()).isEqualTo(8000);
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
                mock(AmusementParkVisitRepository.class),
                mock(AmusementParkService.class),
                learnerService,
                rewardService,
                directTransactions());

        CafeVisit visit = CafeVisit.start(7L);
        ReflectionTestUtils.setField(visit, "id", 21L);
        Map<String, Object> storedContext = Map.of(
                "queue_context", Map.of("left_count", 2, "right_count", 5));
        DialogueConversation stored = DialogueConversation.forCafeVisit(
                "conversation-queue-1", 7L, 21L, "cafe_queue", 1, storedContext);

        JsonNode envelope = new ObjectMapper().readTree("""
                {
                  "conversation_id": "conversation-queue-1",
                  "turn": {"status": "active", "completion": null}
                }
                """);

        when(cafeVisitRepository.findByPublicId(visit.getPublicId())).thenReturn(Optional.of(visit));
        when(cafeVisitRepository.findById(21L)).thenReturn(Optional.of(visit));
        when(dialogueRepository.findFirstByCafeVisitIdAndScenarioIdOrderByRoundDesc(21L, "cafe_queue"))
                .thenReturn(Optional.of(stored));
        when(dialogueClient.getConversation("conversation-queue-1")).thenReturn(envelope);

        Map<String, Object> result = service.startCafeDialogue(
                7L,
                visit.getPublicId(),
                new StartCafeDialogueRequest(
                        "cafe_queue",
                        new QueueContext(4, 1),
                        null,
                        "resume",
                        null,
                        false));

        assertThat(result.get("conversation_id")).isEqualTo("conversation-queue-1");
        assertThat(result.get("scenario_context")).isEqualTo(storedContext);
        verify(dialogueClient, never()).createConversation(any());
    }

    @Test
    void alreadyPassedCafeStageCanOpenANewDialogue() throws Exception {
        DialogueClient dialogueClient = mock(DialogueClient.class);
        DialogueConversationRepository dialogueRepository = mock(DialogueConversationRepository.class);
        CafeVisitRepository cafeVisitRepository = mock(CafeVisitRepository.class);
        LearnerService learnerService = mock(LearnerService.class);
        DialogueService service = new DialogueService(
                dialogueClient,
                dialogueRepository,
                mock(LearningSessionRepository.class),
                mock(AttemptRepository.class),
                cafeVisitRepository,
                mock(CafeService.class),
                mock(AmusementParkVisitRepository.class),
                mock(AmusementParkService.class),
                learnerService,
                mock(RewardService.class),
                directTransactions());

        // 줄 서기·계산을 마쳐 거스름돈까지 온 방문. 앞 돌다리를 다시 눌러도
        // (또는 그때 AI 대화 생성이 실패해 저장된 대화가 없어도) 대화는 열려야 한다.
        CafeVisit visit = CafeVisit.start(7L);
        ReflectionTestUtils.setField(visit, "id", 21L);
        visit.advanceTo(CafeStage.CHANGE);
        Learner learner = Learner.register("표시 이름", "R-007", 1L);
        ReflectionTestUtils.setField(learner, "id", 7L);
        JsonNode envelope = new ObjectMapper().readTree("""
                {
                  "conversation_id": "conversation-queue-2",
                  "turn": {"status": "active", "completion": null}
                }
                """);

        when(cafeVisitRepository.findByPublicId(visit.getPublicId())).thenReturn(Optional.of(visit));
        when(cafeVisitRepository.findById(21L)).thenReturn(Optional.of(visit));
        when(dialogueRepository.findFirstByCafeVisitIdAndScenarioIdOrderByRoundDesc(21L, "cafe_queue"))
                .thenReturn(Optional.empty());
        when(learnerService.require(7L)).thenReturn(learner);
        when(dialogueClient.createConversation(any())).thenReturn(envelope);

        Map<String, Object> result = service.startCafeDialogue(
                7L,
                visit.getPublicId(),
                new StartCafeDialogueRequest(
                        "cafe_queue", new QueueContext(4, 1), null, null, null, false));

        assertThat(result.get("conversation_id")).isEqualTo("conversation-queue-2");
        verify(dialogueRepository).save(any(DialogueConversation.class));
    }

    @Test
    void notYetReachedCafeStageCannotOpenADialogue() {
        DialogueClient dialogueClient = mock(DialogueClient.class);
        CafeVisitRepository cafeVisitRepository = mock(CafeVisitRepository.class);
        DialogueConversationRepository dialogueRepository = mock(DialogueConversationRepository.class);
        DialogueService service = new DialogueService(
                dialogueClient,
                dialogueRepository,
                mock(LearningSessionRepository.class),
                mock(AttemptRepository.class),
                cafeVisitRepository,
                mock(CafeService.class),
                mock(AmusementParkVisitRepository.class),
                mock(AmusementParkService.class),
                mock(LearnerService.class),
                mock(RewardService.class),
                directTransactions());

        CafeVisit visit = CafeVisit.start(7L);
        ReflectionTestUtils.setField(visit, "id", 21L);

        when(cafeVisitRepository.findByPublicId(visit.getPublicId())).thenReturn(Optional.of(visit));
        when(dialogueRepository.findFirstByCafeVisitIdAndScenarioIdOrderByRoundDesc(21L, "cafe_change"))
                .thenReturn(Optional.empty());

        StartCafeDialogueRequest request = new StartCafeDialogueRequest(
                "cafe_change",
                null,
                new CafeContext(
                        List.of(new CafeMenuItem("americano", "아메리카노", 3000)),
                        "americano",
                        null),
                null,
                null,
                false);

        assertThatThrownBy(() -> service.startCafeDialogue(7L, visit.getPublicId(), request))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("아직 열리지 않은 카페 단계");
        verify(dialogueClient, never()).createConversation(any());
    }

    @Test
    void restartOpensANewRoundWithTheFreshProblem() throws Exception {
        DialogueClient dialogueClient = mock(DialogueClient.class);
        DialogueConversationRepository dialogueRepository = mock(DialogueConversationRepository.class);
        CafeVisitRepository cafeVisitRepository = mock(CafeVisitRepository.class);
        LearnerService learnerService = mock(LearnerService.class);
        DialogueService service = new DialogueService(
                dialogueClient,
                dialogueRepository,
                mock(LearningSessionRepository.class),
                mock(AttemptRepository.class),
                cafeVisitRepository,
                mock(CafeService.class),
                mock(AmusementParkVisitRepository.class),
                mock(AmusementParkService.class),
                learnerService,
                mock(RewardService.class),
                directTransactions());

        // 카페를 끝낸 방문. 세 단계가 모두 열린 연습 모드라 줄 서기를 다시 열 수 있어야 한다.
        CafeVisit visit = CafeVisit.start(7L);
        ReflectionTestUtils.setField(visit, "id", 21L);
        visit.advanceTo(CafeStage.COMPLETE);
        Learner learner = Learner.register("표시 이름", "R-007", 1L);
        ReflectionTestUtils.setField(learner, "id", 7L);

        DialogueConversation firstRound = DialogueConversation.forCafeVisit(
                "conversation-queue-1",
                7L,
                21L,
                "cafe_queue",
                1,
                Map.of("queue_context", Map.of("left_count", 2, "right_count", 5)));
        firstRound.markCleared();

        JsonNode envelope = new ObjectMapper().readTree("""
                {
                  "conversation_id": "conversation-queue-2",
                  "turn": {"status": "active", "completion": null}
                }
                """);

        when(cafeVisitRepository.findByPublicId(visit.getPublicId())).thenReturn(Optional.of(visit));
        when(cafeVisitRepository.findById(21L)).thenReturn(Optional.of(visit));
        when(dialogueRepository.findFirstByCafeVisitIdAndScenarioIdOrderByRoundDesc(21L, "cafe_queue"))
                .thenReturn(Optional.of(firstRound));
        when(learnerService.require(7L)).thenReturn(learner);
        when(dialogueClient.createConversation(any())).thenReturn(envelope);

        Map<String, Object> result = service.startCafeDialogue(
                7L,
                visit.getPublicId(),
                new StartCafeDialogueRequest(
                        "cafe_queue", new QueueContext(4, 1), null, null, null, true));

        assertThat(result.get("conversation_id")).isEqualTo("conversation-queue-2");
        // 1회차의 옛 문제가 아니라 화면이 방금 뽑은 새 문제가 저장되어야 한다.
        assertThat(result.get("scenario_context"))
                .isEqualTo(Map.of("queue_context", Map.of("left_count", 4, "right_count", 1)));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> aiRequest = ArgumentCaptor.forClass(Map.class);
        verify(dialogueClient).createConversation(aiRequest.capture());
        assertThat(aiRequest.getValue())
                .containsEntry("learning_session_id", visit.getPublicId())
                .containsEntry("conversation_round", 2);

        ArgumentCaptor<DialogueConversation> saved =
                ArgumentCaptor.forClass(DialogueConversation.class);
        verify(dialogueRepository).save(saved.capture());
        assertThat(saved.getValue().getRound()).isEqualTo(2);
        assertThat(saved.getValue().getClearedAt()).isNull();

        // 새 회차는 아직 통과하지 않았으므로 대화 검증 없이 완료로 단락되면 안 된다.
        @SuppressWarnings("unchecked")
        Map<String, Object> progress = (Map<String, Object>) result.get("stage_progress");
        assertThat(progress)
                .containsEntry("completed", false)
                .containsEntry("source", "pending");
    }

    @Test
    void completedParkVisitCanRestartOneScenarioWithStableV2Identity() throws Exception {
        DialogueClient dialogueClient = mock(DialogueClient.class);
        DialogueConversationRepository dialogueRepository = mock(DialogueConversationRepository.class);
        AmusementParkVisitRepository visitRepository = mock(AmusementParkVisitRepository.class);
        AmusementParkService parkService = mock(AmusementParkService.class);
        LearnerService learnerService = mock(LearnerService.class);
        DialogueService service = new DialogueService(
                dialogueClient,
                dialogueRepository,
                mock(LearningSessionRepository.class),
                mock(AttemptRepository.class),
                mock(CafeVisitRepository.class),
                mock(CafeService.class),
                visitRepository,
                parkService,
                learnerService,
                mock(RewardService.class),
                directTransactions());

        AmusementParkVisit visit = AmusementParkVisit.start(7L);
        ReflectionTestUtils.setField(visit, "id", 31L);
        visit.advanceTo(AmusementParkStage.COMPLETE);
        Learner learner = Learner.register("표시 이름", "R-007", 1L);
        ReflectionTestUtils.setField(learner, "id", 7L);
        DialogueConversation firstRound = DialogueConversation.forParkVisit(
                "conversation-park-1",
                7L,
                31L,
                "amusement_pass_compare",
                1,
                Map.of("content_owner", "mormi_ai"),
                null);
        JsonNode envelope = new ObjectMapper().readTree("""
                {
                  "conversation_id": "conversation-park-2",
                  "turn": {"status": "active", "completion": null}
                }
                """);

        when(parkService.requireOwned(7L, visit.getPublicId())).thenReturn(visit);
        when(visitRepository.findById(31L)).thenReturn(Optional.of(visit));
        when(dialogueRepository.findByLearnerIdAndRequestId(7L, "park-restart-2"))
                .thenReturn(Optional.empty());
        when(dialogueRepository.findFirstByParkVisitIdAndScenarioIdOrderByRoundDesc(
                        31L, "amusement_pass_compare"))
                .thenReturn(Optional.of(firstRound));
        when(learnerService.require(7L)).thenReturn(learner);
        when(dialogueClient.createConversation(any())).thenReturn(envelope);

        Map<String, Object> result = service.startParkDialogue(
                7L,
                visit.getPublicId(),
                new StartParkDialogueRequest(
                        "amusement_pass_compare", "restart", "park-restart-2"));

        assertThat(result.get("conversation_id")).isEqualTo("conversation-park-2");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> aiRequest = ArgumentCaptor.forClass(Map.class);
        verify(dialogueClient).createConversation(aiRequest.capture());
        assertThat(aiRequest.getValue())
                .containsEntry("learning_session_id", visit.getPublicId())
                .containsEntry("conversation_round", 2)
                .containsEntry("scenario_id", "amusement_pass_compare");

        ArgumentCaptor<DialogueConversation> saved =
                ArgumentCaptor.forClass(DialogueConversation.class);
        verify(dialogueRepository).save(saved.capture());
        assertThat(saved.getValue().getParkVisitId()).isEqualTo(31L);
        assertThat(saved.getValue().getRound()).isEqualTo(2);
    }

    @Test
    void replayRoundRecordsItsOwnAttemptBand() throws Exception {
        DialogueClient dialogueClient = mock(DialogueClient.class);
        DialogueConversationRepository dialogueRepository = mock(DialogueConversationRepository.class);
        CafeVisitRepository cafeVisitRepository = mock(CafeVisitRepository.class);
        CafeService cafeService = mock(CafeService.class);
        DialogueService service = new DialogueService(
                dialogueClient,
                dialogueRepository,
                mock(LearningSessionRepository.class),
                mock(AttemptRepository.class),
                cafeVisitRepository,
                cafeService,
                mock(AmusementParkVisitRepository.class),
                mock(AmusementParkService.class),
                mock(LearnerService.class),
                mock(RewardService.class),
                directTransactions());

        CafeVisit visit = CafeVisit.start(7L);
        ReflectionTestUtils.setField(visit, "id", 21L);
        visit.advanceTo(CafeStage.COMPLETE);
        DialogueConversation secondRound = DialogueConversation.forCafeVisit(
                "conversation-queue-2",
                7L,
                21L,
                "cafe_queue",
                2,
                Map.of("queue_context", Map.of("left_count", 4, "right_count", 1)));

        JsonNode childResponse = new ObjectMapper().readTree("{\"turn_id\":\"turn-1\"}");
        JsonNode envelope = new ObjectMapper().readTree("""
                {
                  "conversation_id": "conversation-queue-2",
                  "turn": {
                    "status": "completed",
                    "state_version": 3,
                    "completion": {
                      "outcome": "supported",
                      "teach_reward_eligible": true,
                      "verified_facts": {"left_count": 4, "right_count": 1}
                    }
                  }
                }
                """);

        // applyTurn() 이 트랜잭션을 다시 열고 detached 엔티티를 PK로 재조회한다.
        ReflectionTestUtils.setField(secondRound, "id", 903L);
        when(dialogueRepository.findByConversationId("conversation-queue-2"))
                .thenReturn(Optional.of(secondRound));
        when(dialogueRepository.findById(903L)).thenReturn(Optional.of(secondRound));
        when(cafeVisitRepository.findById(21L)).thenReturn(Optional.of(visit));
        when(dialogueClient.respond("conversation-queue-2", childResponse)).thenReturn(envelope);
        when(cafeService.submitQueue(any(), any(), any())).thenReturn(
                new StageResultResponse(
                        visit.getPublicId(), "queue", true, "menu", true, 3, 1, 1, 0, "queue_correct"));

        service.respond(7L, "conversation-queue-2", childResponse);

        // 방문이 이미 COMPLETE 라도 재연습 회차는 대화 검증을 거쳐야 하고,
        // 시도 번호는 1회차(900,001~)와 겹치지 않는 2회차 대역에 저장된다.
        ArgumentCaptor<QueueRequest> request = ArgumentCaptor.forClass(QueueRequest.class);
        verify(cafeService).submitQueue(any(), any(), request.capture());
        assertThat(request.getValue().attemptNo()).isEqualTo(901_003);
        assertThat(secondRound.getClearedAt()).isNotNull();
    }

    @Test
    void startModeRestartOpensANewRoundOnRefresh() throws Exception {
        DialogueClient dialogueClient = mock(DialogueClient.class);
        DialogueConversationRepository dialogueRepository = mock(DialogueConversationRepository.class);
        CafeVisitRepository cafeVisitRepository = mock(CafeVisitRepository.class);
        LearnerService learnerService = mock(LearnerService.class);
        DialogueService service = new DialogueService(
                dialogueClient,
                dialogueRepository,
                mock(LearningSessionRepository.class),
                mock(AttemptRepository.class),
                cafeVisitRepository,
                mock(CafeService.class),
                mock(AmusementParkVisitRepository.class),
                mock(AmusementParkService.class),
                learnerService,
                mock(RewardService.class),
                directTransactions());

        // 대화를 몇 턴 진행한 뒤 새로고침. 옛 restart=false 여도 start_mode 가 우선한다.
        CafeVisit visit = CafeVisit.start(7L);
        ReflectionTestUtils.setField(visit, "id", 21L);
        Learner learner = Learner.register("표시 이름", "R-007", 1L);
        ReflectionTestUtils.setField(learner, "id", 7L);
        DialogueConversation firstRound = DialogueConversation.forCafeVisit(
                "conversation-queue-1",
                7L,
                21L,
                "cafe_queue",
                1,
                Map.of("queue_context", Map.of("left_count", 2, "right_count", 5)));

        JsonNode envelope = new ObjectMapper().readTree("""
                {
                  "conversation_id": "conversation-queue-2",
                  "turn": {"status": "active", "completion": null}
                }
                """);

        when(cafeVisitRepository.findByPublicId(visit.getPublicId())).thenReturn(Optional.of(visit));
        when(cafeVisitRepository.findById(21L)).thenReturn(Optional.of(visit));
        when(dialogueRepository.findByLearnerIdAndRequestId(7L, "req-1"))
                .thenReturn(Optional.empty());
        when(dialogueRepository.findFirstByCafeVisitIdAndScenarioIdOrderByRoundDesc(21L, "cafe_queue"))
                .thenReturn(Optional.of(firstRound));
        when(learnerService.require(7L)).thenReturn(learner);
        when(dialogueClient.createConversation(any())).thenReturn(envelope);

        Map<String, Object> result = service.startCafeDialogue(
                7L,
                visit.getPublicId(),
                new StartCafeDialogueRequest(
                        "cafe_queue", new QueueContext(4, 1), null, "restart", "req-1", false));

        assertThat(result.get("conversation_id")).isEqualTo("conversation-queue-2");
        ArgumentCaptor<DialogueConversation> saved =
                ArgumentCaptor.forClass(DialogueConversation.class);
        verify(dialogueRepository).save(saved.capture());
        assertThat(saved.getValue().getRound()).isEqualTo(2);
        assertThat(saved.getValue().getRequestId()).isEqualTo("req-1");
    }

    @Test
    void retriedRequestIdReturnsTheAlreadyCreatedRoundWithoutANewConversation() throws Exception {
        DialogueClient dialogueClient = mock(DialogueClient.class);
        DialogueConversationRepository dialogueRepository = mock(DialogueConversationRepository.class);
        CafeVisitRepository cafeVisitRepository = mock(CafeVisitRepository.class);
        DialogueService service = new DialogueService(
                dialogueClient,
                dialogueRepository,
                mock(LearningSessionRepository.class),
                mock(AttemptRepository.class),
                cafeVisitRepository,
                mock(CafeService.class),
                mock(AmusementParkVisitRepository.class),
                mock(AmusementParkService.class),
                mock(LearnerService.class),
                mock(RewardService.class),
                directTransactions());

        CafeVisit visit = CafeVisit.start(7L);
        ReflectionTestUtils.setField(visit, "id", 21L);
        Map<String, Object> storedContext = Map.of(
                "queue_context", Map.of("left_count", 4, "right_count", 1));
        DialogueConversation created = DialogueConversation.forCafeVisit(
                "conversation-queue-2", 7L, 21L, "cafe_queue", 2, storedContext, "req-1");

        JsonNode envelope = new ObjectMapper().readTree("""
                {
                  "conversation_id": "conversation-queue-2",
                  "turn": {"status": "active", "completion": null}
                }
                """);

        when(cafeVisitRepository.findByPublicId(visit.getPublicId())).thenReturn(Optional.of(visit));
        when(cafeVisitRepository.findById(21L)).thenReturn(Optional.of(visit));
        when(dialogueRepository.findByLearnerIdAndRequestId(7L, "req-1"))
                .thenReturn(Optional.of(created));
        when(dialogueClient.getConversation("conversation-queue-2")).thenReturn(envelope);

        // 첫 요청이 회차를 만든 뒤 같은 요청이 네트워크 재시도로 다시 도착했다.
        Map<String, Object> result = service.startCafeDialogue(
                7L,
                visit.getPublicId(),
                new StartCafeDialogueRequest(
                        "cafe_queue", new QueueContext(4, 1), null, "restart", "req-1", false));

        assertThat(result.get("conversation_id")).isEqualTo("conversation-queue-2");
        assertThat(result.get("scenario_context")).isEqualTo(storedContext);
        verify(dialogueClient, never()).createConversation(any());
        verify(dialogueRepository, never()).save(any());
    }

    @Test
    void requestIdReusedOnADifferentScenarioIsRejected() throws Exception {
        DialogueClient dialogueClient = mock(DialogueClient.class);
        DialogueConversationRepository dialogueRepository = mock(DialogueConversationRepository.class);
        CafeVisitRepository cafeVisitRepository = mock(CafeVisitRepository.class);
        DialogueService service = new DialogueService(
                dialogueClient,
                dialogueRepository,
                mock(LearningSessionRepository.class),
                mock(AttemptRepository.class),
                cafeVisitRepository,
                mock(CafeService.class),
                mock(AmusementParkVisitRepository.class),
                mock(AmusementParkService.class),
                mock(LearnerService.class),
                mock(RewardService.class),
                directTransactions());

        CafeVisit visit = CafeVisit.start(7L);
        ReflectionTestUtils.setField(visit, "id", 21L);
        DialogueConversation created = DialogueConversation.forCafeVisit(
                "conversation-queue-2", 7L, 21L, "cafe_queue", 2,
                Map.of("queue_context", Map.of("left_count", 4, "right_count", 1)), "req-1");

        when(cafeVisitRepository.findByPublicId(visit.getPublicId())).thenReturn(Optional.of(visit));
        when(dialogueRepository.findByLearnerIdAndRequestId(7L, "req-1"))
                .thenReturn(Optional.of(created));

        StartCafeDialogueRequest request = new StartCafeDialogueRequest(
                "cafe_change",
                null,
                new CafeContext(
                        List.of(new CafeMenuItem("americano", "아메리카노", 3000)),
                        "americano",
                        null),
                "restart",
                "req-1",
                false);

        assertThatThrownBy(() -> service.startCafeDialogue(7L, visit.getPublicId(), request))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("이미 사용");
        verify(dialogueClient, never()).createConversation(any());
    }

    @Test
    void homeTeachingWithoutBodyResumesTheLatestRound() {
        DialogueClient dialogueClient = mock(DialogueClient.class);
        DialogueConversationRepository dialogueRepository = mock(DialogueConversationRepository.class);
        LearningSessionRepository sessionRepository = mock(LearningSessionRepository.class);
        DialogueService service = new DialogueService(
                dialogueClient,
                dialogueRepository,
                sessionRepository,
                mock(AttemptRepository.class),
                mock(CafeVisitRepository.class),
                mock(CafeService.class),
                mock(AmusementParkVisitRepository.class),
                mock(AmusementParkService.class),
                mock(LearnerService.class),
                mock(RewardService.class),
                directTransactions());

        LearningSession session = LearningSession.start(7L, "number-count", 42);
        ReflectionTestUtils.setField(session, "id", 11L);
        DialogueConversation latest = DialogueConversation.forLearningSession(
                "conversation-teach-2", 7L, 11L, 2, null);
        JsonNode envelope = mock(JsonNode.class);

        when(sessionRepository.findByPublicId(session.getPublicId())).thenReturn(Optional.of(session));
        when(dialogueRepository.findFirstByLearningSessionIdOrderByRoundDesc(11L))
                .thenReturn(Optional.of(latest));
        when(dialogueClient.getConversation("conversation-teach-2")).thenReturn(envelope);

        JsonNode result = service.startHomeTeaching(7L, session.getPublicId(), null);

        assertThat(result).isSameAs(envelope);
        verify(dialogueClient, never()).createConversation(any());
    }

    @Test
    void homeTeachingRestartOpensANewRoundAndPointsTheSessionToIt() throws Exception {
        DialogueClient dialogueClient = mock(DialogueClient.class);
        DialogueConversationRepository dialogueRepository = mock(DialogueConversationRepository.class);
        LearningSessionRepository sessionRepository = mock(LearningSessionRepository.class);
        AttemptRepository attemptRepository = mock(AttemptRepository.class);
        LearnerService learnerService = mock(LearnerService.class);
        RewardService rewardService = mock(RewardService.class);
        DialogueService service = new DialogueService(
                dialogueClient,
                dialogueRepository,
                sessionRepository,
                attemptRepository,
                mock(CafeVisitRepository.class),
                mock(CafeService.class),
                mock(AmusementParkVisitRepository.class),
                mock(AmusementParkService.class),
                learnerService,
                rewardService,
                directTransactions());

        LearningSession session = LearningSession.start(7L, "number-count", 42);
        ReflectionTestUtils.setField(session, "id", 11L);
        Learner learner = Learner.register("표시 이름", "R-007", 1L);
        ReflectionTestUtils.setField(learner, "id", 7L);
        DialogueConversation firstRound = DialogueConversation.forLearningSession(
                "conversation-teach-1", 7L, 11L, 1, null);
        JsonNode envelope = new ObjectMapper().readTree("""
                {
                  "conversation_id": "conversation-teach-2",
                  "turn": {"status": "active", "completion": null}
                }
                """);

        when(sessionRepository.findByPublicId(session.getPublicId())).thenReturn(Optional.of(session));
        when(dialogueRepository.findByLearnerIdAndRequestId(7L, "req-7"))
                .thenReturn(Optional.empty());
        when(dialogueRepository.findFirstByLearningSessionIdOrderByRoundDesc(11L))
                .thenReturn(Optional.of(firstRound));
        when(attemptRepository.countDistinctCorrectQuestions(11L, "drill")).thenReturn(5);
        when(attemptRepository.findByLearningSessionIdOrderByIdAsc(11L)).thenReturn(List.of());
        when(learnerService.require(7L)).thenReturn(learner);
        when(dialogueClient.createConversation(any())).thenReturn(envelope);

        JsonNode result = service.startHomeTeaching(
                7L, session.getPublicId(), new StartTeachingRequest("restart", "req-7"));

        assertThat(result.path("conversation_id").asString()).isEqualTo("conversation-teach-2");
        // 세션이 신뢰하는 대화가 새 회차로 바뀌어야 완료 보상 검증도 새 회차를 본다.
        assertThat(session.getConversationId()).isEqualTo("conversation-teach-2");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> request = ArgumentCaptor.forClass(Map.class);
        verify(dialogueClient).createConversation(request.capture());
        assertThat(request.getValue()).containsEntry("conversation_round", 2);

        ArgumentCaptor<DialogueConversation> saved =
                ArgumentCaptor.forClass(DialogueConversation.class);
        verify(dialogueRepository).save(saved.capture());
        assertThat(saved.getValue().getRound()).isEqualTo(2);
        assertThat(saved.getValue().getRequestId()).isEqualTo("req-7");
    }

    /** 순차 배포 방어선: 구버전 AI가 같은 대화를 반환해도 유니크 제약으로 500이 나지 않는다. */
    @Test
    void homeTeachingRestartSafelyReusesTheRowWhenAiReturnsTheSameConversation() throws Exception {
        DialogueClient dialogueClient = mock(DialogueClient.class);
        DialogueConversationRepository dialogueRepository = mock(DialogueConversationRepository.class);
        LearningSessionRepository sessionRepository = mock(LearningSessionRepository.class);
        AttemptRepository attemptRepository = mock(AttemptRepository.class);
        LearnerService learnerService = mock(LearnerService.class);
        DialogueService service = new DialogueService(
                dialogueClient,
                dialogueRepository,
                sessionRepository,
                attemptRepository,
                mock(CafeVisitRepository.class),
                mock(CafeService.class),
                mock(AmusementParkVisitRepository.class),
                mock(AmusementParkService.class),
                learnerService,
                mock(RewardService.class),
                directTransactions());

        LearningSession session = LearningSession.start(7L, "number-count", 42);
        ReflectionTestUtils.setField(session, "id", 11L);
        Learner learner = Learner.register("표시 이름", "R-007", 1L);
        ReflectionTestUtils.setField(learner, "id", 7L);
        DialogueConversation firstRound = DialogueConversation.forLearningSession(
                "conversation-teach-1", 7L, 11L, 1, null);
        // AI가 새 대화 대신 1회차와 같은 conversation_id 를 돌려준 상황
        JsonNode envelope = new ObjectMapper().readTree("""
                {
                  "conversation_id": "conversation-teach-1",
                  "turn": {"status": "active", "completion": null}
                }
                """);

        when(sessionRepository.findByPublicId(session.getPublicId())).thenReturn(Optional.of(session));
        when(dialogueRepository.findFirstByLearningSessionIdOrderByRoundDesc(11L))
                .thenReturn(Optional.of(firstRound));
        when(dialogueRepository.findByConversationId("conversation-teach-1"))
                .thenReturn(Optional.of(firstRound));
        when(attemptRepository.countDistinctCorrectQuestions(11L, "drill")).thenReturn(5);
        when(attemptRepository.findByLearningSessionIdOrderByIdAsc(11L)).thenReturn(List.of());
        when(learnerService.require(7L)).thenReturn(learner);
        when(dialogueClient.createConversation(any())).thenReturn(envelope);

        JsonNode result = service.startHomeTeaching(
                7L, session.getPublicId(), new StartTeachingRequest("restart", "req-8"));

        assertThat(result.path("conversation_id").asString()).isEqualTo("conversation-teach-1");
        assertThat(session.getConversationId()).isEqualTo("conversation-teach-1");
        // 같은 대화의 복구이므로 새 회차 행을 만들지 않아야 유니크 제약에 걸리지 않는다.
        verify(dialogueRepository, never()).save(any());
    }

    /** #37 방어선: 다른 학습 세션의 대화가 돌아오면 이어 쓰지 않고 재시도를 유도한다. */
    @Test
    void homeTeachingRejectsAConversationBelongingToAnotherLearning() throws Exception {
        DialogueClient dialogueClient = mock(DialogueClient.class);
        DialogueConversationRepository dialogueRepository = mock(DialogueConversationRepository.class);
        LearningSessionRepository sessionRepository = mock(LearningSessionRepository.class);
        AttemptRepository attemptRepository = mock(AttemptRepository.class);
        LearnerService learnerService = mock(LearnerService.class);
        DialogueService service = new DialogueService(
                dialogueClient,
                dialogueRepository,
                sessionRepository,
                attemptRepository,
                mock(CafeVisitRepository.class),
                mock(CafeService.class),
                mock(AmusementParkVisitRepository.class),
                mock(AmusementParkService.class),
                learnerService,
                mock(RewardService.class),
                directTransactions());

        LearningSession session = LearningSession.start(7L, "number-count", 42);
        ReflectionTestUtils.setField(session, "id", 11L);
        Learner learner = Learner.register("표시 이름", "R-007", 1L);
        ReflectionTestUtils.setField(learner, "id", 7L);
        DialogueConversation otherSessions = DialogueConversation.forLearningSession(
                "conversation-teach-9", 7L, 99L, 1, null);
        JsonNode envelope = new ObjectMapper().readTree("""
                {
                  "conversation_id": "conversation-teach-9",
                  "turn": {"status": "active", "completion": null}
                }
                """);

        when(sessionRepository.findByPublicId(session.getPublicId())).thenReturn(Optional.of(session));
        when(dialogueRepository.findByConversationId("conversation-teach-9"))
                .thenReturn(Optional.of(otherSessions));
        when(attemptRepository.countDistinctCorrectQuestions(11L, "drill")).thenReturn(5);
        when(attemptRepository.findByLearningSessionIdOrderByIdAsc(11L)).thenReturn(List.of());
        when(learnerService.require(7L)).thenReturn(learner);
        when(dialogueClient.createConversation(any())).thenReturn(envelope);

        assertThatThrownBy(() -> service.startHomeTeaching(
                7L, session.getPublicId(), new StartTeachingRequest("restart", "req-9")))
                .isInstanceOf(com.mormi.backend.common.ApiException.class)
                .hasFieldOrPropertyWithValue("code", "dialogue_conversation_mismatch");
        verify(dialogueRepository, never()).save(any());
    }

    @Test
    void equalQueueCountsAreRejectedBeforeCreatingADialogue() {
        StartFixture fixture = startFixture("cafe_queue", CafeStage.QUEUE);
        StartCafeDialogueRequest request = new StartCafeDialogueRequest(
                "cafe_queue", new QueueContext(3, 3), null, null, null, false);

        assertThatThrownBy(
                () -> fixture.service().startCafeDialogue(7L, fixture.visit().getPublicId(), request))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "queue_count_equal");
        verify(fixture.dialogueClient(), never()).createConversation(any());
    }

    @Test
    void outOfRangeQueueCountsAreRejectedBeforeCreatingADialogue() {
        StartFixture fixture = startFixture("cafe_queue", CafeStage.QUEUE);
        StartCafeDialogueRequest request = new StartCafeDialogueRequest(
                "cafe_queue", new QueueContext(7, 2), null, null, null, false);

        assertThatThrownBy(
                () -> fixture.service().startCafeDialogue(7L, fixture.visit().getPublicId(), request))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "queue_count_range");
        verify(fixture.dialogueClient(), never()).createConversation(any());
    }

    @Test
    void menuPriceMismatchIsRejectedBeforeCreatingADialogue() {
        StartFixture fixture = startFixture("cafe_budget_menu", CafeStage.MENU);
        StartCafeDialogueRequest request = new StartCafeDialogueRequest(
                "cafe_budget_menu",
                null,
                new CafeContext(
                        List.of(
                                new CafeMenuItem("americano", "아메리카노", 3500),
                                new CafeMenuItem("cookie", "쿠키", 2000)),
                        "americano",
                        8000),
                null,
                null,
                false);

        assertThatThrownBy(
                () -> fixture.service().startCafeDialogue(7L, fixture.visit().getPublicId(), request))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "menu_price_mismatch");
        verify(fixture.dialogueClient(), never()).createConversation(any());
    }

    @Test
    void mormiMenuOutsideTheBoardIsRejectedBeforeCreatingADialogue() {
        StartFixture fixture = startFixture("cafe_budget_menu", CafeStage.MENU);
        StartCafeDialogueRequest request = new StartCafeDialogueRequest(
                "cafe_budget_menu",
                null,
                new CafeContext(
                        List.of(
                                new CafeMenuItem("americano", "아메리카노", 3000),
                                new CafeMenuItem("cookie", "쿠키", 2000)),
                        "milk",
                        8000),
                null,
                null,
                false);

        assertThatThrownBy(
                () -> fixture.service().startCafeDialogue(7L, fixture.visit().getPublicId(), request))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "mormi_menu_unknown");
        verify(fixture.dialogueClient(), never()).createConversation(any());
    }

    @Test
    void unknownBudgetIsRejectedBeforeCreatingADialogue() {
        StartFixture fixture = startFixture("cafe_budget_menu", CafeStage.MENU);
        StartCafeDialogueRequest request = new StartCafeDialogueRequest(
                "cafe_budget_menu",
                null,
                new CafeContext(
                        List.of(
                                new CafeMenuItem("americano", "아메리카노", 3000),
                                new CafeMenuItem("cookie", "쿠키", 2000)),
                        "americano",
                        6000),
                null,
                null,
                false);

        assertThatThrownBy(
                () -> fixture.service().startCafeDialogue(7L, fixture.visit().getPublicId(), request))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "budget");
        verify(fixture.dialogueClient(), never()).createConversation(any());
    }

    /** 대화 시작 거절 테스트 공통 준비물. 계약 위반이 AI 호출 전에 걸리는지 본다. */
    private record StartFixture(
            DialogueService service, DialogueClient dialogueClient, CafeVisit visit) {
    }

    private StartFixture startFixture(String scenarioId, CafeStage reachedStage) {
        DialogueClient dialogueClient = mock(DialogueClient.class);
        DialogueConversationRepository dialogueRepository = mock(DialogueConversationRepository.class);
        CafeVisitRepository cafeVisitRepository = mock(CafeVisitRepository.class);
        DialogueService service = new DialogueService(
                dialogueClient,
                dialogueRepository,
                mock(LearningSessionRepository.class),
                mock(AttemptRepository.class),
                cafeVisitRepository,
                mock(CafeService.class),
                mock(AmusementParkVisitRepository.class),
                mock(AmusementParkService.class),
                mock(LearnerService.class),
                mock(RewardService.class),
                directTransactions());

        CafeVisit visit = CafeVisit.start(7L);
        ReflectionTestUtils.setField(visit, "id", 21L);
        visit.advanceTo(reachedStage);
        when(cafeVisitRepository.findByPublicId(visit.getPublicId())).thenReturn(Optional.of(visit));
        when(dialogueRepository.findFirstByCafeVisitIdAndScenarioIdOrderByRoundDesc(21L, scenarioId))
                .thenReturn(Optional.empty());
        return new StartFixture(service, dialogueClient, visit);
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
                mock(AmusementParkVisitRepository.class),
                mock(AmusementParkService.class),
                learnerService,
                rewardService,
                directTransactions());

        LearningSession session = LearningSession.start(7L, "number-count", 42);
        ReflectionTestUtils.setField(session, "id", 11L);
        Learner learner = Learner.register("표시 이름", "R-007", 1L);
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
                    null,
                    null,
                    Map.of("selected_choice_id", "choice-" + index));
            ReflectionTestUtils.setField(attempt, "id", (long) index + 1);
            attempts.add(attempt);
        }

        JsonNode envelope = mock(JsonNode.class);
        JsonNode conversationId = mock(JsonNode.class);
        JsonNode turn = mock(JsonNode.class);
        when(envelope.path("conversation_id")).thenReturn(conversationId);
        when(conversationId.asString()).thenReturn("conversation-safe-1");
        when(envelope.path("turn")).thenReturn(turn);
        when(turn.isMissingNode()).thenReturn(false);

        when(sessionRepository.findByPublicId(session.getPublicId())).thenReturn(Optional.of(session));
        when(dialogueRepository.findFirstByLearningSessionIdOrderByRoundDesc(11L))
                .thenReturn(Optional.empty());
        when(attemptRepository.countDistinctCorrectQuestions(11L, "drill")).thenReturn(5);
        when(attemptRepository.findByLearningSessionIdOrderByIdAsc(11L)).thenReturn(attempts);
        when(learnerService.require(7L)).thenReturn(learner);
        when(rewardService.sessionReward(11L, RewardSource.DRILL)).thenReturn(1000);
        when(dialogueClient.createConversation(any())).thenReturn(envelope);

        JsonNode result = service.startHomeTeaching(7L, session.getPublicId(), null);

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
                .containsEntry("conversation_round", 1)
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
