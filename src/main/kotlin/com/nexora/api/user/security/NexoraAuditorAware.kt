package com.nexora.api.user.security

import org.springframework.data.domain.AuditorAware
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import java.util.Optional
import java.util.UUID

/**
 * Resuelve el `createdBy` de [com.nexora.api.common.domain.BaseEntity] al
 * userId del principal autenticado de la request actual (ver
 * [com.nexora.api.user.security.NexoraJwtAuthenticationConverter]: el
 * principal siempre es un [NexoraUserDetails]). Sin autenticación en el
 * contexto — auto-registro de un usuario nuevo, jobs de sistema sin
 * request HTTP — devuelve vacío, y `createdBy` queda en null.
 */
@Component
class NexoraAuditorAware : AuditorAware<UUID> {

    override fun getCurrentAuditor(): Optional<UUID> {
        val principal = SecurityContextHolder.getContext().authentication?.principal as? NexoraUserDetails
            ?: return Optional.empty()
        return Optional.of(principal.userId)
    }
}
