package com.mormi.backend.auth;

/**
 * 인증된 계정. 컨트롤러에서 {@code @AuthenticationPrincipal} 로 주입받는다.
 * subjectId 는 역할별 프로필의 기본 키다: 학생이면 learners.id, 교사면 educators.id.
 * tokenId 는 로그아웃이 "이 요청에 쓰인 토큰"만 폐기하기 위해 들고 다닌다.
 */
public record AccountPrincipal(Long accountId, String role, Long subjectId, Long tokenId) {

    public boolean isLearner() {
        return Account.ROLE_LEARNER.equals(role);
    }

    public boolean isEducator() {
        return Account.ROLE_EDUCATOR.equals(role);
    }
}
