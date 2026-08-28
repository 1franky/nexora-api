package com.nexora.api.category.domain

import com.nexora.api.common.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import java.util.UUID

enum class CategoryType {
    INCOME,
    EXPENSE,
}

enum class CategoryStatus {
    ACTIVE,
    ARCHIVED,
}

/**
 * Categoría para clasificar ingresos o gastos (plan.md, secciones 3.1/3.2).
 * Las transferencias no llevan categoría.
 */
@Entity
@Table(name = "categories")
class Category(

    @Column(name = "user_id", nullable = false)
    var userId: UUID,

    @Column(nullable = false)
    var name: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var type: CategoryType,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: CategoryStatus = CategoryStatus.ACTIVE,

) : BaseEntity()
