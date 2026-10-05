package com.nexora.api.scheduledcharge.web

import com.nexora.api.account.domain.AccountType
import com.nexora.api.scheduledcharge.domain.ScheduledChargeFrequency
import com.nexora.api.scheduledcharge.domain.ScheduledChargeInput
import com.nexora.api.scheduledcharge.domain.ScheduledChargeStatus
import com.nexora.api.scheduledcharge.domain.ScheduledChargeView
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@Schema(description = "Alta y edición usan el mismo cuerpo. La edición solo aplica hacia adelante.")
data class ScheduledChargeRequest(
    @field:NotNull(message = "La cuenta es obligatoria.")
    val accountId: UUID,

    @field:NotBlank(message = "El nombre es obligatorio.")
    @field:Size(max = 120, message = "El nombre no puede pasar de 120 caracteres.")
    val name: String,

    @field:NotNull(message = "El monto es obligatorio.")
    @field:DecimalMin(value = "0.0", inclusive = false, message = "El monto debe ser mayor a cero.")
    val amount: BigDecimal,

    val categoryId: UUID? = null,

    @field:Size(max = 500, message = "La descripción no puede pasar de 500 caracteres.")
    val description: String? = null,

    @field:NotNull(message = "La frecuencia es obligatoria.")
    val frequency: ScheduledChargeFrequency,

    @field:NotNull(message = "El día es obligatorio.")
    @field:Min(value = 1, message = "El día debe estar entre 1 y 31.")
    @field:Max(value = 31, message = "El día debe estar entre 1 y 31.")
    @field:Schema(description = "Si el mes no tiene ese día, el cargo cae en el último día del mes.")
    val dayOfMonth: Int,

    @field:Min(value = 1, message = "El mes debe estar entre 1 y 12.")
    @field:Max(value = 12, message = "El mes debe estar entre 1 y 12.")
    @field:Schema(description = "Obligatorio si frequency = YEARLY; null si MONTHLY.")
    val monthOfYear: Int? = null,

    @field:NotNull(message = "La fecha de inicio es obligatoria.")
    @field:Schema(description = "Si es pasada, al crear se registran de inmediato los cargos atrasados.")
    val startDate: LocalDate,

    @field:Schema(description = "Inclusive. null = sin fecha de fin.")
    val endDate: LocalDate? = null,
) {
    fun toInput() = ScheduledChargeInput(
        accountId = accountId,
        name = name,
        amount = amount,
        categoryId = categoryId,
        description = description,
        frequency = frequency,
        dayOfMonth = dayOfMonth,
        monthOfYear = monthOfYear,
        startDate = startDate,
        endDate = endDate,
    )
}

data class ScheduledChargeResponse(
    val id: UUID,
    val accountId: UUID,
    val accountName: String,
    val accountType: AccountType,
    val currency: String,
    val name: String,
    val amount: BigDecimal,
    val categoryId: UUID?,
    val description: String?,
    val frequency: ScheduledChargeFrequency,
    val dayOfMonth: Int,
    val monthOfYear: Int?,
    val startDate: LocalDate,
    val endDate: LocalDate?,
    @field:Schema(description = "Próximo cargo; null si está FINISHED o CANCELLED.")
    val nextRunDate: LocalDate?,
    val lastRunDate: LocalDate?,
    val status: ScheduledChargeStatus,
    @field:Schema(description = "Por qué se pausó automáticamente (p. ej. categoría archivada). Se limpia al reanudar.")
    val lastError: String?,
    val createdAt: Instant,
) {
    companion object {
        fun from(view: ScheduledChargeView): ScheduledChargeResponse {
            val charge = view.charge
            return ScheduledChargeResponse(
                id = requireNotNull(charge.id),
                accountId = charge.accountId,
                accountName = view.account.name,
                accountType = view.account.type,
                currency = view.account.currency,
                name = charge.name,
                amount = charge.amount,
                categoryId = charge.categoryId,
                description = charge.description,
                frequency = charge.frequency,
                dayOfMonth = charge.dayOfMonth,
                monthOfYear = charge.monthOfYear,
                startDate = charge.startDate,
                endDate = charge.endDate,
                nextRunDate = charge.nextRunDate,
                lastRunDate = charge.lastRunDate,
                status = charge.status,
                lastError = charge.lastError,
                createdAt = requireNotNull(charge.createdAt),
            )
        }
    }
}
