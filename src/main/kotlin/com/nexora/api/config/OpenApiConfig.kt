package com.nexora.api.config

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class OpenApiConfig {

    @Bean
    fun nexoraOpenApi(): OpenAPI =
        OpenAPI().info(
            Info()
                .title("Nexora API")
                .description("API central de la plataforma de finanzas personales Nexora.")
                .version("v0.0.1")
        )
}
