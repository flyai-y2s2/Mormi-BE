package com.mormi.backend.dialogue;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DialogueConversationRepository extends JpaRepository<DialogueConversation, Long> {

    Optional<DialogueConversation> findByConversationId(String conversationId);

    /** 홈 가르치기도 재시작 회차가 쌓이므로 가장 최근 회차를 본다. */
    Optional<DialogueConversation> findFirstByLearningSessionIdOrderByRoundDesc(
            Long learningSessionId);

    List<DialogueConversation> findByLearnerIdOrderByCreatedAtAsc(Long learnerId);

    /** 재연습 회차가 쌓이므로 가장 최근 회차를 본다. */
    Optional<DialogueConversation> findFirstByCafeVisitIdAndScenarioIdOrderByRoundDesc(
            Long cafeVisitId, String scenarioId);

    Optional<DialogueConversation> findFirstByParkVisitIdAndScenarioIdOrderByRoundDesc(
            Long parkVisitId, String scenarioId);

    /** 네트워크 재시도로 중복 도착한 시작 요청을 이미 만든 회차로 되돌린다. */
    Optional<DialogueConversation> findByLearnerIdAndRequestId(Long learnerId, String requestId);
}
