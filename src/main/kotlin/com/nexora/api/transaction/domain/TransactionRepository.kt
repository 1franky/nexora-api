package com.nexora.api.transaction.domain

import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDate
import java.util.UUID

interface TransactionRepository : JpaRepository<Transaction, UUID> {
    fun findAllByAccountId(accountId: UUID, sort: Sort): List<Transaction>
    fun findAllByAccountIdIn(accountIds: List<UUID>, sort: Sort): List<Transaction>
    fun findAllByAccountIdIn(accountIds: List<UUID>, pageable: Pageable): List<Transaction>
    fun findAllByAccountIdInAndDateBetween(accountIds: List<UUID>, start: LocalDate, end: LocalDate): List<Transaction>
    fun findAllByAccountIdInAndDateAfter(accountIds: List<UUID>, date: LocalDate): List<Transaction>
}
