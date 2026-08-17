package com.mormi.backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * SecurityConfig 는 LearnerTokenFilter 를 주입받고, 그 필터는 다시 AuthService 를 거쳐
 * PasswordEncoder 를 필요로 한다. 인코더를 여기로 분리해 순환 참조를 끊는다.
 */
@Configuration
public class PasswordConfig {

    /** 비밀번호는 BCrypt 해시로만 보관한다. 같은 평문도 매번 다른 해시가 나오므로 matches 로 비교한다. */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
