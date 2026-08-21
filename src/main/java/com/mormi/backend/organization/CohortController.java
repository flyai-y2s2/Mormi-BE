package com.mormi.backend.organization;

import com.mormi.backend.auth.AccountPrincipal;
import com.mormi.backend.organization.CohortDtos.CohortLearnerResponse;
import com.mormi.backend.organization.CohortDtos.CohortReportResponse;
import com.mormi.backend.organization.CohortDtos.CohortResponse;
import com.mormi.backend.organization.CohortDtos.CreateCohortRequest;
import com.mormi.backend.organization.CohortDtos.IssueResearchCodesRequest;
import com.mormi.backend.organization.CohortDtos.IssuedResearchCodeResponse;
import jakarta.validation.Valid;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 교사 전용. SecurityConfig 가 /v1/cohorts/** 를 ROLE_EDUCATOR 로 막는다. */
@RestController
@RequestMapping("/v1/cohorts")
public class CohortController {

    private final CohortService cohortService;

    public CohortController(CohortService cohortService) {
        this.cohortService = cohortService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CohortResponse create(
            @AuthenticationPrincipal AccountPrincipal principal,
            @Valid @RequestBody CreateCohortRequest request) {
        return cohortService.create(principal.subjectId(), request.name());
    }

    @GetMapping
    public List<CohortResponse> list(@AuthenticationPrincipal AccountPrincipal principal) {
        return cohortService.list(principal.subjectId());
    }

    @PostMapping("/{cohortId}/research-codes")
    @ResponseStatus(HttpStatus.CREATED)
    public List<IssuedResearchCodeResponse> issueResearchCodes(
            @AuthenticationPrincipal AccountPrincipal principal,
            @PathVariable Long cohortId,
            @Valid @RequestBody IssueResearchCodesRequest request) {
        return cohortService.issueResearchCodes(principal.subjectId(), cohortId, request.codes());
    }

    @GetMapping("/{cohortId}/learners")
    public List<CohortLearnerResponse> learners(
            @AuthenticationPrincipal AccountPrincipal principal, @PathVariable Long cohortId) {
        return cohortService.learners(principal.subjectId(), cohortId);
    }

    @GetMapping("/{cohortId}/reports")
    public CohortReportResponse report(
            @AuthenticationPrincipal AccountPrincipal principal,
            @PathVariable Long cohortId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to) {
        return cohortService.report(principal.subjectId(), cohortId, from, to);
    }
}
