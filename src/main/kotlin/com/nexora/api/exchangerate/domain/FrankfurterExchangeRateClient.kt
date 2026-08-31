package com.nexora.api.exchangerate.domain

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import java.math.BigDecimal
import java.net.http.HttpClient
import java.time.Duration

/** Espejo mínimo de la respuesta de Frankfurter, ej. {"amount":1,"base":"USD","date":"...","rates":{"MXN":17.5}}. */
private data class FrankfurterResponse(
    val rates: Map<String, Double> = emptyMap(),
)

/**
 * Fuente real de [ExchangeRateService]: Frankfurter (frankfurter.dev),
 * tipos de cambio de referencia del Banco Central Europeo — gratis, sin
 * API key. Solo actualiza en días hábiles (fines de semana repite el
 * último valor hábil), suficiente para una caché que igual refresca a lo
 * más una vez al día.
 *
 * No todas las monedas que un usuario podría teclear al crear una cuenta
 * están en su catálogo (~30, las que sigue el BCE): si no encuentra la
 * moneda pedida, o la llamada falla, devuelve null y deja que
 * [ExchangeRateService] decida el respaldo.
 */
@Component
class FrankfurterExchangeRateClient(
    @Value("\${nexora.exchange-rate.api-base-url}") baseUrl: String,
) : ExchangeRateClient {

    private val log = LoggerFactory.getLogger(FrankfurterExchangeRateClient::class.java)

    private val restClient = RestClient.builder()
        .baseUrl(baseUrl)
        .requestFactory(
            JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build())
                .apply { setReadTimeout(Duration.ofSeconds(5)) },
        )
        .build()

    override fun fetchRate(from: String, to: String): BigDecimal? {
        if (from == to) return BigDecimal.ONE
        return try {
            val response = restClient.get()
                .uri { it.path("/latest").queryParam("from", from).queryParam("to", to).build() }
                .retrieve()
                .body(FrankfurterResponse::class.java)
            val rate = response?.rates?.get(to)
            if (rate == null) {
                log.warn("Frankfurter no devolvió tipo de cambio de {} a {} (¿moneda no soportada?).", from, to)
                return null
            }
            BigDecimal.valueOf(rate)
        } catch (e: RestClientException) {
            log.warn("Error consultando el tipo de cambio de {} a {}: {}", from, to, e.message)
            null
        }
    }
}
