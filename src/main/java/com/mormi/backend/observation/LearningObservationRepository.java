package com.mormi.backend.observation;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LearningObservationRepository extends JpaRepository<LearningObservation, Long> {

    Optional<LearningObservation> findByAiObservationId(String aiObservationId);

    List<LearningObservation> findByLearnerIdOrderByIdAsc(Long learnerId);

    List<LearningObservation> findByLearningSessionIdOrderByIdAsc(Long learningSessionId);

    long countByConversationId(String conversationId);
}
