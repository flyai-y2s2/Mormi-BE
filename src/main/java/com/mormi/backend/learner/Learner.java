package com.mormi.backend.learner;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 테스트 대상 아동 한 명. display_name 은 화면 개인화 표시 전용이며
 * PostHog 이벤트와 AI 프롬프트에는 analytics_id 만 사용한다.
 */
@Entity
@Table(name = "learners")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Learner {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "display_name", nullable = false, length = 40)
    @Setter
    private String displayName;

    /** 아이가 온보딩에서 지어 준 캐릭터 이름. NULL 이면 아직 안 지은 것이다. */
    @Column(name = "character_name", length = 12)
    private String characterName;

    @Column(name = "research_code", nullable = false, length = 40, updatable = false)
    private String researchCode;

    @Column(name = "analytics_id", nullable = false, updatable = false)
    private UUID analyticsId;

    /** 로그인 계정. 아이디·비밀번호·역할은 accounts 가 관리한다. */
    @Column(name = "account_id", nullable = false, updatable = false)
    private Long accountId;

    @Column(name = "conversation_storage_consent", nullable = false)
    @Setter
    private boolean conversationStorageConsent;

    @Column(name = "retention_policy", nullable = false, length = 20)
    @Setter
    private String retentionPolicy;

    @Column(name = "onboarding_completed_at")
    @Setter
    private OffsetDateTime onboardingCompletedAt;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private OffsetDateTime createdAt;

    private Learner(String displayName, String researchCode) {
        this.displayName = displayName;
        this.researchCode = researchCode;
        this.analyticsId = UUID.randomUUID();
        // 파일럿 참여자는 온보딩 전에 보호자·기관 동의를 완료한다.
        this.conversationStorageConsent = true;
        this.retentionPolicy = "permanent";
        this.onboardingCompletedAt = OffsetDateTime.now();
    }

    /** 회원가입. 크리덴셜은 accounts 가, 토큰은 auth_tokens 가 관리한다. */
    public static Learner register(String displayName, String researchCode, Long accountId) {
        Learner learner = new Learner(displayName, researchCode);
        learner.accountId = accountId;
        return learner;
    }

    /**
     * 동의가 없으면 원문 보존 정책은 반드시 no_raw 여야 한다.
     * Mormi-AI 의 SessionCreate 검증과 같은 규칙이다.
     */
    public void applyConsent(boolean consent, String policy) {
        if (consent && (policy == null || "no_raw".equals(policy))) {
            throw new IllegalArgumentException("consented raw storage requires a retention_policy");
        }
        if (!consent && policy != null && !"no_raw".equals(policy)) {
            throw new IllegalArgumentException("retention_policy must be no_raw without storage consent");
        }
        this.conversationStorageConsent = consent;
        this.retentionPolicy = consent ? policy : "no_raw";
    }

    /**
     * 아이가 지은 캐릭터 이름을 저장한다. 다시 부르면 덮어쓴다.
     * 길이는 display_name 과 같은 규칙으로 앞뒤 공백을 턴 뒤 1~12자여야 한다.
     */
    public void nameCharacter(String name) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty() || trimmed.length() > 12) {
            throw new IllegalArgumentException("캐릭터 이름은 공백을 뺀 1~12자여야 합니다.");
        }
        this.characterName = trimmed;
    }
}
