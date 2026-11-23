package com.nexora.api

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * Contrato OpenAPI completo y versionado en el repositorio: `openapi/openapi-v1.yaml`.
 *
 * Es la copia que fijan los clientes que generan código a partir del contrato (por ejemplo,
 * watch-companion, igual que velora-android con velora-api): leen el archivo de un commit
 * concreto de este repo, sin levantar la API. Esta prueba lo genera desde springdoc
 * (`/v3/api-docs.yaml`) y falla en CI si el archivo del repo no coincide con el código.
 *
 * Si falla porque cambiaste el contrato a propósito, regenera el archivo y súbelo en el mismo PR:
 *
 * ```
 * ./gradlew test --tests com.nexora.api.OpenApiExportTests -PupdateOpenApi
 * ```
 *
 * y sube `info.version` en [com.nexora.api.config.OpenApiConfig] según las reglas del README
 * («Contrato OpenAPI»). [ContractStabilityTests] sigue vigilando por separado que no desaparezcan
 * endpoints ni campos.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class OpenApiExportTests {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Test
    fun `openapi-v1 yaml del repositorio coincide con el contrato que expone la API`() {
        val live = normalize(
            mockMvc.perform(get("/v3/api-docs.yaml"))
                .andExpect(status().isOk)
                .andReturn().response.getContentAsString(StandardCharsets.UTF_8)
        )

        if (System.getProperty("openapi.update") == "true") {
            Files.createDirectories(SPEC.parent)
            Files.writeString(SPEC, live)
            return
        }

        if (!Files.exists(SPEC)) fail("No existe $SPEC. Genéralo con: $UPDATE_COMMAND")
        assertEquals(
            Files.readString(SPEC),
            live,
            "$SPEC no coincide con el contrato que expone la API. Si el cambio es intencional, " +
                "regenéralo con `$UPDATE_COMMAND` y sube info.version (README, «Contrato OpenAPI»).",
        )
    }

    companion object {
        val SPEC: Path = Path.of("openapi", "openapi-v1.yaml")
        const val UPDATE_COMMAND = "./gradlew test --tests com.nexora.api.OpenApiExportTests -PupdateOpenApi"

        /**
         * Quita `servers`: springdoc lo calcula a partir de la petición (en la prueba, `http://localhost`), así que
         * no describe la API sino dónde se pidió el documento. Cada cliente configura su URL base.
         */
        fun normalize(yaml: String): String {
            val out = StringBuilder()
            var skipping = false
            for (line in yaml.lines()) {
                val topLevel = line.isNotEmpty() && !line[0].isWhitespace() && line[0] != '-'
                if (topLevel) skipping = line.startsWith("servers:")
                if (!skipping) out.append(line).append('\n')
            }
            return out.toString().trimEnd() + "\n"
        }
    }
}
