package com.mormi.backend.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class AuthDtos {

    private AuthDtos() {
    }

    /**
     * 회원가입. 아동 사용자가 직접 입력하므로 정책은 최소 기준만 둔다.
     * research_code 는 연구 식별자로만 쓰이고 인증에는 관여하지 않는다.
     */
    public record SignupRequest(
            @NotBlank @Size(max = 12) String displayName,
            @NotBlank @Size(max = 40) @Pattern(regexp = "[A-Za-z0-9._-]+") String researchCode,
            @NotBlank @Size(min = 4, max = 20) @Pattern(regexp = "[A-Za-z0-9]+") String loginId,
            @NotBlank @Size(min = 8, max = 72) String password) {
    }

    /** 로그인. 형식 검증은 최소로 두고, 틀린 값은 전부 같은 401 로 응답한다. */
    public record LoginRequest(
            @NotBlank @Size(max = 20) String loginId,
            @NotBlank @Size(max = 72) String password) {
    }
}
