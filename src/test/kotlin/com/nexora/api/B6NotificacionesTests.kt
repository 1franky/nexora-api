package com.nexora.api

import com.jayway.jsonpath.JsonPath
import com.nexora.api.creditcard.domain.BillingCycleCalculator
import com.nexora.api.notification.domain.NotificationService
import com.nexora.api.notification.domain.NotificationType
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import com.nexora.api.support.registerAuthenticateAndGetUserId
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.RequestPostProcessor
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pruebas de integración de B6 (notificaciones): HTTP -> seguridad ->
 * servicio -> Postgres (Testcontainers).
 *
 * Para los casos que dependen de "cuántos días faltan para el vencimiento"
 * se llama a [NotificationService.generateForUser] directamente con una
 * fecha de referencia simulada, en vez de depender del reloj real: cerca de
 * fin de mes, con día de corte/pago tope 28, el vencimiento de una tarjeta
 * recién creada nunca cae a 0-3 días de HOY (el mínimo alcanzable ronda los
 * 4+ días) — así que forzar el escenario vía la API pública no es
 * determinista según el día del mes en que corra el test.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class B6NotificacionesTests {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var notificationService: NotificationService

    @Autowired
    lateinit var billingCycleCalculator: BillingCycleCalculator

    private val today: LocalDate = LocalDate.now()

    @Test
    fun `sin tarjetas ni cuotas no hay notificaciones`() {
        val (auth, _) = registerAndAuth("sinnada")
        val response = mockMvc.perform(get("/api/v1/notifications").with(auth))
            .andExpect(status().isOk).andReturn().response.contentAsString
        assertEquals(0, JsonPath.read<Int>(response, "$.length()"))
    }

    @Test
    fun `una tarjeta que vence en 2 dias genera PAYMENT_DUE_SOON`() {
        val (auth, userId) = registerAndAuth("duesoon")
        val cardId = createCreditCard(auth)
        purchase(auth, cardId, "1000")
        val realDueDate = nextPaymentDueDateOf(auth, cardId)

        notificationService.generateForUser(userId, realDueDate.minusDays(2))

        val response = mockMvc.perform(get("/api/v1/notifications").with(auth))
            .andExpect(status().isOk).andReturn().response.contentAsString
        assertEquals(1, JsonPath.read<Int>(response, "$.length()"))
        assertEquals("PAYMENT_DUE_SOON", JsonPath.read(response, "$.[0].type"))
        val message: String = JsonPath.read(response, "$.[0].message")
        assertTrue(message.contains("2 día"), "el mensaje debe mencionar los días restantes: $message")
    }

    @Test
    fun `una tarjeta que vence hoy genera PAYMENT_DUE`() {
        val (auth, userId) = registerAndAuth("duetoday")
        val cardId = createCreditCard(auth)
        purchase(auth, cardId, "1000")
        val realDueDate = nextPaymentDueDateOf(auth, cardId)

        notificationService.generateForUser(userId, realDueDate)

        val response = mockMvc.perform(get("/api/v1/notifications").with(auth))
            .andExpect(status().isOk).andReturn().response.contentAsString
        assertEquals(1, JsonPath.read<Int>(response, "$.length()"))
        assertEquals("PAYMENT_DUE", JsonPath.read(response, "$.[0].type"))
    }

    @Test
    fun `una tarjeta fuera de la ventana no genera notificacion`() {
        val (auth, userId) = registerAndAuth("fueraventana")
        val cardId = createCreditCard(auth)
        purchase(auth, cardId, "1000")
        val realDueDate = nextPaymentDueDateOf(auth, cardId)

        notificationService.generateForUser(userId, realDueDate.minusDays(10))

        assertEquals(0, notificationService.listForUser(userId, false).size)
    }

    @Test
    fun `llamar dos veces con la misma fecha no duplica la notificacion`() {
        val (auth, userId) = registerAndAuth("noduplica")
        val cardId = createCreditCard(auth)
        purchase(auth, cardId, "1000")
        val realDueDate = nextPaymentDueDateOf(auth, cardId)
        val referenceDay = realDueDate.minusDays(1)

        notificationService.generateForUser(userId, referenceDay)
        notificationService.generateForUser(userId, referenceDay)

        assertEquals(1, notificationService.listForUser(userId, false).size)
    }

    @Test
    fun `en el periodo de gracia la alerta es por el ciclo ya cerrado, no por el del mes siguiente`() {
        // Corte 1, pago 15. La compra (hoy) entra en el ciclo que cierra el día 1 del mes
        // que sigue; simulamos el día 13 de ese mes: ese ciclo ya cerró y vence el 15 —
        // dentro de 2 días —, aunque el corte "siguiente" (día 1 del otro mes) lleve a
        // un pago del 15 del mes siguiente. Antes de la corrección no se generaba alerta.
        val (auth, userId) = registerAndAuth("gracia")
        val cardId = createCreditCard(auth, closingDay = 1, paymentDueDay = 15)
        purchase(auth, cardId, "1000")
        val graceMonth = YearMonth.from(today).plusMonths(1)

        notificationService.generateForUser(userId, graceMonth.atDay(13))

        val notifications = notificationService.listForUser(userId, false)
        assertEquals(1, notifications.size)
        assertEquals(NotificationType.PAYMENT_DUE_SOON, notifications[0].type)
        assertTrue(notifications[0].message.contains("2 día"), "el mensaje debe mencionar 2 días: ${notifications[0].message}")
    }

    @Test
    fun `el detalle de la tarjeta muestra la fecha de pago del ciclo en gracia`() {
        // Elegimos corte/pago de modo que HOY caiga en el período de gracia del último corte.
        val day = today.dayOfMonth
        val (closingDay, paymentDueDay) = if (day in 2..28) (day - 1) to minOf(day + 5, 28) else 28 to 5
        val cycle = billingCycleCalculator.currentPaymentCycle(closingDay, paymentDueDay, today)
        assertTrue(cycle.isGracePeriod, "precondición: hoy debe estar en período de gracia")
        val (auth, _) = registerAndAuth("detallegracia")
        val cardId = createCreditCard(auth, closingDay, paymentDueDay)
        purchase(auth, cardId, "1000", cycle.closingDate.minusDays(3))

        assertEquals(cycle.paymentDueDate, nextPaymentDueDateOf(auth, cardId))
    }

    @Test
    fun `una cuota por vencer genera INSTALLMENT_DUE`() {
        val (auth, userId) = registerAndAuth("cuotapendiente")
        val cardId = createCreditCard(auth)
        val plan = mockMvc.perform(
            post("/api/v1/credit-cards/$cardId/installment-plans")
                .with(auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"amount":2000,"date":"$today","merchant":"Tienda","installmentCount":2,"interestRate":0}""")
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        val installmentDueDate = LocalDate.parse(JsonPath.read(plan, "$.installments[0].dueDate"))

        notificationService.generateForUser(userId, installmentDueDate.minusDays(1))

        val notifications = notificationService.listForUser(userId, false)
        assertTrue(notifications.any { it.type == NotificationType.INSTALLMENT_DUE }, "se esperaba una notificación INSTALLMENT_DUE")
    }

    @Test
    fun `marcar como leida actualiza el estado`() {
        val (auth, userId) = registerAndAuth("marcarleida")
        val cardId = createCreditCard(auth)
        purchase(auth, cardId, "1000")
        val realDueDate = nextPaymentDueDateOf(auth, cardId)
        notificationService.generateForUser(userId, realDueDate)
        val notificationId = notificationService.listForUser(userId, false).first().id.toString()

        val response = mockMvc.perform(post("/api/v1/notifications/$notificationId/read").with(auth))
            .andExpect(status().isOk).andReturn().response.contentAsString

        assertEquals("READ", JsonPath.read(response, "$.status"))
        assertNotNull(JsonPath.read<Any?>(response, "$.readAt"))
    }

    @Test
    fun `marcar todas como leidas deja la lista de no leidas vacia`() {
        val (auth, userId) = registerAndAuth("marcartodas")
        val cardId = createCreditCard(auth)
        purchase(auth, cardId, "1000")
        val realDueDate = nextPaymentDueDateOf(auth, cardId)
        notificationService.generateForUser(userId, realDueDate)

        mockMvc.perform(post("/api/v1/notifications/read-all").with(auth)).andExpect(status().isNoContent)

        assertEquals(0, notificationService.listForUser(userId, true).size)
    }

    @Test
    fun `un usuario no puede marcar como leida la notificacion de otro`() {
        val (ownerAuth, ownerUserId) = registerAndAuth("notifowner")
        val cardId = createCreditCard(ownerAuth)
        purchase(ownerAuth, cardId, "1000")
        val realDueDate = nextPaymentDueDateOf(ownerAuth, cardId)
        notificationService.generateForUser(ownerUserId, realDueDate)
        val notificationId = notificationService.listForUser(ownerUserId, false).first().id.toString()

        val (intruderAuth, _) = registerAndAuth("notifintruder")
        mockMvc.perform(post("/api/v1/notifications/$notificationId/read").with(intruderAuth))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `endpoint protegido exige autenticacion`() {
        mockMvc.perform(get("/api/v1/notifications")).andExpect(status().isUnauthorized)
    }

    // --- helpers ---

    private fun registerAndAuth(prefix: String): Pair<RequestPostProcessor, UUID> {
        return mockMvc.registerAuthenticateAndGetUserId(prefix)
    }

    private fun createCreditCard(auth: RequestPostProcessor, closingDay: Int = 15, paymentDueDay: Int = 5): String {
        val response = mockMvc.perform(
            post("/api/v1/credit-cards")
                .with(auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """{"name":"BBVA Azul","bank":"BBVA","last4":"1234","creditLimit":50000,"closingDay":$closingDay,"paymentDueDay":$paymentDueDay,"currency":"MXN"}"""
                )
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        return JsonPath.read(response, "$.id")
    }

    private fun purchase(auth: RequestPostProcessor, cardId: String, amount: String, date: LocalDate = today) {
        mockMvc.perform(
            post("/api/v1/credit-cards/$cardId/purchases")
                .with(auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"amount":$amount,"date":"$date","merchant":"Amazon"}""")
        ).andExpect(status().isCreated)
    }

    private fun nextPaymentDueDateOf(auth: RequestPostProcessor, cardId: String): LocalDate {
        val response = mockMvc.perform(get("/api/v1/credit-cards/$cardId").with(auth))
            .andExpect(status().isOk).andReturn().response.contentAsString
        return LocalDate.parse(JsonPath.read(response, "$.nextPaymentDueDate"))
    }

}
