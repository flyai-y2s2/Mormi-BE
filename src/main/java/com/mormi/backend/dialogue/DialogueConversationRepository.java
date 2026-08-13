package com.mormi.backend.dialogue;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DialogueConversationRepository extends JpaRepository<DialogueConversation, Long> {

    Optional<DialogueConversation> findByConversationId(String conversationId);

    Optional<DialogueConversation> findByLearningSessionId(Long learningSessionId);

    Optional<DialogueConversation> findByCafeVisitIdAndScenarioId(Long cafeVisitId, String scenarioId);
}
