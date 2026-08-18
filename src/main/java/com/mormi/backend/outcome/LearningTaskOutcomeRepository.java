package com.mormi.backend.outcome;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LearningTaskOutcomeRepository extends JpaRepository<LearningTaskOutcome, Long> {

    Optional<LearningTaskOutcome> findByLearningSessionIdAndTaskKey(Long learningSessionId, String taskKey);

    List<LearningTaskOutcome> findByLearningSessionIdOrderByTaskKeyAsc(Long learningSessionId);

    List<LearningTaskOutcome> findByLearnerIdOrderByIdAsc(Long learnerId);
}
