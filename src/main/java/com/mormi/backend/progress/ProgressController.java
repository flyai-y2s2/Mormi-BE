package com.mormi.backend.progress;

import com.mormi.backend.auth.LearnerPrincipal;
import com.mormi.backend.progress.ProgressDtos.ProgressResponse;
import com.mormi.backend.progress.ProgressDtos.ThemeView;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1")
public class ProgressController {

    private final ProgressService progressService;

    public ProgressController(ProgressService progressService) {
        this.progressService = progressService;
    }

    @GetMapping("/progress")
    public ProgressResponse progress(@AuthenticationPrincipal LearnerPrincipal principal) {
        return progressService.snapshot(principal.learnerId());
    }

    @GetMapping("/themes")
    public List<ThemeView> themes(@AuthenticationPrincipal LearnerPrincipal principal) {
        return progressService.themes(principal.learnerId());
    }
}
