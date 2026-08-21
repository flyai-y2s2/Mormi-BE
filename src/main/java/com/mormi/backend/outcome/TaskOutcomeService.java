package com.mormi.backend.outcome;

import com.mormi.backend.common.ExpressionLevels;
import com.mormi.backend.observation.LearningObservation;
import com.mormi.backend.observation.LearningObservationRepository;
import com.mormi.backend.session.Attempt;
import com.mormi.backend.session.AttemptRepository;
import com.mormi.backend.session.LearningSession;
import com.mormi.backend.session.LearningSessionRepository;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 문제별 수행 결과를 attempts 와 learning_observations 에서 다시 계산한다.
 *
 * <p>관찰 이벤트는 늦게 도착할 수 있으므로 한 번 계산하고 끝내지 않는다.
 * 근거가 새로 들어올 때마다 같은 규칙으로 다시 계산해 덮어쓴다.
 * 그래서 이 서비스는 몇 번을 호출해도 같은 결과를 만든다.
 */
@Service
public class TaskOutcomeService {

    /** 규칙이 바뀌면 올린다. 예전 규칙으로 계산된 행을 찾아 다시 돌리기 위한 표식이다. */
    public static final String RULE_VERSION = "v1";

    /** 높은 인덱스일수록 힌트가 크다. H0 = 없음, H3 = 최대. */
    private static final List<String> HINT_LEVELS = List.of("H0", "H1", "H2", "H3");

    private final LearningSessionRepository sessionRepository;
    private final AttemptRepository attemptRepository;
    private final LearningObservationRepository observationRepository;
    private final LearningTaskOutcomeRepository outcomeRepository;
    private final StarNoteOutcomeLinker starNoteLinker;

    public TaskOutcomeService(
            LearningSessionRepository sessionRepository,
            AttemptRepository attemptRepository,
            LearningObservationRepository observationRepository,
            LearningTaskOutcomeRepository outcomeRepository,
            StarNoteOutcomeLinker starNoteLinker) {
        this.sessionRepository = sessionRepository;
        this.attemptRepository = attemptRepository;
        this.observationRepository = observationRepository;
        this.outcomeRepository = outcomeRepository;
        this.starNoteLinker = starNoteLinker;
    }

    @Transactional
    public void recompute(Long learningSessionId) {
        if (learningSessionId == null) {
            return;
        }
        // 같은 세션을 두 곳에서 동시에 다시 계산하면 같은 행을 나란히 넣으려다 충돌한다.
        // 세션 행을 잠가 세션 단위로 한 번에 하나씩만 계산하게 한다.
        LearningSession session = sessionRepository.findByIdForUpdate(learningSessionId).orElse(null);
        if (session == null) {
            return;
        }

        Map<String, List<Attempt>> attemptsByTask =
                attemptRepository.findByLearningSessionIdOrderByIdAsc(learningSessionId).stream()
                        .filter(attempt -> attempt.getItemId() != null)
                        .collect(Collectors.groupingBy(
                                Attempt::getItemId, LinkedHashMap::new, Collectors.toList()));

        Map<String, List<LearningObservation>> observationsByTask =
                observationRepository.findByLearningSessionIdOrderByIdAsc(learningSessionId).stream()
                        .filter(observation -> observation.getTaskId() != null)
                        .sorted(byObservedTime())
                        .collect(Collectors.groupingBy(
                                LearningObservation::getTaskId, LinkedHashMap::new, Collectors.toList()));

        Set<String> taskKeys = new LinkedHashSet<>(attemptsByTask.keySet());
        taskKeys.addAll(observationsByTask.keySet());

        for (String taskKey : taskKeys) {
            LearningTaskOutcome outcome = outcomeRepository
                    .findByLearningSessionIdAndTaskKey(learningSessionId, taskKey)
                    .orElseGet(() -> LearningTaskOutcome.of(
                            session.getLearnerId(), learningSessionId, taskKey));
            outcome.apply(
                    compute(
                            attemptsByTask.getOrDefault(taskKey, List.of()),
                            observationsByTask.getOrDefault(taskKey, List.of())),
                    RULE_VERSION);
            outcomeRepository.save(outcome);
            // 별노트가 outcome 행보다 먼저 도착해 원장에서 기다리고 있었으면 여기서 채워진다.
            starNoteLinker.relink(learningSessionId, taskKey);
        }
    }

    private TaskOutcomeFields compute(List<Attempt> attempts, List<LearningObservation> observations) {
        boolean hasAttempts = !attempts.isEmpty();
        boolean anyCorrect = attempts.stream().anyMatch(Attempt::isCorrect);

        // 도움 사용 여부는 관찰에서만 알 수 있다.
        // help_used 를 담은 관찰이 하나도 없으면 '안 썼다'가 아니라 '모른다'로 남긴다.
        Boolean helpUsed = anyTrue(observations.stream().map(LearningObservation::getHelpUsed).toList());
        Boolean systemFailure = observations.isEmpty()
                ? null
                : Boolean.TRUE.equals(anyTrue(observations.stream()
                        .map(LearningObservation::getSystemError).toList()))
                        || Boolean.TRUE.equals(anyTrue(observations.stream()
                                .map(LearningObservation::getFallbackOccurred).toList()));

        Map.Entry<String, Integer> bottleneck = mostFrequentBottleneck(observations);

        return new TaskOutcomeFields(
                hasAttempts ? attempts.get(0).getActivity() : null,
                hasAttempts ? attempts.size() : null,
                hasAttempts ? (int) attempts.stream().filter(attempt -> !attempt.isCorrect()).count() : null,
                hasAttempts ? attempts.get(0).isCorrect() : null,
                hasAttempts ? retrySuccess(attempts) : null,
                successAfterHelp(hasAttempts, anyCorrect, helpUsed),
                ExpressionLevels.canonicalForRead(
                        firstNonNull(observations.stream().map(LearningObservation::getExpressionBefore).toList())),
                ExpressionLevels.canonicalForRead(
                        lastNonNull(observations.stream().map(LearningObservation::getExpressionAfter).toList())),
                expressionLowest(observations),
                firstNonNull(observations.stream().map(LearningObservation::getHintBefore).toList()),
                lastNonNull(observations.stream().map(LearningObservation::getHintAfter).toList()),
                hintMax(observations),
                completionOutcome(observations, systemFailure, hasAttempts, anyCorrect, helpUsed),
                systemFailure,
                bottleneck == null ? null : bottleneck.getKey(),
                bottleneck == null ? null : bottleneck.getValue(),
                attempts.stream().map(Attempt::getId).toList(),
                observations.stream().map(LearningObservation::getId).toList());
    }

    /** 오답을 낸 뒤에 정답에 도달했는지. 시도 기록만으로 판단한다. */
    private Boolean retrySuccess(List<Attempt> attempts) {
        boolean sawWrong = false;
        for (Attempt attempt : attempts) {
            if (!attempt.isCorrect()) {
                sawWrong = true;
            } else if (sawWrong) {
                return true;
            }
        }
        return false;
    }

    private Boolean successAfterHelp(boolean hasAttempts, boolean anyCorrect, Boolean helpUsed) {
        if (!hasAttempts || helpUsed == null) {
            return null;
        }
        return anyCorrect && helpUsed;
    }

    /**
     * 완료 결과. 도움 사용 여부를 모르면 독립·지원을 가르지 않고 null 로 둔다.
     * 모르는 것을 '독립 완료'로 적으면 리포트가 아이를 실제보다 잘한 것으로 그린다.
     */
    private String completionOutcome(
            List<LearningObservation> observations,
            Boolean systemFailure,
            boolean hasAttempts,
            boolean anyCorrect,
            Boolean helpUsed) {

        if (Boolean.TRUE.equals(systemFailure)) {
            return "system_failure";
        }
        String reported = lastNonNull(
                observations.stream().map(LearningObservation::getCompletionOutcome).toList());
        if (reported != null) {
            return reported;
        }
        if (!hasAttempts) {
            return null;
        }
        if (!anyCorrect) {
            return "incomplete";
        }
        if (helpUsed == null) {
            return null;
        }
        return helpUsed ? "supported" : "independent";
    }

    /** 가장 자주 관찰된 병목 후보와 그 횟수. 횟수를 함께 둬야 단일 관찰과 반복 관찰을 가른다. */
    private Map.Entry<String, Integer> mostFrequentBottleneck(List<LearningObservation> observations) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (LearningObservation observation : observations) {
            String candidate = observation.getBottleneckCandidate();
            if (candidate != null) {
                counts.merge(candidate, 1, Integer::sum);
            }
        }
        return counts.entrySet().stream().max(Map.Entry.comparingByValue()).orElse(null);
    }

    /** 발화사다리에서 가장 많이 내려간 단계. 계약에 없는 값은 무시한다. */
    private String expressionLowest(List<LearningObservation> observations) {
        String lowest = null;
        for (LearningObservation observation : observations) {
            for (String value : new String[] {
                    observation.getExpressionBefore(), observation.getExpressionAfter()}) {
                // List.of 가 만든 불변 리스트는 indexOf(null) 에서 NPE 를 던진다.
                if (value == null) {
                    continue;
                }
                String canonical = ExpressionLevels.activeOrNull(value);
                if (canonical == null) {
                    continue;
                }
                int rank = ExpressionLevels.ACTIVE_SUPPORT_ORDER.indexOf(canonical);
                if (lowest == null || rank < ExpressionLevels.ACTIVE_SUPPORT_ORDER.indexOf(lowest)) {
                    lowest = canonical;
                }
            }
        }
        return lowest;
    }

    /** 힌트사다리에서 가장 높이 올라간 단계. */
    private String hintMax(List<LearningObservation> observations) {
        String max = null;
        for (LearningObservation observation : observations) {
            for (String value : new String[] {
                    observation.getHintBefore(), observation.getHintAfter()}) {
                if (value == null) {
                    continue;
                }
                int rank = HINT_LEVELS.indexOf(value);
                if (rank >= 0 && (max == null || rank > HINT_LEVELS.indexOf(max))) {
                    max = value;
                }
            }
        }
        return max;
    }

    /** 값이 하나도 없으면 null(모름), 하나라도 true 면 true, 전부 false 면 false. */
    private Boolean anyTrue(List<Boolean> values) {
        Boolean result = null;
        for (Boolean value : values) {
            if (Boolean.TRUE.equals(value)) {
                return true;
            }
            if (value != null) {
                result = false;
            }
        }
        return result;
    }

    private String firstNonNull(List<String> values) {
        return values.stream().filter(value -> value != null).findFirst().orElse(null);
    }

    private String lastNonNull(List<String> values) {
        String result = null;
        for (String value : values) {
            if (value != null) {
                result = value;
            }
        }
        return result;
    }

    /** 저장 순서가 아니라 관측 시각 순서로 본다. 늦게 도착한 이벤트가 순서를 뒤집을 수 있다. */
    private Comparator<LearningObservation> byObservedTime() {
        return Comparator
                .comparing(
                        LearningObservation::getObservedAt,
                        Comparator.nullsLast(Comparator.<OffsetDateTime>naturalOrder()))
                .thenComparing(LearningObservation::getId);
    }
}
