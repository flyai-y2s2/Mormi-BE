package com.mormi.backend.dialogue;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DialogueConversationRepository extends JpaRepository<DialogueConversation, Long> {

    Optional<DialogueConversation> findByConversationId(String conversationId);

    Optional<DialogueConversation> findByLearningSessionId(Long learningSessionId);

    List<DialogueConversation> findByLearnerIdOrderByCreatedAtAsc(Long learnerId);

    /** 재연습 회차가 쌓이므로 가장 최근 회차를 본다. */
    Optional<DialogueConversation> findFirstByCafeVisitIdAndScenarioIdOrderByRoundDesc(
            Long cafeVisitId, String scenarioId);
}
