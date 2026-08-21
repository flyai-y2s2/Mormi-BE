package com.mormi.backend.auth;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.mormi.backend.learner.LearnerDtos.LearnerResponse;
import com.mormi.backend.organization.Educator;
import com.mormi.backend.organization.Organization;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;

public final class AuthDtos {

    private AuthDtos() {
    }

    /**
     * 학생 회원가입. 아동 사용자가 직접 입력하므로 정책은 최소 기준만 둔다.
     * research_code 는 연구 식별자로만 쓰이고 인증에는 관여하지 않는다.
     */
    public record SignupRequest(
            @NotBlank @Size(max = 12) String displayName,
            @NotBlank @Size(max = 40) @Pattern(regexp = "[A-Za-z0-9._-]+") String researchCode,
            @NotBlank @Size(min = 4, max = 20) @Pattern(regexp = "[A-Za-z0-9]+") String loginId,
            @NotBlank @Size(min = 8, max = 72) String password) {
    }

    /** 교사 회원가입. 기관 이름은 자유 입력이고 이름이 정확히 같으면 같은 기관이 된다. */
    public record EducatorSignupRequest(
            @NotBlank @Size(max = 80) String organizationName,
            @NotBlank @Size(max = 40) String displayName,
            @NotBlank @Pattern(regexp = "교사|연구자") String position,
            @NotBlank @Size(min = 4, max = 20) @Pattern(regexp = "[A-Za-z0-9]+") String loginId,
            @NotBlank @Size(min = 8, max = 72) String password) {
    }

    /** 로그인. 형식 검증은 최소로 두고, 틀린 값은 전부 같은 401 로 응답한다. */
    public record LoginRequest(
            @NotBlank @Size(max = 20) String loginId,
            @NotBlank @Size(max = 72) String password) {
    }

    /** 교사 프로필. position 은 educators.role 컬럼이며 계정 role 과 겹쳐 이름을 바꿨다. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record EducatorResponse(
            Long id,
            String displayName,
            String position,
            Long organizationId,
            String organizationName,
            OffsetDateTime createdAt,
            String accessToken) {

        public static EducatorResponse of(
                Educator educator, Organization organization, String accessToken) {
            return new EducatorResponse(
                    educator.getId(),
                    educator.getDisplayName(),
                    educator.getRole(),
                    organization.getId(),
                    organization.getName(),
                    educator.getCreatedAt(),
                    accessToken);
        }
    }

    /**
     * 통합 로그인 응답. 프런트는 role 로 도착지를 가르고,
     * 역할에 맞는 쪽(learner 또는 educator)만 채워진다.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record LoginResponse(
            String role,
            String accessToken,
            LearnerResponse learner,
            EducatorResponse educator) {

        public static LoginResponse forLearner(LearnerResponse learner, String accessToken) {
            return new LoginResponse(Account.ROLE_LEARNER, accessToken, learner, null);
        }

        public static LoginResponse forEducator(EducatorResponse educator, String accessToken) {
            return new LoginResponse(Account.ROLE_EDUCATOR, accessToken, null, educator);
        }
    }
}
