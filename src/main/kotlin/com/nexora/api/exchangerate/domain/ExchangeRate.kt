package com.nexora.api.exchangerate.domain

import com.nexora.api.common.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.math.BigDecimal

/**
 * Tipo de cambio en caché de [currency] a la moneda base de la app (MXN,
 * ver [ExchangeRateService]). No se guarda historial, solo el último valor
 * conocido — [BaseEntity.updatedAt] es lo que [ExchangeRateService] usa
 * para decidir si ya está obsoleto y toca refrescarlo.
 */
@Entity
@Table(name = "exchange_rates")
class ExchangeRate(

    @Column(nullable = false, unique = true, length = 3)
    var currency: String,

    @Column(name = "rate_to_base", nullable = false, precision = 19, scale = 6)
    var rateToBase: BigDecimal,

) : BaseEntity()
