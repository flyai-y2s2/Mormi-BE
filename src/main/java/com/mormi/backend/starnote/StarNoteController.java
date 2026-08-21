package com.mormi.backend.starnote;

import com.mormi.backend.auth.AccountPrincipal;
import com.mormi.backend.starnote.StarNoteDtos.StarNoteList;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/learners")
public class StarNoteController {

    private final StarNoteQueryService starNoteQueryService;

    public StarNoteController(StarNoteQueryService starNoteQueryService) {
        this.starNoteQueryService = starNoteQueryService;
    }

    /** 별노트 목록 (본인만). 최신순 고정 정렬, 커서 페이지네이션. 비활성 노트는 제외한다. */
    @GetMapping("/{learnerId}/star-notes")
    public StarNoteList list(
            @PathVariable Long learnerId,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String cursor,
            @AuthenticationPrincipal AccountPrincipal principal) {
        return starNoteQueryService.list(learnerId, principal.subjectId(), limit, cursor);
    }
}
