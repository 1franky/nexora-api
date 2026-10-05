package com.nexora.api.scheduledcharge.domain

import com.nexora.api.account.domain.Account
import com.nexora.api.account.domain.AccountService
import com.nexora.api.account.domain.AccountStatus
import com.nexora.api.account.domain.AccountType
import com.nexora.api.audit.domain.AuditEventType
import com.nexora.api.audit.domain.AuditLogService
import com.nexora.api.category.domain.CategoryService
import com.nexora.api.category.domain.CategoryStatus
import com.nexora.api.category.domain.CategoryType
import com.nexora.api.common.domain.BusinessRuleException
import com.nexora.api.common.domain.NotFoundException
import com.nexora.api.notification.domain.Notification
import com.nexora.api.notification.domain.NotificationRepository
import com.nexora.api.notification.domain.NotificationType
import com.nexora.api.transaction.domain.TransactionService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** Datos editables de un cargo programado, comunes a alta y edición. */
data class ScheduledChargeInput(
    val accountId: UUID,
    val name: String,
    val amount: BigDecimal,
    val categoryId: UUID?,
    val description: String?,
    val frequency: ScheduledChargeFrequency,
    val dayOfMonth: Int,
    val monthOfYear: Int?,
    val startDate: LocalDate,
    val endDate: LocalDate?,
)

/** Cargo + su cuenta, para que la respuesta incluya nombre/tipo/moneda sin otra consulta del cliente. */
data class ScheduledChargeView(val charge: ScheduledCharge, val account: Account)

/**
 * Cargos programados (B14, plan-cargos-programados.md). Las reglas de
 * negocio (cursor, atrasados, pausa, edición solo hacia adelante) están
 * descritas en la sección 2 del plan.
 */
@Service
class ScheduledChargeService(
    private val scheduledChargeRepository: ScheduledChargeRepository,
    private val transactionService: TransactionService,
    private val accountService: AccountService,
    private val categoryService: CategoryService,
    private val notificationRepository: NotificationRepository,
    private val auditLogService: AuditLogService,
    transactionManager: PlatformTransactionManager,
) {

    private val log = LoggerFactory.getLogger(ScheduledChargeService::class.java)
    private val transactionTemplate = TransactionTemplate(transactionManager)

    /**
     * Da de alta el cargo y registra en la misma request las ocurrencias ya
     * vencidas (fecha de inicio pasada), para que el usuario las vea al
     * instante sin esperar al job diario.
     */
    @Transactional
    fun create(userId: UUID, input: ScheduledChargeInput, today: LocalDate = today()): ScheduledChargeView {
        val account = validate(userId, input)
        val charge = ScheduledCharge(
            userId = userId,
            accountId = input.accountId,
            name = input.name.trim(),
            amount = input.amount,
            categoryId = input.categoryId,
            description = input.description?.trim()?.ifEmpty { null },
            frequency = input.frequency,
            dayOfMonth = input.dayOfMonth,
            monthOfYear = input.monthOfYear,
            startDate = input.startDate,
            endDate = input.endDate,
        )
        charge.nextRunDate = charge.firstOccurrenceOnOrAfter(input.startDate)
        if (input.endDate != null && charge.nextRunDate!! > input.endDate) {
            throw BusinessRuleException("No hay ningún cargo entre la fecha de inicio y la fecha de fin.")
        }
        val saved = scheduledChargeRepository.save(charge)
        auditLogService.record(
            userId, AuditEventType.SCHEDULED_CHARGE_CREATED, "ScheduledCharge", requireNotNull(saved.id),
            "Cargo programado '${saved.name}' de \$${money(saved.amount)} en '${account.name}'.",
        )
        postDueOccurrences(saved, today, notify = false)
        return ScheduledChargeView(saved, account)
    }

    /**
     * Edita el cargo solo hacia adelante: los movimientos ya generados no se
     * tocan. Si cambia la regla de fechas, el próximo cargo se recalcula desde
     * el día siguiente al último registrado — pero nunca antes de hoy, para
     * no recuperar ocurrencias de un periodo en que estuvo pausado.
     */
    @Transactional
    fun update(userId: UUID, id: UUID, input: ScheduledChargeInput, today: LocalDate = today()): ScheduledChargeView {
        val charge = getOwnedForUpdate(userId, id)
        if (charge.status == ScheduledChargeStatus.FINISHED || charge.status == ScheduledChargeStatus.CANCELLED) {
            throw BusinessRuleException("Un cargo programado ${statusLabel(charge.status)} ya no se puede editar.")
        }
        if (charge.lastRunDate != null && input.startDate != charge.startDate) {
            throw BusinessRuleException("La fecha de inicio no se puede cambiar porque este cargo ya generó movimientos.")
        }
        val account = validate(userId, input)

        val scheduleChanged = charge.frequency != input.frequency ||
            charge.dayOfMonth != input.dayOfMonth ||
            charge.monthOfYear != input.monthOfYear ||
            charge.startDate != input.startDate

        charge.accountId = input.accountId
        charge.name = input.name.trim()
        charge.amount = input.amount
        charge.categoryId = input.categoryId
        charge.description = input.description?.trim()?.ifEmpty { null }
        charge.frequency = input.frequency
        charge.dayOfMonth = input.dayOfMonth
        charge.monthOfYear = input.monthOfYear
        charge.startDate = input.startDate
        charge.endDate = input.endDate

        if (scheduleChanged) {
            val from = charge.lastRunDate?.let { maxOf(it.plusDays(1), today) } ?: input.startDate
            charge.nextRunDate = charge.firstOccurrenceOnOrAfter(from)
        }
        finishIfPastEnd(charge)

        val saved = scheduledChargeRepository.save(charge)
        auditLogService.record(
            userId, AuditEventType.SCHEDULED_CHARGE_UPDATED, "ScheduledCharge", requireNotNull(saved.id),
            "Cargo programado '${saved.name}' editado: monto actual \$${money(saved.amount)}.",
        )
        if (saved.status == ScheduledChargeStatus.ACTIVE) postDueOccurrences(saved, today, notify = false)
        return ScheduledChargeView(saved, account)
    }

    @Transactional
    fun pause(userId: UUID, id: UUID): ScheduledChargeView {
        val charge = getOwnedForUpdate(userId, id)
        if (charge.status != ScheduledChargeStatus.ACTIVE) {
            throw BusinessRuleException("Solo se puede pausar un cargo programado activo.")
        }
        charge.status = ScheduledChargeStatus.PAUSED
        return view(userId, scheduledChargeRepository.save(charge))
    }

    /**
     * Reanuda desde hoy: lo que habría tocado mientras estuvo pausado no se
     * registra (pausar significa "no me cobraron"). Limpia [ScheduledCharge.lastError].
     */
    @Transactional
    fun resume(userId: UUID, id: UUID, today: LocalDate = today()): ScheduledChargeView {
        val charge = getOwnedForUpdate(userId, id)
        if (charge.status != ScheduledChargeStatus.PAUSED) {
            throw BusinessRuleException("Solo se puede reanudar un cargo programado pausado.")
        }
        validationError(charge)?.let { throw BusinessRuleException(it) }

        val from = listOfNotNull(today, charge.startDate, charge.lastRunDate?.plusDays(1)).max()
        charge.nextRunDate = charge.firstOccurrenceOnOrAfter(from)
        charge.status = ScheduledChargeStatus.ACTIVE
        charge.lastError = null
        finishIfPastEnd(charge)
        val saved = scheduledChargeRepository.save(charge)
        if (saved.status == ScheduledChargeStatus.ACTIVE) postDueOccurrences(saved, today, notify = false)
        return view(userId, saved)
    }

    /** Baja lógica: deja de generar, pero los movimientos ya registrados se conservan. */
    @Transactional
    fun cancel(userId: UUID, id: UUID) {
        val charge = getOwnedForUpdate(userId, id)
        if (charge.status == ScheduledChargeStatus.CANCELLED) return
        charge.status = ScheduledChargeStatus.CANCELLED
        charge.nextRunDate = null
        scheduledChargeRepository.save(charge)
        auditLogService.record(
            userId, AuditEventType.SCHEDULED_CHARGE_CANCELLED, "ScheduledCharge", requireNotNull(charge.id),
            "Cargo programado '${charge.name}' cancelado.",
        )
    }

    fun getOwned(userId: UUID, id: UUID): ScheduledChargeView {
        val charge = scheduledChargeRepository.findByIdAndUserId(id, userId)
            ?: throw NotFoundException("Cargo programado no encontrado.")
        return view(userId, charge)
    }

    /**
     * Activos y pausados primero, por próximo cargo; luego terminados y
     * cancelados. [accountId] es opcional (filtra a una cuenta, validando propiedad).
     */
    fun listForUser(userId: UUID, accountId: UUID?): List<ScheduledChargeView> {
        val charges = if (accountId != null) {
            accountService.getOwned(userId, accountId)
            scheduledChargeRepository.findAllByUserIdAndAccountId(userId, accountId)
        } else {
            scheduledChargeRepository.findAllByUserId(userId)
        }
        val accountsById = accountService.listForUser(userId).associateBy { it.id }
        return charges
            .sortedWith(
                compareBy<ScheduledCharge>(
                    { it.status == ScheduledChargeStatus.FINISHED || it.status == ScheduledChargeStatus.CANCELLED },
                    { it.nextRunDate ?: LocalDate.MAX },
                    { it.name.lowercase() },
                )
            )
            .map { ScheduledChargeView(it, requireNotNull(accountsById[it.accountId])) }
    }

    /**
     * Job diario ([ScheduledChargeScheduler]): registra todo lo vencido a
     * [today]. Cada cargo va en su propia transacción — si uno falla de
     * forma inesperada, se revierte solo ese y se reintenta al día siguiente.
     */
    fun processDue(today: LocalDate = today()) {
        val dueIds = scheduledChargeRepository.findDueIds(today)
        if (dueIds.isEmpty()) return
        log.info("Procesando {} cargo(s) programado(s) vencidos a {}", dueIds.size, today)
        for (id in dueIds) {
            try {
                transactionTemplate.executeWithoutResult {
                    val charge = scheduledChargeRepository.findByIdForUpdate(id) ?: return@executeWithoutResult
                    if (charge.status == ScheduledChargeStatus.ACTIVE) postDueOccurrences(charge, today, notify = true)
                }
            } catch (ex: Exception) {
                log.warn("No se pudo procesar el cargo programado {}", id, ex)
            }
        }
    }

    /**
     * Avanza el cursor registrando cada ocurrencia vencida. Valida cuenta y
     * categoría *antes* de llamar a [TransactionService]: si estas fallaran
     * dentro de su @Transactional, la transacción entera quedaría marcada
     * para rollback aunque atrapáramos la excepción — y lo que queremos es
     * pausar el cargo y avisar, no perder ese cambio de estado.
     */
    private fun postDueOccurrences(charge: ScheduledCharge, today: LocalDate, notify: Boolean) {
        val posted = mutableListOf<LocalDate>()
        while (charge.status == ScheduledChargeStatus.ACTIVE) {
            val date = charge.nextRunDate ?: break
            if (date > today) break
            if (charge.endDate != null && date > charge.endDate!!) break

            val error = validationError(charge)
            if (error != null) {
                charge.status = ScheduledChargeStatus.PAUSED
                charge.lastError = error
                notify(
                    charge, NotificationType.SCHEDULED_CHARGE_FAILED, today, "Cargo programado pausado",
                    "No se pudo registrar el cargo de ${charge.name} del $date: $error Revísalo y reanúdalo.",
                )
                break
            }
            record(charge, date)
            posted += date
            charge.lastRunDate = date
            charge.nextRunDate = charge.occurrenceAfter(date)
        }
        finishIfPastEnd(charge)
        scheduledChargeRepository.save(charge)

        if (notify && posted.isNotEmpty()) {
            val accountName = accountService.getOwned(charge.userId, charge.accountId).name
            val message = if (posted.size == 1) {
                "Se registró el cargo de ${charge.name} por \$${money(charge.amount)} en $accountName."
            } else {
                "Se registraron ${posted.size} cargos de ${charge.name} (\$${money(charge.amount)} c/u) en $accountName."
            }
            notify(charge, NotificationType.SCHEDULED_CHARGE_POSTED, today, "Cargo programado registrado", message)
        }
    }

    private fun record(charge: ScheduledCharge, date: LocalDate) {
        val account = accountService.getOwned(charge.userId, charge.accountId)
        if (account.type == AccountType.CREDIT_CARD) {
            transactionService.recordCreditCardPurchase(
                userId = charge.userId,
                cardAccountId = charge.accountId,
                amount = charge.amount,
                date = date,
                merchant = charge.name,
                categoryId = charge.categoryId,
                description = charge.description,
                reference = null,
                scheduledChargeId = charge.id,
            )
        } else {
            transactionService.recordExpense(
                userId = charge.userId,
                accountId = charge.accountId,
                amount = charge.amount,
                date = date,
                categoryId = charge.categoryId,
                description = charge.description ?: charge.name,
                reference = null,
                scheduledChargeId = charge.id,
            )
        }
    }

    private fun finishIfPastEnd(charge: ScheduledCharge) {
        val next = charge.nextRunDate ?: return
        val end = charge.endDate ?: return
        if (next > end) {
            charge.status = ScheduledChargeStatus.FINISHED
            charge.nextRunDate = null
        }
    }

    /** Validación de alta/edición: lanza [BusinessRuleException] con un mensaje legible. */
    private fun validate(userId: UUID, input: ScheduledChargeInput): Account {
        if (input.name.isBlank()) throw BusinessRuleException("El nombre es obligatorio.")
        if (input.amount <= BigDecimal.ZERO) throw BusinessRuleException("El monto debe ser mayor a cero.")
        if (input.dayOfMonth !in 1..31) throw BusinessRuleException("El día debe estar entre 1 y 31.")
        when (input.frequency) {
            ScheduledChargeFrequency.MONTHLY -> if (input.monthOfYear != null) {
                throw BusinessRuleException("El mes solo aplica a cargos anuales.")
            }
            ScheduledChargeFrequency.YEARLY -> if (input.monthOfYear == null || input.monthOfYear !in 1..12) {
                throw BusinessRuleException("Un cargo anual necesita el mes (1 a 12).")
            }
        }
        if (input.endDate != null && input.endDate < input.startDate) {
            throw BusinessRuleException("La fecha de fin no puede ser anterior a la fecha de inicio.")
        }
        val account = accountService.getOwned(userId, input.accountId)
        accountError(account)?.let { throw BusinessRuleException(it) }
        input.categoryId?.let { categoryError(userId, it) }?.let { throw BusinessRuleException(it) }
        return account
    }

    /** La misma validación de cuenta/categoría, pero contra el estado actual del cargo (job y reanudar). */
    private fun validationError(charge: ScheduledCharge): String? =
        accountError(accountService.getOwned(charge.userId, charge.accountId))
            ?: charge.categoryId?.let { categoryError(charge.userId, it) }

    private fun accountError(account: Account): String? = when {
        account.status != AccountStatus.ACTIVE -> "La cuenta '${account.name}' no está activa."
        account.type == AccountType.AFORE || account.type == AccountType.PPR ->
            "No se pueden programar cargos a una cuenta de tipo ${account.type}."
        else -> null
    }

    private fun categoryError(userId: UUID, categoryId: UUID): String? {
        val category = categoryService.getOwned(userId, categoryId)
        return when {
            category.type != CategoryType.EXPENSE -> "La categoría '${category.name}' debe ser de tipo EXPENSE."
            category.status != CategoryStatus.ACTIVE -> "La categoría '${category.name}' está archivada."
            else -> null
        }
    }

    private fun getOwnedForUpdate(userId: UUID, id: UUID): ScheduledCharge =
        scheduledChargeRepository.findByIdForUpdate(id)?.takeIf { it.userId == userId }
            ?: throw NotFoundException("Cargo programado no encontrado.")

    private fun view(userId: UUID, charge: ScheduledCharge): ScheduledChargeView =
        ScheduledChargeView(charge, accountService.getOwned(userId, charge.accountId))

    private fun notify(charge: ScheduledCharge, type: NotificationType, today: LocalDate, title: String, message: String) {
        notificationRepository.save(
            Notification(
                userId = charge.userId, type = type, title = title, message = message,
                relatedEntityId = charge.id, forDate = today,
            )
        )
    }

    private fun statusLabel(status: ScheduledChargeStatus): String = when (status) {
        ScheduledChargeStatus.ACTIVE -> "activo"
        ScheduledChargeStatus.PAUSED -> "pausado"
        ScheduledChargeStatus.FINISHED -> "terminado"
        ScheduledChargeStatus.CANCELLED -> "cancelado"
    }

    private fun money(amount: BigDecimal): String = amount.setScale(2, RoundingMode.HALF_UP).toPlainString()

    companion object {
        /**
         * "Hoy" en hora de México, no en la zona del servidor: el VPS corre en
         * UTC, y a las 18:00 de CDMX ya sería "mañana" — el cargo del día
         * siguiente se registraría con 6 horas de anticipación.
         */
        val ZONE: ZoneId = ZoneId.of("America/Mexico_City")

        fun today(): LocalDate = LocalDate.now(ZONE)
    }
}
