package com.nexora.api.category.domain

import com.nexora.api.common.domain.NotFoundException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.text.Collator
import java.util.Locale
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

    /**
     * Orden alfabético en español (sin distinguir mayúsculas, "Área" junto a
     * las demás con A), que es el orden en que web y Android las muestran en
     * los selectores. Se ordena aquí y no con ORDER BY porque el resultado de
     * Postgres depende del collation de la base (con "C", "Área" quedaría
     * después de "Zapatos").
     */
    fun listForUser(userId: UUID): List<Category> {
        val collator = Collator.getInstance(Locale.forLanguageTag("es-MX")).apply { strength = Collator.SECONDARY }
        return categoryRepository.findAllByUserId(userId).sortedWith(compareBy(collator) { it.name })
    }

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
