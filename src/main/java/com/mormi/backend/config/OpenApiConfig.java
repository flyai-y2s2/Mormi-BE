package com.mormi.backend.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * API 명세의 단일 소스.
 *
 * <p>손으로 쓴 목록 문서는 코드와 반드시 어긋나므로, 명세는 컨트롤러에서 뽑는다.
 * 프런트는 이 문서(/v3/api-docs)에서 타입을 생성하고, 여기에 없는 경로는 없는 API다.
 */
@Configuration
public class OpenApiConfig {

    private static final String LEARNER_TOKEN = "learnerToken";

    @Bean
    public OpenAPI mormiOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Mormi 학습 API")
                        .version("v1")
                        .description("""
                                학습자·진도·보상·카페 생활수학을 담당하는 Spring 백엔드.

                                온보딩(POST /v1/learners)과 복구(POST /v1/learners/auth), /health 를 빼면
                                모든 /v1 경로가 학습자 토큰을 요구한다. 오른쪽 위 Authorize 에
                                access_token 을 넣으면 이 화면에서 그대로 호출해 볼 수 있다.

                                오류 응답은 항상 {"code": "...", "message": "..."} 형태이며,
                                code 목록은 docs/ERROR_CODES.md 를 따른다."""))
                .components(new Components().addSecuritySchemes(LEARNER_TOKEN,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .description("POST /v1/learners 응답의 access_token")))
                .addSecurityItem(new SecurityRequirement().addList(LEARNER_TOKEN));
    }
}
