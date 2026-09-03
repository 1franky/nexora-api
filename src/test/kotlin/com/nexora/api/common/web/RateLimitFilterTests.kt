package com.nexora.api.common.web

import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import kotlin.test.assertEquals

/**
 * Prueba aislada (sin contexto de Spring): el filtro real se comparte como
 * singleton entre todas las clases @SpringBootTest (Spring cachea el
 * ApplicationContext), así que probar el umbral ahí arrastraría estado
 * entre tests de módulos distintos. Aquí se instancia directamente con un
 * límite bajo, sin tocar el resto del suite.
 */
class RateLimitFilterTests {

    @Test
    fun `permite hasta el limite configurado y luego devuelve 429`() {
        val filter = RateLimitFilter(maxRequests = 3, windowSeconds = 60)

        repeat(3) {
            val request = loginRequest()
            val response = MockHttpServletResponse()
            filter.doFilter(request, response, MockFilterChain())
            assertEquals(200, response.status, "las primeras 3 deberían pasar sin que el filtro las toque")
        }

        val response = MockHttpServletResponse()
        filter.doFilter(loginRequest(), response, MockFilterChain())
        assertEquals(429, response.status)
    }

    @Test
    fun `no limita rutas distintas a login o registro`() {
        val filter = RateLimitFilter(maxRequests = 1, windowSeconds = 60)

        repeat(5) {
            val request = MockHttpServletRequest("GET", "/api/v1/accounts")
            val response = MockHttpServletResponse()
            filter.doFilter(request, response, MockFilterChain())
            assertEquals(200, response.status)
        }
    }

    @Test
    fun `forgot-password usa su propio limite, no el default`() {
        // Default alto (nunca se dispara), forgot-password bajo (3) — confirma que
        // pathLimits realmente distingue por path y no cae al default para éste.
        val filter = RateLimitFilter(maxRequests = 1000, windowSeconds = 60, forgotPasswordMaxRequests = 3, forgotPasswordWindowSeconds = 900)

        repeat(3) {
            val response = MockHttpServletResponse()
            filter.doFilter(forgotPasswordRequest(), response, MockFilterChain())
            assertEquals(200, response.status)
        }

        val response = MockHttpServletResponse()
        filter.doFilter(forgotPasswordRequest(), response, MockFilterChain())
        assertEquals(429, response.status)
    }

    @Test
    fun `ips distintas tienen su propio contador`() {
        val filter = RateLimitFilter(maxRequests = 1, windowSeconds = 60)

        val first = loginRequest().apply { remoteAddr = "10.0.0.1" }
        filter.doFilter(first, MockHttpServletResponse(), MockFilterChain())

        val second = loginRequest().apply { remoteAddr = "10.0.0.2" }
        val secondResponse = MockHttpServletResponse()
        filter.doFilter(second, secondResponse, MockFilterChain())

        assertEquals(200, secondResponse.status)
    }

    private fun loginRequest(): MockHttpServletRequest = MockHttpServletRequest("POST", "/api/v1/auth/login")

    private fun forgotPasswordRequest(): MockHttpServletRequest = MockHttpServletRequest("POST", "/api/v1/auth/forgot-password")
}
