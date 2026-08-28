package com.nexora.api.creditcard.domain

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface CreditCardRepository : JpaRepository<CreditCard, UUID> {
    fun findByAccountId(accountId: UUID): CreditCard?
    fun findAllByAccountIdIn(accountIds: List<UUID>): List<CreditCard>
}
