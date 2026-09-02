package com.nexora.api.config

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.security.SecurityRequirement
import io.swagger.v3.oas.models.security.SecurityScheme
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

private const val BEARER_AUTH_SCHEME = "bearerAuth"

/**
 * Todos los endpoints requieren el access token JWT emitido por
 * /api/v1/auth/login (Authorization: Bearer <token>), salvo los pocos
 * marcados públicos en [SecurityConfig] (registro, login/refresh/logout).
 * Ese esquema se declara una sola vez aquí, como requisito global — cada
 * controlador de un endpoint público lo desactiva puntualmente con
 * `@SecurityRequirements` (lista vacía), en vez de repetir
 * `@SecurityRequirement(name = "bearerAuth")` en cada uno de los ~35
 * endpoints protegidos.
 */
@Configuration
class OpenApiConfig {

    @Bean
    fun nexoraOpenApi(): OpenAPI =
        OpenAPI()
            .info(
                Info()
                    .title("Nexora API")
                    .description("API central de la plataforma de finanzas personales Nexora.")
                    .version("v0.0.1")
            )
            .components(
                Components().addSecuritySchemes(
                    BEARER_AUTH_SCHEME,
                    SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")
                        .description("Access token obtenido en POST /api/v1/auth/login (o /refresh)."),
                )
            )
            .addSecurityItem(SecurityRequirement().addList(BEARER_AUTH_SCHEME))
}
