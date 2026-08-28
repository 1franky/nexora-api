package com.nexora.api.support

import com.jayway.jsonpath.JsonPath
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.RequestPostProcessor
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

/** Contraseña usada por todos los usuarios de prueba registrados con estos helpers. */
const val TEST_PASSWORD = "password123"

/**
 * Registra un usuario nuevo (email único) y hace login contra
 * /api/v1/auth/login, devolviendo un [RequestPostProcessor] que agrega el
 * access token JWT como header `Authorization: Bearer ...` a cada request
 * (reemplaza el `httpBasic(...)` usado antes de B7).
 */
fun MockMvc.registerAndAuthenticate(prefix: String): RequestPostProcessor {
    val email = "$prefix+${System.nanoTime()}@nexora.test"
    perform(
        post("/api/v1/users")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"email":"$email","password":"$TEST_PASSWORD","displayName":"Test User"}""")
    ).andExpect(status().isCreated)

    val loginResponse = perform(
        post("/api/v1/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"email":"$email","password":"$TEST_PASSWORD"}""")
    ).andExpect(status().isOk).andReturn().response.contentAsString
    val accessToken = JsonPath.read<String>(loginResponse, "$.accessToken")

    return RequestPostProcessor { request ->
        request.addHeader("Authorization", "Bearer $accessToken")
        request
    }
}

/** Igual que [registerAndAuthenticate], pero además devuelve el id del usuario creado. */
fun MockMvc.registerAuthenticateAndGetUserId(prefix: String): Pair<RequestPostProcessor, UUID> {
    val auth = registerAndAuthenticate(prefix)
    val me = perform(get("/api/v1/users/me").with(auth)).andExpect(status().isOk).andReturn().response.contentAsString
    return auth to UUID.fromString(JsonPath.read(me, "$.id"))
}
