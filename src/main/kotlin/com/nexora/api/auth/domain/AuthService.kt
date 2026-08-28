package com.nexora.api.auth.domain

import com.nexora.api.common.domain.UnauthorizedException
import com.nexora.api.config.JwtProperties
import com.nexora.api.user.domain.User
import com.nexora.api.user.domain.UserRepository
import com.nexora.api.user.domain.UserStatus
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.JwsHeader
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.UUID

data class TokenPair(
    val accessToken: String,
    val refreshToken: String,
    val expiresInSeconds: Long,
)

/**
 * Emite y valida los tokens propios de la API: access token JWT (firmado
 * HS256, de corta duración) + refresh token opaco (sin firmar, solo su hash
 * se guarda — ver [RefreshToken]). No hay Authorization Server externo
 * (plan.md, sección 11 "Seguridad" anticipa esto como una posible evolución
 * posterior; aquí se implementa el caso de un solo backend que emite y
 * valida sus propios tokens para sus propios clientes — Web y Android).
 */
@Service
class AuthService(
    private val userRepository: UserRepository,
    private val refreshTokenRepository: RefreshTokenRepository,
    private val passwordEncoder: PasswordEncoder,
    private val jwtEncoder: JwtEncoder,
    private val jwtProperties: JwtProperties,
) {

    @Transactional
    fun login(email: String, rawPassword: String): TokenPair {
        val user = userRepository.findByEmailIgnoreCase(email).orElse(null)
        if (user == null || user.status != UserStatus.ACTIVE || !passwordEncoder.matches(rawPassword, user.passwordHash)) {
            throw UnauthorizedException("Email o contraseña inválidos.")
        }
        return issueTokenPair(user)
    }

    /**
     * Cambia un refresh token válido por un access token nuevo, rotando el
     * refresh token (se revoca el usado y se emite uno nuevo). Si el token
     * no existe, ya expiró o ya fue revocado —por ejemplo, porque ya se
     * usó una vez y alguien intenta reusarlo, señal de robo— se rechaza.
     */
    @Transactional
    fun refresh(rawRefreshToken: String): TokenPair {
        val tokenHash = hash(rawRefreshToken)
        val stored = refreshTokenRepository.findByTokenHash(tokenHash)
            ?: throw UnauthorizedException("Refresh token inválido.")
        if (!stored.isValid(Instant.now())) {
            throw UnauthorizedException("Refresh token inválido o expirado.")
        }
        stored.revokedAt = Instant.now()
        refreshTokenRepository.save(stored)

        val user = userRepository.findById(stored.userId).orElse(null)
        if (user == null || user.status != UserStatus.ACTIVE) {
            throw UnauthorizedException("Usuario no encontrado o inactivo.")
        }
        return issueTokenPair(user)
    }

    @Transactional
    fun logout(rawRefreshToken: String) {
        val stored = refreshTokenRepository.findByTokenHash(hash(rawRefreshToken)) ?: return
        if (stored.revokedAt == null) {
            stored.revokedAt = Instant.now()
            refreshTokenRepository.save(stored)
        }
    }

    private fun issueTokenPair(user: User): TokenPair {
        val accessToken = buildAccessToken(requireNotNull(user.id))
        val rawRefreshToken = generateOpaqueToken()
        refreshTokenRepository.save(
            RefreshToken(
                userId = requireNotNull(user.id),
                tokenHash = hash(rawRefreshToken),
                expiresAt = Instant.now().plus(jwtProperties.refreshTokenTtlDays, ChronoUnit.DAYS),
            )
        )
        return TokenPair(accessToken, rawRefreshToken, jwtProperties.accessTokenTtlMinutes * 60)
    }

    private fun buildAccessToken(userId: UUID): String {
        val now = Instant.now()
        val claims = JwtClaimsSet.builder()
            .issuer("nexora-api")
            .issuedAt(now)
            .expiresAt(now.plus(jwtProperties.accessTokenTtlMinutes, ChronoUnit.MINUTES))
            .subject(userId.toString())
            // Garantiza que cada token sea único aunque dos se emitan en el
            // mismo segundo (iat/exp idénticos darían el mismo JWT, ya que
            // la firma HMAC es determinista).
            .claim("jti", UUID.randomUUID().toString())
            .build()
        val header = JwsHeader.with(MacAlgorithm.HS256).build()
        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).tokenValue
    }

    private fun generateOpaqueToken(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun hash(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return Base64.getEncoder().encodeToString(digest)
    }
}
