package com.nexora.api.creditcard.domain

import com.nexora.api.installment.domain.Installment
import com.nexora.api.installment.domain.InstallmentPlanService
import com.nexora.api.transaction.domain.TransactionRepository
import com.nexora.api.transaction.domain.TransactionType
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/** El pago de tarjeta que de verdad toca hacer a continuación: cuándo y cuánto. */
data class UpcomingCardPayment(
    val creditCardId: UUID,
    val creditCardName: String,
    val dueDate: LocalDate,
    val expectedPayment: BigDecimal,
)

/**
 * Fuente única de "cuál es el próximo pago de esta tarjeta" — la usan el
 * dashboard (próximos pagos), el detalle de la tarjeta (nextPaymentDueDate)
 * y las alertas de vencimiento (NotificationService).
 *
 * Antes solo el dashboard tenía esta lógica; el detalle y las alertas
 * usaban [CreditCardView.nextPaymentDueDate], que siempre mira el corte
 * *siguiente* e ignora el período de gracia: con corte 1 y pago 15, el
 * día 10 mostraba el pago del 15 del mes siguiente en vez del de este mes,
 * y como los días previos al vencimiento siempre caen en período de
 * gracia, la alerta PAYMENT_DUE/PAYMENT_DUE_SOON casi nunca se generaba.
 */
@Service
class CardPaymentScheduleService(
    private val creditCardService: CreditCardService,
    private val installmentPlanService: InstallmentPlanService,
    private val transactionRepository: TransactionRepository,
    private val billingCycleCalculator: BillingCycleCalculator,
) {

    /** Próximo pago de cada tarjeta del usuario, indexado por `creditCardId`. */
    fun upcomingPaymentsForUser(userId: UUID, today: LocalDate = LocalDate.now()): Map<UUID, UpcomingCardPayment> {
        val cards = creditCardService.listForUser(userId)
        if (cards.isEmpty()) return emptyMap()
        val pendingInstallmentsByCard = installmentPlanService.pendingInstallmentsByCard(userId)
        return cards.associate { card ->
            val cardId = requireNotNull(card.creditCard.id)
            cardId to upcomingPaymentFor(card, pendingInstallmentsByCard[cardId] ?: emptyList(), today)
        }
    }

    fun upcomingPaymentForCard(userId: UUID, card: CreditCardView, today: LocalDate = LocalDate.now()): UpcomingCardPayment {
        val installments = installmentPlanService.pendingInstallmentsByCard(userId)[card.creditCard.id] ?: emptyList()
        return upcomingPaymentFor(card, installments, today)
    }

    /**
     * currentDebt es la deuda total de la tarjeta (una compra a MSI/MCI se
     * registra por su monto completo desde el día 1, ver
     * InstallmentPlanService.create; una compra de contado normal también
     * se registra completa el día de la compra), no lo que toca pagar en
     * el próximo corte. Dos cosas quedan fuera de "próximo pago":
     * - Las cuotas MSI/MCI pendientes cuya fecha límite es posterior al
     *   pago que se está calculando (a una compra de $12,000 a 12 MSI
     *   solo le corresponde $1,000 este corte).
     * - Las compras de contado (sin plan) fechadas después del corte de
     *   este ciclo — ya pertenecen al ciclo siguiente, se facturan hasta
     *   el próximo corte de ese (con corte 27, una compra el día 29 no
     *   entra en el pago que vence este mes).
     *
     * Además, [billingCycleCalculator] decide *cuál* ciclo es el
     * relevante: si [today] cae en el período de gracia de un ciclo que ya
     * cerró (después de su corte, pero antes de su propia fecha límite de
     * pago), ese es el que urge — no el que cierra más adelante. Si ese
     * ciclo de gracia no tiene nada pendiente de verdad (ya se pagó, o es
     * una tarjeta nueva sin actividad en ese ciclo — todo lo debido es más
     * nuevo que su corte), se usa el ciclo que sigue en su lugar.
     */
    fun upcomingPaymentFor(card: CreditCardView, installments: List<Installment>, today: LocalDate = LocalDate.now()): UpcomingCardPayment {
        val cardId = requireNotNull(card.creditCard.id)
        val cardAccountId = requireNotNull(card.account.id)
        val closingDay = card.creditCard.closingDay
        val paymentDueDay = card.creditCard.paymentDueDay
        val cycle = billingCycleCalculator.currentPaymentCycle(closingDay, paymentDueDay, today)
        val forwardClosing = billingCycleCalculator.closingDateOnOrAfter(closingDay, today)
        val forwardDueDate = billingCycleCalculator.paymentDueDateFor(forwardClosing, closingDay, paymentDueDay)

        // El corte de gracia siempre es <= el corte hacia adelante — una sola query cubre ambos candidatos.
        val earliestClosing = minOf(cycle.closingDate, forwardClosing)
        val purchasesSinceEarliestClosing = transactionRepository
            .findAllByAccountIdAndTypeAndDateAfter(cardAccountId, TransactionType.CREDIT_CARD_PURCHASE, earliestClosing)
            .filterNot { installmentPlanService.isLinkedToPlan(requireNotNull(it.id)) }

        fun expectedAsOf(closingDate: LocalDate, paymentDueDate: LocalDate): BigDecimal {
            val futurePurchases = purchasesSinceEarliestClosing.filter { it.date > closingDate }.fold(BigDecimal.ZERO) { acc, t -> acc + t.amount }
            val futureInstallments = installments.filter { it.dueDate > paymentDueDate }.fold(BigDecimal.ZERO) { acc, i -> acc + i.amount }
            return (card.currentDebt - futurePurchases - futureInstallments).max(BigDecimal.ZERO)
        }

        if (cycle.isGracePeriod) {
            val graceExpected = expectedAsOf(cycle.closingDate, cycle.paymentDueDate)
            if (graceExpected > BigDecimal.ZERO) {
                return UpcomingCardPayment(cardId, card.creditCard.name, cycle.paymentDueDate, graceExpected)
            }
        }
        return UpcomingCardPayment(cardId, card.creditCard.name, forwardDueDate, expectedAsOf(forwardClosing, forwardDueDate))
    }
}
