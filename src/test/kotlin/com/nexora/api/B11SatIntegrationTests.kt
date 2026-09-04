package com.nexora.api

import com.jayway.jsonpath.JsonPath
import com.nexora.api.sat.domain.CfdiInvoiceRepository
import com.nexora.api.sat.domain.CfdiTipo
import com.nexora.api.sat.domain.SatCertificateRepository
import com.nexora.api.sat.domain.SatCertificateStatus
import com.nexora.api.sat.domain.SatSyncService
import com.nexora.api.support.FakeSatSoapClient
import com.nexora.api.support.TestSatKeys
import com.nexora.api.support.TestSatSoapClientConfig
import com.nexora.api.support.registerAndAuthenticate
import com.nexora.api.support.registerAuthenticateAndGetUserId
import com.nexora.api.support.testCfdiXml
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pruebas de integración de B11 (integración SAT): HTTP -> seguridad ->
 * servicio -> cifrado real -> Postgres (Testcontainers), con el Web Service
 * del SAT reemplazado por [FakeSatSoapClient] (ver [TestSatSoapClientConfig])
 * — ningún test le pega al SAT real. El .cer/.key son generados en el acto
 * por [TestSatKeys] (autofirmados, no una e.firma real), pero se leen con el
 * mismo código de producción (SatKeyReader) que una e.firma de verdad.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class, TestSatSoapClientConfig::class)
class B11SatIntegrationTests {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var fakeSatSoapClient: FakeSatSoapClient

    @Autowired
    lateinit var satCertificateRepository: SatCertificateRepository

    @Autowired
    lateinit var cfdiInvoiceRepository: CfdiInvoiceRepository

    @Autowired
    lateinit var syncService: SatSyncService

    /**
     * [fakeSatSoapClient] es un bean singleton compartido por todos los
     * tests de esta clase (Spring cachea el ApplicationContext): sin
     * resetearlo, `nextPackageXmls` que dejó un test anterior contaminaría
     * la primera sincronización automática (disparada por `connect()`) del
     * siguiente test, antes de que ese test alcance a configurar la suya.
     */
    @BeforeEach
    fun resetFakeSatSoapClient() {
        fakeSatSoapClient.nextPackageXmls = emptyList()
        fakeSatSoapClient.failAuthentication = false
        fakeSatSoapClient.authenticateCalls = 0
        fakeSatSoapClient.descargarPaqueteCalls = 0
    }

    private fun connect(auth: org.springframework.test.web.servlet.request.RequestPostProcessor, keys: TestSatKeys.Generated, password: String = "test-password-123") =
        mockMvc.perform(
            multipart("/api/v1/sat/certificate")
                .file(MockMultipartFile("cer", "e.cer", "application/x-x509-ca-cert", keys.cerBytes))
                .file(MockMultipartFile("key", "e.key", "application/octet-stream", keys.keyBytes))
                .param("password", password)
                .with(auth),
        )

    /**
     * `connect()` siempre dispara una primera sync en background
     * (syncIncrementalAsync, ver SatController) — para que no compita con
     * el `syncService.syncNow(...)` explícito que hacen los tests de abajo
     * (ambos guardando en la misma tabla, con `nextPackageXmls` cambiando
     * de valor entre medio), se espera a que esa primera sync ya haya
     * corrido antes de continuar.
     */
    private fun waitForFirstAsyncSync(satCertificateId: UUID) {
        val deadline = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < deadline) {
            if (satCertificateRepository.findById(satCertificateId).orElseThrow().lastSyncAt != null) return
            Thread.sleep(50)
        }
        error("La primera sincronización automática no terminó a tiempo.")
    }

    @Test
    fun `conectar una e-firma valida la queda cifrada en BD, nunca en claro`() {
        val auth = mockMvc.registerAndAuthenticate("sat-connect")
        val keys = TestSatKeys.generate(rfc = "TEST010101AB1")

        connect(auth, keys)
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.rfc").value(keys.rfc))
            .andExpect(jsonPath("$.status").value("ACTIVO"))

        assertTrue(fakeSatSoapClient.authenticateCalls > 0, "connect() debe hacer un login de prueba contra el SAT antes de guardar")

        val stored = satCertificateRepository.findAll().first { it.rfc == keys.rfc }
        val storedKeyAsText = String(stored.privateKeyEncrypted)
        val storedPasswordAsText = String(stored.passwordEncrypted)
        assertTrue(
            !storedKeyAsText.contains("PRIVATE KEY") && !storedPasswordAsText.contains("test-password-123"),
            "la llave privada y la contraseña nunca deben quedar legibles en la columna cifrada",
        )
    }

    @Test
    fun `una contrasena incorrecta se rechaza sin guardar nada`() {
        val auth = mockMvc.registerAndAuthenticate("sat-badpass")
        val keys = TestSatKeys.generate(rfc = "TEST020202BC2", password = "la-correcta")

        connect(auth, keys, password = "una-incorrecta").andExpect(status().isBadRequest)

        assertEquals(null, satCertificateRepository.findAll().firstOrNull { it.rfc == "TEST020202BC2" })
    }

    @Test
    fun `el SAT rechazando la autenticacion tambien impide guardar la conexion`() {
        val auth = mockMvc.registerAndAuthenticate("sat-rejected")
        val keys = TestSatKeys.generate(rfc = "TEST030303CD3")
        fakeSatSoapClient.failAuthentication = true

        try {
            connect(auth, keys).andExpect(status().isBadRequest)
        } finally {
            fakeSatSoapClient.failAuthentication = false
        }
    }

    @Test
    fun `sincronizar descarga y persiste las facturas del paquete, sin duplicar en un segundo sync`() {
        val (auth, userId) = mockMvc.registerAuthenticateAndGetUserId("sat-sync")
        val keys = TestSatKeys.generate(rfc = "TEST040404DE4")
        val invoiceUuid = UUID.randomUUID().toString()

        connect(auth, keys).andExpect(status().isCreated)
        val certificateId = requireNotNull(satCertificateRepository.findAll().first { it.rfc == keys.rfc }.id)
        waitForFirstAsyncSync(certificateId)

        fakeSatSoapClient.nextPackageXmls = listOf(testCfdiXml(emisorRfc = keys.rfc, receptorRfc = "OTRO010101XX1", uuid = invoiceUuid))
        // syncNow es bloqueante (a diferencia de syncIncrementalAsync, que dispara el controller
        // en background) — se llama aquí directo para no depender de temporización de un hilo @Async.
        val nuevas = syncService.syncNow(certificateId, Instant.now().minusSeconds(3600), Instant.now())
        assertEquals(1, nuevas)

        val invoices = cfdiInvoiceRepository.findAll().filter { it.userId == userId }
        assertEquals(1, invoices.size)
        assertEquals(invoiceUuid, invoices.first().uuidFiscal)
        assertEquals(CfdiTipo.EMITIDAS, invoices.first().tipo, "el RFC conectado es el emisor del CFDI de prueba")

        // Re-sincronizar el mismo rango no debe duplicar la factura (idempotencia, plan sección 6).
        val nuevasSegundaVez = syncService.syncNow(certificateId, Instant.now().minusSeconds(3600), Instant.now())
        assertEquals(0, nuevasSegundaVez)
        assertEquals(1, cfdiInvoiceRepository.findAll().count { it.userId == userId })
    }

    @Test
    fun `listar y descargar el XML de una factura ya sincronizada`() {
        val (auth, userId) = mockMvc.registerAuthenticateAndGetUserId("sat-list")
        val keys = TestSatKeys.generate(rfc = "TEST050505EF5")

        connect(auth, keys).andExpect(status().isCreated)
        val certificateId = requireNotNull(satCertificateRepository.findAll().first { it.rfc == keys.rfc }.id)
        waitForFirstAsyncSync(certificateId)

        fakeSatSoapClient.nextPackageXmls = listOf(testCfdiXml(emisorRfc = keys.rfc, receptorRfc = "OTRO010101XX1"))
        syncService.syncNow(certificateId, Instant.now().minusSeconds(3600), Instant.now())

        val listResponse = mockMvc.perform(get("/api/v1/sat/invoices").with(auth))
            .andExpect(status().isOk)
            .andReturn().response.contentAsString
        val invoiceId = UUID.fromString(JsonPath.read<String>(listResponse, "$.content[0].id"))

        mockMvc.perform(get("/api/v1/sat/invoices/$invoiceId/xml").with(auth))
            .andExpect(status().isOk)
    }

    @Test
    fun `desconectar borra el material sensible pero conserva las facturas ya descargadas`() {
        val (auth, userId) = mockMvc.registerAuthenticateAndGetUserId("sat-revoke")
        val keys = TestSatKeys.generate(rfc = "TEST060606FG6")

        connect(auth, keys).andExpect(status().isCreated)
        val certificateId = requireNotNull(satCertificateRepository.findAll().first { it.rfc == keys.rfc }.id)
        waitForFirstAsyncSync(certificateId)

        fakeSatSoapClient.nextPackageXmls = listOf(testCfdiXml(emisorRfc = keys.rfc, receptorRfc = "OTRO010101XX1"))
        syncService.syncNow(certificateId, Instant.now().minusSeconds(3600), Instant.now())
        assertEquals(1, cfdiInvoiceRepository.findAll().count { it.userId == userId })

        mockMvc.perform(delete("/api/v1/sat/certificate").with(auth)).andExpect(status().isNoContent)

        val revoked = satCertificateRepository.findById(certificateId).orElseThrow()
        assertEquals(SatCertificateStatus.REVOCADO, revoked.status)
        assertEquals(0, revoked.privateKeyEncrypted.size)
        assertEquals(0, revoked.passwordEncrypted.size)
        assertEquals(1, cfdiInvoiceRepository.findAll().count { it.userId == userId }, "revocar no debe borrar las facturas ya descargadas")
    }
}
