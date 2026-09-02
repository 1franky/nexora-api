package com.nexora.api

import com.jayway.jsonpath.JsonPath
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * Contract tests (plan.md, sección 14 "API REST"; issue #7, último punto
 * de B7 "Calidad"): nexora-web y nexora-android ya consumen esta API "de
 * verdad" — el contrato OpenAPI generado en `/v3/api-docs` no debería
 * cambiar de forma silenciosa e involuntaria.
 *
 * No hay un consumer-driven contract tool (Pact, Spring Cloud Contract)
 * de por medio: en vez de eso, esta prueba compara una versión normalizada
 * del contrato actual (endpoints y nombres de campo de cada schema, sin
 * texto de summary/description — eso cambia seguido y nunca es un cambio
 * de contrato) contra una "foto" congelada en
 * `src/test/resources/contract/openapi-contract-snapshot.json`.
 *
 * Si esta prueba falla: si el cambio de contrato fue intencional (nuevo
 * endpoint, campo agregado/renombrado, etc.), regenera el snapshot con
 * `./gradlew bootRun`, `curl localhost:3005/v3/api-docs`, y el mismo
 * script de normalización de este archivo — y avisa a nexora-web/
 * nexora-android si el cambio no es aditivo. Si no fue intencional, es
 * justo lo que esta prueba existe para atrapar.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class ContractStabilityTests {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Test
    fun `el contrato OpenAPI (endpoints y campos de cada schema) no cambio sin querer`() {
        val liveSpec = mockMvc.perform(get("/v3/api-docs"))
            .andExpect(status().isOk).andReturn().response.contentAsString

        val liveEndpoints = extractEndpoints(liveSpec)
        val liveSchemas = extractSchemas(liveSpec)

        val snapshot = javaClass.getResourceAsStream("/contract/openapi-contract-snapshot.json")
            ?.bufferedReader()?.readText()
            ?: fail("No se encontró src/test/resources/contract/openapi-contract-snapshot.json")
        val frozenEndpoints = JsonPath.read<List<String>>(snapshot, "$.endpoints")
        val frozenSchemas = JsonPath.read<Map<String, List<String>>>(snapshot, "$.schemas")

        val addedEndpoints = liveEndpoints - frozenEndpoints.toSet()
        val removedEndpoints = frozenEndpoints.toSet() - liveEndpoints
        assertEquals(
            emptySet(), removedEndpoints,
            "Endpoint(s) que ya no existen en el contrato — si es intencional, actualiza el snapshot; si no, es una regresión.",
        )
        assertEquals(
            emptySet(), addedEndpoints,
            "Endpoint(s) nuevos que aún no están en el snapshot — actualízalo si el endpoint es intencional.",
        )

        for ((schemaName, frozenFields) in frozenSchemas) {
            val liveFields = liveSchemas[schemaName]
                ?: fail("El schema '$schemaName' desapareció del contrato (o se le quitaron todos sus campos).")
            assertEquals(
                frozenFields.toSet(), liveFields.toSet(),
                "Los campos de '$schemaName' cambiaron respecto al snapshot congelado — revisa si es un cambio de contrato intencional.",
            )
        }
        val newSchemas = liveSchemas.keys - frozenSchemas.keys
        assertEquals(emptySet(), newSchemas, "Schema(s) nuevos que aún no están en el snapshot — actualízalo si son intencionales.")
    }

    private fun extractEndpoints(spec: String): Set<String> {
        val paths = JsonPath.read<Map<String, Any>>(spec, "$.paths")
        val httpMethods = setOf("get", "post", "put", "patch", "delete")
        return paths.flatMap { (path, methods) ->
            @Suppress("UNCHECKED_CAST")
            (methods as Map<String, Any>).keys
                .filter { it.lowercase() in httpMethods }
                .map { "${it.uppercase()} $path" }
        }.toSet()
    }

    private fun extractSchemas(spec: String): Map<String, List<String>> {
        val schemas = JsonPath.read<Map<String, Any>>(spec, "$.components.schemas")
        return schemas.mapNotNull { (name, schema) ->
            @Suppress("UNCHECKED_CAST")
            val properties = (schema as Map<String, Any>)["properties"] as? Map<String, Any>
            properties?.let { name to it.keys.sorted() }
        }.toMap()
    }
}
