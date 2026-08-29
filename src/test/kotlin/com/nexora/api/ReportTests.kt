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
 * Pruebas de integración de /api/v1/reports (W6 de nexora-web): reporte por
 * rango de fechas, filtrable por cuenta y tipo de movimiento.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class ReportTests {

    @Autowired
    lateinit var mockMvc: MockMvc

    private val today: LocalDate = LocalDate.now()

    @Test
    fun `reporte vacio para un usuario sin cuentas`() {
        val auth = registerAndAuth("reportevacio")

        val response = mockMvc.perform(get("/api/v1/reports").with(auth).param("from", "$today").param("to", "$today"))
            .andExpect(status().isOk).andReturn().response.contentAsString

        assertMoneyEquals("0", JsonPath.read<Any>(response, "$.totalIncome").toString())
        assertMoneyEquals("0", JsonPath.read<Any>(response, "$.totalExpense").toString())
        assertEquals(0, JsonPath.read<Int>(response, "$.transactions.length()"))
    }

    @Test
    fun `ingresos y gastos del rango se agregan por total y por categoria`() {
        val auth = registerAndAuth("reporterango")
        val accountId = createAccount(auth, "Débito", "5000")
        val salaryCategory = createCategory(auth, "Nómina", "INCOME")
        val foodCategory = createCategory(auth, "Comida", "EXPENSE")

        recordTransaction(auth, "INCOME", accountId, "3000", salaryCategory)
        recordTransaction(auth, "EXPENSE", accountId, "800", foodCategory)
        recordTransaction(auth, "EXPENSE", accountId, "200", foodCategory)

        val from = today.withDayOfMonth(1)
        val response = mockMvc.perform(
            get("/api/v1/reports").with(auth).param("from", "$from").param("to", "$today")
        ).andExpect(status().isOk).andReturn().response.contentAsString

        assertMoneyEquals("3000", JsonPath.read<Any>(response, "$.totalIncome").toString())
        assertMoneyEquals("1000", JsonPath.read<Any>(response, "$.totalExpense").toString())
        assertMoneyEquals("2000", JsonPath.read<Any>(response, "$.balance").toString())

        assertEquals(1, JsonPath.read<Int>(response, "$.expensesByCategory.length()"))
        assertEquals("Comida", JsonPath.read(response, "$.expensesByCategory[0].categoryName"))
        assertMoneyEquals("1000", JsonPath.read<Any>(response, "$.expensesByCategory[0].amount").toString())

        assertEquals(3, JsonPath.read<Int>(response, "$.transactions.length()"))

        val currentMonthIndex = JsonPath.read<List<String>>(response, "$.monthlyIncome[*].month").indexOf("$today".substring(0, 7))
        assertMoneyEquals(
            "3000",
            JsonPath.read<Any>(response, "$.monthlyIncome[$currentMonthIndex].amount").toString(),
        )
    }

    @Test
    fun `filtrar por tipo de movimiento acota el reporte`() {
        val auth = registerAndAuth("reportetipo")
        val accountId = createAccount(auth, "Débito", "5000")

        recordTransaction(auth, "INCOME", accountId, "1000", null)
        recordTransaction(auth, "EXPENSE", accountId, "400", null)

        val response = mockMvc.perform(
            get("/api/v1/reports").with(auth)
                .param("from", "$today").param("to", "$today")
                .param("type", "EXPENSE")
        ).andExpect(status().isOk).andReturn().response.contentAsString

        assertEquals(1, JsonPath.read<Int>(response, "$.transactions.length()"))
        assertEquals("EXPENSE", JsonPath.read(response, "$.transactions[0].type"))
        assertMoneyEquals("0", JsonPath.read<Any>(response, "$.totalIncome").toString())
        assertMoneyEquals("400", JsonPath.read<Any>(response, "$.totalExpense").toString())
    }

    @Test
    fun `filtrar por cuenta acota el reporte a esa cuenta`() {
        val auth = registerAndAuth("reportecuenta")
        val accountA = createAccount(auth, "Débito", "5000")
        val accountB = createAccount(auth, "Ahorro", "1000")

        recordTransaction(auth, "EXPENSE", accountA, "300", null)
        recordTransaction(auth, "EXPENSE", accountB, "700", null)

        val response = mockMvc.perform(
            get("/api/v1/reports").with(auth)
                .param("from", "$today").param("to", "$today")
                .param("accountId", accountA)
        ).andExpect(status().isOk).andReturn().response.contentAsString

        assertEquals(1, JsonPath.read<Int>(response, "$.transactions.length()"))
        assertMoneyEquals("300", JsonPath.read<Any>(response, "$.totalExpense").toString())
    }

    @Test
    fun `una cuenta de otro usuario es rechazada como si no existiera`() {
        val auth = registerAndAuth("reporteajeno")
        val otherAuth = registerAndAuth("reporteotro")
        val otherAccountId = createAccount(otherAuth, "Débito", "1000")

        mockMvc.perform(
            get("/api/v1/reports").with(auth)
                .param("from", "$today").param("to", "$today")
                .param("accountId", otherAccountId)
        ).andExpect(status().isNotFound)
    }

    @Test
    fun `rango invertido es rechazado`() {
        val auth = registerAndAuth("reporterangoinvalido")

        mockMvc.perform(
            get("/api/v1/reports").with(auth)
                .param("from", "$today").param("to", "${today.minusDays(1)}")
        ).andExpect(status().isBadRequest)
    }

    @Test
    fun `endpoint protegido exige autenticacion`() {
        mockMvc.perform(get("/api/v1/reports").param("from", "$today").param("to", "$today"))
            .andExpect(status().isUnauthorized)
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

    private fun assertMoneyEquals(expected: String, actual: String) {
        assertEquals(0, BigDecimal(expected).compareTo(BigDecimal(actual)), "esperado $expected pero fue $actual")
    }
}
