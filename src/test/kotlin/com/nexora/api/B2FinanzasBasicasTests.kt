package com.nexora.api

import com.jayway.jsonpath.JsonPath
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
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
 * Pruebas de integración de B2 (usuarios, cuentas, categorías, movimientos)
 * de punta a punta: HTTP -> seguridad -> servicio -> Postgres (Testcontainers).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class B2FinanzasBasicasTests {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Test
    fun `registrar usuario y consultar perfil`() {
        val email = uniqueEmail("ana")
        registerUser(email)

        val response = mockMvc.perform(get("/api/v1/users/me").with(bearerLogin(email, PASSWORD)))
            .andExpect(status().isOk)
            .andReturn().response.contentAsString

        assertEquals(email, JsonPath.read(response, "$.email"))
        assertEquals("Test User", JsonPath.read(response, "$.displayName"))
    }

    @Test
    fun `registrar el mismo email dos veces devuelve 409`() {
        val email = uniqueEmail("dup")
        registerUser(email)
        mockMvc.perform(
            post("/api/v1/users")
                .contentType(MediaType.APPLICATION_JSON)
                .content(registerBody(email))
        ).andExpect(status().isConflict)
    }

    @Test
    fun `endpoints protegidos exigen autenticacion`() {
        mockMvc.perform(get("/api/v1/accounts")).andExpect(status().isUnauthorized)
    }

    @Test
    fun `crear cuenta y consultarla`() {
        val email = uniqueEmail("cuentas")
        registerUser(email)
        val auth = bearerLogin(email, PASSWORD)

        val accountId = createAccount(auth, name = "Débito BBVA", type = "DEBIT", openingBalance = "1000.00")

        assertMoneyEquals("1000.00", getAccountBalance(auth, accountId))
    }

    @Test
    fun `registrar un ingreso incrementa el saldo de la cuenta`() {
        val email = uniqueEmail("ingreso")
        registerUser(email)
        val auth = bearerLogin(email, PASSWORD)
        val accountId = createAccount(auth, name = "Ahorro", type = "SAVINGS", openingBalance = "0")

        mockMvc.perform(
            post("/api/v1/transactions")
                .with(auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"type":"INCOME","accountId":"$accountId","amount":500.50,"date":"2026-08-28","description":"Nómina"}""")
        ).andExpect(status().isCreated)

        assertMoneyEquals("500.50", getAccountBalance(auth, accountId))
    }

    @Test
    fun `registrar un gasto decrementa el saldo de la cuenta`() {
        val email = uniqueEmail("gasto")
        registerUser(email)
        val auth = bearerLogin(email, PASSWORD)
        val accountId = createAccount(auth, name = "Débito", type = "DEBIT", openingBalance = "1000")

        mockMvc.perform(
            post("/api/v1/transactions")
                .with(auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"type":"EXPENSE","accountId":"$accountId","amount":150,"date":"2026-08-28"}""")
        ).andExpect(status().isCreated)

        assertMoneyEquals("850.00", getAccountBalance(auth, accountId))
    }

    @Test
    fun `una categoria de tipo incorrecto es rechazada`() {
        val email = uniqueEmail("cat")
        registerUser(email)
        val auth = bearerLogin(email, PASSWORD)
        val accountId = createAccount(auth, name = "Débito", type = "DEBIT", openingBalance = "1000")

        val categoryResponse = mockMvc.perform(
            post("/api/v1/categories")
                .with(auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"Nómina","type":"INCOME"}""")
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        val categoryId = JsonPath.read<String>(categoryResponse, "$.id")

        // Una categoría de tipo INCOME no debe poder usarse en un movimiento EXPENSE.
        mockMvc.perform(
            post("/api/v1/transactions")
                .with(auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """{"type":"EXPENSE","accountId":"$accountId","amount":100,"date":"2026-08-28","categoryId":"$categoryId"}"""
                )
        ).andExpect(status().isBadRequest)
    }

    @Test
    fun `una transferencia mueve dinero entre cuentas sin afectar el total disponible`() {
        val email = uniqueEmail("transfer")
        registerUser(email)
        val auth = bearerLogin(email, PASSWORD)
        val accountA = createAccount(auth, name = "Cuenta A", type = "DEBIT", openingBalance = "1000")
        val accountB = createAccount(auth, name = "Cuenta B", type = "SAVINGS", openingBalance = "0")

        val transferResponse = mockMvc.perform(
            post("/api/v1/transfers")
                .with(auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"fromAccountId":"$accountA","toAccountId":"$accountB","amount":300,"date":"2026-08-28"}""")
        ).andExpect(status().isCreated).andReturn().response.contentAsString

        assertEquals("TRANSFER", JsonPath.read(transferResponse, "$.outgoing.type"))
        assertEquals("TRANSFER", JsonPath.read(transferResponse, "$.incoming.type"))
        // outgoing y incoming comparten el mismo "type" (TRANSFER): balanceEffect es lo único
        // que distingue la pierna que sale de la que entra (lo que necesita un cliente para
        // pintar el monto en rojo o en verde sin adivinar).
        assertMoneyEquals("-300.00", BigDecimal(JsonPath.read<Any>(transferResponse, "$.outgoing.balanceEffect").toString()))
        assertMoneyEquals("300.00", BigDecimal(JsonPath.read<Any>(transferResponse, "$.incoming.balanceEffect").toString()))

        assertMoneyEquals("700.00", getAccountBalance(auth, accountA))
        assertMoneyEquals("300.00", getAccountBalance(auth, accountB))

        val summary = mockMvc.perform(get("/api/v1/accounts/summary").with(auth))
            .andExpect(status().isOk).andReturn().response.contentAsString
        // La transferencia no debe alterar el total disponible: el dinero solo cambió de cuenta.
        assertMoneyEquals("1000.00", BigDecimal(JsonPath.read<Any>(summary, "$.availableBalance").toString()))
    }

    @Test
    fun `transferir a la misma cuenta es rechazado`() {
        val email = uniqueEmail("selftransfer")
        registerUser(email)
        val auth = bearerLogin(email, PASSWORD)
        val accountId = createAccount(auth, name = "Cuenta", type = "DEBIT", openingBalance = "500")

        mockMvc.perform(
            post("/api/v1/transfers")
                .with(auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"fromAccountId":"$accountId","toAccountId":"$accountId","amount":100,"date":"2026-08-28"}""")
        ).andExpect(status().isBadRequest)
    }

    @Test
    fun `un usuario no puede ver cuentas de otro usuario`() {
        val ownerEmail = uniqueEmail("owner")
        registerUser(ownerEmail)
        val ownerAuth = bearerLogin(ownerEmail, PASSWORD)
        val accountId = createAccount(ownerAuth, name = "Privada", type = "DEBIT", openingBalance = "10")

        val intruderEmail = uniqueEmail("intruder")
        registerUser(intruderEmail)
        val intruderAuth = bearerLogin(intruderEmail, PASSWORD)

        mockMvc.perform(get("/api/v1/accounts/$accountId").with(intruderAuth))
            .andExpect(status().isNotFound)
    }

    // --- helpers ---

    /** Compara montos por valor numérico, ignorando la escala (ej. "700.0" == "700.0000"). */
    private fun assertMoneyEquals(expected: String, actual: BigDecimal) {
        assertEquals(0, BigDecimal(expected).compareTo(actual), "esperado $expected pero fue $actual")
    }

    private fun uniqueEmail(prefix: String) = "$prefix+${System.nanoTime()}@nexora.test"

    private fun registerBody(email: String) =
        """{"email":"$email","password":"$PASSWORD","displayName":"Test User"}"""

    private fun registerUser(email: String) {
        mockMvc.perform(
            post("/api/v1/users")
                .contentType(MediaType.APPLICATION_JSON)
                .content(registerBody(email))
        ).andExpect(status().isCreated)
    }

    /** Login real (JWT) contra /api/v1/auth/login; reemplaza al httpBasic de antes de B7. */
    private fun bearerLogin(email: String, password: String): RequestPostProcessor {
        val response = mockMvc.perform(
            post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"email":"$email","password":"$password"}""")
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val accessToken = JsonPath.read<String>(response, "$.accessToken")
        return RequestPostProcessor { request -> request.addHeader("Authorization", "Bearer $accessToken"); request }
    }

    private fun createAccount(auth: RequestPostProcessor, name: String, type: String, openingBalance: String): String {
        val response = mockMvc.perform(
            post("/api/v1/accounts")
                .with(auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"$name","type":"$type","currency":"MXN","openingBalance":$openingBalance}""")
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        return JsonPath.read(response, "$.id")
    }

    private fun getAccountBalance(auth: RequestPostProcessor, accountId: String): BigDecimal {
        val response = mockMvc.perform(get("/api/v1/accounts/$accountId").with(auth))
            .andExpect(status().isOk).andReturn().response.contentAsString
        return BigDecimal(JsonPath.read<Any>(response, "$.balance").toString())
    }

    companion object {
        private const val PASSWORD = "password123"
    }
}
