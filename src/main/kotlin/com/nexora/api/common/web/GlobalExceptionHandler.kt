package com.nexora.api.common.web

import com.nexora.api.common.domain.BusinessRuleException
import com.nexora.api.common.domain.ConflictException
import com.nexora.api.common.domain.ForbiddenException
import com.nexora.api.common.domain.NotFoundException
import com.nexora.api.common.domain.UnauthorizedException
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class GlobalExceptionHandler {

    @ExceptionHandler(NotFoundException::class)
    fun handleNotFound(ex: NotFoundException, request: HttpServletRequest): ResponseEntity<ApiError> =
        respond(HttpStatus.NOT_FOUND, ex.message, request)

    @ExceptionHandler(ForbiddenException::class)
    fun handleForbidden(ex: ForbiddenException, request: HttpServletRequest): ResponseEntity<ApiError> =
        respond(HttpStatus.FORBIDDEN, ex.message, request)

    @ExceptionHandler(BusinessRuleException::class)
    fun handleBusinessRule(ex: BusinessRuleException, request: HttpServletRequest): ResponseEntity<ApiError> =
        respond(HttpStatus.BAD_REQUEST, ex.message, request)

    @ExceptionHandler(ConflictException::class)
    fun handleConflict(ex: ConflictException, request: HttpServletRequest): ResponseEntity<ApiError> =
        respond(HttpStatus.CONFLICT, ex.message, request)

    @ExceptionHandler(UnauthorizedException::class)
    fun handleUnauthorized(ex: UnauthorizedException, request: HttpServletRequest): ResponseEntity<ApiError> =
        respond(HttpStatus.UNAUTHORIZED, ex.message, request)

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(ex: MethodArgumentNotValidException, request: HttpServletRequest): ResponseEntity<ApiError> {
        val fieldErrors = ex.bindingResult.fieldErrors.map { FieldError(it.field, it.defaultMessage) }
        val body = ApiError(
            status = HttpStatus.BAD_REQUEST.value(),
            error = HttpStatus.BAD_REQUEST.reasonPhrase,
            message = "La solicitud contiene datos inválidos.",
            path = request.requestURI,
            fieldErrors = fieldErrors,
        )
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body)
    }

    private fun respond(status: HttpStatus, message: String?, request: HttpServletRequest): ResponseEntity<ApiError> {
        val body = ApiError(
            status = status.value(),
            error = status.reasonPhrase,
            message = message,
            path = request.requestURI,
        )
        return ResponseEntity.status(status).body(body)
    }
}
