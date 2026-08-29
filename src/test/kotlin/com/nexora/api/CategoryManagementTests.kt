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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.RequestPostProcessor
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.LocalDate
import kotlin.test.assertEquals

/**
 * Gestión completa de categorías (plan.md, sección 19): renombrar, archivar
 * y reactivar. Crear/listar ya se probaban indirectamente en B2; esto cubre
 * lo que agrega esa gestión, incluyendo que una categoría archivada deje de
 * poder usarse en movimientos nuevos.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class CategoryManagementTests {

    @Autowired
    lateinit var mockMvc: MockMvc

    private val today: LocalDate = LocalDate.now()

    @Test
    fun `renombrar una categoria actualiza su nombre`() {
        val auth = registerAndAuth("catrename")
        val categoryId = createCategory(auth, "Comida", "EXPENSE")

        val response = mockMvc.perform(
            patch("/api/v1/categories/$categoryId")
                .with(auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"Restaurantes"}""")
        ).andExpect(status().isOk).andReturn().response.contentAsString

        assertEquals("Restaurantes", JsonPath.read(response, "$.name"))
        assertEquals("EXPENSE", JsonPath.read(response, "$.type"))
    }

    @Test
    fun `archivar y reactivar una categoria cambia su estado`() {
        val auth = registerAndAuth("catarchive")
        val categoryId = createCategory(auth, "Comida", "EXPENSE")

        val archived = mockMvc.perform(post("/api/v1/categories/$categoryId/archive").with(auth))
            .andExpect(status().isOk).andReturn().response.contentAsString
        assertEquals("ARCHIVED", JsonPath.read(archived, "$.status"))

        val reactivated = mockMvc.perform(post("/api/v1/categories/$categoryId/activate").with(auth))
            .andExpect(status().isOk).andReturn().response.contentAsString
        assertEquals("ACTIVE", JsonPath.read(reactivated, "$.status"))
    }

    @Test
    fun `una categoria archivada no se puede usar en un movimiento nuevo`() {
        val auth = registerAndAuth("catarchivado")
        val accountId = createAccount(auth, "Débito", "5000")
        val categoryId = createCategory(auth, "Comida", "EXPENSE")
        mockMvc.perform(post("/api/v1/categories/$categoryId/archive").with(auth)).andExpect(status().isOk)

        mockMvc.perform(
            post("/api/v1/transactions")
                .with(auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"type":"EXPENSE","accountId":"$accountId","amount":100,"date":"$today","categoryId":"$categoryId"}""")
        ).andExpect(status().isBadRequest)
    }

    @Test
    fun `una categoria archivada sigue apareciendo en el listado`() {
        val auth = registerAndAuth("catlistado")
        val categoryId = createCategory(auth, "Comida", "EXPENSE")
        mockMvc.perform(post("/api/v1/categories/$categoryId/archive").with(auth)).andExpect(status().isOk)

        val response = mockMvc.perform(get("/api/v1/categories").with(auth))
            .andExpect(status().isOk).andReturn().response.contentAsString

        assertEquals(1, JsonPath.read<Int>(response, "$.length()"))
        assertEquals("ARCHIVED", JsonPath.read(response, "$[0].status"))
    }

    @Test
    fun `una categoria de otro usuario es rechazada como si no existiera`() {
        val auth = registerAndAuth("catajeno")
        val otherAuth = registerAndAuth("catotro")
        val otherCategoryId = createCategory(otherAuth, "Comida", "EXPENSE")

        mockMvc.perform(
            patch("/api/v1/categories/$otherCategoryId")
                .with(auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"Hackeada"}""")
        ).andExpect(status().isNotFound)
    }

    @Test
    fun `renombrar sin autenticacion es rechazado`() {
        mockMvc.perform(
            patch("/api/v1/categories/${java.util.UUID.randomUUID()}")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"x"}""")
        ).andExpect(status().isUnauthorized)
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
}
