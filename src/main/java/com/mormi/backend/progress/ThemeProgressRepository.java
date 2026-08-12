package com.mormi.backend.progress;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ThemeProgressRepository extends JpaRepository<ThemeProgress, Long> {

    Optional<ThemeProgress> findByLearnerIdAndThemeId(Long learnerId, String themeId);

    List<ThemeProgress> findByLearnerId(Long learnerId);
}
