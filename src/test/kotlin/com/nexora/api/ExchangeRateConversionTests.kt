package com.nexora.api

import com.jayway.jsonpath.JsonPath
import com.nexora.api.exchangerate.domain.ExchangeRate
import com.nexora.api.exchangerate.domain.ExchangeRateRepository
import com.nexora.api.support.registerAndAuthenticate
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.RequestPostProcessor
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.math.BigDecimal
import kotlin.test.assertEquals

/**
 * Pruebas de integración de la conversión de moneda al agregar disponible/
 * patrimonio (nota #4 del backlog: antes se sumaban las cuentas tal cual,
 * como si todas fueran MXN). El entorno de test apunta
 * NEXORA_EXCHANGE_RATE_API_BASE_URL a un puerto sin nada escuchando (ver
 * build.gradle.kts), así que la fuente externa siempre falla rápido y
 * determinista: para probar la conversión en sí se inserta el ExchangeRate
 * directamente vía el repositorio, simulando que ya se había cacheado con
 * éxito antes.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class ExchangeRateConversionTests {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var exchangeRateRepository: ExchangeRateRepository

    @Test
    fun `una cuenta en otra moneda se convierte a MXN antes de sumarse al disponible y patrimonio`() {
        val auth = registerAndAuth("multimoneda")
        exchangeRateRepository.save(ExchangeRate(currency = "USD", rateToBase = BigDecimal("17.50")))

        createAccount(auth, name = "Débito MXN", type = "DEBIT", currency = "MXN", openingBalance = "1000")
        createAccount(auth, name = "Débito USD", type = "DEBIT", currency = "USD", openingBalance = "100")

        val response = mockMvc.perform(get("/api/v1/accounts/summary").with(auth))
            .andExpect(status().isOk).andReturn().response.contentAsString

        // 1000 MXN + (100 USD * 17.50) = 1000 + 1750 = 2750, no 1000 + 100 = 1100 (el bug original).
        assertMoneyEquals("2750", JsonPath.read<Any>(response, "$.availableBalance").toString())
        assertMoneyEquals("2750", JsonPath.read<Any>(response, "$.netWorth").toString())
    }

    @Test
    fun `sin tipo de cambio en cache y con la fuente externa inalcanzable, usa 1 a 1 como respaldo temporal`() {
        val auth = registerAndAuth("sincache")
        // Ninguna moneda distinta de MXN tiene ExchangeRate guardado: el cliente real intenta
        // la fuente externa (inalcanzable en test), y ExchangeRateService cae a 1:1.
        createAccount(auth, name = "Débito EUR", type = "DEBIT", currency = "EUR", openingBalance = "50")

        val response = mockMvc.perform(get("/api/v1/accounts/summary").with(auth))
            .andExpect(status().isOk).andReturn().response.contentAsString

        assertMoneyEquals("50", JsonPath.read<Any>(response, "$.availableBalance").toString())
    }

    @Test
    fun `el patrimonio neto del dashboard tambien refleja la conversion`() {
        val auth = registerAndAuth("dashboardmultimoneda")
        // Moneda distinta a la de los otros tests de esta clase: exchange_rates no está
        // aislada por usuario/test (es una caché global), así que reusar "USD" chocaría
        // contra su unique constraint entre tests.
        exchangeRateRepository.save(ExchangeRate(currency = "CAD", rateToBase = BigDecimal("20")))
        createAccount(auth, name = "Ahorro CAD", type = "SAVINGS", currency = "CAD", openingBalance = "10")

        val response = mockMvc.perform(get("/api/v1/dashboard").with(auth))
            .andExpect(status().isOk).andReturn().response.contentAsString

        assertMoneyEquals("200", JsonPath.read<Any>(response, "$.netWorth").toString())
    }

    @Test
    fun `la deuda y el credito disponible del dashboard tambien se convierten a MXN`() {
        val auth = registerAndAuth("tarjetamultimoneda")
        // Moneda propia, distinta a la de los demás tests de esta clase (ver el comentario
        // sobre "CAD" arriba: exchange_rates es una caché global, no aislada por test).
        exchangeRateRepository.save(ExchangeRate(currency = "GBP", rateToBase = BigDecimal("18")))

        val cardId = createCreditCard(auth, creditLimit = "1000", currency = "GBP")
        mockMvc.perform(
            post("/api/v1/credit-cards/$cardId/purchases")
                .with(auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"amount":100,"date":"2026-08-28","merchant":"Amazon"}""")
        ).andExpect(status().isCreated)

        val response = mockMvc.perform(get("/api/v1/dashboard").with(auth))
            .andExpect(status().isOk).andReturn().response.contentAsString

        // Deuda: 100 GBP * 18 = 1800 MXN. Disponible: (1000 - 100) GBP * 18 = 16200 MXN.
        assertMoneyEquals("1800", JsonPath.read<Any>(response, "$.creditCardDebt").toString())
        assertMoneyEquals("16200", JsonPath.read<Any>(response, "$.availableCredit").toString())
    }

    // --- helpers ---

    private fun registerAndAuth(prefix: String): RequestPostProcessor = mockMvc.registerAndAuthenticate(prefix)

    private fun createAccount(auth: RequestPostProcessor, name: String, type: String, currency: String, openingBalance: String): String {
        val response = mockMvc.perform(
            post("/api/v1/accounts")
                .with(auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"$name","type":"$type","currency":"$currency","openingBalance":$openingBalance}""")
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        return JsonPath.read(response, "$.id")
    }

    private fun createCreditCard(auth: RequestPostProcessor, creditLimit: String, currency: String): String {
        val response = mockMvc.perform(
            post("/api/v1/credit-cards")
                .with(auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """{"name":"Tarjeta","bank":"Banco","last4":"1234","creditLimit":$creditLimit,"closingDay":15,"paymentDueDay":5,"currency":"$currency"}"""
                )
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        return JsonPath.read(response, "$.id")
    }

    private fun assertMoneyEquals(expected: String, actual: String) {
        assertEquals(0, BigDecimal(expected).compareTo(BigDecimal(actual)), "esperado $expected pero fue $actual")
    }
}
