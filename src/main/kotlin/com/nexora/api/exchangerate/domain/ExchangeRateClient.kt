package com.nexora.api.exchangerate.domain

import java.math.BigDecimal

/**
 * Fuente externa de tipos de cambio (ver [ExchangeRateService], que la
 * cachea en BD). Implementación real: FrankfurterExchangeRateClient.
 */
fun interface ExchangeRateClient {
    /** `null` si la moneda no existe en la fuente o la llamada falla — [ExchangeRateService] decide el respaldo. */
    fun fetchRate(from: String, to: String): BigDecimal?
}
