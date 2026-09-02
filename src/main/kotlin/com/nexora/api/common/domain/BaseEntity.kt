package com.nexora.api.common.domain

import jakarta.persistence.Column
import jakarta.persistence.EntityListeners
import jakarta.persistence.Id
import jakarta.persistence.MappedSuperclass
import org.hibernate.annotations.UuidGenerator
import org.springframework.data.annotation.CreatedBy
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.jpa.domain.support.AuditingEntityListener
import java.time.Instant
import java.util.UUID

/**
 * Campos comunes a toda entidad de dominio: id (UUID, generado por la
 * aplicación) y auditoría básica (plan.md, sección 13 "Auditoría").
 * [createdBy] lo puebla [com.nexora.api.user.security.NexoraAuditorAware]
 * a partir del usuario autenticado de la request actual — nullable porque
 * no hay uno en el auto-registro de un [com.nexora.api.user.domain.User]
 * nuevo, ni en jobs de sistema sin request HTTP (tipos de cambio
 * cacheados, notificaciones generadas por el scheduler diario). Los
 * eventos financieros explícitos que además pide el plan (compras, pagos,
 * movimientos, planes MSI/MCI) quedan en la tabla `audit_log` aparte —
 * ver [com.nexora.api.audit.domain.AuditLogService].
 */
@MappedSuperclass
@EntityListeners(AuditingEntityListener::class)
abstract class BaseEntity {

    @Id
    @UuidGenerator
    @Column(nullable = false, updatable = false)
    var id: UUID? = null
        protected set

    @CreatedDate
    @Column(nullable = false, updatable = false)
    var createdAt: Instant? = null
        protected set

    @LastModifiedDate
    @Column(nullable = false)
    var updatedAt: Instant? = null
        protected set

    @CreatedBy
    @Column(name = "created_by", updatable = false)
    var createdBy: UUID? = null
        protected set
}
