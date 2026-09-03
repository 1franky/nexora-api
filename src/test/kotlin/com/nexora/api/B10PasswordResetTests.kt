package com.nexora.api

import com.jayway.jsonpath.JsonPath
import com.nexora.api.auth.domain.PasswordResetCode
import com.nexora.api.auth.domain.PasswordResetCodeRepository
import com.nexora.api.auth.domain.RefreshTokenRepository
import com.nexora.api.support.FakeEmailSender
import com.nexora.api.support.TEST_PASSWORD
import com.nexora.api.support.TestEmailConfig
import com.nexora.api.user.domain.UserRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Pruebas de integración de B10 (recuperación de contraseña por OTP): HTTP
 * -> seguridad -> servicio -> Postgres (Testcontainers), con el envío de
 * correo reemplazado por [FakeEmailSender] (ver TestEmailConfig) — ningún
 * test le pega a la API real de Resend.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class, TestEmailConfig::class)
class B10PasswordResetTests {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var fakeEmailSender: FakeEmailSender

    @Autowired
    lateinit var userRepository: UserRepository

    @Autowired
    lateinit var passwordResetCodeRepository: PasswordResetCodeRepository

    @Autowired
    lateinit var refreshTokenRepository: RefreshTokenRepository

    @Autowired
    lateinit var passwordEncoder: PasswordEncoder

    @Test
    fun `forgot-password con email existente envia un codigo de 6 digitos`() {
        val email = registerUser("existente")

        forgotPassword(email).andExpect(status().isOk)

        val code = fakeEmailSender.lastCodeFor(email)
        assertEquals(6, code.length)
    }

    @Test
    fun `forgot-password con email inexistente responde igual que con uno existente`() {
        val existingEmail = registerUser("comparacion")
        val existingResponse = forgotPassword(existingEmail).andReturn().response

        val fakeEmail = "no-existe+${System.nanoTime()}@nexora.test"
        val fakeResponse = forgotPassword(fakeEmail).andReturn().response

        // Mismo status y mismo cuerpo (vacío) en ambos casos — no debe ser posible
        // distinguir "existe" de "no existe" por la respuesta (sección 3, punto 7).
        assertEquals(existingResponse.status, fakeResponse.status)
        assertEquals(existingResponse.contentAsString, fakeResponse.contentAsString)
    }

    @Test
    fun `reset-password con el codigo correcto actualiza la contrasena`() {
        val email = registerUser("resetok")
        forgotPassword(email).andExpect(status().isOk)
        val code = fakeEmailSender.lastCodeFor(email)

        resetPassword(email, code, "nueva-contrasena-123").andExpect(status().isNoContent)

        login(email, "nueva-contrasena-123").andExpect(status().isOk)
        login(email, TEST_PASSWORD).andExpect(status().isUnauthorized)
    }

    @Test
    fun `reset-password exitoso revoca las sesiones activas`() {
        val email = registerUser("revocacion")
        val loginBody = login(email, TEST_PASSWORD).andExpect(status().isOk).andReturn().response.contentAsString
        val oldRefreshToken: String = JsonPath.read(loginBody, "$.refreshToken")

        forgotPassword(email).andExpect(status().isOk)
        val code = fakeEmailSender.lastCodeFor(email)
        resetPassword(email, code, "nueva-contrasena-123").andExpect(status().isNoContent)

        mockMvc.perform(
            post("/api/v1/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"refreshToken":"$oldRefreshToken"}""")
        ).andExpect(status().isUnauthorized)
    }

    @Test
    fun `reset-password con codigo incorrecto devuelve 401`() {
        val email = registerUser("codigoerroneo")
        forgotPassword(email).andExpect(status().isOk)

        resetPassword(email, "000000", "nueva-contrasena-123").andExpect(status().isUnauthorized)
    }

    @Test
    fun `tras agotar los intentos el codigo correcto ya no funciona`() {
        val email = registerUser("intentosagotados")
        forgotPassword(email).andExpect(status().isOk)
        val code = fakeEmailSender.lastCodeFor(email)
        val wrongCode = if (code == "000000") "000001" else "000000"

        // 5 intentos fallidos agotan el código (MAX_ATTEMPTS, ver PasswordResetCode).
        repeat(5) {
            resetPassword(email, wrongCode, "nueva-contrasena-123").andExpect(status().isUnauthorized)
        }

        // El código real, que nunca falló por sí mismo, ya no sirve: se agotó.
        resetPassword(email, code, "nueva-contrasena-123").andExpect(status().isUnauthorized)
    }

    @Test
    fun `codigo expirado devuelve 401`() {
        val email = registerUser("expirado")
        val user = userRepository.findByEmailIgnoreCase(email).get()
        val code = "654321"
        passwordResetCodeRepository.save(
            PasswordResetCode(
                userId = requireNotNull(user.id),
                codeHash = requireNotNull(passwordEncoder.encode(code)),
                expiresAt = Instant.now().minusSeconds(60),
            )
        )

        resetPassword(email, code, "nueva-contrasena-123").andExpect(status().isUnauthorized)
    }

    @Test
    fun `pedir un segundo codigo invalida el primero`() {
        val email = registerUser("segundocodigo")
        forgotPassword(email).andExpect(status().isOk)
        val firstCode = fakeEmailSender.lastCodeFor(email)

        forgotPassword(email).andExpect(status().isOk)
        val secondCode = fakeEmailSender.lastCodeFor(email)
        assertNotEquals(firstCode, secondCode)

        resetPassword(email, firstCode, "nueva-contrasena-123").andExpect(status().isUnauthorized)
        resetPassword(email, secondCode, "nueva-contrasena-123").andExpect(status().isNoContent)
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

    private fun forgotPassword(email: String) = mockMvc.perform(
        post("/api/v1/auth/forgot-password")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"email":"$email"}""")
    )

    private fun resetPassword(email: String, code: String, newPassword: String) = mockMvc.perform(
        post("/api/v1/auth/reset-password")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"email":"$email","code":"$code","newPassword":"$newPassword"}""")
    )
}
