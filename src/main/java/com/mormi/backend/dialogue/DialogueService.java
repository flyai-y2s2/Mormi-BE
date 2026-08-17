package com.mormi.backend.dialogue;

import com.mormi.backend.cafe.CafeStage;
import com.mormi.backend.cafe.CafeService;
import com.mormi.backend.cafe.CafeVisit;
import com.mormi.backend.cafe.CafeVisitRepository;
import com.mormi.backend.cafe.CafeDtos.ChangeRequest;
import com.mormi.backend.cafe.CafeDtos.MenuRequest;
import com.mormi.backend.cafe.CafeDtos.PaymentRequest;
import com.mormi.backend.cafe.CafeDtos.QueueRequest;
import com.mormi.backend.cafe.CafeDtos.StageResultResponse;
import com.mormi.backend.common.ApiException;
import com.mormi.backend.curriculum.CurriculumCatalog;
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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * 인증된 학습자와 AI 대화를 잇는 오케스트레이션 계층.
 *
 * <p>브라우저가 learner_id, 보존 동의, 연습 결과, 대화 소유권을 정하지 못하게 하고
 * BE에 저장된 사실만 Mormi-AI로 보낸다.
 */
@Service
public class DialogueService {

    private static final String ACTIVITY_DRILL = "drill";

    private final DialogueClient dialogueClient;
    private final DialogueConversationRepository dialogueRepository;
    private final LearningSessionRepository sessionRepository;
    private final AttemptRepository attemptRepository;
    private final CafeVisitRepository cafeVisitRepository;
    private final CafeService cafeService;
    private final LearnerService learnerService;
    private final RewardService rewardService;

    public DialogueService(
            DialogueClient dialogueClient,
            DialogueConversationRepository dialogueRepository,
            LearningSessionRepository sessionRepository,
            AttemptRepository attemptRepository,
            CafeVisitRepository cafeVisitRepository,
            CafeService cafeService,
            LearnerService learnerService,
            RewardService rewardService) {
        this.dialogueClient = dialogueClient;
        this.dialogueRepository = dialogueRepository;
        this.sessionRepository = sessionRepository;
        this.attemptRepository = attemptRepository;
        this.cafeVisitRepository = cafeVisitRepository;
        this.cafeService = cafeService;
        this.learnerService = learnerService;
        this.rewardService = rewardService;
    }

    /** 마지막 drill 저장 이후 호출한다. 반복 결과 저장과 가르치기 시작을 한 API로 묶는다. */
    @Transactional
    public JsonNode startHomeTeaching(Long learnerId, String publicSessionId) {
        LearningSession session = requireSessionOwned(learnerId, publicSessionId);
        if (session.isCompleted()) {
            throw ApiException.conflict("session_completed", "이미 완료된 세션입니다.");
        }

        DialogueConversation existing = dialogueRepository
                .findByLearningSessionId(session.getId())
                .orElse(null);
        if (existing != null) {
            return dialogueClient.getConversation(existing.getConversationId());
        }

        int solvedQuestions = attemptRepository.countDistinctCorrectQuestions(
                session.getId(), ACTIVITY_DRILL);
        if (solvedQuestions < CurriculumCatalog.MASTERY_TARGET) {
            throw ApiException.conflict(
                    "drill_not_completed",
                    "반복 문제 %d개를 마친 뒤 모르미를 가르칠 수 있습니다."
                            .formatted(CurriculumCatalog.MASTERY_TARGET));
        }

        Learner learner = learnerService.require(learnerId);
        String practiceResultId = practiceResultId(session);

        Map<String, Object> request = baseRequest(learner, "home_teach", "home_teach");
        request.put("learning_session_id", session.getPublicId());
        request.put("practice_result_id", practiceResultId);
        request.put("practice_summary", buildPracticeSummary(session));

        JsonNode envelope = requireEnvelope(dialogueClient.createConversation(request));
        String conversationId = envelope.path("conversation_id").asString();

        session.setPracticeResultId(practiceResultId);
        session.setConversationId(conversationId);
        dialogueRepository.save(DialogueConversation.forLearningSession(
                conversationId, learnerId, session.getId()));
        return envelope;
    }

    /** 카페 화면에 고정된 문제 사실만 받아, BE가 이미 열어 준 단계의 AI 대화를 시작한다. */
    @Transactional
    public Map<String, Object> startCafeDialogue(
            Long learnerId, String publicVisitId, StartCafeDialogueRequest request) {
        CafeVisit visit = requireCafeOwned(learnerId, publicVisitId);
        DialogueConversation latest = dialogueRepository
                .findFirstByCafeVisitIdAndScenarioIdOrderByRoundDesc(visit.getId(), request.scenarioId())
                .orElse(null);
        // 다시 연습이 아니면 새로고침 복구로 보고 마지막 회차를 그대로 이어 준다.
        if (latest != null && !request.restart()) {
            return envelopeWithContext(
                    latest, dialogueClient.getConversation(latest.getConversationId()));
        }

        // 단계 제출(CafeService.requireStageReached)과 같은 기준으로 연다. 이미 통과한
        // 돌다리를 다시 눌러도 대화가 열려야 하고, 첫 시도 때 AI 대화 생성이 실패해
        // 저장된 대화가 없는 단계도 뒤늦게 다시 열 수 있어야 한다. 막을 것은 아직
        // 도달하지 않은 앞선 단계뿐이다. 완료된 방문은 네 단계를 모두 지났으므로
        // 어느 단계든 다시 연습할 수 있다.
        CafeStage requestedStage = stageForScenario(request.scenarioId());
        if (!visit.isCompleted() && !requestedStage.isReachedBy(visit.stage())) {
            throw ApiException.conflict(
                    "dialogue_stage_locked",
                    "아직 열리지 않은 카페 단계입니다. 현재 단계: " + visit.getStage());
        }
        int round = latest == null ? 1 : latest.getRound() + 1;

        Learner learner = learnerService.require(learnerId);
        Map<String, Object> body = baseRequest(learner, "cafe", request.scenarioId());
        Map<String, Object> scenarioContext = new LinkedHashMap<>();
        if ("cafe_queue".equals(request.scenarioId())) {
            if (request.queueContext() == null || request.queueContext().isEmpty()) {
                throw ApiException.badRequest("queue_context_required", "줄 화면 정보가 필요합니다.");
            }
            body.put("queue_context", request.queueContext());
            scenarioContext.put("queue_context", new LinkedHashMap<>(request.queueContext()));
        } else {
            if (request.cafeContext() == null || request.cafeContext().isEmpty()) {
                throw ApiException.badRequest("cafe_context_required", "메뉴 화면 정보가 필요합니다.");
            }
            body.put("cafe_context", request.cafeContext());
            scenarioContext.put("cafe_context", new LinkedHashMap<>(request.cafeContext()));
        }

        JsonNode envelope = requireEnvelope(dialogueClient.createConversation(body));
        String conversationId = envelope.path("conversation_id").asString();
        DialogueConversation dialogue = DialogueConversation.forCafeVisit(
                conversationId, learnerId, visit.getId(), request.scenarioId(), round, scenarioContext);
        dialogueRepository.save(dialogue);
        return envelopeWithContext(dialogue, envelope);
    }

    @Transactional
    public Object getConversation(Long learnerId, String conversationId) {
        DialogueConversation dialogue = requireDialogueOwned(learnerId, conversationId);
        JsonNode envelope = dialogueClient.getConversation(conversationId);
        return dialogue.getCafeVisitId() == null ? envelope : envelopeWithContext(dialogue, envelope);
    }

    /** 원문은 저장하지 않고, 소유권 확인 뒤 AI로만 전달한다. */
    @Transactional
    public Object respond(Long learnerId, String conversationId, JsonNode response) {
        DialogueConversation dialogue = requireDialogueOwned(learnerId, conversationId);
        JsonNode envelope = dialogueClient.respond(conversationId, response);
        return dialogue.getCafeVisitId() == null ? envelope : envelopeWithContext(dialogue, envelope);
    }

    private Map<String, Object> buildPracticeSummary(LearningSession session) {
        List<Attempt> attempts = attemptRepository.findByLearningSessionIdOrderByIdAsc(session.getId())
                .stream()
                .filter(attempt -> ACTIVITY_DRILL.equals(attempt.getActivity()))
                .toList();

        Map<Integer, List<Attempt>> byQuestion = new LinkedHashMap<>();
        for (Attempt attempt : attempts) {
            byQuestion.computeIfAbsent(attempt.getQuestionIndex(), ignored -> new ArrayList<>())
                    .add(attempt);
        }
        byQuestion.values().forEach(values -> values.sort(Comparator.comparing(Attempt::getId)));

        int firstTryCorrect = (int) byQuestion.values().stream()
                .filter(values -> !values.isEmpty() && values.getFirst().isCorrect())
                .count();
        int wrongAttempts = (int) attempts.stream().filter(attempt -> !attempt.isCorrect()).count();

        Set<String> misconceptionTags = new LinkedHashSet<>();
        List<Map<String, Object>> compactAttempts = new ArrayList<>();
        for (Attempt attempt : attempts.stream().limit(50).toList()) {
            String tag = stringMeta(attempt, "misconception_tag");
            if (tag != null) {
                misconceptionTags.add(tag);
            }
            Map<String, Object> compact = new LinkedHashMap<>();
            compact.put("item_id", attempt.getItemId() == null
                    ? session.getCurriculumSessionId() + ":" + attempt.getQuestionIndex()
                    : attempt.getItemId());
            compact.put("correct", attempt.isCorrect());
            if (tag != null) {
                compact.put("misconception_tag", tag);
            }
            if (attempt.getElapsedMs() != null) {
                compact.put("latency_ms", attempt.getElapsedMs());
            }
            compactAttempts.add(compact);
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("curriculum_session_id", session.getCurriculumSessionId());
        summary.put("skill_id", session.getCurriculumSessionId());
        summary.put("attempts", compactAttempts);
        summary.put("question_count", byQuestion.size());
        summary.put("first_try_correct_count", firstTryCorrect);
        summary.put("wrong_attempt_count", wrongAttempts);
        summary.put("earned_reward", rewardService.sessionReward(session.getId(), RewardSource.DRILL));
        summary.put("misconception_tags", List.copyOf(misconceptionTags));
        return summary;
    }

    private Map<String, Object> baseRequest(Learner learner, String scene, String scenarioId) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("learner_id", learner.getId());
        request.put("scene", scene);
        request.put("scenario_id", scenarioId);
        request.put("conversation_storage_consent", learner.isConversationStorageConsent());
        request.put("retention_policy", learner.getRetentionPolicy());
        return request;
    }

    private JsonNode requireEnvelope(JsonNode envelope) {
        if (envelope == null || envelope.path("conversation_id").asString().isBlank()
                || envelope.path("turn").isMissingNode()) {
            throw ApiException.serviceUnavailable(
                    "dialogue_invalid_response", "대화 서버 응답 형식이 올바르지 않습니다.");
        }
        return envelope;
    }

    private Map<String, Object> envelopeWithContext(
            DialogueConversation dialogue, JsonNode rawEnvelope) {
        JsonNode envelope = requireEnvelope(rawEnvelope);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("conversation_id", envelope.path("conversation_id").asString());
        response.put("turn", envelope.path("turn"));
        response.put("scenario_context", dialogue.getScenarioContext());
        response.put("stage_progress", reconcileCafeCompletion(dialogue, envelope));
        return response;
    }

    /**
     * AI가 가르치기/도움 경로를 끝냈을 때 검증된 슬롯만 사용해 카페 진행을 맞춘다.
     * 자유 발화 원문이나 모르미 대사는 절대 단계 정답으로 사용하지 않는다.
     */
    private Map<String, Object> reconcileCafeCompletion(
            DialogueConversation dialogue, JsonNode envelope) {
        CafeStage expectedStage = stageForScenario(dialogue.getScenarioId());
        CafeVisit visit = cafeVisitRepository.findById(dialogue.getCafeVisitId())
                .orElseThrow(() -> ApiException.notFound("카페 방문을 찾을 수 없습니다."));

        // 통과 여부는 방문 진행도가 아니라 "이 회차가 통과했는지"로 본다.
        // 방문 진행도로 판정하면 이미 지난 단계를 다시 연습할 때 대화 검증 없이
        // 무조건 통과가 되어 버린다(줄 서기는 결정적 제출 API를 쓰지 않아 특히 그렇다).
        if (dialogue.getClearedAt() != null) {
            return stageProgress(expectedStage, visit.stage(), true, "stage_attempt");
        }

        JsonNode turn = envelope.path("turn");
        JsonNode completion = turn.path("completion");
        boolean completedByDialogue = "completed".equals(turn.path("status").asString())
                && completion.path("teach_reward_eligible").asBoolean(false)
                && !"bright_exit".equals(completion.path("outcome").asString());
        if (!completedByDialogue) {
            return stageProgress(expectedStage, visit.stage(), false, "pending");
        }

        JsonNode facts = completion.path("verified_facts");
        if (!facts.isObject()) {
            throw ApiException.serviceUnavailable(
                    "dialogue_completion_facts_missing",
                    "대화 완료 사실을 확인하지 못했습니다. 잠시 뒤 다시 시도해 주세요.");
        }

        // 회차마다 1,000칸씩 떨어진 대역을 쓴다. 회차가 달라도 대화 안의 state_version 은
        // 다시 1부터 시작하므로, 대역을 나누지 않으면 이전 회차 기록의 멱등키에 걸려
        // 새 답이 저장되지 않는다. 1회차는 예전 계산식과 같은 값을 유지한다.
        int stateVersion = Math.min(Math.max(1, turn.path("state_version").asInt(1)), 999);
        int attemptNo = 900_000 + (dialogue.getRound() - 1) * 1_000 + stateVersion;
        StageResultResponse result = switch (expectedStage) {
            case QUEUE -> syncQueue(dialogue, facts, attemptNo);
            case MENU -> syncMenu(dialogue, facts, attemptNo);
            case CALCULATE -> syncCalculation(dialogue, facts, attemptNo);
            case CHANGE -> syncChange(dialogue, facts, attemptNo);
            case COMPLETE -> throw ApiException.conflict(
                    "cafe_visit_completed", "이미 완료된 카페 방문입니다.");
        };
        if (result.isCorrect()) {
            dialogue.markCleared();
        }
        CafeStage nextStage = CafeStage.from(result.nextStage());
        return stageProgress(expectedStage, nextStage, result.isCorrect(), "dialogue_verified_facts");
    }

    private StageResultResponse syncQueue(
            DialogueConversation dialogue, JsonNode facts, int attemptNo) {
        Map<String, Object> context = nestedContext(dialogue, "queue_context");
        int left = contextInt(context, "left_count");
        int right = contextInt(context, "right_count");
        int verifiedLeft = factInt(facts, "left_count");
        int verifiedRight = factInt(facts, "right_count");
        if (verifiedLeft != left || verifiedRight != right) {
            throw ApiException.serviceUnavailable(
                    "dialogue_completion_fact_mismatch",
                    "검증된 줄 정보와 화면 문제가 일치하지 않습니다.");
        }
        int smaller = Math.min(verifiedLeft, verifiedRight);
        return cafeService.submitQueue(
                dialogue.getLearnerId(),
                cafeVisitPublicId(dialogue),
                new QueueRequest(left, right, smaller, true, attemptNo, null));
    }

    private StageResultResponse syncMenu(
            DialogueConversation dialogue, JsonNode facts, int attemptNo) {
        Map<String, Object> context = nestedContext(dialogue, "cafe_context");
        return cafeService.submitMenu(
                dialogue.getLearnerId(),
                cafeVisitPublicId(dialogue),
                new MenuRequest(
                        List.of(contextString(context, "mormi_menu_id"), factText(facts, "child_menu_id")),
                        contextInt(context, "budget"),
                        attemptNo,
                        null));
    }

    private StageResultResponse syncCalculation(
            DialogueConversation dialogue, JsonNode facts, int attemptNo) {
        Map<String, Object> context = nestedContext(dialogue, "cafe_context");
        return cafeService.submitPayment(
                dialogue.getLearnerId(),
                cafeVisitPublicId(dialogue),
                new PaymentRequest(
                        List.of(contextString(context, "mormi_menu_id"), factText(facts, "child_menu_id")),
                        factInt(facts, "result"),
                        attemptNo,
                        null));
    }

    private StageResultResponse syncChange(
            DialogueConversation dialogue, JsonNode facts, int attemptNo) {
        Map<String, Object> context = nestedContext(dialogue, "cafe_context");
        int result = factInt(facts, "result");
        Map<Integer, Integer> counts = Map.of(
                1000, result / 1000,
                500, (result % 1000) / 500);
        return cafeService.submitChange(
                dialogue.getLearnerId(),
                cafeVisitPublicId(dialogue),
                new ChangeRequest(
                        contextString(context, "mormi_menu_id"), counts, attemptNo, null));
    }

    private String cafeVisitPublicId(DialogueConversation dialogue) {
        return cafeVisitRepository.findById(dialogue.getCafeVisitId())
                .orElseThrow(() -> ApiException.notFound("카페 방문을 찾을 수 없습니다."))
                .getPublicId();
    }

    private Map<String, Object> stageProgress(
            CafeStage stage, CafeStage nextStage, boolean completed, String source) {
        Map<String, Object> progress = new LinkedHashMap<>();
        progress.put("stage", stage.value());
        progress.put("completed", completed);
        progress.put("next_stage", nextStage.value());
        progress.put("source", source);
        return progress;
    }

    private Map<String, Object> nestedContext(
            DialogueConversation dialogue, String key) {
        Object value = dialogue.getScenarioContext().get(key);
        if (!(value instanceof Map<?, ?> raw)) {
            throw ApiException.serviceUnavailable(
                    "dialogue_context_missing", "저장된 카페 문제 정보를 찾지 못했습니다.");
        }
        Map<String, Object> context = new LinkedHashMap<>();
        raw.forEach((nestedKey, nestedValue) -> context.put(String.valueOf(nestedKey), nestedValue));
        return context;
    }

    private int contextInt(Map<String, Object> context, String key) {
        Object value = context.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException error) {
            throw ApiException.serviceUnavailable(
                    "dialogue_context_invalid", "저장된 카페 숫자 정보가 올바르지 않습니다.");
        }
    }

    private String contextString(Map<String, Object> context, String key) {
        Object value = context.get(key);
        if (!(value instanceof String text) || text.isBlank()) {
            throw ApiException.serviceUnavailable(
                    "dialogue_context_invalid", "저장된 카페 메뉴 정보가 올바르지 않습니다.");
        }
        return text;
    }

    private int factInt(JsonNode facts, String key) {
        JsonNode value = facts.path(key);
        try {
            return Integer.parseInt(value.asString());
        } catch (NumberFormatException error) {
            throw ApiException.serviceUnavailable(
                    "dialogue_completion_fact_invalid", "검증된 계산 결과를 확인하지 못했습니다.");
        }
    }

    private String factText(JsonNode facts, String key) {
        String value = facts.path(key).asString();
        if (value.isBlank()) {
            throw ApiException.serviceUnavailable(
                    "dialogue_completion_fact_invalid", "검증된 메뉴 선택을 확인하지 못했습니다.");
        }
        return value;
    }

    private String practiceResultId(LearningSession session) {
        return "practice_" + session.getPublicId().substring("session_".length());
    }

    private String stringMeta(Attempt attempt, String key) {
        Object value = attempt.getAnswerMeta().get(key);
        if (!(value instanceof String text) || text.isBlank()) {
            return null;
        }
        return text.length() <= 80 ? text : text.substring(0, 80);
    }

    private CafeStage stageForScenario(String scenarioId) {
        return switch (scenarioId) {
            case "cafe_queue" -> CafeStage.QUEUE;
            case "cafe_budget_menu" -> CafeStage.MENU;
            case "cafe_menu_total" -> CafeStage.CALCULATE;
            case "cafe_change" -> CafeStage.CHANGE;
            default -> throw ApiException.badRequest(
                    "dialogue_scenario_invalid", "지원하지 않는 카페 대화입니다: " + scenarioId);
        };
    }

    private LearningSession requireSessionOwned(Long learnerId, String publicId) {
        LearningSession session = sessionRepository.findByPublicId(publicId)
                .orElseThrow(() -> ApiException.notFound("학습 세션을 찾을 수 없습니다."));
        if (!session.getLearnerId().equals(learnerId)) {
            throw ApiException.forbidden("다른 학습자의 세션입니다.");
        }
        return session;
    }

    private CafeVisit requireCafeOwned(Long learnerId, String publicId) {
        CafeVisit visit = cafeVisitRepository.findByPublicId(publicId)
                .orElseThrow(() -> ApiException.notFound("카페 방문을 찾을 수 없습니다."));
        if (!visit.getLearnerId().equals(learnerId)) {
            throw ApiException.forbidden("다른 학습자의 카페 방문입니다.");
        }
        return visit;
    }

    private DialogueConversation requireDialogueOwned(Long learnerId, String conversationId) {
        DialogueConversation dialogue = dialogueRepository.findByConversationId(conversationId)
                .orElseThrow(() -> ApiException.notFound("대화를 찾을 수 없습니다."));
        if (!dialogue.getLearnerId().equals(learnerId)) {
            throw ApiException.forbidden("다른 학습자의 대화입니다.");
        }
        return dialogue;
    }
}
