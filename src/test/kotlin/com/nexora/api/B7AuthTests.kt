package com.nexora.api

import com.jayway.jsonpath.JsonPath
import com.nexora.api.support.TEST_PASSWORD
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.RequestPostProcessor
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Pruebas de integración de B7 (login/refresh/logout con JWT): HTTP ->
 * seguridad -> servicio -> Postgres (Testcontainers).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class B7AuthTests {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Test
    fun `login exitoso devuelve access y refresh token`() {
        val email = registerUser("loginok")

        val response = login(email, TEST_PASSWORD)
        val body = response.andReturn().response.contentAsString

        val accessToken: String = JsonPath.read(body, "$.accessToken")
        val refreshToken: String = JsonPath.read(body, "$.refreshToken")
        assertTrue(accessToken.isNotBlank())
        assertTrue(refreshToken.isNotBlank())
        assertEquals("Bearer", JsonPath.read(body, "$.tokenType"))
        assertTrue(JsonPath.read<Int>(body, "$.expiresInSeconds") > 0)
    }

    @Test
    fun `login con contrasena incorrecta devuelve 401`() {
        val email = registerUser("badpass")
        mockMvc.perform(
            post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"email":"$email","password":"otra-contraseña"}""")
        ).andExpect(status().isUnauthorized)
    }

    @Test
    fun `login con email inexistente devuelve 401`() {
        mockMvc.perform(
            post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"email":"no-existe+${System.nanoTime()}@nexora.test","password":"$TEST_PASSWORD"}""")
        ).andExpect(status().isUnauthorized)
    }

    @Test
    fun `el access token permite acceder a un endpoint protegido`() {
        val email = registerUser("accesook")
        val accessToken = loginAndGetAccessToken(email)

        val response = mockMvc.perform(get("/api/v1/users/me").with(bearer(accessToken)))
            .andExpect(status().isOk).andReturn().response.contentAsString
        assertEquals(email, JsonPath.read(response, "$.email"))
    }

    @Test
    fun `un access token invalido es rechazado`() {
        mockMvc.perform(get("/api/v1/users/me").with(bearer("esto-no-es-un-jwt-valido")))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `un refresh token valido emite un access token nuevo y funcional`() {
        val email = registerUser("refreshok")
        val loginBody = login(email, TEST_PASSWORD).andReturn().response.contentAsString
        val refreshToken: String = JsonPath.read(loginBody, "$.refreshToken")
        val originalAccessToken: String = JsonPath.read(loginBody, "$.accessToken")

        val refreshBody = mockMvc.perform(
            post("/api/v1/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"refreshToken":"$refreshToken"}""")
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val newAccessToken: String = JsonPath.read(refreshBody, "$.accessToken")

        assertNotEquals(originalAccessToken, newAccessToken)
        mockMvc.perform(get("/api/v1/users/me").with(bearer(newAccessToken))).andExpect(status().isOk)
    }

    @Test
    fun `un refresh token ya usado no se puede reutilizar (rotacion)`() {
        val email = registerUser("rotacion")
        val loginBody = login(email, TEST_PASSWORD).andReturn().response.contentAsString
        val refreshToken: String = JsonPath.read(loginBody, "$.refreshToken")

        mockMvc.perform(
            post("/api/v1/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"refreshToken":"$refreshToken"}""")
        ).andExpect(status().isOk)

        // Reusar el mismo refresh token (ya rotado) debe ser rechazado.
        mockMvc.perform(
            post("/api/v1/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"refreshToken":"$refreshToken"}""")
        ).andExpect(status().isUnauthorized)
    }

    @Test
    fun `logout revoca el refresh token`() {
        val email = registerUser("logoutok")
        val loginBody = login(email, TEST_PASSWORD).andReturn().response.contentAsString
        val refreshToken: String = JsonPath.read(loginBody, "$.refreshToken")

        mockMvc.perform(
            post("/api/v1/auth/logout")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"refreshToken":"$refreshToken"}""")
        ).andExpect(status().isNoContent)

        mockMvc.perform(
            post("/api/v1/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"refreshToken":"$refreshToken"}""")
        ).andExpect(status().isUnauthorized)
    }

    // --- helpers ---

    private fun registerUser(prefix: String): String {
        val email = "$prefix+${System.nanoTime()}@nexora.test"
        mockMvc.perform(
            post("/api/v1/users")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"email":"$email","password":"$TEST_PASSWORD","displayName":"Test User"}""")
        ).andExpect(status().isCreated)
        return email
    }

    private fun login(email: String, password: String) = mockMvc.perform(
        post("/api/v1/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"email":"$email","password":"$password"}""")
    )

    private fun loginAndGetAccessToken(email: String): String {
        val body = login(email, TEST_PASSWORD).andExpect(status().isOk).andReturn().response.contentAsString
        return JsonPath.read(body, "$.accessToken")
    }

    private fun bearer(accessToken: String): RequestPostProcessor =
        RequestPostProcessor { request -> request.addHeader("Authorization", "Bearer $accessToken"); request }
}
