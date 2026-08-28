package com.nexora.api.category.web

import com.nexora.api.category.domain.CategoryService
import com.nexora.api.user.security.NexoraUserDetails
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/categories")
class CategoryController(
    private val categoryService: CategoryService,
) {

    @PostMapping
    fun create(
        @AuthenticationPrincipal principal: NexoraUserDetails,
        @Valid @RequestBody request: CreateCategoryRequest,
    ): ResponseEntity<CategoryResponse> {
        val category = categoryService.create(principal.userId, request.name, request.type)
        return ResponseEntity.status(HttpStatus.CREATED).body(CategoryResponse.from(category))
    }

    @GetMapping
    fun list(@AuthenticationPrincipal principal: NexoraUserDetails): List<CategoryResponse> =
        categoryService.listForUser(principal.userId).map(CategoryResponse::from)
}
