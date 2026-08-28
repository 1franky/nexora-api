package com.nexora.api.category.web

import com.nexora.api.category.domain.Category
import com.nexora.api.category.domain.CategoryStatus
import com.nexora.api.category.domain.CategoryType
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import java.time.Instant
import java.util.UUID

data class CreateCategoryRequest(
    @field:NotBlank(message = "El nombre es obligatorio.")
    val name: String,

    @field:NotNull(message = "El tipo de categoría es obligatorio.")
    val type: CategoryType,
)

data class CategoryResponse(
    val id: UUID,
    val name: String,
    val type: CategoryType,
    val status: CategoryStatus,
    val createdAt: Instant,
) {
    companion object {
        fun from(category: Category): CategoryResponse = CategoryResponse(
            id = requireNotNull(category.id),
            name = category.name,
            type = category.type,
            status = category.status,
            createdAt = requireNotNull(category.createdAt),
        )
    }
}
