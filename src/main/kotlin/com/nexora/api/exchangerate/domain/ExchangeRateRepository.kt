package com.nexora.api.exchangerate.domain

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.math.BigDecimal
import java.util.UUID

interface ExchangeRateRepository : JpaRepository<ExchangeRate, UUID> {
    fun findByCurrency(currency: String): ExchangeRate?

    /**
     * INSERT/UPDATE atómico. [ExchangeRateService.rateToBase] hace un
     * findByCurrency y, si no hay nada (o está obsoleto), consulta la
     * fuente externa y guarda el resultado — pero entre esa lectura y este
     * guardado, otra petición para la misma moneda nunca antes cacheada
     * pudo haber ganado la carrera. Con `save()` (INSERT si el id es
     * nuevo) eso sería una violación del unique de currency; con
     * `ON CONFLICT DO UPDATE` es un solo statement atómico que no falla.
     */
    @Modifying
    @Query(
        value = """
            INSERT INTO exchange_rates (id, currency, rate_to_base, created_at, updated_at)
            VALUES (gen_random_uuid(), :currency, :rate, now(), now())
            ON CONFLICT (currency) DO UPDATE SET rate_to_base = :rate, updated_at = now()
        """,
        nativeQuery = true,
    )
    fun upsert(@Param("currency") currency: String, @Param("rate") rate: BigDecimal)
}
