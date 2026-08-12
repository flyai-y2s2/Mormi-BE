package com.mormi.backend.progress;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 장소 해금 상태. 해금 판정은 서버가 하고 프런트는 표시만 한다. */
@Entity
@Table(name = "theme_progress")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ThemeProgress {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "learner_id", nullable = false, updatable = false)
    private Long learnerId;

    @Column(name = "theme_id", nullable = false, length = 40, updatable = false)
    private String themeId;

    @Column(name = "unlocked_at")
    @Setter
    private OffsetDateTime unlockedAt;

    @Column(name = "completed_at")
    @Setter
    private OffsetDateTime completedAt;

    private ThemeProgress(Long learnerId, String themeId) {
        this.learnerId = learnerId;
        this.themeId = themeId;
    }

    public static ThemeProgress of(Long learnerId, String themeId) {
        return new ThemeProgress(learnerId, themeId);
    }

    public boolean isUnlocked() {
        return unlockedAt != null;
    }
}
