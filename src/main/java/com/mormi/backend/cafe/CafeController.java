package com.mormi.backend.cafe;

import com.mormi.backend.auth.LearnerPrincipal;
import com.mormi.backend.cafe.CafeDtos.CafeVisitView;
import com.mormi.backend.cafe.CafeDtos.ChangeRequest;
import com.mormi.backend.cafe.CafeDtos.MenuRequest;
import com.mormi.backend.cafe.CafeDtos.PaymentRequest;
import com.mormi.backend.cafe.CafeDtos.QueueRequest;
import com.mormi.backend.cafe.CafeDtos.StageResultResponse;
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
@RequestMapping("/v1/cafe-visits")
public class CafeController {

    private final CafeService cafeService;

    public CafeController(CafeService cafeService) {
        this.cafeService = cafeService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CafeVisitView start(@AuthenticationPrincipal LearnerPrincipal principal) {
        return cafeService.start(principal.learnerId());
    }

    @GetMapping("/{visitId}")
    public CafeVisitView get(
            @AuthenticationPrincipal LearnerPrincipal principal, @PathVariable String visitId) {
        return cafeService.view(principal.learnerId(), visitId);
    }

    @PostMapping("/{visitId}/queue")
    public StageResultResponse queue(
            @AuthenticationPrincipal LearnerPrincipal principal,
            @PathVariable String visitId,
            @Valid @RequestBody QueueRequest request) {
        return cafeService.submitQueue(principal.learnerId(), visitId, request);
    }

    @PostMapping("/{visitId}/menu")
    public StageResultResponse menu(
            @AuthenticationPrincipal LearnerPrincipal principal,
            @PathVariable String visitId,
            @Valid @RequestBody MenuRequest request) {
        return cafeService.submitMenu(principal.learnerId(), visitId, request);
    }

    @PostMapping("/{visitId}/payments")
    public StageResultResponse payments(
            @AuthenticationPrincipal LearnerPrincipal principal,
            @PathVariable String visitId,
            @Valid @RequestBody PaymentRequest request) {
        return cafeService.submitPayment(principal.learnerId(), visitId, request);
    }

    @PostMapping("/{visitId}/change")
    public StageResultResponse change(
            @AuthenticationPrincipal LearnerPrincipal principal,
            @PathVariable String visitId,
            @Valid @RequestBody ChangeRequest request) {
        return cafeService.submitChange(principal.learnerId(), visitId, request);
    }

    @PostMapping("/{visitId}/complete")
    public CafeVisitView complete(
            @AuthenticationPrincipal LearnerPrincipal principal, @PathVariable String visitId) {
        return cafeService.complete(principal.learnerId(), visitId);
    }
}
