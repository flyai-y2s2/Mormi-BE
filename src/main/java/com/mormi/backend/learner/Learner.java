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

    @Column(name = "research_code", nullable = false, length = 40, updatable = false)
    private String researchCode;

    @Column(name = "analytics_id", nullable = false, updatable = false)
    private UUID analyticsId;

    /** 아이디·비밀번호 도입 전에 만들어진 학습자만 값을 가진다. 신규 가입은 채우지 않는다. */
    @Column(name = "login_id", length = 30, updatable = false)
    private String loginId;

    /** BCrypt 해시는 항상 60자다. 평문은 어디에도 남기지 않는다. */
    @Column(name = "password_hash", length = 60)
    @Setter
    private String passwordHash;

    /** @deprecated 토큰은 learner_tokens 가 관리한다. FE 전환 후 컬럼과 함께 제거한다. */
    @Deprecated
    @Column(name = "token_hash", length = 64)
    @Setter
    private String tokenHash;

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

    /** 아이디·비밀번호 회원가입. 토큰은 learner_tokens 가 따로 발급한다. */
    public static Learner register(
            String displayName, String researchCode, String loginId, String passwordHash) {
        Learner learner = new Learner(displayName, researchCode);
        learner.loginId = loginId;
        learner.passwordHash = passwordHash;
        return learner;
    }

    /** @deprecated 연구 코드 단독 온보딩. FE 전환 후 제거한다. */
    @Deprecated
    public static Learner create(String displayName, String researchCode, String tokenHash) {
        Learner learner = new Learner(displayName, researchCode);
        learner.tokenHash = tokenHash;
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
}
