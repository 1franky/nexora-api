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
import java.time.LocalDate
import kotlin.test.assertEquals

/**
 * GET /api/v1/transactions sin [accountId]: lista los movimientos de todas
 * las cuentas del usuario juntos (pedido en un issue de nexora-web — el
 * selector de cuenta pasa de ser obligatorio para listar a ser un filtro
 * sobre la lista ya unificada).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class TransactionListingTests {

    @Autowired
    lateinit var mockMvc: MockMvc

    private val today: LocalDate = LocalDate.now()

    @Test
    fun `sin accountId lista movimientos de todas las cuentas juntos`() {
        val auth = registerAndAuth("listtodas")
        val accountA = createAccount(auth, "Débito", "5000")
        val accountB = createAccount(auth, "Ahorro", "1000")
        recordExpense(auth, accountA, "300")
        recordExpense(auth, accountB, "700")

        val response = mockMvc.perform(get("/api/v1/transactions").with(auth))
            .andExpect(status().isOk).andReturn().response.contentAsString

        assertEquals(2, JsonPath.read<Int>(response, "$.length()"))
    }

    @Test
    fun `con accountId sigue acotando a esa cuenta`() {
        val auth = registerAndAuth("listuna")
        val accountA = createAccount(auth, "Débito", "5000")
        val accountB = createAccount(auth, "Ahorro", "1000")
        recordExpense(auth, accountA, "300")
        recordExpense(auth, accountB, "700")

        val response = mockMvc.perform(get("/api/v1/transactions").with(auth).param("accountId", accountA))
            .andExpect(status().isOk).andReturn().response.contentAsString

        assertEquals(1, JsonPath.read<Int>(response, "$.length()"))
        assertEquals(accountA, JsonPath.read(response, "$[0].accountId"))
    }

    @Test
    fun `sin cuentas devuelve una lista vacia`() {
        val auth = registerAndAuth("listvacio")

        val response = mockMvc.perform(get("/api/v1/transactions").with(auth))
            .andExpect(status().isOk).andReturn().response.contentAsString

        assertEquals(0, JsonPath.read<Int>(response, "$.length()"))
    }

    @Test
    fun `una cuenta de otro usuario es rechazada como si no existiera`() {
        val auth = registerAndAuth("listajeno")
        val otherAuth = registerAndAuth("listotro")
        val otherAccountId = createAccount(otherAuth, "Débito", "1000")

        mockMvc.perform(get("/api/v1/transactions").with(auth).param("accountId", otherAccountId))
            .andExpect(status().isNotFound)
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

    private fun recordExpense(auth: RequestPostProcessor, accountId: String, amount: String) {
        mockMvc.perform(
            post("/api/v1/transactions")
                .with(auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"type":"EXPENSE","accountId":"$accountId","amount":$amount,"date":"$today"}""")
        ).andExpect(status().isCreated)
    }
}
