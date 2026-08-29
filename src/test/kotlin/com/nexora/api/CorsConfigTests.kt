package com.nexora.api

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * MockMvc no simula CORS de navegador (curl tampoco: CORS es una
 * restricción exclusiva del navegador) — así que sin esta prueba, un
 * preflight OPTIONS bloqueado por Spring Security pasaba inadvertido pese
 * a los otros 55 tests en verde. Se detectó recién con pruebas reales en
 * Chrome contra nexora-web (:3006) — ver SecurityConfig/CorsProperties.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class CorsConfigTests {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Test
    fun `el preflight de un origen permitido responde sin autenticacion`() {
        mockMvc.perform(
            options("/api/v1/users")
                .header(HttpHeaders.ORIGIN, "http://localhost:3006")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
        )
            .andExpect(status().isOk)
            .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:3006"))
    }

    @Test
    fun `un origen no permitido no recibe cabeceras CORS`() {
        mockMvc.perform(
            options("/api/v1/users")
                .header(HttpHeaders.ORIGIN, "http://evil.example.com")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
        )
            .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN))
    }

    @Test
    fun `el preflight tambien funciona para un endpoint protegido`() {
        mockMvc.perform(
            options("/api/v1/accounts")
                .header(HttpHeaders.ORIGIN, "http://localhost:3006")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
        )
            .andExpect(status().isOk)
            .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:3006"))
    }
}
