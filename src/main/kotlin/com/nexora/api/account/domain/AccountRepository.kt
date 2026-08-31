package com.nexora.api.account.domain

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface AccountRepository : JpaRepository<Account, UUID> {
    fun findAllByUserIdOrderByNameAsc(userId: UUID): List<Account>
    fun findAllByUserIdAndTypeOrderByNameAsc(userId: UUID, type: AccountType): List<Account>
    fun findByIdAndUserId(id: UUID, userId: UUID): Account?
}
