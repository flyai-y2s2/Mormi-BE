package com.mormi.backend.report;

import com.mormi.backend.common.ApiException;
import com.mormi.backend.organization.LearnerEnrollmentRepository;
import com.mormi.backend.outcome.LearningTaskOutcome;
import com.mormi.backend.outcome.LearningTaskOutcomeRepository;
import com.mormi.backend.outcome.TaskOutcomeService;
import com.mormi.backend.session.LearningSession;
import com.mormi.backend.session.LearningSessionRepository;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 저장된 집계에서 구조화 리포트 스냅샷을 만든다. LLM 은 쓰지 않는다.
 *
 * <p>본문의 모든 값은 learning_task_outcomes 에서 나오고, 각 항목이 근거 ID 를 갖는다.
 * 정적 커리큘럼 문장을 아이 관찰값처럼 섞지 않는다.
 */
@Service
public class ReportSnapshotService {

    /**
     * AI 계약(OBSERVATION_EVENTS.md) 리포트 사용 원칙.
     * 모든 요약에 단일 관찰이 진단이 아님을 표시한다.
     */
    static final String DISCLAIMER = "단일 세션의 수행 관찰이며 진단이 아님";

    private final LearningSessionRepository sessionRepository;
    private final LearningTaskOutcomeRepository outcomeRepository;
    private final LearnerEnrollmentRepository enrollmentRepository;
    private final ReportSnapshotRepository snapshotRepository;

    public ReportSnapshotService(
            LearningSessionRepository sessionRepository,
            LearningTaskOutcomeRepository outcomeRepository,
            LearnerEnrollmentRepository enrollmentRepository,
            ReportSnapshotRepository snapshotRepository) {
        this.sessionRepository = sessionRepository;
        this.outcomeRepository = outcomeRepository;
        this.enrollmentRepository = enrollmentRepository;
        this.snapshotRepository = snapshotRepository;
    }

    @Transactional
    public ReportSnapshot snapshotForLearner(Long learnerId, OffsetDateTime from, OffsetDateTime to) {
        Evidence evidence = new Evidence();
        Map<String, Object> section = learnerSection(learnerId, from, to, evidence);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("disclaimer", DISCLAIMER);
        body.put("learner", section);

        return snapshotRepository.save(ReportSnapshot.forLearner(
                learnerId, from, to, body,
                evidence.observationIds, evidence.attemptIds, evidence.outcomeIds,
                TaskOutcomeService.RULE_VERSION));
    }

    /** 학급 스냅샷. 재적 중인 아이들의 섹션을 한 본문에 모은다. */
    @Transactional
    public ReportSnapshot snapshotForCohort(Long cohortId, OffsetDateTime from, OffsetDateTime to) {
        List<Long> learnerIds = enrollmentRepository.findActiveLearnerIds(cohortId);
        if (learnerIds.isEmpty()) {
            throw ApiException.notFound("재적 중인 학습자가 없는 학급입니다.");
        }

        Evidence evidence = new Evidence();
        List<Map<String, Object>> sections = new ArrayList<>();
        for (Long learnerId : learnerIds) {
            sections.add(learnerSection(learnerId, from, to, evidence));
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("disclaimer", DISCLAIMER);
        body.put("learners", sections);

        return snapshotRepository.save(ReportSnapshot.forCohort(
                cohortId, from, to, body,
                evidence.observationIds, evidence.attemptIds, evidence.outcomeIds,
                TaskOutcomeService.RULE_VERSION));
    }

    private Map<String, Object> learnerSection(
            Long learnerId, OffsetDateTime from, OffsetDateTime to, Evidence evidence) {

        List<LearningSession> sessions = sessionRepository
                .findByLearnerIdAndCompletedAtIsNotNullOrderByCompletedAtDesc(learnerId)
                .stream()
                .filter(session -> !session.getCompletedAt().isBefore(from)
                        && !session.getCompletedAt().isAfter(to))
                .toList();

        List<Map<String, Object>> taskEntries = new ArrayList<>();
        for (LearningSession session : sessions) {
            for (LearningTaskOutcome outcome
                    : outcomeRepository.findByLearningSessionIdOrderByTaskKeyAsc(session.getId())) {
                taskEntries.add(taskEntry(session, outcome));
                evidence.collect(outcome);
            }
        }

        Map<String, Object> section = new LinkedHashMap<>();
        section.put("learner_id", learnerId);
        section.put("session_count", sessions.size());
        section.put("tasks", taskEntries);
        return section;
    }

    /** 값은 전부 집계 행에서 옮긴다. null 은 그대로 null 로 내보내 '수집 안 됨'을 보존한다. */
    private Map<String, Object> taskEntry(LearningSession session, LearningTaskOutcome outcome) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("learning_session_id", session.getPublicId());
        entry.put("curriculum_session_id", session.getCurriculumSessionId());
        entry.put("task_key", outcome.getTaskKey());
        entry.put("activity", outcome.getActivity());
        entry.put("attempt_count", outcome.getAttemptCount());
        entry.put("wrong_attempt_count", outcome.getWrongAttemptCount());
        entry.put("first_try_success", outcome.getFirstTrySuccess());
        entry.put("retry_success", outcome.getRetrySuccess());
        entry.put("success_after_help", outcome.getSuccessAfterHelp());
        entry.put("expression_start", outcome.getExpressionStart());
        entry.put("expression_end", outcome.getExpressionEnd());
        entry.put("expression_lowest", outcome.getExpressionLowest());
        entry.put("hint_start", outcome.getHintStart());
        entry.put("hint_end", outcome.getHintEnd());
        entry.put("hint_max", outcome.getHintMax());
        entry.put("completion_outcome", outcome.getCompletionOutcome());
        entry.put("system_failure", outcome.getSystemFailure());
        entry.put("bottleneck_candidate", outcome.getBottleneckCandidate());
        entry.put("bottleneck_evidence_count", outcome.getBottleneckEvidenceCount());
        entry.put("evidence_attempt_ids", Arrays.asList(outcome.getSourceAttemptIds()));
        entry.put("evidence_observation_ids", Arrays.asList(outcome.getSourceObservationIds()));
        return entry;
    }

    /** 스냅샷 전체가 참조한 근거 ID 모음. */
    private static final class Evidence {
        private final List<Long> observationIds = new ArrayList<>();
        private final List<Long> attemptIds = new ArrayList<>();
        private final List<Long> outcomeIds = new ArrayList<>();

        private void collect(LearningTaskOutcome outcome) {
            outcomeIds.add(outcome.getId());
            attemptIds.addAll(Arrays.asList(outcome.getSourceAttemptIds()));
            observationIds.addAll(Arrays.asList(outcome.getSourceObservationIds()));
        }
    }
}
