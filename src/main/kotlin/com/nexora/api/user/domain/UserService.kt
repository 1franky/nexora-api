package com.nexora.api.user.domain

import com.nexora.api.common.domain.ConflictException
import com.nexora.api.common.domain.NotFoundException
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class UserService(
    private val userRepository: UserRepository,
    private val passwordEncoder: PasswordEncoder,
) {

    @Transactional
    fun register(email: String, rawPassword: String, displayName: String): User {
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw ConflictException("Ya existe una cuenta registrada con ese email.")
        }
        val user = User(
            email = email.trim().lowercase(),
            passwordHash = requireNotNull(passwordEncoder.encode(rawPassword)),
            displayName = displayName.trim(),
        )
        return userRepository.save(user)
    }

    fun getById(userId: UUID): User =
        userRepository.findById(userId).orElseThrow { NotFoundException("Usuario no encontrado.") }
}
