package com.nexora.api.auth.domain

import com.nexora.api.common.domain.UnauthorizedException
import com.nexora.api.config.JwtProperties
import com.nexora.api.email.EmailSender
import com.nexora.api.email.passwordResetEmailContent
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

private const val PASSWORD_RESET_CODE_TTL_MINUTES = 10L

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
    private val passwordResetCodeRepository: PasswordResetCodeRepository,
    private val passwordEncoder: PasswordEncoder,
    private val jwtEncoder: JwtEncoder,
    private val jwtProperties: JwtProperties,
    private val emailSender: EmailSender,
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

    /**
     * B10: genera y envía un OTP de 6 dígitos para restablecer la
     * contraseña. Silenciosamente no hace nada si el email no corresponde a
     * ningún usuario activo — [com.nexora.api.auth.web.AuthController]
     * responde igual de genérico en ambos casos, para no filtrar qué emails
     * están registrados (enumeración de usuarios).
     */
    @Transactional
    fun forgotPassword(email: String) {
        val user = userRepository.findByEmailIgnoreCase(email).orElse(null)
        if (user == null || user.status != UserStatus.ACTIVE) return
        val userId = requireNotNull(user.id)

        // Un solo código activo por usuario: invalida los previos no usados en vez de
        // dejarlos convivir (evita ambigüedad de "cuál es el vigente").
        passwordResetCodeRepository.findByUserIdAndUsedAtIsNullAndExpiresAtAfter(userId, Instant.now())
            .forEach { it.usedAt = Instant.now(); passwordResetCodeRepository.save(it) }

        val code = "%06d".format(SecureRandom().nextInt(1_000_000))
        passwordResetCodeRepository.save(
            PasswordResetCode(
                userId = userId,
                codeHash = requireNotNull(passwordEncoder.encode(code)),
                expiresAt = Instant.now().plus(PASSWORD_RESET_CODE_TTL_MINUTES, ChronoUnit.MINUTES),
            )
        )
        val content = passwordResetEmailContent(code, PASSWORD_RESET_CODE_TTL_MINUTES)
        emailSender.send(
            to = user.email,
            subject = "Tu código para restablecer tu contraseña en Nexora",
            textBody = content.text,
            htmlBody = content.html,
        )
    }

    /**
     * B10: valida el OTP y, si es correcto, actualiza la contraseña y
     * revoca todas las sesiones activas del usuario (cualquier dispositivo
     * logueado queda desconectado — igual que si alguien más comprometió la
     * cuenta, este es el momento de purgar sus accesos).
     *
     * `noRollbackFor` es necesario: por defecto @Transactional revierte TODO
     * en cualquier RuntimeException no capturada — sin esto, el incremento
     * de `attempts` de un intento fallido se revertiría junto con el resto
     * de la transacción al lanzar [UnauthorizedException], y el límite de
     * intentos (ver [PasswordResetCode.isUsable]) nunca llegaría a cumplirse.
     */
    @Transactional(noRollbackFor = [UnauthorizedException::class])
    fun resetPassword(email: String, code: String, newPassword: String) {
        val user = userRepository.findByEmailIgnoreCase(email).orElse(null)
            ?: throw UnauthorizedException("Código inválido o expirado.")
        val userId = requireNotNull(user.id)

        val candidates = passwordResetCodeRepository.findByUserIdAndUsedAtIsNullAndExpiresAtAfter(userId, Instant.now())
        val match = candidates.firstOrNull { it.isUsable(Instant.now()) && passwordEncoder.matches(code, it.codeHash) }

        if (match == null) {
            // Cuenta el intento en todos los códigos activos del usuario (normalmente hay
            // como mucho uno) — así probar códigos al azar también agota el vigente, no
            // solo "no matcheó y ya".
            candidates.forEach { it.attempts++; passwordResetCodeRepository.save(it) }
            throw UnauthorizedException("Código inválido o expirado.")
        }

        match.usedAt = Instant.now()
        passwordResetCodeRepository.save(match)

        user.passwordHash = requireNotNull(passwordEncoder.encode(newPassword))
        userRepository.save(user)

        refreshTokenRepository.findByUserIdAndRevokedAtIsNull(userId)
            .forEach { it.revokedAt = Instant.now(); refreshTokenRepository.save(it) }
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
