package com.nexora.api.user.security

import com.nexora.api.user.domain.UserRepository
import org.springframework.core.convert.converter.Converter
import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Convierte el [Jwt] validado por el resource server en una [Authentication]
 * cuyo principal sigue siendo [NexoraUserDetails] — así ningún controlador
 * existente (`@AuthenticationPrincipal principal: NexoraUserDetails`) tuvo
 * que cambiar al migrar de HTTP Basic a JWT (ver SecurityConfig).
 */
@Component
class NexoraJwtAuthenticationConverter(
    private val userRepository: UserRepository,
) : Converter<Jwt, AbstractAuthenticationToken> {

    override fun convert(jwt: Jwt): AbstractAuthenticationToken {
        val userId = runCatching { UUID.fromString(jwt.subject) }.getOrNull()
            ?: throw InvalidBearerTokenException("Token inválido.")
        val user = userRepository.findById(userId).orElse(null)
            ?: throw InvalidBearerTokenException("El usuario de este token ya no existe.")
        val principal = NexoraUserDetails(user)
        return UsernamePasswordAuthenticationToken(principal, jwt, principal.authorities)
    }
}
