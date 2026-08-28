package com.nexora.api.transaction.domain

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.domain.Sort
import java.util.UUID

interface TransactionRepository : JpaRepository<Transaction, UUID> {
    fun findAllByAccountId(accountId: UUID, sort: Sort): List<Transaction>
}
