package com.mormi.backend.session;

import com.mormi.backend.common.ApiException;
import com.mormi.backend.curriculum.CurriculumCatalog;
import com.mormi.backend.dialogue.DialogueClient;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LearningSessionService {

    private static final String ACTIVITY_DRILL = "drill";

    private final LearningSessionRepository sessionRepository;
    private final AttemptRepository attemptRepository;
    private final RewardService rewardService;
    private final ThemeProgressService themeProgressService;
    private final DialogueClient dialogueClient;

    public LearningSessionService(
            LearningSessionRepository sessionRepository,
            AttemptRepository attemptRepository,
            RewardService rewardService,
            ThemeProgressService themeProgressService,
            DialogueClient dialogueClient) {
        this.sessionRepository = sessionRepository;
        this.attemptRepository = attemptRepository;
        this.rewardService = rewardService;
        this.themeProgressService = themeProgressService;
        this.dialogueClient = dialogueClient;
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

        boolean correct = Boolean.TRUE.equals(request.isCorrect());
        int wrongBefore = attemptRepository.countWrongForQuestion(
                session.getId(), request.activity(), request.questionIndex());

        Map<String, Object> answerMeta = new LinkedHashMap<>();
        if (request.answerMeta() != null) {
            answerMeta.putAll(request.answerMeta());
        }
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

    private RecordAttemptResponse buildAttemptResponse(
            LearningSession session, Attempt attempt, boolean duplicate, int granted) {
        int correctCount = attemptRepository.countCorrect(session.getId(), ACTIVITY_DRILL);
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
     * <p>가르치기 500원은 conversation_id 로 Mormi-AI 에 완료 여부를 확인한 뒤에만 지급한다.
     * 프런트가 saveReport 를 두 번 호출해도 멱등키 때문에 한 번만 적립된다.
     */
    @Transactional
    public CompleteSessionResponse complete(
            Long learnerId, String publicId, CompleteSessionRequest request) {

        LearningSession session = requireOwned(learnerId, publicId);

        boolean teachEligible = false;
        if (!session.isCompleted()) {
            session.setTransferSolved(request.transferSolved());
            session.setScaffoldLevel(request.scaffoldLevel());
            session.setConversationId(request.conversationId());
            session.setElapsedSeconds(
                    request.elapsedSeconds() != null ? request.elapsedSeconds() : session.serverElapsedSeconds());
            session.setTimedOut(request.timedOut() || session.exceededTimeLimit());
            session.setPracticeResultId("practice_" + session.getPublicId().substring("session_".length()));
            session.setCompletedAt(OffsetDateTime.now());
        }

        if (request.conversationId() != null && !request.conversationId().isBlank()) {
            teachEligible = dialogueClient.isTeachRewardEligible(request.conversationId());
            if (teachEligible) {
                rewardService.grant(
                        learnerId,
                        session.getId(),
                        RewardSource.TEACH,
                        CurriculumCatalog.TEACH_REWARD,
                        "teach-reward:%s:%s".formatted(session.getPublicId(), request.conversationId()));
            }
        }

        List<String> completed = sessionRepository.findCompletedCurriculumSessionIds(learnerId);
        boolean cafeUnlocked = themeProgressService.syncCafeUnlock(learnerId, Set.copyOf(completed));

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
                attemptRepository.countCorrect(session.getId(), ACTIVITY_DRILL),
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
}
