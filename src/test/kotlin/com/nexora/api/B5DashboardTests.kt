package com.nexora.api

import com.jayway.jsonpath.JsonPath
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import com.nexora.api.support.registerAndAuthenticate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.RequestPostProcessor
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.assertEquals

/**
 * Pruebas de integración de B5 (dashboard) de punta a punta: HTTP ->
 * seguridad -> servicio -> Postgres (Testcontainers).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class B5DashboardTests {

    @Autowired
    lateinit var mockMvc: MockMvc

    private val today: LocalDate = LocalDate.now()

    @Test
    fun `dashboard vacio para un usuario sin cuentas`() {
        val auth = registerAndAuth("vacio")

        val response = mockMvc.perform(get("/api/v1/dashboard").with(auth))
            .andExpect(status().isOk).andReturn().response.contentAsString

        assertMoneyEquals("0", JsonPath.read<Any>(response, "$.availableBalance").toString())
        assertMoneyEquals("0", JsonPath.read<Any>(response, "$.netWorth").toString())
        assertEquals(0, JsonPath.read<Int>(response, "$.recentTransactions.length()"))
        assertEquals(0, JsonPath.read<Int>(response, "$.upcomingPayments.length()"))
        assertEquals(6, JsonPath.read<Int>(response, "$.netWorthEvolution.length()"))
    }

    @Test
    fun `ingresos y gastos del mes se reflejan en el resumen y por categoria`() {
        val auth = registerAndAuth("resumen")
        val accountId = createAccount(auth, "Débito", "5000")
        val salaryCategory = createCategory(auth, "Nómina", "INCOME")
        val foodCategory = createCategory(auth, "Comida", "EXPENSE")

        recordTransaction(auth, "INCOME", accountId, "3000", salaryCategory)
        recordTransaction(auth, "EXPENSE", accountId, "800", foodCategory)
        recordTransaction(auth, "EXPENSE", accountId, "200", foodCategory)

        val response = mockMvc.perform(get("/api/v1/dashboard").with(auth))
            .andExpect(status().isOk).andReturn().response.contentAsString

        assertMoneyEquals("3000", JsonPath.read<Any>(response, "$.incomeThisMonth").toString())
        assertMoneyEquals("1000", JsonPath.read<Any>(response, "$.expenseThisMonth").toString())
        assertMoneyEquals("2000", JsonPath.read<Any>(response, "$.monthlyBalance").toString())

        assertEquals(1, JsonPath.read<Int>(response, "$.expensesByCategory.length()"))
        assertEquals("Comida", JsonPath.read(response, "$.expensesByCategory[0].categoryName"))
        assertMoneyEquals("1000", JsonPath.read<Any>(response, "$.expensesByCategory[0].amount").toString())

        assertEquals(1, JsonPath.read<Int>(response, "$.incomeByCategory.length()"))
        assertEquals("Nómina", JsonPath.read(response, "$.incomeByCategory[0].categoryName"))

        // Invariantes: el último punto de cada evolución (mes actual) debe coincidir con el resumen.
        assertMoneyEquals(
            "1000",
            JsonPath.read<Any>(response, "$.expenseEvolution[5].amount").toString(),
        )
        assertMoneyEquals(
            JsonPath.read<Any>(response, "$.netWorth").toString(),
            JsonPath.read<Any>(response, "$.netWorthEvolution[5].amount").toString(),
        )
    }

    @Test
    fun `deuda de tarjetas, credito disponible y proximo pago se reflejan en el dashboard`() {
        val auth = registerAndAuth("tarjetadash")
        val cardId = createCreditCard(auth)
        mockMvc.perform(
            post("/api/v1/credit-cards/$cardId/purchases")
                .with(auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"amount":5000,"date":"$today","merchant":"Amazon"}""")
        ).andExpect(status().isCreated)

        val response = mockMvc.perform(get("/api/v1/dashboard").with(auth))
            .andExpect(status().isOk).andReturn().response.contentAsString

        assertMoneyEquals("5000", JsonPath.read<Any>(response, "$.creditCardDebt").toString())
        assertMoneyEquals("45000", JsonPath.read<Any>(response, "$.availableCredit").toString())
        assertEquals(1, JsonPath.read<Int>(response, "$.upcomingPayments.length()"))
        assertEquals("BBVA Azul", JsonPath.read(response, "$.upcomingPayments[0].creditCardName"))
        assertMoneyEquals("5000", JsonPath.read<Any>(response, "$.upcomingPayments[0].expectedPayment").toString())
    }

    @Test
    fun `el proximo pago de una compra a MSI es solo la cuota del corte actual, no la deuda total`() {
        val auth = registerAndAuth("proximopagomsi")
        val cardId = createCreditCard(auth)
        mockMvc.perform(
            post("/api/v1/credit-cards/$cardId/installment-plans")
                .with(auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"amount":12000,"date":"$today","merchant":"Liverpool","installmentCount":12,"interestRate":0}""")
        ).andExpect(status().isCreated)

        val response = mockMvc.perform(get("/api/v1/dashboard").with(auth))
            .andExpect(status().isOk).andReturn().response.contentAsString

        // La compra se registra por el total ($12,000) desde el día 1 (currentDebt/creditCardDebt
        // sí reflejan eso), pero el próximo pago es solo la cuota que corresponde a este corte.
        assertMoneyEquals("12000", JsonPath.read<Any>(response, "$.creditCardDebt").toString())
        assertEquals(1, JsonPath.read<Int>(response, "$.upcomingPayments.length()"))
        assertMoneyEquals("1000", JsonPath.read<Any>(response, "$.upcomingPayments[0].expectedPayment").toString())
    }

    @Test
    fun `el proximo pago suma una compra normal y la cuota del corte de un plan MSI en la misma tarjeta`() {
        val auth = registerAndAuth("proximopagomixto")
        val cardId = createCreditCard(auth)
        mockMvc.perform(
            post("/api/v1/credit-cards/$cardId/purchases")
                .with(auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"amount":500,"date":"$today","merchant":"OXXO"}""")
        ).andExpect(status().isCreated)
        mockMvc.perform(
            post("/api/v1/credit-cards/$cardId/installment-plans")
                .with(auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"amount":12000,"date":"$today","merchant":"Liverpool","installmentCount":12,"interestRate":0}""")
        ).andExpect(status().isCreated)

        val response = mockMvc.perform(get("/api/v1/dashboard").with(auth))
            .andExpect(status().isOk).andReturn().response.contentAsString

        assertMoneyEquals("12500", JsonPath.read<Any>(response, "$.creditCardDebt").toString())
        assertMoneyEquals("1500", JsonPath.read<Any>(response, "$.upcomingPayments[0].expectedPayment").toString())
    }

    @Test
    fun `un plan MSI activo se refleja en el compromiso mensual`() {
        val auth = registerAndAuth("msidash")
        val cardId = createCreditCard(auth)
        mockMvc.perform(
            post("/api/v1/credit-cards/$cardId/installment-plans")
                .with(auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"amount":6000,"date":"$today","merchant":"Liverpool","installmentCount":6,"interestRate":0}""")
        ).andExpect(status().isCreated)

        val response = mockMvc.perform(get("/api/v1/dashboard").with(auth))
            .andExpect(status().isOk).andReturn().response.contentAsString

        assertEquals(1, JsonPath.read<Int>(response, "$.activeMsiPlansCount"))
        assertMoneyEquals("1000", JsonPath.read<Any>(response, "$.monthlyInstallmentCommitment").toString())
    }

    @Test
    fun `recentTransactionsLimit limita el numero de movimientos recientes`() {
        val auth = registerAndAuth("recientes")
        val accountId = createAccount(auth, "Débito", "10000")
        repeat(5) { recordTransaction(auth, "EXPENSE", accountId, "10", null) }

        val response = mockMvc.perform(
            get("/api/v1/dashboard").with(auth).param("recentTransactionsLimit", "3")
        ).andExpect(status().isOk).andReturn().response.contentAsString

        assertEquals(3, JsonPath.read<Int>(response, "$.recentTransactions.length()"))
    }

    @Test
    fun `endpoint protegido exige autenticacion`() {
        mockMvc.perform(get("/api/v1/dashboard")).andExpect(status().isUnauthorized)
    }

    // --- helpers ---

    private fun registerAndAuth(prefix: String): RequestPostProcessor = mockMvc.registerAndAuthenticate(prefix)

    private fun createAccount(auth: RequestPostProcessor, name: String, openingBalance: String): String {
        val response = mockMvc.perform(
            post("/api/v1/accounts")
                .with(auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"$name","type":"DEBIT","currency":"MXN","openingBalance":$openingBalance}""")
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        return JsonPath.read(response, "$.id")
    }

    private fun createCategory(auth: RequestPostProcessor, name: String, type: String): String {
        val response = mockMvc.perform(
            post("/api/v1/categories")
                .with(auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"$name","type":"$type"}""")
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        return JsonPath.read(response, "$.id")
    }

    private fun recordTransaction(auth: RequestPostProcessor, type: String, accountId: String, amount: String, categoryId: String?) {
        val categoryJson = categoryId?.let { ""","categoryId":"$it"""" } ?: ""
        mockMvc.perform(
            post("/api/v1/transactions")
                .with(auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"type":"$type","accountId":"$accountId","amount":$amount,"date":"$today"$categoryJson}""")
        ).andExpect(status().isCreated)
    }

    private fun createCreditCard(auth: RequestPostProcessor): String {
        val response = mockMvc.perform(
            post("/api/v1/credit-cards")
                .with(auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """{"name":"BBVA Azul","bank":"BBVA","last4":"1234","creditLimit":50000,"closingDay":15,"paymentDueDay":5,"currency":"MXN"}"""
                )
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        return JsonPath.read(response, "$.id")
    }

    private fun assertMoneyEquals(expected: String, actual: String) {
        assertEquals(0, BigDecimal(expected).compareTo(BigDecimal(actual)), "esperado $expected pero fue $actual")
    }

}
