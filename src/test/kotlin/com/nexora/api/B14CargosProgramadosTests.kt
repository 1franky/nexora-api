package com.nexora.api

import com.jayway.jsonpath.JsonPath
import com.nexora.api.notification.domain.NotificationService
import com.nexora.api.notification.domain.NotificationType
import com.nexora.api.scheduledcharge.domain.ScheduledChargeFrequency
import com.nexora.api.scheduledcharge.domain.ScheduledChargeInput
import com.nexora.api.scheduledcharge.domain.ScheduledChargeService
import com.nexora.api.scheduledcharge.domain.ScheduledChargeStatus
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
import org.springframework.test.web.servlet.request.RequestPostProcessor
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pruebas de integración de B14 (cargos programados): HTTP -> seguridad ->
 * servicio -> Postgres (Testcontainers).
 *
 * Igual que en [B6NotificacionesTests], los casos que dependen de "qué día
 * es hoy" llaman al servicio directamente con una fecha de referencia fija
 * (ScheduledChargeService.create/processDue/resume aceptan `today`), en vez
 * de depender del reloj real.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class B14CargosProgramadosTests {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var scheduledChargeService: ScheduledChargeService

    @Autowired
    lateinit var notificationService: NotificationService

    private val oct5: LocalDate = LocalDate.of(2026, 10, 5)

    @Test
    fun `alta con inicio futuro no genera movimientos todavia`() {
        val (auth, _) = registerAndAuth("futuro")
        val cardAccountId = createCreditCardAccount(auth)
        val start = LocalDate.now().plusDays(40)

        val response = mockMvc.perform(
            post("/api/v1/scheduled-charges")
                .with(auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """{"accountId":"$cardAccountId","name":"Disney+","amount":119,"frequency":"MONTHLY",
                       "dayOfMonth":${start.dayOfMonth},"startDate":"$start"}"""
                )
        ).andExpect(status().isCreated).andReturn().response.contentAsString

        assertEquals("ACTIVE", JsonPath.read(response, "$.status"))
        assertEquals(start.toString(), JsonPath.read(response, "$.nextRunDate"))
        assertEquals("CREDIT_CARD", JsonPath.read(response, "$.accountType"))
        assertEquals("MXN", JsonPath.read(response, "$.currency"))
        assertEquals(0, generatedTransactions(auth, cardAccountId, JsonPath.read(response, "$.id")).size)
    }

    @Test
    fun `alta con inicio pasado en tarjeta registra los cargos atrasados como compras`() {
        val (auth, userId) = registerAndAuth("atrasados")
        val cardAccountId = createCreditCardAccount(auth)

        val view = scheduledChargeService.create(
            userId, input(cardAccountId, startDate = LocalDate.of(2026, 7, 1)), today = oct5,
        )

        val transactions = generatedTransactions(auth, cardAccountId, view.charge.id.toString())
        assertEquals(
            listOf("2026-09-15", "2026-08-15", "2026-07-15"),
            transactions.map { it["date"] },
        )
        assertTrue(transactions.all { it["type"] == "CREDIT_CARD_PURCHASE" && it["merchant"] == "Disney+" })
        assertEquals(LocalDate.of(2026, 10, 15), view.charge.nextRunDate)
        assertEquals(LocalDate.of(2026, 9, 15), view.charge.lastRunDate)
        assertEquals(BigDecimal("357.00"), cardDebt(auth, cardAccountId))
    }

    @Test
    fun `en una cuenta de debito genera gastos y baja el saldo`() {
        val (auth, userId) = registerAndAuth("debito")
        val accountId = createAccount(auth, "DEBIT", "1000")

        val view = scheduledChargeService.create(
            userId, input(accountId, name = "Gimnasio", amount = "500", startDate = LocalDate.of(2026, 9, 1)), today = oct5,
        )

        val transactions = generatedTransactions(auth, accountId, view.charge.id.toString())
        assertEquals(1, transactions.size)
        assertEquals("EXPENSE", transactions[0]["type"])
        assertEquals("Gimnasio", transactions[0]["description"])
        assertEquals(0, BigDecimal("500").compareTo(accountBalance(auth, accountId)))
    }

    @Test
    fun `el job registra cada ocurrencia una sola vez aunque corra dos veces`() {
        val (auth, userId) = registerAndAuth("idempotente")
        val cardAccountId = createCreditCardAccount(auth)
        val view = scheduledChargeService.create(userId, input(cardAccountId, startDate = LocalDate.of(2026, 10, 10)), today = oct5)
        val chargeId = view.charge.id.toString()

        scheduledChargeService.processDue(LocalDate.of(2026, 10, 15))
        scheduledChargeService.processDue(LocalDate.of(2026, 10, 15))
        assertEquals(1, generatedTransactions(auth, cardAccountId, chargeId).size)

        scheduledChargeService.processDue(LocalDate.of(2026, 12, 20))
        assertEquals(3, generatedTransactions(auth, cardAccountId, chargeId).size)

        val posted = notificationService.listForUser(userId, false).filter { it.type == NotificationType.SCHEDULED_CHARGE_POSTED }
        assertEquals(2, posted.size)
        assertTrue(posted.any { it.message.contains("2 cargos") }, "la corrida atrasada debe resumirse en una sola notificación")
    }

    @Test
    fun `dia 31 cae en el ultimo dia de los meses cortos`() {
        val (auth, userId) = registerAndAuth("dia31")
        val cardAccountId = createCreditCardAccount(auth)
        val view = scheduledChargeService.create(
            userId, input(cardAccountId, dayOfMonth = 31, startDate = LocalDate.of(2027, 1, 1)), today = oct5,
        )

        scheduledChargeService.processDue(LocalDate.of(2027, 4, 30))

        assertEquals(
            listOf("2027-04-30", "2027-03-31", "2027-02-28", "2027-01-31"),
            generatedTransactions(auth, cardAccountId, view.charge.id.toString()).map { it["date"] },
        )
    }

    @Test
    fun `cargo anual se registra una vez por año`() {
        val (auth, userId) = registerAndAuth("anual")
        val cardAccountId = createCreditCardAccount(auth)
        val view = scheduledChargeService.create(
            userId,
            input(
                cardAccountId, name = "Amazon Prime", amount = "899", frequency = ScheduledChargeFrequency.YEARLY,
                dayOfMonth = 3, monthOfYear = 3, startDate = LocalDate.of(2026, 1, 1),
            ),
            today = oct5,
        )

        assertEquals(listOf("2026-03-03"), generatedTransactions(auth, cardAccountId, view.charge.id.toString()).map { it["date"] })
        assertEquals(LocalDate.of(2027, 3, 3), view.charge.nextRunDate)
    }

    @Test
    fun `con fecha de fin queda FINISHED despues de la ultima ocurrencia`() {
        val (auth, userId) = registerAndAuth("fin")
        val cardAccountId = createCreditCardAccount(auth)
        val view = scheduledChargeService.create(
            userId, input(cardAccountId, startDate = LocalDate.of(2026, 8, 1), endDate = LocalDate.of(2026, 9, 30)), today = oct5,
        )

        assertEquals(2, generatedTransactions(auth, cardAccountId, view.charge.id.toString()).size)
        assertEquals(ScheduledChargeStatus.FINISHED, view.charge.status)
        assertNull(view.charge.nextRunDate)
    }

    @Test
    fun `reanudar sigue desde hoy sin registrar lo del periodo pausado`() {
        val (auth, userId) = registerAndAuth("pausa")
        val cardAccountId = createCreditCardAccount(auth)
        val chargeId = scheduledChargeService.create(userId, input(cardAccountId, startDate = LocalDate.of(2026, 10, 10)), today = oct5)
            .charge.id!!

        scheduledChargeService.pause(userId, chargeId)
        scheduledChargeService.processDue(LocalDate.of(2026, 12, 20))
        val resumed = scheduledChargeService.resume(userId, chargeId, today = LocalDate.of(2027, 1, 10))

        assertEquals(0, generatedTransactions(auth, cardAccountId, chargeId.toString()).size)
        assertEquals(ScheduledChargeStatus.ACTIVE, resumed.charge.status)
        assertEquals(LocalDate.of(2027, 1, 15), resumed.charge.nextRunDate)
    }

    @Test
    fun `editar el monto solo afecta a los cargos futuros`() {
        val (auth, userId) = registerAndAuth("editar")
        val cardAccountId = createCreditCardAccount(auth)
        val chargeId = scheduledChargeService.create(userId, input(cardAccountId, startDate = LocalDate.of(2026, 8, 1)), today = oct5)
            .charge.id!!

        scheduledChargeService.update(userId, chargeId, input(cardAccountId, amount = "139", startDate = LocalDate.of(2026, 8, 1)), today = oct5)
        scheduledChargeService.processDue(LocalDate.of(2026, 10, 15))

        val amounts = generatedTransactions(auth, cardAccountId, chargeId.toString()).map { BigDecimal(it["amount"].toString()).toInt() }
        assertEquals(listOf(139, 119, 119), amounts)
    }

    @Test
    fun `borrar un movimiento generado no lo vuelve a generar`() {
        val (auth, userId) = registerAndAuth("borrar")
        val cardAccountId = createCreditCardAccount(auth)
        val chargeId = scheduledChargeService.create(userId, input(cardAccountId, startDate = LocalDate.of(2026, 8, 1)), today = oct5)
            .charge.id!!.toString()
        val first = generatedTransactions(auth, cardAccountId, chargeId).first()

        mockMvc.perform(delete("/api/v1/transactions/${first["id"]}").with(auth)).andExpect(status().isNoContent)
        scheduledChargeService.processDue(oct5)

        assertEquals(1, generatedTransactions(auth, cardAccountId, chargeId).size)
    }

    @Test
    fun `si la categoria se archivo, el cargo se pausa y avisa`() {
        val (auth, userId) = registerAndAuth("categoria")
        val cardAccountId = createCreditCardAccount(auth)
        val categoryId = createCategory(auth)
        val chargeId = scheduledChargeService.create(
            userId, input(cardAccountId, categoryId = categoryId, startDate = LocalDate.of(2026, 10, 10)), today = oct5,
        ).charge.id!!
        mockMvc.perform(post("/api/v1/categories/$categoryId/archive").with(auth)).andExpect(status().isOk)

        scheduledChargeService.processDue(LocalDate.of(2026, 10, 15))

        val response = mockMvc.perform(get("/api/v1/scheduled-charges/$chargeId").with(auth))
            .andExpect(status().isOk).andReturn().response.contentAsString
        assertEquals("PAUSED", JsonPath.read(response, "$.status"))
        assertNotNull(JsonPath.read<String?>(response, "$.lastError"))
        assertEquals(0, generatedTransactions(auth, cardAccountId, chargeId.toString()).size)
        assertTrue(notificationService.listForUser(userId, false).any { it.type == NotificationType.SCHEDULED_CHARGE_FAILED })

        // Reanudar con la categoría todavía archivada explica el motivo.
        mockMvc.perform(post("/api/v1/scheduled-charges/$chargeId/resume").with(auth)).andExpect(status().isBadRequest)
    }

    @Test
    fun `cancelar conserva los movimientos ya generados y deja de generar`() {
        val (auth, userId) = registerAndAuth("cancelar")
        val cardAccountId = createCreditCardAccount(auth)
        val chargeId = scheduledChargeService.create(userId, input(cardAccountId, startDate = LocalDate.of(2026, 9, 1)), today = oct5)
            .charge.id!!.toString()

        mockMvc.perform(delete("/api/v1/scheduled-charges/$chargeId").with(auth)).andExpect(status().isNoContent)
        scheduledChargeService.processDue(LocalDate.of(2026, 12, 20))

        assertEquals(1, generatedTransactions(auth, cardAccountId, chargeId).size)
        val list = mockMvc.perform(get("/api/v1/scheduled-charges").with(auth))
            .andExpect(status().isOk).andReturn().response.contentAsString
        assertEquals("CANCELLED", JsonPath.read(list, "$[0].status"))
    }

    @Test
    fun `no se permiten cargos a AFORE ni reglas de fecha inconsistentes`() {
        val (auth, _) = registerAndAuth("validaciones")
        val aforeId = createAccount(auth, "AFORE", "0")
        val debitId = createAccount(auth, "DEBIT", "0")
        val today = LocalDate.now()

        postCharge(auth, """{"accountId":"$aforeId","name":"X","amount":10,"frequency":"MONTHLY","dayOfMonth":1,"startDate":"$today"}""")
            .andExpect(status().isBadRequest)
        postCharge(auth, """{"accountId":"$debitId","name":"X","amount":10,"frequency":"YEARLY","dayOfMonth":1,"startDate":"$today"}""")
            .andExpect(status().isBadRequest)
        postCharge(auth, """{"accountId":"$debitId","name":"X","amount":10,"frequency":"MONTHLY","dayOfMonth":1,"monthOfYear":3,"startDate":"$today"}""")
            .andExpect(status().isBadRequest)
        postCharge(auth, """{"accountId":"$debitId","name":"X","amount":10,"frequency":"MONTHLY","dayOfMonth":32,"startDate":"$today"}""")
            .andExpect(status().isBadRequest)
        postCharge(
            auth,
            """{"accountId":"$debitId","name":"X","amount":10,"frequency":"MONTHLY","dayOfMonth":1,"startDate":"$today","endDate":"${today.minusDays(1)}"}""",
        ).andExpect(status().isBadRequest)
    }

    @Test
    fun `un usuario no puede ver ni operar los cargos de otro`() {
        val (ownerAuth, ownerId) = registerAndAuth("dueno")
        val cardAccountId = createCreditCardAccount(ownerAuth)
        val chargeId = scheduledChargeService.create(ownerId, input(cardAccountId, startDate = LocalDate.of(2026, 10, 10)), today = oct5)
            .charge.id!!

        val (intruderAuth, _) = registerAndAuth("intruso")
        mockMvc.perform(get("/api/v1/scheduled-charges/$chargeId").with(intruderAuth)).andExpect(status().isNotFound)
        mockMvc.perform(post("/api/v1/scheduled-charges/$chargeId/pause").with(intruderAuth)).andExpect(status().isNotFound)
        mockMvc.perform(delete("/api/v1/scheduled-charges/$chargeId").with(intruderAuth)).andExpect(status().isNotFound)
        mockMvc.perform(get("/api/v1/scheduled-charges").param("accountId", cardAccountId.toString()).with(intruderAuth))
            .andExpect(status().isNotFound)
        postCharge(
            intruderAuth,
            """{"accountId":"$cardAccountId","name":"X","amount":10,"frequency":"MONTHLY","dayOfMonth":1,"startDate":"${LocalDate.now()}"}""",
        ).andExpect(status().isNotFound)
    }

    @Test
    fun `endpoint protegido exige autenticacion`() {
        mockMvc.perform(get("/api/v1/scheduled-charges")).andExpect(status().isUnauthorized)
    }

    // --- helpers ---

    private fun registerAndAuth(prefix: String): Pair<RequestPostProcessor, UUID> =
        mockMvc.registerAuthenticateAndGetUserId("b14$prefix")

    private fun input(
        accountId: UUID,
        name: String = "Disney+",
        amount: String = "119",
        frequency: ScheduledChargeFrequency = ScheduledChargeFrequency.MONTHLY,
        dayOfMonth: Int = 15,
        monthOfYear: Int? = null,
        categoryId: UUID? = null,
        startDate: LocalDate,
        endDate: LocalDate? = null,
    ) = ScheduledChargeInput(
        accountId = accountId,
        name = name,
        amount = BigDecimal(amount),
        categoryId = categoryId,
        description = null,
        frequency = frequency,
        dayOfMonth = dayOfMonth,
        monthOfYear = monthOfYear,
        startDate = startDate,
        endDate = endDate,
    )

    private fun postCharge(auth: RequestPostProcessor, body: String) =
        mockMvc.perform(post("/api/v1/scheduled-charges").with(auth).contentType(MediaType.APPLICATION_JSON).content(body))

    /** Crea una tarjeta y devuelve el id de su Account (lo que usa un cargo programado). */
    private fun createCreditCardAccount(auth: RequestPostProcessor): UUID {
        val response = mockMvc.perform(
            post("/api/v1/credit-cards")
                .with(auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """{"name":"BBVA Azul","bank":"BBVA","last4":"1234","creditLimit":50000,"closingDay":15,"paymentDueDay":5,"currency":"MXN"}"""
                )
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        return UUID.fromString(JsonPath.read(response, "$.accountId"))
    }

    private fun createAccount(auth: RequestPostProcessor, type: String, openingBalance: String): UUID {
        val response = mockMvc.perform(
            post("/api/v1/accounts")
                .with(auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"Cuenta $type","type":"$type","currency":"MXN","openingBalance":$openingBalance}""")
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        return UUID.fromString(JsonPath.read(response, "$.id"))
    }

    private fun createCategory(auth: RequestPostProcessor): UUID {
        val response = mockMvc.perform(
            post("/api/v1/categories")
                .with(auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"Suscripciones","type":"EXPENSE"}""")
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        return UUID.fromString(JsonPath.read(response, "$.id"))
    }

    /** Movimientos de la cuenta generados por ese cargo, del más reciente al más viejo. */
    private fun generatedTransactions(auth: RequestPostProcessor, accountId: UUID, chargeId: String): List<Map<String, Any?>> {
        val response = mockMvc.perform(get("/api/v1/transactions").param("accountId", accountId.toString()).with(auth))
            .andExpect(status().isOk).andReturn().response.contentAsString
        return JsonPath.read<List<Map<String, Any?>>>(response, "$").filter { it["scheduledChargeId"] == chargeId }
    }

    private fun accountBalance(auth: RequestPostProcessor, accountId: UUID): BigDecimal {
        val response = mockMvc.perform(get("/api/v1/accounts/$accountId").with(auth))
            .andExpect(status().isOk).andReturn().response.contentAsString
        return BigDecimal(JsonPath.read<Any>(response, "$.balance").toString())
    }

    private fun cardDebt(auth: RequestPostProcessor, cardAccountId: UUID): BigDecimal {
        val response = mockMvc.perform(get("/api/v1/credit-cards").with(auth))
            .andExpect(status().isOk).andReturn().response.contentAsString
        val debt = JsonPath.read<List<Any>>(response, "$[?(@.accountId == '$cardAccountId')].currentDebt").first()
        return BigDecimal(debt.toString()).setScale(2)
    }
}
