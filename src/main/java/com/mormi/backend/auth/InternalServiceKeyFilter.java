package com.mormi.backend.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 내부 서버끼리 쓰는 경로(/internal/**)를 공유 키로 막는다.
 *
 * <p>학습자 토큰과 달리 사람의 신원이 아니라 호출하는 서비스를 확인한다.
 * 파일럿 규모에서 AI와 BE가 같은 인프라 안에 있으므로 공유 키로 충분하다.
 * 외부에 노출되거나 서비스가 늘어나면 mTLS 나 서비스 토큰 발급으로 옮긴다.
 */
@Component
public class InternalServiceKeyFilter extends OncePerRequestFilter {

    public static final String SERVICE_AUTHORITY = "ROLE_INTERNAL_SERVICE";

    private static final String HEADER = "X-Mormi-Service-Key";
    private static final String INTERNAL_PREFIX = "/internal/";

    private final String serviceKey;

    public InternalServiceKeyFilter(@Value("${mormi.observation.ingest-key:}") String serviceKey) {
        this.serviceKey = serviceKey;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        if (request.getRequestURI().startsWith(INTERNAL_PREFIX)
                && SecurityContextHolder.getContext().getAuthentication() == null
                && matches(request.getHeader(HEADER))) {
            var authentication = new UsernamePasswordAuthenticationToken(
                    "internal-service", null, List.of(new SimpleGrantedAuthority(SERVICE_AUTHORITY)));
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authentication);
        }
        filterChain.doFilter(request, response);
    }

    private boolean matches(String provided) {
        // 키를 설정하지 않은 환경에서 빈 값끼리 같다고 판정하면 설정 누락이 곧 인증 해제가 된다.
        if (serviceKey.isBlank() || provided == null) {
            return false;
        }
        // 다른 글자가 나오면 바로 멈추는 비교는 걸린 시간으로 키를 흘린다. 상수 시간 비교를 쓴다.
        return MessageDigest.isEqual(
                serviceKey.getBytes(StandardCharsets.UTF_8), provided.getBytes(StandardCharsets.UTF_8));
    }
}
