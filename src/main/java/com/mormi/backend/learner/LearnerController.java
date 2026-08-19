package com.mormi.backend.learner;

import com.mormi.backend.auth.LearnerPrincipal;
import com.mormi.backend.learner.LearnerDtos.CreateLearnerRequest;
import com.mormi.backend.learner.LearnerDtos.ConversationConsentRequest;
import com.mormi.backend.learner.LearnerDtos.LearnerAuthRequest;
import com.mormi.backend.learner.LearnerDtos.LearnerResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/learners")
public class LearnerController {

    private final LearnerService learnerService;

    public LearnerController(LearnerService learnerService) {
        this.learnerService = learnerService;
    }

    /** @deprecated 연구 코드는 크리덴셜이 아니다. POST /v1/auth/signup 을 쓴다. */
    @Deprecated
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public LearnerResponse create(@Valid @RequestBody CreateLearnerRequest request) {
        return learnerService.createOrRestore(request);
    }

    /** @deprecated 연구 코드는 크리덴셜이 아니다. POST /v1/auth/login 을 쓴다. */
    @Deprecated
    @PostMapping("/auth")
    public LearnerResponse authenticate(@Valid @RequestBody LearnerAuthRequest request) {
        return learnerService.restore(request.researchCode());
    }

    @GetMapping("/{learnerId}")
    public LearnerResponse get(
            @PathVariable Long learnerId, @AuthenticationPrincipal LearnerPrincipal principal) {
        return learnerService.get(learnerId, principal.learnerId());
    }

    @PatchMapping("/me/conversation-consent")
    public LearnerResponse updateConversationConsent(
            @AuthenticationPrincipal LearnerPrincipal principal,
            @Valid @RequestBody ConversationConsentRequest request) {
        return learnerService.updateConversationConsent(principal.learnerId(), request);
    }
}
