package com.mormi.backend.auth;

import com.mormi.backend.auth.AuthDtos.EducatorResponse;
import com.mormi.backend.auth.AuthDtos.EducatorSignupRequest;
import com.mormi.backend.auth.AuthDtos.LoginRequest;
import com.mormi.backend.auth.AuthDtos.LoginResponse;
import com.mormi.backend.auth.AuthDtos.SignupRequest;
import com.mormi.backend.learner.LearnerDtos.LearnerResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    public LearnerResponse signup(@Valid @RequestBody SignupRequest request) {
        return authService.signup(request);
    }

    @PostMapping("/educators/signup")
    @ResponseStatus(HttpStatus.CREATED)
    public EducatorResponse educatorSignup(@Valid @RequestBody EducatorSignupRequest request) {
        return authService.educatorSignup(request);
    }

    /** 학생·교사 공용. 응답의 role 로 프런트가 도착지를 가른다. */
    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    /** 이 요청에 쓰인 토큰만 폐기한다. 다른 기기는 계속 로그인 상태로 둔다. */
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@AuthenticationPrincipal AccountPrincipal principal) {
        authService.logout(principal.tokenId());
    }

    /** 공용 기기에 로그인한 채 두고 온 경우를 위해 모든 기기에서 로그아웃한다. */
    @PostMapping("/logout-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logoutAll(@AuthenticationPrincipal AccountPrincipal principal) {
        authService.logoutAll(principal.accountId());
    }
}
