package com.nexora.api.common.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.util.UUID

/**
 * Registro de una escritura ya ejecutada bajo una Idempotency-Key (ver
 * com.nexora.api.common.web.IdempotencyFilter). Plan.md, sección 16
 * ("Consideraciones para soporte offline"): permite que nexora-android
 * reintente una petición POST creada sin conexión (A8, cola de escritura
 * offline) sin arriesgarse a duplicar el movimiento/compra/pago si el
 * reintento llega después de que el servidor ya la procesó pero la
 * respuesta nunca le llegó al cliente.
 */
@Entity
@Table(name = "idempotency_records")
class IdempotencyRecord(

    @Column(name = "user_id", nullable = false)
    var userId: UUID,

    @Column(name = "key_value", nullable = false, length = 255)
    var keyValue: String,

    /** Hash de método + ruta + cuerpo de la petición original — detecta que la misma key se reutilice con datos distintos. */
    @Column(nullable = false, length = 64)
    var fingerprint: String,

    @Column(name = "response_status", nullable = false)
    var responseStatus: Int,

    @Column(name = "response_body", nullable = false, columnDefinition = "text")
    var responseBody: String,

) : BaseEntity()
