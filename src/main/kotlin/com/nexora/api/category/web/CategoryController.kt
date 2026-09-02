package com.nexora.api.category.web

import com.nexora.api.category.domain.CategoryService
import com.nexora.api.user.security.NexoraUserDetails
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@Tag(name = "Categorías", description = "Categorías de ingreso/gasto para clasificar movimientos.")
@RestController
@RequestMapping("/api/v1/categories")
class CategoryController(
    private val categoryService: CategoryService,
) {

    @Operation(summary = "Crear una categoría")
    @ApiResponse(responseCode = "201", description = "Categoría creada.")
    @PostMapping
    fun create(
        @AuthenticationPrincipal principal: NexoraUserDetails,
        @Valid @RequestBody request: CreateCategoryRequest,
    ): ResponseEntity<CategoryResponse> {
        val category = categoryService.create(principal.userId, request.name, request.type)
        return ResponseEntity.status(HttpStatus.CREATED).body(CategoryResponse.from(category))
    }

    @Operation(summary = "Listar categorías", description = "Incluye las archivadas — un movimiento ya categorizado sigue mostrando su categoría aunque esté archivada.")
    @GetMapping
    fun list(@AuthenticationPrincipal principal: NexoraUserDetails): List<CategoryResponse> =
        categoryService.listForUser(principal.userId).map(CategoryResponse::from)

    @Operation(summary = "Renombrar una categoría", description = "El tipo (INCOME/EXPENSE) no se puede editar una vez creada.")
    @PatchMapping("/{id}")
    fun update(
        @AuthenticationPrincipal principal: NexoraUserDetails,
        @Parameter(description = "Id de la categoría.") @PathVariable id: UUID,
        @Valid @RequestBody request: UpdateCategoryRequest,
    ): CategoryResponse = CategoryResponse.from(categoryService.rename(principal.userId, id, request.name))

    @Operation(
        summary = "Archivar una categoría",
        description = "Deja de poder usarse en movimientos nuevos, pero sigue existiendo para lo ya categorizado (no se puede borrar una categoría en uso).",
    )
    @PostMapping("/{id}/archive")
    fun archive(
        @AuthenticationPrincipal principal: NexoraUserDetails,
        @Parameter(description = "Id de la categoría.") @PathVariable id: UUID,
    ): CategoryResponse = CategoryResponse.from(categoryService.archive(principal.userId, id))

    @Operation(summary = "Reactivar una categoría archivada")
    @PostMapping("/{id}/activate")
    fun activate(
        @AuthenticationPrincipal principal: NexoraUserDetails,
        @Parameter(description = "Id de la categoría.") @PathVariable id: UUID,
    ): CategoryResponse = CategoryResponse.from(categoryService.activate(principal.userId, id))
}
