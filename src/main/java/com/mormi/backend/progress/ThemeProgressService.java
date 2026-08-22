package com.mormi.backend.progress;

import com.mormi.backend.curriculum.AmusementParkCatalog;
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
        ThemeProgress progress = requireTheme(learnerId, CurriculumCatalog.THEME_CAFE);

        if (shouldUnlock && !progress.isUnlocked()) {
            progress.setUnlockedAt(OffsetDateTime.now());
        }
        return progress.isUnlocked();
    }

    @Transactional
    public void markCafeCompleted(Long learnerId) {
        markCompleted(learnerId, CurriculumCatalog.THEME_CAFE);
    }

    @Transactional(readOnly = true)
    public boolean isCafeUnlocked(Long learnerId) {
        return isUnlocked(learnerId, CurriculumCatalog.THEME_CAFE);
    }

    /**
     * 놀이동산은 카페를 마쳐야 열린다. 집 → 카페 → 놀이동산 순서를 서버가 보장한다.
     * 카페 해금이 세션 목록으로 판정되는 것과 달리, 여기서는 카페 완료 시각만 보면 된다.
     */
    @Transactional
    public boolean syncAmusementParkUnlock(Long learnerId) {
        boolean cafeCompleted = themeProgressRepository
                .findByLearnerIdAndThemeId(learnerId, CurriculumCatalog.THEME_CAFE)
                .map(cafe -> cafe.getCompletedAt() != null)
                .orElse(false);
        ThemeProgress progress = requireTheme(learnerId, AmusementParkCatalog.THEME_ID);

        if (cafeCompleted && !progress.isUnlocked()) {
            progress.setUnlockedAt(OffsetDateTime.now());
        }
        return progress.isUnlocked();
    }

    @Transactional
    public void markAmusementParkCompleted(Long learnerId) {
        markCompleted(learnerId, AmusementParkCatalog.THEME_ID);
    }

    @Transactional(readOnly = true)
    public boolean isAmusementParkUnlocked(Long learnerId) {
        return isUnlocked(learnerId, AmusementParkCatalog.THEME_ID);
    }

    /** 원장에 행이 없으면 잠긴 상태로 만들어 둔다. 해금 판정은 호출부가 이어서 한다. */
    private ThemeProgress requireTheme(Long learnerId, String themeId) {
        return themeProgressRepository
                .findByLearnerIdAndThemeId(learnerId, themeId)
                .orElseGet(() -> themeProgressRepository.save(ThemeProgress.of(learnerId, themeId)));
    }

    private void markCompleted(Long learnerId, String themeId) {
        themeProgressRepository.findByLearnerIdAndThemeId(learnerId, themeId)
                .ifPresent(progress -> {
                    if (progress.getCompletedAt() == null) {
                        progress.setCompletedAt(OffsetDateTime.now());
                    }
                });
    }

    private boolean isUnlocked(Long learnerId, String themeId) {
        return themeProgressRepository
                .findByLearnerIdAndThemeId(learnerId, themeId)
                .map(ThemeProgress::isUnlocked)
                .orElse(false);
    }
}
