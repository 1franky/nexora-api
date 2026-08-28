package com.nexora.api.user.security

import com.nexora.api.user.domain.User
import com.nexora.api.user.domain.UserStatus
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.userdetails.UserDetails
import java.util.UUID

/**
 * [UserDetails] que expone directamente el [userId] del dominio, para no
 * tener que volver a resolver el email a un [User] en cada request.
 */
class NexoraUserDetails(user: User) : UserDetails {

    val userId: UUID = requireNotNull(user.id)
    private val email = user.email
    private val passwordHash = user.passwordHash
    private val enabled = user.status == UserStatus.ACTIVE

    override fun getAuthorities(): Collection<GrantedAuthority> = listOf(SimpleGrantedAuthority("ROLE_USER"))
    override fun getPassword(): String = passwordHash
    override fun getUsername(): String = email
    override fun isAccountNonExpired(): Boolean = true
    override fun isAccountNonLocked(): Boolean = true
    override fun isCredentialsNonExpired(): Boolean = true
    override fun isEnabled(): Boolean = enabled
}
