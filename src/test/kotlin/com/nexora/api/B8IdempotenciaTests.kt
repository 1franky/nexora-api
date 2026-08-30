package com.nexora.api

import com.jayway.jsonpath.JsonPath
import com.nexora.api.common.domain.IdempotencyRecordRepository
import com.nexora.api.common.web.IDEMPOTENCY_KEY_HEADER
import com.nexora.api.support.registerAndAuthenticate
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID
import kotlin.test.assertEquals

/**
 * Pruebas de integración de B8 (Idempotency-Key): HTTP -> filtro ->
 * servicio -> Postgres (Testcontainers). Usa POST /api/v1/accounts como
 * endpoint de prueba porque es el alta más simple que existe; el filtro es
 * agnóstico al endpoint, así que no hace falta cubrir cada uno.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class B8IdempotenciaTests {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var idempotencyRecordRepository: IdempotencyRecordRepository

    @Test
    fun `reintentar con la misma key devuelve la misma cuenta sin crear otra`() {
        val auth = mockMvc.registerAndAuthenticate("idem-repeat")
        val key = UUID.randomUUID().toString()
        val body = """{"name":"Débito Santander","type":"DEBIT","currency":"MXN","openingBalance":1000}"""

        val first = mockMvc.perform(
            post("/api/v1/accounts").with(auth).withKey(key).contentType(MediaType.APPLICATION_JSON).content(body)
        ).andExpect(status().isCreated).andReturn().response.contentAsString

        val second = mockMvc.perform(
            post("/api/v1/accounts").with(auth).withKey(key).contentType(MediaType.APPLICATION_JSON).content(body)
        ).andExpect(status().isCreated).andReturn().response.contentAsString

        assertEquals(JsonPath.read<String>(first, "$.id"), JsonPath.read<String>(second, "$.id"))

        val accounts = mockMvc.perform(get("/api/v1/accounts").with(auth))
            .andExpect(status().isOk).andReturn().response.contentAsString
        assertEquals(1, JsonPath.read<Int>(accounts, "$.length()"))
    }

    @Test
    fun `la misma key con un cuerpo distinto devuelve 409`() {
        val auth = mockMvc.registerAndAuthenticate("idem-conflict")
        val key = UUID.randomUUID().toString()

        mockMvc.perform(
            post("/api/v1/accounts").with(auth).withKey(key).contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"Débito Santander","type":"DEBIT","currency":"MXN","openingBalance":1000}""")
        ).andExpect(status().isCreated)

        mockMvc.perform(
            post("/api/v1/accounts").with(auth).withKey(key).contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"Ahorro BBVA","type":"SAVINGS","currency":"MXN","openingBalance":500}""")
        ).andExpect(status().isConflict)
    }

    @Test
    fun `keys distintas crean cuentas distintas`() {
        val auth = mockMvc.registerAndAuthenticate("idem-distinct")
        val body = """{"name":"Débito Santander","type":"DEBIT","currency":"MXN","openingBalance":1000}"""

        mockMvc.perform(
            post("/api/v1/accounts").with(auth).withKey(UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content(body)
        ).andExpect(status().isCreated)
        mockMvc.perform(
            post("/api/v1/accounts").with(auth).withKey(UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content(body)
        ).andExpect(status().isCreated)

        val accounts = mockMvc.perform(get("/api/v1/accounts").with(auth))
            .andExpect(status().isOk).andReturn().response.contentAsString
        assertEquals(2, JsonPath.read<Int>(accounts, "$.length()"))
    }

    @Test
    fun `un error de validacion no se cachea y se puede reintentar`() {
        val auth = mockMvc.registerAndAuthenticate("idem-error")
        val key = UUID.randomUUID().toString()
        val invalidBody = """{"name":"","type":"DEBIT","currency":"MXN"}"""
        val validBody = """{"name":"Débito Santander","type":"DEBIT","currency":"MXN","openingBalance":1000}"""

        mockMvc.perform(
            post("/api/v1/accounts").with(auth).withKey(key).contentType(MediaType.APPLICATION_JSON).content(invalidBody)
        ).andExpect(status().isBadRequest)

        // Misma key, ahora con datos válidos: como el intento anterior fue un error, no se cacheó nada bajo
        // esa key ni se guardó su fingerprint — este segundo intento se procesa como si fuera la primera vez.
        mockMvc.perform(
            post("/api/v1/accounts").with(auth).withKey(key).contentType(MediaType.APPLICATION_JSON).content(validBody)
        ).andExpect(status().isCreated)
    }

    @Test
    fun `sin el header la peticion funciona normal y no toca la tabla de idempotencia`() {
        val auth = mockMvc.registerAndAuthenticate("idem-noheader")
        val before = idempotencyRecordRepository.count()

        mockMvc.perform(
            post("/api/v1/accounts").with(auth).contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"Débito Santander","type":"DEBIT","currency":"MXN","openingBalance":1000}""")
        ).andExpect(status().isCreated)

        assertEquals(before, idempotencyRecordRepository.count())
    }

    @Test
    fun `sin autenticacion el header no evita el 401`() {
        mockMvc.perform(
            post("/api/v1/accounts").withKey(UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"Débito Santander","type":"DEBIT","currency":"MXN","openingBalance":1000}""")
        ).andExpect(status().isUnauthorized)
    }

    private fun MockHttpServletRequestBuilder.withKey(key: String) = header(IDEMPOTENCY_KEY_HEADER, key)
}
