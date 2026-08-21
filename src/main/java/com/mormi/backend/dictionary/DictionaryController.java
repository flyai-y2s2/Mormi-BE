package com.mormi.backend.dictionary;

import com.mormi.backend.auth.AccountPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * 궁금해사전 조회. 사전 카드는 독립 자원이 아니라 "그 세션의 카드", "그 대화의 카드"라
 * 기존 자원 경로 밑에 둔다. 응답 본문은 AI 원본 그대로다.
 */
@RestController
public class DictionaryController {

    private final DictionaryService dictionaryService;

    public DictionaryController(DictionaryService dictionaryService) {
        this.dictionaryService = dictionaryService;
    }

    @GetMapping("/v1/learning-sessions/{learningSessionId}/dictionary-card")
    public JsonNode getForLearningSession(
            @AuthenticationPrincipal AccountPrincipal principal,
            @PathVariable String learningSessionId,
            // AI 쿼리 이름을 그대로 쓴다. 중계자가 이름을 바꾸면 번역표만 하나 는다.
            @RequestParam(name = "expected_content_version", required = false)
            Integer expectedContentVersion) {
        return dictionaryService.getCardForLearningSession(
                principal.subjectId(), learningSessionId, expectedContentVersion);
    }

    @GetMapping("/v1/dialogue/conversations/{conversationId}/dictionary-card")
    public JsonNode getForConversation(
            @AuthenticationPrincipal AccountPrincipal principal,
            @PathVariable String conversationId) {
        return dictionaryService.getCardForConversation(
                principal.subjectId(), conversationId);
    }
}
