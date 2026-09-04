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
 * ⚠️ **[autenticar] probado contra el SAT real, todavía no funciona
 * (2026-09-04).** Con una e.firma real (RFC LOSF940729DX3) el SAT sigue
 * respondiendo `a:InvalidSecurity: An error occurred when verifying
 * security for the message` — un fallo genérico sin más detalle. En el
 * camino se confirmaron y corrigieron varios bugs reales (quedan aplicados
 * aquí y en [SatKeyReader]):
 * - El RFC del certificado no se leía bien: `X509Certificate.subjectX500Principal.name`
 *   no decodifica el OID 2.5.4.45 (queda como hex DER crudo) — corregido en
 *   [SatKeyReader.extractRfc] con la API de BouncyCastle.
 * - El `.key` real del SAT usa PBES2 con DES-EDE3-CBC, no el PBES1 que se
 *   había asumido — corregido en [SatKeyReader.readPrivateKey].
 * - El atributo `Id`/`wsu:Id` necesita namespace resuelto (`setAttributeNS`,
 *   no `setAttribute` con un prefijo como texto) para poder firmarlo y
 *   luego serializarlo — corregido en [SatXmlSignatureService].
 * - La firma del Timestamp debe ir con `KeyInfo` → `SecurityTokenReference`
 *   apuntando a un `BinarySecurityToken` (perfil WS-Security X.509 Token
 *   Profile completo), no un certificado embebido directo.
 *
 * **Estructura verificada contra un ejemplo real documentado públicamente**
 * (developers.sw.com.mx, "Descarga Masiva v1.5 – Autenticación") — la
 * estructura actual coincide exactamente: orden dentro de `wsse:Security`
 * (Timestamp, luego BinarySecurityToken, luego Signature — no al revés,
 * como se había probado antes), solo el Timestamp firmado (no el Body,
 * otra hipótesis descartada), RSA-SHA1 + SHA1 (no SHA256), canonicalización
 * exclusiva sin comentarios. Con la estructura ya alineada 1:1 al ejemplo
 * documentado, el SAT **sigue** respondiendo `InvalidSecurity` — el
 * problema restante es más sutil que la forma del XML.
 *
 * Hipótesis que faltan probar (quedan para retomar con más tiempo):
 * - Cadena de certificación completa en el `BinarySecurityToken` (solo se
 *   incluye el certificado del usuario, no el intermedio de la AC del SAT)
 *   — WCF a veces exige la cadena completa para poder validarla.
 * - Comparar byte a byte contra el request real de una librería que sí
 *   funcione (ej. `phpcfdi/sat-ws-descarga-masiva`, PHP) — no fue posible
 *   ejecutarla en esta sesión (requiere PHP + Composer, no disponibles),
 *   pero es la forma más confiable de encontrar la diferencia exacta.
 *
 * Con `NEXORA_SAT_DEBUG_XML=1` se imprime el XML de request completo (sin
 * datos sensibles: el certificado ya es público y la firma no es
 * reversible a la llave privada) — útil para retomar la depuración.
 *
 * Las URLs de los 3 endpoints y los namespaces de las operaciones
 * (`AUTH_NS`/`TYPES_NS`) parecen correctos — el SAT sí reconoce la
 * operación y el SOAPAction (si no, respondería un fallo de "acción no
 * reconocida", no `InvalidSecurity`); solo la verificación de la firma
 * falla. Los pasos 2-4 (`solicitarDescarga`/`verificarSolicitud`/
 * `descargarPaquete`) no se han podido probar todavía porque dependen de
 * pasar primero por [autenticar].
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

        // Orden exacto dentro de o:Security — confirmado contra un ejemplo
        // real documentado públicamente (developers.sw.com.mx, "Descarga
        // Masiva v1.5 – Autenticación"): Timestamp primero, luego
        // BinarySecurityToken, luego Signature. El orden invertido
        // (BinarySecurityToken antes que Timestamp, que se había probado
        // primero por instinto) es una causa real de InvalidSecurity.
        val timestampId = "_ts-${UUID.randomUUID()}"
        val now = Instant.now()
        val timestamp = document.createElementNS(WSU_NS, "u:Timestamp").also {
            // setAttributeNS (no setAttribute): el atributo debe quedar
            // realmente asociado al namespace WSU_NS, no solo tener un
            // prefijo "u:" como texto — si no, el serializador XML falla al
            // no poder resolver el prefijo del atributo al escribirlo
            // (aunque el elemento Timestamp sí declare xmlns:u). Verificado
            // contra el SAT real.
            it.setAttributeNS(WSU_NS, "u:Id", timestampId)
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

        // El SAT exige el perfil WS-Security X.509 Token Profile completo: un
        // BinarySecurityToken con el certificado, referenciado desde el
        // KeyInfo de la firma vía SecurityTokenReference — con el
        // certificado embebido directo en KeyInfo (la forma "simple" de
        // XMLDSig) el SAT responde `a:InvalidSecurity` aunque la firma en sí
        // sea válida. Verificado contra el SAT real.
        val bstId = "_bst-${UUID.randomUUID()}"
        document.createElementNS(WSSE_NS, "o:BinarySecurityToken").also {
            it.setAttributeNS(WSU_NS, "u:Id", bstId)
            it.setAttribute("ValueType", SatXmlSignatureService.X509_V3_VALUE_TYPE)
            it.setAttribute("EncodingType", "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-soap-message-security-1.0#Base64Binary")
            it.textContent = Base64.getEncoder().encodeToString(certificate.encoded)
            security.appendChild(it)
        }

        val body = document.createElementNS(SOAP_NS, "s:Body").also(envelope::appendChild)
        document.createElementNS(AUTH_NS, "Autentica").also(body::appendChild)

        // Solo el Timestamp va firmado — no el Body (se había probado
        // firmar ambos por instinto, pero el ejemplo real de referencia
        // firma únicamente el Timestamp).
        signer.signWsSecurityHeader(document, security, listOf(timestamp to timestampId), bstId, privateKey)

        val requestXml = toXmlString(document)
        if (System.getenv("NEXORA_SAT_DEBUG_XML") == "1") println("REQUEST XML:\n$requestXml")
        val responseXml = post(autenticacionUrl, requestXml, soapAction = "$AUTH_NS/IAutenticacion/Autentica")
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
