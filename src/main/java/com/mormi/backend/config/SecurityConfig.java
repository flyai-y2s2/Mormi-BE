package com.mormi.backend.config;

import com.mormi.backend.auth.AuthTokenFilter;
import com.mormi.backend.auth.InternalServiceKeyFilter;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
public class SecurityConfig {

    private final AuthTokenFilter authTokenFilter;
    private final InternalServiceKeyFilter internalServiceKeyFilter;
    private final List<String> allowedOrigins;

    public SecurityConfig(
            AuthTokenFilter authTokenFilter,
            InternalServiceKeyFilter internalServiceKeyFilter,
            @Value("${mormi.cors.allowed-origins:http://localhost:3000}") List<String> allowedOrigins) {
        this.authTokenFilter = authTokenFilter;
        this.internalServiceKeyFilter = internalServiceKeyFilter;
        this.allowedOrigins = allowedOrigins;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                // 토큰이 없으면 403 이 아니라 401 을 돌려준다. 프런트가 재인증으로 분기할 수 있어야 한다.
                .exceptionHandling(handling -> handling.authenticationEntryPoint(
                        (request, response, exception) -> {
                            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
                            response.getWriter().write(
                                    "{\"code\":\"unauthorized\",\"message\":\"인증 토큰이 필요합니다.\"}");
                        }))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/health").permitAll()
                        .requestMatchers(HttpMethod.POST, "/v1/auth/signup").permitAll()
                        .requestMatchers(HttpMethod.POST, "/v1/auth/educators/signup").permitAll()
                        .requestMatchers(HttpMethod.POST, "/v1/auth/login").permitAll()
                        .requestMatchers(HttpMethod.GET, "/v1/local-report-admin/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/v1/local-report-admin/auth-attempt").permitAll()
                        // 내부 서버 전용. 인증 토큰이 아니라 서비스 키로만 통과한다.
                        .requestMatchers("/internal/**")
                        .hasAuthority(InternalServiceKeyFilter.SERVICE_AUTHORITY)
                        // 학습 경로는 아이 전용이다. 교사 토큰으로 부르면 403 이 된다.
                        .requestMatchers(
                                "/v1/learners/**", "/v1/learning-sessions/**", "/v1/cafe-visits/**",
                                "/v1/amusement-park-visits/**", "/v1/dialogue/**", "/v1/progress",
                                "/v1/themes", "/v1/reports/**")
                        .hasRole("LEARNER")
                        // 학급 관리는 교사 전용이다. 학생 토큰으로 부르면 403 이 된다.
                        .requestMatchers("/v1/cohorts/**").hasRole("EDUCATOR")
                        .requestMatchers("/v1/**").authenticated()
                        .anyRequest().permitAll())
                .addFilterBefore(authTokenFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(internalServiceKeyFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(allowedOrigins);
        configuration.setAllowedMethods(List.of("GET", "POST", "PATCH", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        configuration.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
