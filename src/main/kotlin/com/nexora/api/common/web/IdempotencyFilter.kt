package com.nexora.api.common.web

import com.nexora.api.common.domain.IdempotencyRecord
import com.nexora.api.common.domain.IdempotencyRecordRepository
import com.nexora.api.user.security.NexoraUserDetails
import jakarta.servlet.FilterChain
import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import jakarta.servlet.http.HttpServletResponse
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.util.ContentCachingResponseWrapper
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

const val IDEMPOTENCY_KEY_HEADER = "Idempotency-Key"

/**
 * Hace seguro reintentar una escritura (POST) marcada con el header
 * `Idempotency-Key`: si esa key ya se procesó para este usuario, devuelve
 * la respuesta guardada en vez de ejecutar la operación otra vez. Pensado
 * para nexora-android (A8, cola de escritura offline — ver plan.md,
 * sección 16): un reintento tras una respuesta perdida — pero la petición
 * ya aplicada del lado del servidor — no debe duplicar el
 * movimiento/compra/pago.
 *
 * Solo actúa si el cliente manda el header — el resto de los POST (login,
 * registro, etc.) siguen su curso normal sin overhead ni tabla de por
 * medio. Únicamente se cachean respuestas 2xx: un error no persistió
 * nada, así que reintentarlo sin idempotencia es seguro.
 *
 * Ventana de carrera aceptada: si el mismo cliente dispara dos peticiones
 * verdaderamente concurrentes con la misma key (no un reintento
 * secuencial tras timeout, que es el caso real), ambas pueden no
 * encontrar todavía el registro y ejecutar la operación dos veces; el
 * segundo `save` fallará por el índice único (user_id, key_value) pero
 * eso ya no deshace el duplicado. Cerrar esa ventana requeriría reservar
 * la key en su propia transacción antes de ejecutar la cadena — se deja
 * así por ahora porque un cliente único reintentando en serie (el
 * escenario que este filtro existe para resolver) no la dispara.
 */
@Component
class IdempotencyFilter(
    private val idempotencyRecordRepository: IdempotencyRecordRepository,
) : OncePerRequestFilter() {

    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        request.method != "POST" || request.getHeader(IDEMPOTENCY_KEY_HEADER).isNullOrBlank()

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, filterChain: FilterChain) {
        val key = request.getHeader(IDEMPOTENCY_KEY_HEADER)!!
        val userId = currentUserId()
        if (userId == null) {
            // No autenticado: que la cadena siga su curso normal (terminará en 401, sin tocar la tabla).
            filterChain.doFilter(request, response)
            return
        }

        val cachedRequest = CachedBodyHttpServletRequest(request)
        val fingerprint = fingerprint(request.method, request.requestURI, cachedRequest.cachedBody)

        val existing = idempotencyRecordRepository.findByUserIdAndKeyValue(userId, key)
        if (existing != null) {
            if (existing.fingerprint == fingerprint) {
                response.status = existing.responseStatus
                response.contentType = "application/json"
                response.writer.write(existing.responseBody)
            } else {
                response.status = 409
                response.contentType = "application/json"
                response.writer.write(
                    """{"status":409,"error":"Conflict","message":"La Idempotency-Key ya se usó con una petición distinta."}"""
                )
            }
            return
        }

        val cachedResponse = ContentCachingResponseWrapper(response)
        filterChain.doFilter(cachedRequest, cachedResponse)

        if (cachedResponse.status in 200..299) {
            val body = String(cachedResponse.contentAsByteArray, StandardCharsets.UTF_8)
            try {
                idempotencyRecordRepository.save(IdempotencyRecord(userId, key, fingerprint, cachedResponse.status, body))
            } catch (_: DataIntegrityViolationException) {
                // Ver "Ventana de carrera aceptada" arriba: otra petición con la misma key ganó la inserción.
            }
        }
        cachedResponse.copyBodyToResponse()
    }

    private fun currentUserId(): UUID? =
        (SecurityContextHolder.getContext().authentication?.principal as? NexoraUserDetails)?.userId

    private fun fingerprint(method: String, uri: String, body: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(method.toByteArray(StandardCharsets.UTF_8))
        digest.update(uri.toByteArray(StandardCharsets.UTF_8))
        digest.update(body)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

/**
 * El stream del request original solo se puede leer una vez; esta
 * envoltura lo lee completo al construirse para poder calcular el
 * fingerprint aquí en el filtro, y sigue exponiendo esos mismos bytes vía
 * [getInputStream]/[getReader] para que el controlador los lea después
 * con normalidad (deserialización de `@RequestBody`).
 */
private class CachedBodyHttpServletRequest(request: HttpServletRequest) : HttpServletRequestWrapper(request) {
    val cachedBody: ByteArray = request.inputStream.readBytes()

    override fun getInputStream(): ServletInputStream = object : ServletInputStream() {
        private val buffer = ByteArrayInputStream(cachedBody)
        override fun isFinished() = buffer.available() == 0
        override fun isReady() = true
        override fun setReadListener(readListener: ReadListener?) {}
        override fun read(): Int = buffer.read()
    }

    override fun getReader(): BufferedReader =
        BufferedReader(InputStreamReader(ByteArrayInputStream(cachedBody), StandardCharsets.UTF_8))
}
