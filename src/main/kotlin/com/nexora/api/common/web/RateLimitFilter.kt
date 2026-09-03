package com.nexora.api.common.web

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Rate limiting simple (ventana fija, por IP) para los endpoints públicos
 * expuestos a fuerza bruta o abuso: login, registro, y (B10) recuperación de
 * contraseña. Es en memoria del propio proceso — suficiente para una sola
 * instancia; si la API llega a escalar a varias instancias detrás de un
 * balanceador, esto debería moverse a un store compartido (ej. Redis).
 *
 * `forgot-password`/`reset-password` tienen un límite propio, más estricto
 * que el default (login/registro): a diferencia de esos dos, `forgot-password`
 * dispara un envío de correo real (costo, y abuso tipo "email bombing" contra
 * la víctima) — ver el plan de la feature, sección 3.
 */
@Component
class RateLimitFilter(
    @Value("\${nexora.rate-limit.max-requests:10}") private val maxRequests: Int,
    @Value("\${nexora.rate-limit.window-seconds:60}") private val windowSeconds: Long,
    // Configurables aparte (no solo constantes) por la misma razón que maxRequests/
    // windowSeconds ya lo eran: build.gradle.kts los sube en el suite de tests (Spring
    // cachea el ApplicationContext entre clases — sin esto, tests de otros módulos que
    // pegan a estos endpoints agotarían la ventana de un test que sí espera 401/204).
    @Value("\${nexora.rate-limit.forgot-password.max-requests:3}") private val forgotPasswordMaxRequests: Int = 3,
    @Value("\${nexora.rate-limit.forgot-password.window-seconds:900}") private val forgotPasswordWindowSeconds: Long = 900,
    @Value("\${nexora.rate-limit.reset-password.max-requests:5}") private val resetPasswordMaxRequests: Int = 5,
    @Value("\${nexora.rate-limit.reset-password.window-seconds:900}") private val resetPasswordWindowSeconds: Long = 900,
) : OncePerRequestFilter() {

    private data class RateLimit(val maxRequests: Int, val windowSeconds: Long)

    private class Window(@Volatile var start: Instant, val count: AtomicInteger)

    private val windows = ConcurrentHashMap<String, Window>()

    private val pathLimits: Map<String, RateLimit> by lazy {
        val default = RateLimit(maxRequests, windowSeconds)
        mapOf(
            "/api/v1/auth/login" to default,
            "/api/v1/users" to default,
            "/api/v1/auth/forgot-password" to RateLimit(forgotPasswordMaxRequests, forgotPasswordWindowSeconds),
            "/api/v1/auth/reset-password" to RateLimit(resetPasswordMaxRequests, resetPasswordWindowSeconds),
        )
    }

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, filterChain: FilterChain) {
        val limit = if (request.method == "POST") pathLimits[request.requestURI] else null
        if (limit == null) {
            filterChain.doFilter(request, response)
            return
        }

        val key = "${request.remoteAddr}:${request.requestURI}"
        val now = Instant.now()
        val window = windows.compute(key) { _, existing ->
            if (existing == null || Duration.between(existing.start, now).seconds >= limit.windowSeconds) {
                Window(now, AtomicInteger(1))
            } else {
                existing.count.incrementAndGet()
                existing
            }
        }!!

        if (window.count.get() > limit.maxRequests) {
            response.status = 429 // Too Many Requests
            response.contentType = "application/json"
            response.writer.write(
                """{"status":429,"error":"Too Many Requests","message":"Demasiados intentos, espera un momento e inténtalo de nuevo."}"""
            )
            return
        }
        filterChain.doFilter(request, response)
    }
}
