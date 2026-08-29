package com.nexora.api.category.domain

import com.nexora.api.common.domain.NotFoundException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class CategoryService(
    private val categoryRepository: CategoryRepository,
) {

    @Transactional
    fun create(userId: UUID, name: String, type: CategoryType): Category {
        val category = Category(userId = userId, name = name.trim(), type = type)
        return categoryRepository.save(category)
    }

    fun listForUser(userId: UUID): List<Category> = categoryRepository.findAllByUserId(userId)

    fun getOwned(userId: UUID, categoryId: UUID): Category =
        categoryRepository.findByIdAndUserId(categoryId, userId)
            ?: throw NotFoundException("Categoría no encontrada.")

    @Transactional
    fun rename(userId: UUID, categoryId: UUID, name: String): Category {
        val category = getOwned(userId, categoryId)
        category.name = name.trim()
        return categoryRepository.save(category)
    }

    @Transactional
    fun archive(userId: UUID, categoryId: UUID): Category {
        val category = getOwned(userId, categoryId)
        category.status = CategoryStatus.ARCHIVED
        return categoryRepository.save(category)
    }

    @Transactional
    fun activate(userId: UUID, categoryId: UUID): Category {
        val category = getOwned(userId, categoryId)
        category.status = CategoryStatus.ACTIVE
        return categoryRepository.save(category)
    }
}
