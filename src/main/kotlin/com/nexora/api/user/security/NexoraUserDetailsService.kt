package com.nexora.api.user.security

import com.nexora.api.user.domain.UserRepository
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.core.userdetails.UsernameNotFoundException
import org.springframework.stereotype.Service

@Service
class NexoraUserDetailsService(
    private val userRepository: UserRepository,
) : UserDetailsService {

    override fun loadUserByUsername(email: String): NexoraUserDetails {
        val user = userRepository.findByEmailIgnoreCase(email)
            .orElseThrow { UsernameNotFoundException("Credenciales inválidas.") }
        return NexoraUserDetails(user)
    }
}
