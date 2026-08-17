package com.mormi.backend.dictionary;

import com.mormi.backend.common.ApiException;
import com.mormi.backend.dialogue.DialogueConversation;
import com.mormi.backend.dialogue.DialogueConversationRepository;
import com.mormi.backend.session.LearningSession;
import com.mormi.backend.session.LearningSessionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * 궁금해사전 조회의 접근 판정.
 *
 * <p>BE는 사전 문구를 만들거나 고치지 않는다. 여기서 하는 일은 AI를 부르기 전의 확인뿐이다:
 * 요청한 학습자가 그 세션·대화의 주인인지, 그리고 AI 카드를 찾는 열쇠(커리큘럼 id)가 무엇인지.
 * 카드 본문은 {@link DictionaryClient} 가 받아온 그대로 무손실로 돌려준다.
 */
@Service
public class DictionaryService {

    private final DictionaryClient dictionaryClient;
    private final LearningSessionRepository sessionRepository;
    private final DialogueConversationRepository dialogueRepository;

    public DictionaryService(
            DictionaryClient dictionaryClient,
            LearningSessionRepository sessionRepository,
            DialogueConversationRepository dialogueRepository) {
        this.dictionaryClient = dictionaryClient;
        this.sessionRepository = sessionRepository;
        this.dialogueRepository = dialogueRepository;
    }

    /**
     * 학습 세션의 현재 승인된 사전 카드를 읽는다.
     *
     * @param expectedContentVersion FE가 이미 본 콘텐츠 버전. 버전 판단은 콘텐츠 주인인
     *                               AI가 하므로 BE는 비교하지 않고 그대로 전달한다.
     */
    @Transactional(readOnly = true)
    public JsonNode getCardForLearningSession(
            Long learnerId, String publicSessionId, Integer expectedContentVersion) {
        LearningSession session = requireSessionOwned(learnerId, publicSessionId);
        return dictionaryClient.fetchCurrentCard(
                session.getCurriculumSessionId(), expectedContentVersion);
    }

    /**
     * 대화에 고정된 사전 카드 스냅샷을 읽는다. 집 가르치기·카페 대화 모두 이 경로 하나로
     * 커버된다. 카탈로그가 새로 배포되어도 진행 중인 대화는 시작 시점의 카드를 그대로 본다.
     */
    @Transactional(readOnly = true)
    public JsonNode getCardForConversation(Long learnerId, String conversationId) {
        requireDialogueOwned(learnerId, conversationId);
        return dictionaryClient.fetchConversationCard(conversationId);
    }

    // 소유권 검사는 DialogueService 와 같은 모양이지만 빌려 쓰지 않고 여기에 둔다.
    // 대화 쪽 사정으로 바뀔 코드에 사전이 매달리는 것보다 네 줄 중복이 싸다.

    private LearningSession requireSessionOwned(Long learnerId, String publicId) {
        LearningSession session = sessionRepository.findByPublicId(publicId)
                .orElseThrow(() -> ApiException.notFound("학습 세션을 찾을 수 없습니다."));
        if (!session.getLearnerId().equals(learnerId)) {
            throw ApiException.forbidden("다른 학습자의 세션입니다.");
        }
        return session;
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
