package com.nexora.api.support

import com.nexora.api.sat.domain.CfdiTipo
import com.nexora.api.sat.domain.SatDownloadRequestStatus
import com.nexora.api.sat.domain.SatSoapClient
import com.nexora.api.sat.domain.SatSolicitudResult
import com.nexora.api.sat.domain.SatVerificacionResult
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import java.io.ByteArrayOutputStream
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.time.Instant
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Un CFDI 4.0 mínimo válido para pruebas — mismo esqueleto que usan los
 * tests de [com.nexora.api.sat.domain.CfdiParser], con un Concepto y sus
 * Impuestos (además de Sello/NoCertificado/datos completos del Timbre) para
 * que también sirva de fixture a [com.nexora.api.sat.domain.CfdiPdfService].
 */
fun testCfdiXml(emisorRfc: String, receptorRfc: String, uuid: String = UUID.randomUUID().toString()): String = """
    <cfdi:Comprobante xmlns:cfdi="http://www.sat.gob.mx/cfd/4" xmlns:tfd="http://www.sat.gob.mx/TimbreFiscalDigital"
        Version="4.0" Fecha="2026-01-15T12:30:00" SubTotal="1000.00" Total="1160.00" Moneda="MXN"
        FormaPago="03" MetodoPago="PUE" TipoDeComprobante="I" LugarExpedicion="06600"
        Sello="firmaDePruebaDelComprobante123456" NoCertificado="30001000000500003416">
      <cfdi:Emisor Rfc="$emisorRfc" Nombre="Emisor de Prueba" RegimenFiscal="601"/>
      <cfdi:Receptor Rfc="$receptorRfc" Nombre="Receptor de Prueba" UsoCFDI="G03" RegimenFiscalReceptor="616" DomicilioFiscalReceptor="06600"/>
      <cfdi:Conceptos>
        <cfdi:Concepto ClaveProdServ="81111500" Cantidad="1" ClaveUnidad="E48" Unidad="Servicio" Descripcion="Servicio de prueba" ValorUnitario="1000.00" Importe="1000.00">
          <cfdi:Impuestos>
            <cfdi:Traslados>
              <cfdi:Traslado Base="1000.00" Impuesto="002" TipoFactor="Tasa" TasaOCuota="0.160000" Importe="160.00"/>
            </cfdi:Traslados>
          </cfdi:Impuestos>
        </cfdi:Concepto>
      </cfdi:Conceptos>
      <cfdi:Impuestos TotalImpuestosTrasladados="160.00">
        <cfdi:Traslados>
          <cfdi:Traslado Base="1000.00" Impuesto="002" TipoFactor="Tasa" TasaOCuota="0.160000" Importe="160.00"/>
        </cfdi:Traslados>
      </cfdi:Impuestos>
      <cfdi:Complemento>
        <tfd:TimbreFiscalDigital UUID="$uuid" FechaTimbrado="2026-01-15T12:31:00" SelloCFD="firmaDePruebaDelComprobante123456"
            NoCertificadoSAT="30001000000500003415" SelloSAT="firmaDePruebaDelSat654321" RfcProvCertif="PCT991231ABC"/>
      </cfdi:Complemento>
    </cfdi:Comprobante>
""".trimIndent()

private fun zip(xmls: List<String>): ByteArray {
    val out = ByteArrayOutputStream()
    ZipOutputStream(out).use { zip ->
        xmls.forEachIndexed { index, xml ->
            zip.putNextEntry(ZipEntry("cfdi-$index.xml"))
            zip.write(xml.toByteArray())
            zip.closeEntry()
        }
    }
    return out.toByteArray()
}

/**
 * Doble de prueba de [SatSoapClient] (ver [com.nexora.api.support.FakeEmailSender]
 * para el mismo patrón con el envío de correo) — ningún test puede pegarle
 * al SAT real. Por defecto responde éxito inmediato en los 4 pasos con las
 * facturas configuradas en [nextPackageXmls]; [failAuthentication] simula
 * que el SAT rechazó la e.firma.
 */
class FakeSatSoapClient : SatSoapClient {

    var failAuthentication: Boolean = false
    var nextPackageXmls: List<String> = emptyList()
    var authenticateCalls = 0
    var descargarPaqueteCalls = 0

    override fun autenticar(certificate: X509Certificate, privateKey: PrivateKey): String {
        authenticateCalls++
        if (failAuthentication) throw com.nexora.api.sat.domain.SatProtocolException("Autenticación rechazada (fake).")
        return "fake-token-${UUID.randomUUID()}"
    }

    override fun solicitarDescarga(
        token: String,
        rfc: String,
        tipo: CfdiTipo,
        desde: Instant,
        hasta: Instant,
        certificate: X509Certificate,
        privateKey: PrivateKey,
        rfcContraparte: String?,
    ): SatSolicitudResult = SatSolicitudResult(idSolicitud = "fake-solicitud-$tipo", codigoEstatus = "5000", mensaje = "Solicitud aceptada", exitosa = true)

    override fun verificarSolicitud(
        token: String,
        idSolicitud: String,
        rfc: String,
        certificate: X509Certificate,
        privateKey: PrivateKey,
    ): SatVerificacionResult = SatVerificacionResult(
        estado = SatDownloadRequestStatus.TERMINADA,
        codigoEstatus = "5000",
        mensaje = "Solicitud terminada",
        idsPaquetes = listOf("fake-paquete-1"),
    )

    override fun descargarPaquete(token: String, idPaquete: String, rfc: String, certificate: X509Certificate, privateKey: PrivateKey): ByteArray {
        descargarPaqueteCalls++
        return zip(nextPackageXmls)
    }
}

@TestConfiguration(proxyBeanMethods = false)
class TestSatSoapClientConfig {
    @Bean
    @Primary
    fun fakeSatSoapClient(): FakeSatSoapClient = FakeSatSoapClient()
}
