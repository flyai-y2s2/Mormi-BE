package com.mormi.backend.session;

import com.mormi.backend.auth.AccountPrincipal;
import com.mormi.backend.session.SessionDtos.CompleteSessionRequest;
import com.mormi.backend.session.SessionDtos.CompleteSessionResponse;
import com.mormi.backend.session.SessionDtos.RecordAttemptRequest;
import com.mormi.backend.session.SessionDtos.RecordAttemptResponse;
import com.mormi.backend.session.SessionDtos.SessionView;
import com.mormi.backend.session.SessionDtos.StartSessionRequest;
import com.mormi.backend.session.SessionDtos.StartSessionResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/learning-sessions")
public class LearningSessionController {

    private final LearningSessionService learningSessionService;

    public LearningSessionController(LearningSessionService learningSessionService) {
        this.learningSessionService = learningSessionService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public StartSessionResponse start(
            @AuthenticationPrincipal AccountPrincipal principal,
            @Valid @RequestBody StartSessionRequest request) {
        return learningSessionService.start(principal.subjectId(), request);
    }

    @GetMapping("/{learningSessionId}")
    public SessionView get(
            @AuthenticationPrincipal AccountPrincipal principal,
            @PathVariable String learningSessionId) {
        return learningSessionService.view(principal.subjectId(), learningSessionId);
    }

    @PostMapping("/{learningSessionId}/attempts")
    @ResponseStatus(HttpStatus.CREATED)
    public RecordAttemptResponse recordAttempt(
            @AuthenticationPrincipal AccountPrincipal principal,
            @PathVariable String learningSessionId,
            @Valid @RequestBody RecordAttemptRequest request) {
        return learningSessionService.recordAttempt(principal.subjectId(), learningSessionId, request);
    }

    @PostMapping("/{learningSessionId}/complete")
    public CompleteSessionResponse complete(
            @AuthenticationPrincipal AccountPrincipal principal,
            @PathVariable String learningSessionId,
            @Valid @RequestBody CompleteSessionRequest request) {
        return learningSessionService.complete(principal.subjectId(), learningSessionId, request);
    }
}
