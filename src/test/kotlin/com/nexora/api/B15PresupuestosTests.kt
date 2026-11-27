package com.nexora.api

import com.jayway.jsonpath.JsonPath
import com.nexora.api.exchangerate.domain.ExchangeRate
import com.nexora.api.exchangerate.domain.ExchangeRateRepository
import com.nexora.api.notification.domain.NotificationService
import com.nexora.api.notification.domain.NotificationType
import com.nexora.api.support.registerAuthenticateAndGetUserId
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.request.RequestPostProcessor
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pruebas de integración de B15 (presupuestos, plan.md sección 22): HTTP -> seguridad ->
 * servicio -> Postgres (Testcontainers). Igual que en [B6NotificacionesTests], las
 * notificaciones se generan con una fecha de referencia fija en vez del reloj real.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class B15PresupuestosTests {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var notificationService: NotificationService

    @Autowired
    lateinit var exchangeRateRepository: ExchangeRateRepository

    private val today: LocalDate = LocalDate.now()

    @Test
    fun `lo gastado cuenta gastos de debito y compras con tarjeta, no ingresos ni transferencias`() {
        val (auth, _) = registerAndAuth("gastado")
        val food = createCategory(auth, "Comida", "EXPENSE")
        val salary = createCategory(auth, "Sueldo", "INCOME")
        val debit = createAccount(auth, "DEBIT", "10000")
        val savings = createAccount(auth, "SAVINGS", "0")
        val card = createCreditCard(auth)

        recordTransaction(auth, debit, "EXPENSE", "300", food)
        recordPurchase(auth, card, "200", food)
        recordTransaction(auth, debit, "EXPENSE", "150", null)
        recordTransaction(auth, debit, "INCOME", "5000", salary)
        mockMvc.perform(
            post("/api/v1/transfers").with(auth).contentType(MediaType.APPLICATION_JSON)
                .content("""{"fromAccountId":"$debit","toAccountId":"$savings","amount":1000,"date":"$today"}""")
        ).andExpect(status().isCreated)

        val foodBudget = createBudget(auth, food, "1000")
        assertEquals("Comida", JsonPath.read(foodBudget, "$.categoryName"))
        assertMoney("500", JsonPath.read(foodBudget, "$.spent"))
        assertMoney("500", JsonPath.read(foodBudget, "$.remaining"))
        assertMoney("50", JsonPath.read(foodBudget, "$.percentUsed"))
        assertEquals("OK", JsonPath.read(foodBudget, "$.status"))
        assertEquals("MXN", JsonPath.read(foodBudget, "$.currency"))

        createBudget(auth, null, "600")

        val list = mockMvc.perform(get("/api/v1/budgets").with(auth))
            .andExpect(status().isOk).andReturn().response.contentAsString
        // El general primero; cuenta también el gasto sin categoría.
        assertNull(JsonPath.read(list, "$[0].categoryId"))
        assertMoney("650", JsonPath.read(list, "$[0].spent"))
        assertMoney("-50", JsonPath.read(list, "$[0].remaining"))
        assertEquals("EXCEEDED", JsonPath.read(list, "$[0].status"))
        assertEquals("Comida", JsonPath.read(list, "$[1].categoryName"))
    }

    @Test
    fun `el gasto de otro mes no cuenta y el parametro month elige el mes`() {
        val (auth, _) = registerAndAuth("mes")
        val food = createCategory(auth, "Comida", "EXPENSE")
        val debit = createAccount(auth, "DEBIT", "10000")
        val lastMonth = today.minusMonths(1)
        recordTransaction(auth, debit, "EXPENSE", "400", food, lastMonth)

        val budgetId = JsonPath.read<String>(createBudget(auth, food, "500"), "$.id")
        assertMoney("0", JsonPath.read(budget(auth, budgetId, null), "$.spent"))

        val previous = budget(auth, budgetId, lastMonth.toString().substring(0, 7))
        assertMoney("400", JsonPath.read(previous, "$.spent"))
        assertEquals("NEAR_LIMIT", JsonPath.read(previous, "$.status"))
    }

    @Test
    fun `el gasto en otra moneda se convierte a MXN`() {
        val (auth, _) = registerAndAuth("moneda")
        exchangeRateRepository.save(ExchangeRate(currency = "CHF", rateToBase = BigDecimal("20")))
        val travel = createCategory(auth, "Viajes", "EXPENSE")
        val chf = createAccount(auth, "DEBIT", "1000", currency = "CHF")
        recordTransaction(auth, chf, "EXPENSE", "10", travel)

        assertMoney("200", JsonPath.read(createBudget(auth, travel, "1000"), "$.spent"))
    }

    @Test
    fun `reglas de alta - uno por categoria, uno general, solo categorias de gasto activas y monto positivo`() {
        val (auth, _) = registerAndAuth("reglas")
        val food = createCategory(auth, "Comida", "EXPENSE")
        val salary = createCategory(auth, "Sueldo", "INCOME")
        val old = createCategory(auth, "Viejo", "EXPENSE")
        mockMvc.perform(post("/api/v1/categories/$old/archive").with(auth)).andExpect(status().isOk)

        createBudget(auth, food, "1000")
        postBudget(auth, """{"categoryId":"$food","amount":500}""").andExpect(status().isConflict)
        createBudget(auth, null, "5000")
        postBudget(auth, """{"amount":500}""").andExpect(status().isConflict)
        postBudget(auth, """{"categoryId":"$salary","amount":500}""").andExpect(status().isBadRequest)
        postBudget(auth, """{"categoryId":"$old","amount":500}""").andExpect(status().isBadRequest)
        postBudget(auth, """{"amount":0}""").andExpect(status().isBadRequest)
        postBudget(auth, """{"categoryId":"${UUID.randomUUID()}","amount":500}""").andExpect(status().isNotFound)
    }

    @Test
    fun `editar el monto, borrar, y no ver los de otro usuario`() {
        val (auth, _) = registerAndAuth("editar")
        val (other, _) = registerAndAuth("otro")
        val food = createCategory(auth, "Comida", "EXPENSE")
        val budgetId = JsonPath.read<String>(createBudget(auth, food, "1000"), "$.id")

        val updated = mockMvc.perform(
            put("/api/v1/budgets/$budgetId").with(auth).contentType(MediaType.APPLICATION_JSON).content("""{"amount":2500}""")
        ).andExpect(status().isOk).andReturn().response.contentAsString
        assertMoney("2500", JsonPath.read(updated, "$.amount"))

        mockMvc.perform(get("/api/v1/budgets/$budgetId").with(other)).andExpect(status().isNotFound)
        mockMvc.perform(delete("/api/v1/budgets/$budgetId").with(other)).andExpect(status().isNotFound)

        mockMvc.perform(delete("/api/v1/budgets/$budgetId").with(auth)).andExpect(status().isNoContent)
        mockMvc.perform(get("/api/v1/budgets/$budgetId").with(auth)).andExpect(status().isNotFound)
        // Ya se puede crear otro para la misma categoría.
        createBudget(auth, food, "800")
    }

    @Test
    fun `avisa una vez al 80 por ciento y una vez al pasarse, por presupuesto y mes`() {
        val (auth, userId) = registerAndAuth("avisos")
        val food = createCategory(auth, "Comida", "EXPENSE")
        val debit = createAccount(auth, "DEBIT", "10000")
        val oct10 = LocalDate.of(2026, 10, 10)
        createBudget(auth, food, "1000")

        recordTransaction(auth, debit, "EXPENSE", "700", food, LocalDate.of(2026, 10, 2))
        assertTrue(budgetAlerts(userId, oct10).isEmpty(), "70 % todavía no avisa")

        recordTransaction(auth, debit, "EXPENSE", "300", food, LocalDate.of(2026, 10, 3))
        val near = budgetAlerts(userId, oct10)
        assertEquals(listOf(NotificationType.BUDGET_NEAR_LIMIT), near.map { it.type }, "justo el monto es «cerca», no «pasado»")
        assertEquals("Llevas el 100 % del presupuesto de Comida: \$1000.00 de \$1000.00 este mes.", near[0].message)
        assertTrue(budgetAlerts(userId, oct10.plusDays(1)).isEmpty(), "no se repite otro día del mismo mes")

        recordTransaction(auth, debit, "EXPENSE", "50", food, LocalDate.of(2026, 10, 11))
        val exceeded = budgetAlerts(userId, LocalDate.of(2026, 10, 12))
        assertEquals(listOf(NotificationType.BUDGET_EXCEEDED), exceeded.map { it.type })
        assertEquals("Te pasaste del presupuesto de Comida: llevas \$1050.00 de \$1000.00 este mes.", exceeded[0].message)
        assertTrue(budgetAlerts(userId, LocalDate.of(2026, 10, 20)).isEmpty())

        // Noviembre empieza de cero: sin gasto, sin aviso; el sobrante/exceso no pasa al mes siguiente.
        assertTrue(budgetAlerts(userId, LocalDate.of(2026, 11, 5)).isEmpty())
    }

    @Test
    fun `si ya se aviso que se paso, no avisa despues que esta cerca`() {
        val (auth, userId) = registerAndAuth("bajada")
        val food = createCategory(auth, "Comida", "EXPENSE")
        val debit = createAccount(auth, "DEBIT", "10000")
        createBudget(auth, food, "1000")
        val expense = recordTransaction(auth, debit, "EXPENSE", "1200", food, LocalDate.of(2026, 10, 2))
        assertEquals(listOf(NotificationType.BUDGET_EXCEEDED), budgetAlerts(userId, LocalDate.of(2026, 10, 5)).map { it.type })

        mockMvc.perform(delete("/api/v1/transactions/$expense").with(auth)).andExpect(status().isNoContent)
        recordTransaction(auth, debit, "EXPENSE", "900", food, LocalDate.of(2026, 10, 6))
        assertTrue(budgetAlerts(userId, LocalDate.of(2026, 10, 7)).isEmpty())
    }

    private fun registerAndAuth(prefix: String): Pair<RequestPostProcessor, UUID> =
        mockMvc.registerAuthenticateAndGetUserId("b15$prefix")

    /** Las notificaciones de presupuesto que generó esta corrida. */
    private fun budgetAlerts(userId: UUID, day: LocalDate) =
        notificationService.generateForUser(userId, day)
            .filter { it.type == NotificationType.BUDGET_NEAR_LIMIT || it.type == NotificationType.BUDGET_EXCEEDED }

    private fun postBudget(auth: RequestPostProcessor, body: String) =
        mockMvc.perform(post("/api/v1/budgets").with(auth).contentType(MediaType.APPLICATION_JSON).content(body))

    private fun createBudget(auth: RequestPostProcessor, categoryId: UUID?, amount: String): String {
        val category = categoryId?.let { "\"categoryId\":\"$it\"," } ?: ""
        return postBudget(auth, """{$category"amount":$amount}""")
            .andExpect(status().isCreated).andReturn().response.contentAsString
    }

    private fun budget(auth: RequestPostProcessor, id: String, month: String?): String {
        val request = get("/api/v1/budgets/$id").with(auth)
        if (month != null) request.param("month", month)
        return mockMvc.perform(request).andExpect(status().isOk).andReturn().response.contentAsString
    }

    private fun createCategory(auth: RequestPostProcessor, name: String, type: String): UUID {
        val response = mockMvc.perform(
            post("/api/v1/categories").with(auth).contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"$name","type":"$type"}""")
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        return UUID.fromString(JsonPath.read(response, "$.id"))
    }

    private fun createAccount(auth: RequestPostProcessor, type: String, openingBalance: String, currency: String = "MXN"): UUID {
        val response = mockMvc.perform(
            post("/api/v1/accounts").with(auth).contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"Cuenta $type $currency","type":"$type","currency":"$currency","openingBalance":$openingBalance}""")
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        return UUID.fromString(JsonPath.read(response, "$.id"))
    }

    /** Devuelve el id de la tarjeta (no el de su cuenta), que es el que piden las compras. */
    private fun createCreditCard(auth: RequestPostProcessor): UUID {
        val response = mockMvc.perform(
            post("/api/v1/credit-cards").with(auth).contentType(MediaType.APPLICATION_JSON)
                .content(
                    """{"name":"BBVA Azul","bank":"BBVA","last4":"1234","creditLimit":50000,"closingDay":15,"paymentDueDay":5,"currency":"MXN"}"""
                )
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        return UUID.fromString(JsonPath.read(response, "$.id"))
    }

    private fun recordTransaction(
        auth: RequestPostProcessor,
        accountId: UUID,
        type: String,
        amount: String,
        categoryId: UUID?,
        date: LocalDate = today,
    ): String {
        val category = categoryId?.let { ",\"categoryId\":\"$it\"" } ?: ""
        val response = mockMvc.perform(
            post("/api/v1/transactions").with(auth).contentType(MediaType.APPLICATION_JSON)
                .content("""{"type":"$type","accountId":"$accountId","amount":$amount,"date":"$date"$category}""")
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        return JsonPath.read(response, "$.id")
    }

    private fun recordPurchase(auth: RequestPostProcessor, cardId: UUID, amount: String, categoryId: UUID) {
        mockMvc.perform(
            post("/api/v1/credit-cards/$cardId/purchases").with(auth).contentType(MediaType.APPLICATION_JSON)
                .content("""{"amount":$amount,"date":"$today","merchant":"Super","categoryId":"$categoryId"}""")
        ).andExpect(status().isCreated)
    }

    private fun assertMoney(expected: String, actual: Any?) =
        assertEquals(0, BigDecimal(expected).compareTo(BigDecimal(actual.toString())), "esperaba $expected, llegó $actual")
}
