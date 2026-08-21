package com.mormi.backend.dialogue;

import com.mormi.backend.auth.LearnerPrincipal;
import com.mormi.backend.dialogue.DialogueDtos.StartCafeDialogueRequest;
import com.mormi.backend.dialogue.DialogueDtos.StartTeachingRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** 브라우저가 호출하는 인증된 대화 프록시. */
@RestController
public class DialogueController {

    private final DialogueService dialogueService;

    public DialogueController(DialogueService dialogueService) {
        this.dialogueService = dialogueService;
    }

    @PostMapping("/v1/learning-sessions/{learningSessionId}/teaching")
    @ResponseStatus(HttpStatus.CREATED)
    public JsonNode startHomeTeaching(
            @AuthenticationPrincipal LearnerPrincipal principal,
            @PathVariable String learningSessionId,
            @Valid @RequestBody(required = false) StartTeachingRequest request) {
        return dialogueService.startHomeTeaching(principal.learnerId(), learningSessionId, request);
    }

    @PostMapping("/v1/cafe-visits/{visitId}/dialogues")
    @ResponseStatus(HttpStatus.CREATED)
    public Object startCafeDialogue(
            @AuthenticationPrincipal LearnerPrincipal principal,
            @PathVariable String visitId,
            @Valid @RequestBody StartCafeDialogueRequest request) {
        return dialogueService.startCafeDialogue(principal.learnerId(), visitId, request);
    }

    @GetMapping("/v1/dialogue/conversations/{conversationId}")
    public Object getConversation(
            @AuthenticationPrincipal LearnerPrincipal principal,
            @PathVariable String conversationId) {
        return dialogueService.getConversation(principal.learnerId(), conversationId);
    }

    @PostMapping("/v1/dialogue/conversations/{conversationId}/responses")
    public Object respond(
            @AuthenticationPrincipal LearnerPrincipal principal,
            @PathVariable String conversationId,
            @RequestBody JsonNode response) {
        return dialogueService.respond(principal.learnerId(), conversationId, response);
    }
}
