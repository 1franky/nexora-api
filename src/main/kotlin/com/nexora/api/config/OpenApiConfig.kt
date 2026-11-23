package com.nexora.api.config

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.security.SecurityRequirement
import io.swagger.v3.oas.models.security.SecurityScheme
import org.springdoc.core.customizers.OperationCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

private const val BEARER_AUTH_SCHEME = "bearerAuth"

/**
 * Versión del contrato publicado en `openapi/openapi-v1.yaml` (semver, reglas en el README, «Contrato OpenAPI»):
 * menor si el cambio es aditivo, parche si solo cambia documentación; un cambio incompatible va en `/api/v2` y en
 * un archivo `openapi-v2.yaml` nuevo, no aquí.
 */
const val CONTRACT_VERSION = "1.0.0"

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
                    .version(CONTRACT_VERSION)
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

    /**
     * operationId estable: `<controlador><Método>` (`TransactionController.create` → `transactionCreate`). Sin esto,
     * springdoc usa el nombre del método y numera los repetidos (`create_1`, `create_2`…) según el orden en que
     * encuentra los controladores: agregar uno nuevo podía renumerar los existentes y cambiar los nombres de los
     * clientes generados desde `openapi/openapi-v1.yaml`.
     */
    @Bean
    fun stableOperationIds(): OperationCustomizer = OperationCustomizer { operation, handlerMethod ->
        val resource = handlerMethod.beanType.simpleName.removeSuffix("Controller").replaceFirstChar(Char::lowercase)
        operation.operationId(resource + handlerMethod.method.name.replaceFirstChar(Char::uppercase))
    }
}
