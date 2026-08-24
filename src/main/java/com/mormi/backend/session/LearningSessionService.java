package com.mormi.backend.session;

import com.mormi.backend.common.ApiException;
import com.mormi.backend.curriculum.CurriculumCatalog;
import com.mormi.backend.dialogue.DialogueClient;
import com.mormi.backend.outcome.TaskOutcomeService;
import com.mormi.backend.progress.ThemeProgressService;
import com.mormi.backend.reward.RewardService;
import com.mormi.backend.reward.RewardSource;
import com.mormi.backend.session.SessionDtos.AttemptView;
import com.mormi.backend.session.SessionDtos.CompleteSessionRequest;
import com.mormi.backend.session.SessionDtos.CompleteSessionResponse;
import com.mormi.backend.session.SessionDtos.RecordAttemptRequest;
import com.mormi.backend.session.SessionDtos.RecordAttemptResponse;
import com.mormi.backend.session.SessionDtos.SessionView;
import com.mormi.backend.session.SessionDtos.StartSessionRequest;
import com.mormi.backend.session.SessionDtos.StartSessionResponse;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LearningSessionService {

    private static final String ACTIVITY_DRILL = "drill";
    private static final String ACTIVITY_TRANSFER = "transfer";
    private static final Set<String> SAFE_ANSWER_META_KEYS = Set.of(
            "selected_choice_id",
            "selected_choice_ids",
            "locked_choice_ids",
            "misconception_tag",
            "response_category",
            "difficulty_class",
            "expression_level",
            "hint_level",
            "turn_id",
            "input_kind",
            "scaffold_used",
            "selected_menu_ids",
            "action_id");
    private static final Pattern SAFE_META_IDENTIFIER =
            Pattern.compile("[A-Za-z0-9._:-]{1,100}");

    private final LearningSessionRepository sessionRepository;
    private final AttemptRepository attemptRepository;
    private final RewardService rewardService;
    private final ThemeProgressService themeProgressService;
    private final DialogueClient dialogueClient;
    private final TaskOutcomeService taskOutcomeService;
    private final LadderAnalysisTriggerService ladderAnalysisTriggerService;

    public LearningSessionService(
            LearningSessionRepository sessionRepository,
            AttemptRepository attemptRepository,
            RewardService rewardService,
            ThemeProgressService themeProgressService,
            DialogueClient dialogueClient,
            TaskOutcomeService taskOutcomeService,
            LadderAnalysisTriggerService ladderAnalysisTriggerService) {
        this.sessionRepository = sessionRepository;
        this.attemptRepository = attemptRepository;
        this.rewardService = rewardService;
        this.themeProgressService = themeProgressService;
        this.dialogueClient = dialogueClient;
        this.taskOutcomeService = taskOutcomeService;
        this.ladderAnalysisTriggerService = ladderAnalysisTriggerService;
    }

    @Transactional
    public StartSessionResponse start(Long learnerId, StartSessionRequest request) {
        LearningSession session = sessionRepository.save(
                LearningSession.start(learnerId, request.curriculumSessionId(), request.variantSeed()));
        return new StartSessionResponse(
                session.getPublicId(),
                session.getCurriculumSessionId(),
                session.getVariantSeed(),
                session.getStartedAt(),
                CurriculumCatalog.SESSION_TIME_LIMIT_SECONDS,
                CurriculumCatalog.MASTERY_TARGET);
    }

    /**
     * 시도 1건 기록. 정답·오답 모두 남긴다.
     *
     * <p>보상은 클라이언트가 보낸 값을 쓰지 않고 서버가 저장된 오답 수로 계산한다.
     * (learning_session_id, activity, attempt_no) 유니크 제약이 재전송 중복을 막는다.
     */
    @Transactional
    public RecordAttemptResponse recordAttempt(
            Long learnerId, String publicId, RecordAttemptRequest request) {

        LearningSession session = requireOwned(learnerId, publicId);
        if (session.isCompleted()) {
            throw ApiException.conflict("session_completed", "이미 완료된 세션입니다.");
        }

        // 같은 attempt_no 재전송이면 처음 결과를 그대로 돌려준다.
        var existing = attemptRepository.findByLearningSessionIdAndActivityAndAttemptNo(
                session.getId(), request.activity(), request.attemptNo());
        if (existing.isPresent()) {
            Attempt attempt = existing.get();
            return buildAttemptResponse(session, attempt, true, attempt.getRewardGranted());
        }

        // 적용 맥락은 적용(transfer) 시도에만 의미가 있다. drill 에 섞이면 집계가 오염된다.
        if (request.applicationScope() != null && !ACTIVITY_TRANSFER.equals(request.activity())) {
            throw ApiException.badRequest(
                    "application_scope_not_allowed", "application_scope 는 transfer 시도에만 보낼 수 있습니다.");
        }

        boolean correct = Boolean.TRUE.equals(request.isCorrect());
        int wrongBefore = attemptRepository.countWrongForQuestion(
                session.getId(), request.activity(), request.questionIndex());

        Map<String, Object> answerMeta = sanitizeAnswerMeta(request.answerMeta());
        // 서버가 센 오답 수를 기록해 둔다. 클라이언트 값과 어긋나도 이 값이 기준이다.
        answerMeta.put("wrong_count_before", wrongBefore);

        Attempt attempt = attemptRepository.save(Attempt.record(
                session.getId(),
                request.activity(),
                request.attemptNo(),
                request.itemId(),
                request.questionIndex(),
                correct,
                request.elapsedMs(),
                request.applicationScope(),
                request.supportLevel(),
                answerMeta));

        int granted = 0;
        if (correct && ACTIVITY_DRILL.equals(request.activity())) {
            int amount = CurriculumCatalog.drillReward(wrongBefore);
            int alreadyEarned = rewardService.sessionReward(session.getId(), RewardSource.DRILL);
            int room = Math.max(0, CurriculumCatalog.maxDrillReward() - alreadyEarned);
            amount = Math.min(amount, room);
            granted = rewardService.grant(
                    learnerId,
                    session.getId(),
                    RewardSource.DRILL,
                    amount,
                    "drill:%d:%d".formatted(session.getId(), request.questionIndex()));
            if (granted > 0) {
                attempt.setRewardGranted(granted);
            }
        }
        return buildAttemptResponse(session, attempt, false, granted);
    }

    /**
     * 적용 시도 기록이 있으면 서버 기록으로 판정하고, 없으면 FE 값을 쓴다.
     * 적용 시도를 아직 안 보내는 예전 FE 가 깨지지 않으면서도 기록이 생기는 즉시 서버가 기준이 된다.
     */
    private boolean deriveTransferSolved(Long sessionId, boolean reported) {
        if (attemptRepository.countByLearningSessionIdAndActivity(sessionId, ACTIVITY_TRANSFER) == 0) {
            return reported;
        }
        return attemptRepository.countCorrect(sessionId, ACTIVITY_TRANSFER) > 0;
    }

    private RecordAttemptResponse buildAttemptResponse(
            LearningSession session, Attempt attempt, boolean duplicate, int granted) {
        int correctCount = attemptRepository.countDistinctCorrectQuestions(session.getId(), ACTIVITY_DRILL);
        return new RecordAttemptResponse(
                attempt.getId(),
                duplicate,
                granted,
                rewardService.sessionReward(session.getId(), RewardSource.DRILL),
                correctCount,
                CurriculumCatalog.MASTERY_TARGET,
                correctCount >= CurriculumCatalog.MASTERY_TARGET);
    }

    /**
     * 세션 종료와 보상 확정을 한 트랜잭션으로 처리한다.
     *
     * <p>가르치기 500원은 BE가 가르치기 시작 때 저장한 conversation_id 로
     * Mormi-AI 완료 여부를 확인한 뒤에만 지급한다.
     * 프런트가 saveReport 를 두 번 호출해도 멱등키 때문에 한 번만 적립된다.
     */
    @Transactional
    public CompleteSessionResponse complete(
            Long learnerId, String publicId, CompleteSessionRequest request) {

        LearningSession session = requireOwned(learnerId, publicId);

        boolean teachEligible = false;
        boolean completedNow = !session.isCompleted();
        if (completedNow) {
            session.setTransferSolved(deriveTransferSolved(session.getId(), request.transferSolved()));
            session.setScaffoldLevel(request.scaffoldLevel());
            session.setElapsedSeconds(
                    request.elapsedSeconds() != null ? request.elapsedSeconds() : session.serverElapsedSeconds());
            session.setTimedOut(request.timedOut() || session.exceededTimeLimit());
            if (session.getPracticeResultId() == null) {
                session.setPracticeResultId(
                        "practice_" + session.getPublicId().substring("session_".length()));
            }
            session.setCompletedAt(OffsetDateTime.now());
        }

        String trustedConversationId = session.getConversationId();
        if (trustedConversationId != null && !trustedConversationId.isBlank()) {
            teachEligible = dialogueClient.isTeachRewardEligible(trustedConversationId);
            if (teachEligible) {
                rewardService.grant(
                        learnerId,
                        session.getId(),
                        RewardSource.TEACH,
                        CurriculumCatalog.TEACH_REWARD,
                        "teach-reward:%s".formatted(session.getPublicId()));
            }
        }

        // 관찰이 하나도 없는 문제도 집계 행을 갖도록 완료 시점에 한 번 계산한다.
        // 뒤늦게 관찰이 도착하면 수집 쪽에서 같은 규칙으로 다시 계산한다.
        taskOutcomeService.recompute(session.getId());

        List<String> completed = sessionRepository.findCompletedCurriculumSessionIds(learnerId);
        boolean cafeUnlocked = themeProgressService.syncCafeUnlock(learnerId, Set.copyOf(completed));

        if (completedNow) {
            ladderAnalysisTriggerService.schedule(learnerId, session.getPublicId());
        }

        int drillReward = rewardService.sessionReward(session.getId(), RewardSource.DRILL);
        int teachReward = rewardService.sessionReward(session.getId(), RewardSource.TEACH);

        return new CompleteSessionResponse(
                session.getPublicId(),
                session.getPracticeResultId(),
                drillReward,
                teachReward,
                drillReward + teachReward,
                rewardService.walletBalance(learnerId),
                teachReward > 0,
                completed,
                cafeUnlocked,
                session.getCompletedAt());
    }

    @Transactional(readOnly = true)
    public SessionView view(Long learnerId, String publicId) {
        LearningSession session = requireOwned(learnerId, publicId);
        List<AttemptView> attempts = attemptRepository.findByLearningSessionIdOrderByIdAsc(session.getId())
                .stream().map(AttemptView::of).toList();
        return new SessionView(
                session.getPublicId(),
                session.getCurriculumSessionId(),
                session.getVariantSeed(),
                session.getStartedAt(),
                session.getCompletedAt(),
                session.isTimedOut(),
                session.isTransferSolved(),
                session.getScaffoldLevel(),
                session.getConversationId(),
                attemptRepository.countDistinctCorrectQuestions(session.getId(), ACTIVITY_DRILL),
                CurriculumCatalog.MASTERY_TARGET,
                rewardService.sessionReward(session.getId(), RewardSource.DRILL),
                attempts);
    }

    @Transactional(readOnly = true)
    public LearningSession requireOwned(Long learnerId, String publicId) {
        LearningSession session = sessionRepository.findByPublicId(publicId)
                .orElseThrow(() -> ApiException.notFound("학습 세션을 찾을 수 없습니다."));
        if (!session.getLearnerId().equals(learnerId)) {
            throw ApiException.forbidden("다른 학습자의 세션입니다.");
        }
        return session;
    }

    /**
     * 일반 학습 DB에는 자유 발화 원문을 남기지 않는다. 허용된 구조 필드만 복사하고,
     * 원문은 동의 정책을 적용하는 Mormi-AI로만 전달한다.
     */
    private Map<String, Object> sanitizeAnswerMeta(Map<String, Object> source) {
        Map<String, Object> sanitized = new LinkedHashMap<>();
        if (source == null) {
            return sanitized;
        }
        for (String key : SAFE_ANSWER_META_KEYS) {
            Object value = source.get(key);
            if (value instanceof String text && SAFE_META_IDENTIFIER.matcher(text).matches()) {
                sanitized.put(key, value);
            } else if (value instanceof Number || value instanceof Boolean) {
                sanitized.put(key, value);
            } else if (value instanceof List<?> values) {
                List<String> identifiers = values.stream()
                        .limit(20)
                        .filter(String.class::isInstance)
                        .map(String.class::cast)
                        .filter(item -> SAFE_META_IDENTIFIER.matcher(item).matches())
                        .toList();
                if (!identifiers.isEmpty()) {
                    sanitized.put(key, identifiers);
                }
            }
        }
        return sanitized;
    }
}
