package com.nexora.api.category.domain

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface CategoryRepository : JpaRepository<Category, UUID> {
    fun findAllByUserId(userId: UUID): List<Category>
    fun findByIdAndUserId(id: UUID, userId: UUID): Category?
}
