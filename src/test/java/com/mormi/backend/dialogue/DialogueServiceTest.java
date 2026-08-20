package com.mormi.backend.dialogue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mormi.backend.cafe.CafeVisitRepository;
import com.mormi.backend.cafe.CafeVisit;
import com.mormi.backend.cafe.CafeService;
import com.mormi.backend.cafe.CafeStage;
import com.mormi.backend.cafe.CafeDtos.CafeContext;
import com.mormi.backend.cafe.CafeDtos.CafeMenuItem;
import com.mormi.backend.cafe.CafeDtos.QueueContext;
import com.mormi.backend.cafe.CafeDtos.QueueRequest;
import com.mormi.backend.cafe.CafeDtos.StageResultResponse;
import com.mormi.backend.common.ApiException;
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
                learnerService,
                mock(RewardService.class));

        // 줄 서기·메뉴·계산을 마쳐 거스름돈까지 온 방문. 앞 돌다리를 다시 눌러도
        // (또는 그때 AI 대화 생성이 실패해 저장된 대화가 없어도) 대화는 열려야 한다.
        CafeVisit visit = CafeVisit.start(7L);
        ReflectionTestUtils.setField(visit, "id", 21L);
        visit.advanceTo(CafeStage.CHANGE);
        Learner learner = Learner.create("표시 이름", "R-007", "hash");
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
                        "cafe_queue", new QueueContext(4, 1), null, false));

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
                mock(LearnerService.class),
                mock(RewardService.class));

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
                learnerService,
                mock(RewardService.class));

        // 카페를 끝낸 방문. 네 단계가 모두 열린 연습 모드라 줄 서기를 다시 열 수 있어야 한다.
        CafeVisit visit = CafeVisit.start(7L);
        ReflectionTestUtils.setField(visit, "id", 21L);
        visit.advanceTo(CafeStage.COMPLETE);
        Learner learner = Learner.create("표시 이름", "R-007", "hash");
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
                        "cafe_queue", new QueueContext(4, 1), null, true));

        assertThat(result.get("conversation_id")).isEqualTo("conversation-queue-2");
        // 1회차의 옛 문제가 아니라 화면이 방금 뽑은 새 문제가 저장되어야 한다.
        assertThat(result.get("scenario_context"))
                .isEqualTo(Map.of("queue_context", Map.of("left_count", 4, "right_count", 1)));

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
                mock(LearnerService.class),
                mock(RewardService.class));

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

        when(dialogueRepository.findByConversationId("conversation-queue-2"))
                .thenReturn(Optional.of(secondRound));
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
    void equalQueueCountsAreRejectedBeforeCreatingADialogue() {
        StartFixture fixture = startFixture("cafe_queue", CafeStage.QUEUE);
        StartCafeDialogueRequest request = new StartCafeDialogueRequest(
                "cafe_queue", new QueueContext(3, 3), null, false);

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
                "cafe_queue", new QueueContext(7, 2), null, false);

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
                        7000),
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
                mock(LearnerService.class),
                mock(RewardService.class));

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
