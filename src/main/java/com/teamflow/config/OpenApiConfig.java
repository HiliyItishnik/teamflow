package com.teamflow.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {
    @Bean
    OpenAPI teamFlowOpenApi() {
        return new OpenAPI().info(new Info()
                .title("TeamFlow API")
                .version("0.1.0")
                .description("""
                        Командные проекты: рабочие пространства, участники, задачи, исполнители, комментарии и история статусов.
                        Зарегистрируйтесь через /api/v1/auth/register или войдите через /api/v1/auth/login,
                        затем вставьте accessToken в Authorize. Токен действует 30 минут.
                        ADMIN управляет всеми проектами своей команды; MEMBER работает только в проектах, куда добавлен.
                        Списки используют page (от 0) и size (1–100). Ошибки возвращаются в формате application/problem+json.
                        """))
                .components(new Components().addSecuritySchemes("bearerAuth", new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"));
    }
}
