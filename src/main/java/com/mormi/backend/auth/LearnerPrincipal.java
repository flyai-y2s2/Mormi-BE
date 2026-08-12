package com.mormi.backend.auth;

/**
 * 인증된 학습자. 컨트롤러에서 {@code @AuthenticationPrincipal} 로 주입받는다.
 */
public record LearnerPrincipal(Long learnerId, String researchCode) {
}
