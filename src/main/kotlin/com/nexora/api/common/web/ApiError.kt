package com.nexora.api.common.web

import java.time.Instant

/** Cuerpo de error uniforme para toda la API. */
data class ApiError(
    val timestamp: Instant = Instant.now(),
    val status: Int,
    val error: String,
    val message: String?,
    val path: String,
    val fieldErrors: List<FieldError> = emptyList(),
)

data class FieldError(
    val field: String,
    val message: String?,
)
