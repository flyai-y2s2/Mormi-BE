package com.mormi.backend.learner;

import com.mormi.backend.auth.AccountPrincipal;
import com.mormi.backend.learner.LearnerDtos.ConversationConsentRequest;
import com.mormi.backend.learner.LearnerDtos.LearnerResponse;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/learners")
public class LearnerController {

    private final LearnerService learnerService;

    public LearnerController(LearnerService learnerService) {
        this.learnerService = learnerService;
    }

    @GetMapping("/{learnerId}")
    public LearnerResponse get(
            @PathVariable Long learnerId, @AuthenticationPrincipal AccountPrincipal principal) {
        return learnerService.get(learnerId, principal.subjectId());
    }

    @PatchMapping("/me/conversation-consent")
    public LearnerResponse updateConversationConsent(
            @AuthenticationPrincipal AccountPrincipal principal,
            @Valid @RequestBody ConversationConsentRequest request) {
        return learnerService.updateConversationConsent(principal.subjectId(), request);
    }
}
