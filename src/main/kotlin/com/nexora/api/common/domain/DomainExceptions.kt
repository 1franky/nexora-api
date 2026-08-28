package com.nexora.api.common.domain

/** Recurso no encontrado (o no visible para el usuario actual). */
class NotFoundException(message: String) : RuntimeException(message)

/** Violación de una regla de negocio (ej. transferir a la misma cuenta). */
class BusinessRuleException(message: String) : RuntimeException(message)

/** El usuario autenticado intenta operar sobre un recurso que no le pertenece. */
class ForbiddenException(message: String) : RuntimeException(message)

/** Ya existe un recurso con ese identificador único (ej. email duplicado). */
class ConflictException(message: String) : RuntimeException(message)

/** Credenciales o token inválido/expirado (login, refresh). */
class UnauthorizedException(message: String) : RuntimeException(message)
