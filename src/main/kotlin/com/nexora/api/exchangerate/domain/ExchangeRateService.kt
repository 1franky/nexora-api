package com.nexora.api.exchangerate.domain

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

/** Moneda a la que se agregan disponible/patrimonio (ver AccountService.getBalanceSummary, DashboardService). */
const val BASE_CURRENCY = "MXN"

private val STALE_AFTER: Duration = Duration.ofHours(24)

/**
 * Tipo de cambio de cualquier moneda a [BASE_CURRENCY], cacheado en BD
 * ([ExchangeRateRepository]) y refrescado perezosamente en cuanto se pide
 * uno con más de [STALE_AFTER] de antigüedad (o que nunca se había pedido)
 * — no hay un job aparte: cada lectura decide si hace falta refrescar,
 * mismo criterio que ya usa NotificationService para no depender de que un
 * scheduler haya corrido a tiempo.
 *
 * Si la fuente externa falla:
 * - Habiendo un valor en caché (aunque esté obsoleto), se usa ese.
 * - Si nunca se obtuvo ninguno, se usa 1:1 — el mismo comportamiento que
 *   tenía la app antes de esta corrección, pero ahora como último recurso
 *   temporal en vez de la regla general, y autocorregible en cuanto la
 *   fuente vuelva a responder.
 */
@Service
class ExchangeRateService(
    private val exchangeRateRepository: ExchangeRateRepository,
    private val exchangeRateClient: ExchangeRateClient,
) {

    private val log = LoggerFactory.getLogger(ExchangeRateService::class.java)

    @Transactional
    fun rateToBase(currency: String): BigDecimal {
        if (currency == BASE_CURRENCY) return BigDecimal.ONE

        val cached = exchangeRateRepository.findByCurrency(currency)
        val isFresh = cached != null && Duration.between(cached.updatedAt, Instant.now()) <= STALE_AFTER
        if (isFresh) return cached!!.rateToBase

        val fetched = exchangeRateClient.fetchRate(currency, BASE_CURRENCY)
        if (fetched == null) {
            if (cached != null) {
                log.warn("No se pudo refrescar el tipo de cambio de {}; se usa el último conocido ({}).", currency, cached.rateToBase)
                return cached.rateToBase
            }
            log.warn("No se pudo obtener el tipo de cambio de {} y no hay ninguno en caché; se usa 1:1 temporalmente.", currency)
            return BigDecimal.ONE
        }

        // upsert atómico (INSERT ... ON CONFLICT) en vez de save(): entre el findByCurrency
        // de arriba y este punto, otra petición para la misma moneda nunca antes cacheada
        // pudo haber ganado la carrera — con save() eso sería un IllegalStateException por
        // violar el unique de currency; con upsert simplemente se corrige al valor recién
        // obtenido (que en la práctica va a ser casi idéntico al de quien ganó la carrera).
        exchangeRateRepository.upsert(currency, fetched)
        return fetched
    }
}
