package com.nexora.api.sat.domain

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.w3c.dom.Document
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.X509Certificate
import java.time.Instant
import java.time.ZoneId
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
private val XMLDSIG_NS = "http://www.w3.org/2000/09/xmldsig#"
private val X509_V3_VALUE_TYPE = "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-x509-token-profile-1.0#X509v3"
private val AUTH_NS = "http://DescargaMasivaTerceros.gob.mx"
private val TYPES_NS = "http://DescargaMasivaTerceros.sat.gob.mx"
// PHP (phpcfdi/sat-ws-descarga-masiva, implementación de referencia que sí
// funciona contra el SAT real, verificado 2026-09-04) siempre pone ".000"
// como milisegundos del Timestamp, no los reales — se replica tal cual en
// vez de arriesgar una diferencia de formato no probada.
private val TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'.000Z'").withZone(ZoneOffset.UTC)
// FechaInicial/FechaFinal de SolicitaDescarga* van sin zona horaria, en hora
// local de México (igual que phpcfdi, que usa la zona horaria por defecto
// del proceso PHP) — no es un timestamp de protocolo, es un rango de
// negocio de fechas de emisión de CFDI.
private val SOLICITUD_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")

/**
 * Implementación real de [SatSoapClient] contra el Web Service oficial de
 * Descarga Masiva de CFDI del SAT (plan-integracion-sat.md, sección 3).
 *
 * ✅ **[autenticar] confirmado funcionando contra el SAT real (2026-09-04)**,
 * con una e.firma real — token recibido. Costó varias iteraciones porque el
 * SAT es más estricto que el estándar XMLDSig respecto a la forma exacta
 * del WS-Security del paso de autenticación (BinarySecurityToken con
 * `SecurityTokenReference` en `KeyInfo`, orden Timestamp→BST→Signature
 * dentro de `wsse:Security`, RSA-SHA1/SHA1, canonicalización exclusiva) —
 * se terminó de cerrar reproduciendo byte a byte una librería de referencia
 * que sí funciona en producción (`phpcfdi/sat-ws-descarga-masiva`, PHP),
 * de ahí que este método construya el XML a mano en vez de usar
 * [SatXmlSignatureService].
 *
 * ✅ **[solicitarDescarga]/[verificarSolicitud] confirmados funcionando
 * contra el SAT real (2026-09-04)**, para EMITIDAS y RECIBIDAS. Usan un
 * esquema de firma distinto al de Autenticación (confirmado contra la
 * documentación oficial del SAT — un PDF completo, leído con `pdftotext`
 * — y contra un ejemplo real v1.5 de developers.sw.com.mx): canonicalización
 * **estándar** (no exclusiva), un solo transform `enveloped-signature`, y
 * `KeyInfo` con `X509Data/X509IssuerSerial` (no `SecurityTokenReference`) —
 * implementado en [SatXmlSignatureService.signEnveloped]. Dos detalles no
 * documentados que solo aparecieron probando contra el SAT real:
 * - `EstadoComprobante="Todos"` es inválido cuando `TipoSolicitud="CFDI"`
 *   (XML completo) — el SAT no permite descargar cancelados como XML, solo
 *   como Metadata. Se usa `"Vigente"`.
 * - `<des:RfcReceptores>` va **solo** en `SolicitaDescargaEmitidos` (aunque
 *   vacío); incluirlo en `SolicitaDescargaRecibidos` causa `302 Sello Mal
 *   Formado` (un error de firma, no de negocio) — no se documenta esto en
 *   ningún lado, se descubrió por ensayo y error.
 *
 * ⏳ **[descargarPaquete] sin confirmar con datos reales todavía.** Con
 * EMITIDAS, la cuenta de prueba no tenía CFDI en el rango — el SAT
 * respondió correctamente que no había información (no es un fallo). Con
 * RECIBIDAS y una contraparte real que sí facturó a la cuenta de prueba, la
 * solicitud fue aceptada (`5000`) y el SAT estuvo activamente `EN_PROCESO`
 * varios minutos (dos corridas: 6 y 11 intentos de poll antes de fallar) —
 * fuerte indicio de que sí había datos reales — pero terminó en
 * `CodigoEstadoSolicitud` no confirmado / `CodEstatus="404" Mensaje="Error
 * no controlado"` en ambas corridas. La documentación oficial describe 404
 * como un error genérico e inespecífico del propio servicio del SAT
 * ("reintentar; si persiste, reportar por RMA"), consistente con que la
 * solicitud sí avanzó bastante antes de fallar. Pendiente: más reintentos
 * en otro momento, o revisar si hay un límite de volumen/tiempo no
 * documentado que el SAT esté aplicando en su propio backend.
 *
 * Con `NEXORA_SAT_DEBUG_XML=1` se imprime el XML de cada request completo
 * (sin datos sensibles: el certificado ya es público y la firma no es
 * reversible a la llave privada) — útil para seguir depurando.
 */
@Component
class SatWsDescargaMasivaClient(
    @Value("\${nexora.sat.autenticacion-url:https://cfdidescargamasivasolicitud.clouda.sat.gob.mx/Autenticacion/Autenticacion.svc}")
    private val autenticacionUrl: String,
    @Value("\${nexora.sat.solicitud-url:https://cfdidescargamasivasolicitud.clouda.sat.gob.mx/SolicitaDescargaService.svc}")
    private val solicitudUrl: String,
    // Endpoint propio (no el mismo que solicitudUrl) — confirmado contra el
    // código fuente de phpcfdi/sat-ws-descarga-masiva, referencia que sí
    // funciona en producción.
    @Value("\${nexora.sat.verifica-url:https://cfdidescargamasivasolicitud.clouda.sat.gob.mx/VerificaSolicitudDescargaService.svc}")
    private val verificaUrl: String,
    @Value("\${nexora.sat.descarga-url:https://cfdidescargamasiva.clouda.sat.gob.mx/DescargaMasivaService.svc}")
    private val descargaUrl: String,
) : SatSoapClient {

    private val log = LoggerFactory.getLogger(SatWsDescargaMasivaClient::class.java)
    private val signer = SatXmlSignatureService()
    private val xPath = XPathFactory.newInstance().newXPath()
    private val restClient = RestClient.builder().build()

    /**
     * Construcción manual de string (no DOM + Apache Santuario, a
     * diferencia de [solicitarDescarga]/[verificarSolicitud]/[descargarPaquete])
     * — replica byte a byte la forma que usa `phpcfdi/sat-ws-descarga-masiva`,
     * una librería PHP que **sí funciona contra el SAT real** (confirmado
     * 2026-09-04 con esta misma e.firma). El enfoque con Apache Santuario
     * producía una firma criptográficamente válida pero el SAT la rechazaba
     * igual (`InvalidSecurity`) tras múltiples correcciones de estructura —
     * la sospecha es que WCF, del lado del SAT, es más estricto de lo que
     * el estándar XMLDSig exige respecto a la forma exacta del XML (prefijos
     * de namespace, por ejemplo), y la única forma de estar seguro de
     * calzar es reproducir un ejemplo que ya se sabe que funciona.
     */
    override fun autenticar(certificate: X509Certificate, privateKey: PrivateKey): String {
        val timestampId = "_0"
        val now = Instant.now()
        val created = TIMESTAMP_FORMAT.format(now)
        val expires = TIMESTAMP_FORMAT.format(now.plusSeconds(300))
        val bstId = "uuid-${UUID.randomUUID()}-1"
        val certificateBase64 = Base64.getEncoder().encodeToString(certificate.encoded)

        // Se firma solo el Timestamp — el mismo contenido, pero esta copia
        // declara xmlns:u localmente (necesario para canonicalizarlo de
        // forma aislada); en el envelope final el Timestamp hereda xmlns:u
        // del propio s:Envelope, sin redeclararlo.
        val timestampToDigest = """<u:Timestamp xmlns:u="$WSU_NS" u:Id="$timestampId"><u:Created>$created</u:Created><u:Expires>$expires</u:Expires></u:Timestamp>"""
        val digest = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest(timestampToDigest.toByteArray(Charsets.UTF_8)))

        val signedInfo = """<SignedInfo xmlns="$XMLDSIG_NS"><CanonicalizationMethod Algorithm="http://www.w3.org/2001/10/xml-exc-c14n#"></CanonicalizationMethod><SignatureMethod Algorithm="http://www.w3.org/2000/09/xmldsig#rsa-sha1"></SignatureMethod><Reference URI="#$timestampId"><Transforms><Transform Algorithm="http://www.w3.org/2001/10/xml-exc-c14n#"></Transform></Transforms><DigestMethod Algorithm="http://www.w3.org/2000/09/xmldsig#sha1"></DigestMethod><DigestValue>$digest</DigestValue></Reference></SignedInfo>"""
        val signatureValue = Signature.getInstance("SHA1withRSA").apply {
            initSign(privateKey)
            update(signedInfo.toByteArray(Charsets.UTF_8))
        }.sign().let { Base64.getEncoder().encodeToString(it) }
        // El SignedInfo del documento final no redeclara xmlns (hereda del
        // Signature padre) — pero SÍ se firmó con el xmlns explícito arriba,
        // replicando lo que produciría canonicalizar el subárbol aislado.
        val signedInfoForOutput = signedInfo.replace("""<SignedInfo xmlns="$XMLDSIG_NS">""", "<SignedInfo>")

        val signatureBlock = """<Signature xmlns="$XMLDSIG_NS">$signedInfoForOutput<SignatureValue>$signatureValue</SignatureValue><KeyInfo><o:SecurityTokenReference><o:Reference URI="#$bstId" ValueType="$X509_V3_VALUE_TYPE"/></o:SecurityTokenReference></KeyInfo></Signature>"""

        val requestXml = """<s:Envelope xmlns:s="$SOAP_NS" xmlns:u="$WSU_NS"><s:Header><o:Security xmlns:o="$WSSE_NS" s:mustUnderstand="1"><u:Timestamp u:Id="$timestampId"><u:Created>$created</u:Created><u:Expires>$expires</u:Expires></u:Timestamp><o:BinarySecurityToken u:Id="$bstId" ValueType="$X509_V3_VALUE_TYPE" EncodingType="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-soap-message-security-1.0#Base64Binary">$certificateBase64</o:BinarySecurityToken>$signatureBlock</o:Security></s:Header><s:Body><Autentica xmlns="$AUTH_NS"/></s:Body></s:Envelope>"""

        if (System.getenv("NEXORA_SAT_DEBUG_XML") == "1") println("REQUEST XML:\n$requestXml")
        val responseXml = post(autenticacionUrl, requestXml, soapAction = "$AUTH_NS/IAutenticacion/Autentica")
        val token = xPath.evaluate("//*[local-name()='AutenticaResult']", parse(responseXml), XPathConstants.STRING) as String
        if (token.isBlank()) {
            throw SatProtocolException("El SAT no devolvió token de autenticación — revisar respuesta cruda en logs con nivel DEBUG.")
        }
        return token.trim()
    }

    /**
     * Igual que [autenticar]: construcción manual de string, no DOM +
     * Apache Santuario, replicando `phpcfdi/sat-ws-descarga-masiva`.
     *
     * [rfcContraparte] (RECIBIDAS): en `FielRequestBuilder::queryIssuedReceived`
     * de esa librería (fuente real revisada 2026-09-05, no solo su doc), el
     * `RfcEmisor` sale de `RfcMatches::getFirst()->getValue()` — que devuelve
     * `''` si no se especificó ningún RFC (`RfcMatches::getFirst()` no revienta
     * con la lista vacía), y `buildFinalXml` filtra del XML cualquier atributo
     * en `''`. Es decir: la librería sí arma `SolicitaDescargaRecibidos` sin
     * `RfcEmisor` en absoluto cuando no se filtra por un emisor específico —
     * contradice una suposición anterior de este archivo ("el SAT exige un
     * RfcEmisor específico para RECIBIDAS"), que quedaba de una prueba previa
     * cuyo error real pudo deberse a otra causa (posiblemente el mismo bug de
     * `RfcReceptores` en RECIBIDAS que sí está confirmado más abajo). Pendiente
     * de una prueba real end-to-end para confirmar del todo — mientras tanto,
     * ya no se rechaza de antemano: se omite el atributo si no hay contraparte.
     */
    override fun solicitarDescarga(
        token: String,
        rfc: String,
        tipo: CfdiTipo,
        desde: Instant,
        hasta: Instant,
        certificate: X509Certificate,
        privateKey: PrivateKey,
        rfcContraparte: String?,
    ): SatSolicitudResult {
        val nodeName = if (tipo == CfdiTipo.EMITIDAS) "SolicitaDescargaEmitidos" else "SolicitaDescargaRecibidos"
        val cdmx = ZoneId.of("America/Mexico_City")
        val fechaInicial = SOLICITUD_DATE_FORMAT.format(desde.atZone(cdmx))
        val fechaFinal = SOLICITUD_DATE_FORMAT.format(hasta.atZone(cdmx))

        val document = signer.newDocumentBuilder().newDocument()
        val envelope = document.createElementNS(SOAP_NS, "s:Envelope").also(document::appendChild)
        document.createElementNS(SOAP_NS, "s:Header").also(envelope::appendChild)
        val body = document.createElementNS(SOAP_NS, "s:Body").also(envelope::appendChild)
        val peticion = document.createElementNS(TYPES_NS, "des:$nodeName").also(body::appendChild)
        val solicitudId = "_${UUID.randomUUID()}"
        val solicitud = document.createElementNS(TYPES_NS, "des:solicitud").also {
            it.setAttribute("Id", solicitudId)
            // "Todos" (incluye cancelados) no es válido cuando TipoSolicitud="CFDI"
            // (XML completo) — el SAT responde "No se permite la descarga
            // de xml que se encuentren cancelados" (verificado contra el
            // SAT real). Solo aplica a "Metadata"; para XML completo debe
            // ser "Vigente" (o "Cancelado" en una solicitud aparte, si
            // alguna vez hace falta traer específicamente cancelados).
            it.setAttribute("EstadoComprobante", "Vigente")
            it.setAttribute("FechaInicial", fechaInicial)
            it.setAttribute("FechaFinal", fechaFinal)
            it.setAttribute("RfcSolicitante", rfc)
            it.setAttribute("TipoSolicitud", "CFDI")
            // EMITIDAS: el usuario es el emisor (RfcEmisor = rfc, siempre presente).
            // RECIBIDAS: el usuario es el receptor (RfcReceptor = rfc, siempre
            // presente); RfcEmisor solo se agrega si se quiere acotar a una
            // contraparte específica — se omite del todo si no (ver el
            // docstring de solicitarDescarga sobre por qué esto debería ser
            // válido para el SAT, replicando phpcfdi/sat-ws-descarga-masiva).
            if (tipo == CfdiTipo.EMITIDAS) {
                it.setAttribute("RfcEmisor", rfc)
            } else {
                if (!rfcContraparte.isNullOrBlank()) it.setAttribute("RfcEmisor", rfcContraparte)
                it.setAttribute("RfcReceptor", rfc)
            }
            peticion.appendChild(it)
        }
        // Solo para EMITIDAS — presente incluso vacío ahí. Para RECIBIDAS no
        // debe aparecer en absoluto (confirmado contra el SAT real:
        // incluirlo causaba "302 Sello Mal Formado").
        if (tipo == CfdiTipo.EMITIDAS) {
            document.createElementNS(TYPES_NS, "des:RfcReceptores").also(solicitud::appendChild)
        }

        signer.signEnveloped(document, solicitud, solicitudId, certificate, privateKey)

        val requestXml = toXmlString(document)
        if (System.getenv("NEXORA_SAT_DEBUG_XML") == "1") println("REQUEST XML (solicitar):\n$requestXml")
        val responseXml = post(solicitudUrl, requestXml, soapAction = "$TYPES_NS/ISolicitaDescargaService/$nodeName", bearer = token)
        val responseDoc = parse(responseXml)
        val idSolicitud = xPathValue(responseDoc, "//*[local-name()='${nodeName}Result']/@IdSolicitud")
        val codigo = xPathValue(responseDoc, "//*[local-name()='${nodeName}Result']/@CodEstatus")
        val mensaje = xPathValue(responseDoc, "//*[local-name()='${nodeName}Result']/@Mensaje")
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
        val document = signer.newDocumentBuilder().newDocument()
        val envelope = document.createElementNS(SOAP_NS, "s:Envelope").also(document::appendChild)
        document.createElementNS(SOAP_NS, "s:Header").also(envelope::appendChild)
        val body = document.createElementNS(SOAP_NS, "s:Body").also(envelope::appendChild)
        val peticion = document.createElementNS(TYPES_NS, "des:VerificaSolicitudDescarga").also(body::appendChild)
        val solicitudId = "_${UUID.randomUUID()}"
        val solicitud = document.createElementNS(TYPES_NS, "des:solicitud").also {
            it.setAttribute("Id", solicitudId)
            it.setAttribute("IdSolicitud", idSolicitud)
            it.setAttribute("RfcSolicitante", rfc)
            peticion.appendChild(it)
        }
        signer.signEnveloped(document, solicitud, solicitudId, certificate, privateKey)

        val requestXml = toXmlString(document)
        if (System.getenv("NEXORA_SAT_DEBUG_XML") == "1") println("REQUEST XML (verificar):\n$requestXml")
        // Endpoint propio (verificaUrl), no solicitudUrl — confirmado contra la librería de referencia.
        val responseXml = post(verificaUrl, requestXml, soapAction = "$TYPES_NS/IVerificaSolicitudDescargaService/VerificaSolicitudDescarga", bearer = token)
        if (System.getenv("NEXORA_SAT_DEBUG_XML") == "1") println("RESPONSE XML (verificar):\n$responseXml")
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
        val document = signer.newDocumentBuilder().newDocument()
        val envelope = document.createElementNS(SOAP_NS, "s:Envelope").also(document::appendChild)
        document.createElementNS(SOAP_NS, "s:Header").also(envelope::appendChild)
        val body = document.createElementNS(SOAP_NS, "s:Body").also(envelope::appendChild)
        val peticion = document.createElementNS(TYPES_NS, "des:PeticionDescargaMasivaTercerosEntrada").also(body::appendChild)
        val solicitudId = "_${UUID.randomUUID()}"
        val solicitud = document.createElementNS(TYPES_NS, "des:peticionDescarga").also {
            it.setAttribute("Id", solicitudId)
            it.setAttribute("IdPaquete", idPaquete)
            it.setAttribute("RfcSolicitante", rfc)
            peticion.appendChild(it)
        }
        signer.signEnveloped(document, solicitud, solicitudId, certificate, privateKey)

        val requestXml = toXmlString(document)
        if (System.getenv("NEXORA_SAT_DEBUG_XML") == "1") println("REQUEST XML (descargar):\n$requestXml")
        val responseXml = post(descargaUrl, requestXml, soapAction = "$TYPES_NS/IDescargaMasivaTercerosService/Descargar", bearer = token)
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
