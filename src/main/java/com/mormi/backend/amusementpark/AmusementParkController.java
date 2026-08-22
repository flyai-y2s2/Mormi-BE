package com.mormi.backend.amusementpark;

import com.mormi.backend.amusementpark.AmusementParkDtos.ParkVisitView;
import com.mormi.backend.amusementpark.AmusementParkDtos.StageAttemptRequest;
import com.mormi.backend.amusementpark.AmusementParkDtos.StageResultResponse;
import com.mormi.backend.auth.AccountPrincipal;
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
@RequestMapping("/v1/amusement-park-visits")
public class AmusementParkController {

    private final AmusementParkService amusementParkService;

    public AmusementParkController(AmusementParkService amusementParkService) {
        this.amusementParkService = amusementParkService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ParkVisitView start(@AuthenticationPrincipal AccountPrincipal principal) {
        return amusementParkService.start(principal.subjectId());
    }

    @GetMapping("/{visitId}")
    public ParkVisitView get(
            @AuthenticationPrincipal AccountPrincipal principal, @PathVariable String visitId) {
        return amusementParkService.view(principal.subjectId(), visitId);
    }

    /** 세 스테이지가 같은 모양(키→값)의 답을 내므로 단계별 경로를 따로 두지 않는다. */
    @PostMapping("/{visitId}/stages/{stageId}")
    public StageResultResponse submit(
            @AuthenticationPrincipal AccountPrincipal principal,
            @PathVariable String visitId,
            @PathVariable String stageId,
            @Valid @RequestBody StageAttemptRequest request) {
        return amusementParkService.submit(principal.subjectId(), visitId, stageId, request);
    }

    @PostMapping("/{visitId}/complete")
    public ParkVisitView complete(
            @AuthenticationPrincipal AccountPrincipal principal, @PathVariable String visitId) {
        return amusementParkService.complete(principal.subjectId(), visitId);
    }
}
