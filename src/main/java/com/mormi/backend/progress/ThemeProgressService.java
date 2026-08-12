package com.mormi.backend.progress;

import com.mormi.backend.curriculum.CurriculumCatalog;
import java.time.OffsetDateTime;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ThemeProgressService {

    private final ThemeProgressRepository themeProgressRepository;

    public ThemeProgressService(ThemeProgressRepository themeProgressRepository) {
        this.themeProgressRepository = themeProgressRepository;
    }

    /**
     * 완료 세션 목록으로 카페 해금 여부를 서버가 판정하고 원장에 반영한다.
     * 한 번 해금되면 다시 잠기지 않는다.
     *
     * @return 현재 해금 여부
     */
    @Transactional
    public boolean syncCafeUnlock(Long learnerId, Set<String> completedSessionIds) {
        boolean shouldUnlock = CurriculumCatalog.isCafeUnlocked(completedSessionIds);
        ThemeProgress progress = themeProgressRepository
                .findByLearnerIdAndThemeId(learnerId, CurriculumCatalog.THEME_CAFE)
                .orElseGet(() -> themeProgressRepository.save(
                        ThemeProgress.of(learnerId, CurriculumCatalog.THEME_CAFE)));

        if (shouldUnlock && !progress.isUnlocked()) {
            progress.setUnlockedAt(OffsetDateTime.now());
        }
        return progress.isUnlocked();
    }

    @Transactional
    public void markCafeCompleted(Long learnerId) {
        themeProgressRepository.findByLearnerIdAndThemeId(learnerId, CurriculumCatalog.THEME_CAFE)
                .ifPresent(progress -> {
                    if (progress.getCompletedAt() == null) {
                        progress.setCompletedAt(OffsetDateTime.now());
                    }
                });
    }

    @Transactional(readOnly = true)
    public boolean isCafeUnlocked(Long learnerId) {
        return themeProgressRepository
                .findByLearnerIdAndThemeId(learnerId, CurriculumCatalog.THEME_CAFE)
                .map(ThemeProgress::isUnlocked)
                .orElse(false);
    }
}
