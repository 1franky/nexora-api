package com.nexora.api.budget.domain

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface BudgetRepository : JpaRepository<Budget, UUID> {
    fun findAllByUserId(userId: UUID): List<Budget>
    fun findByIdAndUserId(id: UUID, userId: UUID): Budget?
    fun existsByUserIdAndCategoryId(userId: UUID, categoryId: UUID): Boolean
    fun existsByUserIdAndCategoryIdIsNull(userId: UUID): Boolean
}
