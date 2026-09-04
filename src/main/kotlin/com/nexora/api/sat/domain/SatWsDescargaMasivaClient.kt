package com.nexora.api.sat.domain

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.w3c.dom.Document
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.UUID
import java.util.zip.ZipInputStream
import javax.xml.xpath.XPathConstants
import javax.xml.xpath.XPathFactory

private val SOAP_NS = "http://schemas.xmlsoap.org/soap/envelope/"
private val WSSE_NS = "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd"
private val WSU_NS = "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd"
private val AUTH_NS = "http://DescargaMasivaTerceros.gob.mx"
private val TYPES_NS = "http://DescargaMasivaTerceros.sat.gob.mx"
private val TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

/**
 * Implementación real de [SatSoapClient] contra el Web Service oficial de
 * Descarga Masiva de CFDI del SAT (plan-integracion-sat.md, sección 3).
 *
 * ⚠️ **Verificar antes de producción**: las URLs de los 3 endpoints y la
 * forma exacta de los envelopes SOAP (namespaces, WS-Security del paso de
 * autenticación) son las documentadas públicamente por implementaciones de
 * referencia del protocolo al momento de escribir este cliente — el SAT
 * ajusta detalles de este servicio de vez en cuando. Antes de usarlo con
 * datos reales: probar los 4 pasos contra el SAT con una e.firma de
 * prueba y ajustar aquí lo que no calce (especialmente [autenticar], la
 * parte más sensible a cambios de protocolo).
 */
@Component
class SatWsDescargaMasivaClient(
    @Value("\${nexora.sat.autenticacion-url:https://cfdidescargamasivasolicitud.clouda.sat.gob.mx/Autenticacion/Autenticacion.svc}")
    private val autenticacionUrl: String,
    @Value("\${nexora.sat.solicitud-url:https://cfdidescargamasivasolicitud.clouda.sat.gob.mx/SolicitaDescargaService.svc}")
    private val solicitudUrl: String,
    @Value("\${nexora.sat.descarga-url:https://cfdidescargamasiva.clouda.sat.gob.mx/DescargaMasivaService.svc}")
    private val descargaUrl: String,
) : SatSoapClient {

    private val log = LoggerFactory.getLogger(SatWsDescargaMasivaClient::class.java)
    private val signer = SatXmlSignatureService()
    private val xPath = XPathFactory.newInstance().newXPath()
    private val restClient = RestClient.builder().build()

    override fun autenticar(certificate: X509Certificate, privateKey: PrivateKey): String {
        val builder = signer.newDocumentBuilder()
        val document = builder.newDocument()

        val envelope = document.createElementNS(SOAP_NS, "s:Envelope")
        document.appendChild(envelope)
        val header = document.createElementNS(SOAP_NS, "s:Header").also(envelope::appendChild)
        val security = document.createElementNS(WSSE_NS, "o:Security").also {
            it.setAttributeNS(SOAP_NS, "s:mustUnderstand", "1")
            header.appendChild(it)
        }

        val timestampId = "_ts-${UUID.randomUUID()}"
        val now = Instant.now()
        val timestamp = document.createElementNS(WSU_NS, "u:Timestamp").also {
            it.setAttribute("u:Id", timestampId)
            security.appendChild(it)
        }
        document.createElementNS(WSU_NS, "u:Created").also {
            it.textContent = TIMESTAMP_FORMAT.format(now)
            timestamp.appendChild(it)
        }
        document.createElementNS(WSU_NS, "u:Expires").also {
            it.textContent = TIMESTAMP_FORMAT.format(now.plusSeconds(300))
            timestamp.appendChild(it)
        }

        signer.signEnveloped(document, timestamp, timestampId, certificate, privateKey)

        val body = document.createElementNS(SOAP_NS, "s:Body").also(envelope::appendChild)
        document.createElementNS(AUTH_NS, "Autentica").also(body::appendChild)

        val responseXml = post(autenticacionUrl, toXmlString(document), soapAction = "$AUTH_NS/IAutenticacion/Autentica")
        val token = xPath.evaluate("//*[local-name()='AutenticaResult']", parse(responseXml), XPathConstants.STRING) as String
        if (token.isBlank()) {
            throw SatProtocolException("El SAT no devolvió token de autenticación — revisar respuesta cruda en logs con nivel DEBUG.")
        }
        return token.trim()
    }

    override fun solicitarDescarga(
        token: String,
        rfc: String,
        tipo: CfdiTipo,
        desde: Instant,
        hasta: Instant,
        certificate: X509Certificate,
        privateKey: PrivateKey,
    ): SatSolicitudResult {
        val builder = signer.newDocumentBuilder()
        val document = builder.newDocument()
        val envelope = document.createElementNS(SOAP_NS, "s:Envelope").also(document::appendChild)
        document.createElementNS(SOAP_NS, "s:Header").also(envelope::appendChild)
        val body = document.createElementNS(SOAP_NS, "s:Body").also(envelope::appendChild)

        val peticion = document.createElementNS(TYPES_NS, "SolicitaDescarga").also(body::appendChild)
        val solicitudId = "_req-${UUID.randomUUID()}"
        val solicitud = document.createElementNS(TYPES_NS, "solicitud").also {
            it.setAttribute("Id", solicitudId)
            it.setAttribute("FechaInicial", desde.toString())
            it.setAttribute("FechaFinal", hasta.toString())
            it.setAttribute("RfcSolicitante", rfc)
            // EMITIDAS = el usuario es el emisor -> se filtra por RfcEmisor; RECIBIDAS por RfcReceptor.
            if (tipo == CfdiTipo.EMITIDAS) it.setAttribute("RfcEmisor", rfc) else it.setAttribute("RfcReceptor", rfc)
            peticion.appendChild(it)
        }
        signer.signEnveloped(document, solicitud, solicitudId, certificate, privateKey)

        val responseXml = post(solicitudUrl, toXmlString(document), soapAction = "$TYPES_NS/ISolicitaDescargaService/SolicitaDescarga", bearer = token)
        val responseDoc = parse(responseXml)
        val idSolicitud = xPathValue(responseDoc, "//*[local-name()='SolicitaDescargaResult']/@IdSolicitud")
        val codigo = xPathValue(responseDoc, "//*[local-name()='SolicitaDescargaResult']/@CodEstatus")
        val mensaje = xPathValue(responseDoc, "//*[local-name()='SolicitaDescargaResult']/@Mensaje")
        return SatSolicitudResult(
            idSolicitud = idSolicitud.ifBlank { null },
            codigoEstatus = codigo,
            mensaje = mensaje,
            exitosa = idSolicitud.isNotBlank(),
        )
    }

    override fun verificarSolicitud(
        token: String,
        idSolicitud: String,
        rfc: String,
        certificate: X509Certificate,
        privateKey: PrivateKey,
    ): SatVerificacionResult {
        val builder = signer.newDocumentBuilder()
        val document = builder.newDocument()
        val envelope = document.createElementNS(SOAP_NS, "s:Envelope").also(document::appendChild)
        document.createElementNS(SOAP_NS, "s:Header").also(envelope::appendChild)
        val body = document.createElementNS(SOAP_NS, "s:Body").also(envelope::appendChild)

        val peticion = document.createElementNS(TYPES_NS, "VerificaSolicitudDescarga").also(body::appendChild)
        val solicitudId = "_verif-${UUID.randomUUID()}"
        val solicitud = document.createElementNS(TYPES_NS, "solicitud").also {
            it.setAttribute("Id", solicitudId)
            it.setAttribute("IdSolicitud", idSolicitud)
            it.setAttribute("RfcSolicitante", rfc)
            peticion.appendChild(it)
        }
        signer.signEnveloped(document, solicitud, solicitudId, certificate, privateKey)

        val responseXml = post(solicitudUrl, toXmlString(document), soapAction = "$TYPES_NS/ISolicitaDescargaService/VerificaSolicitudDescarga", bearer = token)
        val responseDoc = parse(responseXml)
        val estadoSolicitud = xPathValue(responseDoc, "//*[local-name()='VerificaSolicitudDescargaResult']/@EstadoSolicitud")
        val codigo = xPathValue(responseDoc, "//*[local-name()='VerificaSolicitudDescargaResult']/@CodEstatus")
        val mensaje = xPathValue(responseDoc, "//*[local-name()='VerificaSolicitudDescargaResult']/@Mensaje")
        val idsPaquetes = xPath.evaluate("//*[local-name()='IdsPaquetes']", responseDoc, XPathConstants.NODESET)
            .let { it as org.w3c.dom.NodeList }
            .let { nodes -> (0 until nodes.length).map { nodes.item(it).textContent } }

        return SatVerificacionResult(
            estado = mapEstado(estadoSolicitud),
            codigoEstatus = codigo,
            mensaje = mensaje,
            idsPaquetes = idsPaquetes,
        )
    }

    override fun descargarPaquete(
        token: String,
        idPaquete: String,
        rfc: String,
        certificate: X509Certificate,
        privateKey: PrivateKey,
    ): ByteArray {
        val builder = signer.newDocumentBuilder()
        val document = builder.newDocument()
        val envelope = document.createElementNS(SOAP_NS, "s:Envelope").also(document::appendChild)
        document.createElementNS(SOAP_NS, "s:Header").also(envelope::appendChild)
        val body = document.createElementNS(SOAP_NS, "s:Body").also(envelope::appendChild)

        val peticion = document.createElementNS(TYPES_NS, "PeticionDescargaMasivaTercerosEntrada").also(body::appendChild)
        val solicitudId = "_desc-${UUID.randomUUID()}"
        val solicitud = document.createElementNS(TYPES_NS, "peticionDescarga").also {
            it.setAttribute("Id", solicitudId)
            it.setAttribute("IdPaquete", idPaquete)
            it.setAttribute("RfcSolicitante", rfc)
            peticion.appendChild(it)
        }
        signer.signEnveloped(document, solicitud, solicitudId, certificate, privateKey)

        val responseXml = post(descargaUrl, toXmlString(document), soapAction = "$TYPES_NS/IDescargaMasivaTercerosService/Descargar", bearer = token)
        val responseDoc = parse(responseXml)
        val base64Paquete = xPathValue(responseDoc, "//*[local-name()='Paquete']")
        if (base64Paquete.isBlank()) {
            throw SatProtocolException("El SAT no devolvió el paquete $idPaquete (¿ya expiró? tienen vigencia corta, ~72h).")
        }
        return Base64.getMimeDecoder().decode(base64Paquete)
    }

    private fun mapEstado(estadoSolicitud: String): SatDownloadRequestStatus = when (estadoSolicitud) {
        "1" -> SatDownloadRequestStatus.PENDIENTE
        "2" -> SatDownloadRequestStatus.EN_PROCESO
        "3" -> SatDownloadRequestStatus.TERMINADA
        "4" -> SatDownloadRequestStatus.ERROR
        "5" -> SatDownloadRequestStatus.RECHAZADA
        else -> SatDownloadRequestStatus.ERROR
    }

    private fun post(url: String, xml: String, soapAction: String, bearer: String? = null): String {
        try {
            val request = restClient.post()
                .uri(url)
                .contentType(MediaType.TEXT_XML)
                .header("SOAPAction", soapAction)
            bearer?.let { request.header("Authorization", "WRAP access_token=\"$it\"") }
            return request.body(xml).retrieve().body(String::class.java)
                ?: throw SatProtocolException("Respuesta vacía del SAT en $url.")
        } catch (e: RestClientException) {
            log.warn("Error llamando al SAT ({}): {}", url, e.message)
            throw SatProtocolException("Error de comunicación con el SAT: ${e.message}", e)
        }
    }

    private fun parse(xml: String): Document = signer.newDocumentBuilder().parse(xml.byteInputStream())

    private fun xPathValue(document: Document, expression: String): String =
        (xPath.evaluate(expression, document, XPathConstants.STRING) as String).trim()

    private fun toXmlString(document: Document): String {
        val transformer = javax.xml.transform.TransformerFactory.newInstance().newTransformer()
        val writer = java.io.StringWriter()
        transformer.transform(javax.xml.transform.dom.DOMSource(document), javax.xml.transform.stream.StreamResult(writer))
        return writer.toString()
    }
}

/** Descomprime el `.zip` que devuelve [SatSoapClient.descargarPaquete] — cada entrada es el XML de un CFDI. */
fun unzipCfdiXmls(zipBytes: ByteArray): List<ByteArray> {
    val entries = mutableListOf<ByteArray>()
    ZipInputStream(zipBytes.inputStream()).use { zip ->
        generateSequence { zip.nextEntry }.forEach { entry ->
            if (!entry.isDirectory) entries += zip.readBytes()
            zip.closeEntry()
        }
    }
    return entries
}
