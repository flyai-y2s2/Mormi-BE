package com.mormi.backend.auth;

/**
 * 인증된 학습자. 컨트롤러에서 {@code @AuthenticationPrincipal} 로 주입받는다.
 * tokenId 는 로그아웃이 "이 요청에 쓰인 토큰"만 폐기하기 위해 들고 다닌다.
 */
public record LearnerPrincipal(Long learnerId, Long tokenId) {
}
