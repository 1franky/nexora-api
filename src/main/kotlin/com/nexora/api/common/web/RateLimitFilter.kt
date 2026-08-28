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
 * Rate limiting simple (ventana fija, por IP) solo para los dos endpoints
 * públicos expuestos a fuerza bruta: login y registro. Es en memoria del
 * propio proceso — suficiente para una sola instancia; si la API llega a
 * escalar a varias instancias detrás de un balanceador, esto debería
 * moverse a un store compartido (ej. Redis).
 */
@Component
class RateLimitFilter(
    @Value("\${nexora.rate-limit.max-requests:10}") private val maxRequests: Int,
    @Value("\${nexora.rate-limit.window-seconds:60}") private val windowSeconds: Long,
) : OncePerRequestFilter() {

    private class Window(@Volatile var start: Instant, val count: AtomicInteger)

    private val windows = ConcurrentHashMap<String, Window>()
    private val limitedPaths = setOf("/api/v1/auth/login", "/api/v1/users")

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, filterChain: FilterChain) {
        if (request.method != "POST" || request.requestURI !in limitedPaths) {
            filterChain.doFilter(request, response)
            return
        }

        val key = "${request.remoteAddr}:${request.requestURI}"
        val now = Instant.now()
        val window = windows.compute(key) { _, existing ->
            if (existing == null || Duration.between(existing.start, now).seconds >= windowSeconds) {
                Window(now, AtomicInteger(1))
            } else {
                existing.count.incrementAndGet()
                existing
            }
        }!!

        if (window.count.get() > maxRequests) {
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
