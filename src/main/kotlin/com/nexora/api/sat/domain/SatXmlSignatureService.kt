package com.nexora.api.sat.domain

import org.apache.xml.security.Init
import org.apache.xml.security.algorithms.MessageDigestAlgorithm
import org.apache.xml.security.c14n.Canonicalizer
import org.apache.xml.security.keys.content.X509Data
import org.apache.xml.security.signature.XMLSignature
import org.apache.xml.security.transforms.Transforms
import org.bouncycastle.asn1.x500.style.BCStyle
import org.bouncycastle.cert.jcajce.JcaX509CertificateHolder
import org.w3c.dom.Attr
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.security.PrivateKey
import java.security.cert.X509Certificate
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Firma XML (XMLDSig) para los 3 pasos del protocolo del SAT posteriores a
 * la Autenticación (plan-integracion-sat.md, sección 3):
 * `SolicitaDescarga*`/`VerificaSolicitudDescarga`/
 * `PeticionDescargaMasivaTercerosEntrada` — todos firman su `<des:solicitud>`/
 * `<des:peticionDescarga>` de forma "enveloped" ([signEnveloped]). El paso
 * de Autenticación en sí usa un esquema WS-Security distinto
 * ([signWsSecurityHeader]) — ver [SatWsDescargaMasivaClient.autenticar],
 * que además construye su XML a mano en vez de vía esta clase (los
 * detalles finos de WS-Security no se prestan bien a una API genérica).
 *
 * Se apoya en Apache Santuario (`org.apache.santuario:xmlsec`), la
 * implementación de referencia de XML Security for Java — no reinventa la
 * canonicalización ni el cálculo del digest, ambos notoriamente fáciles de
 * hacer mal a mano. Toda la forma exacta (algoritmos, transforms, KeyInfo)
 * se verificó contra la documentación oficial del SAT y contra el SAT real
 * con una e.firma real (2026-09-04).
 */
class SatXmlSignatureService {

    companion object {
        const val WSSE_NS = "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd"
        const val X509_V3_VALUE_TYPE = "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-x509-token-profile-1.0#X509v3"

        init {
            // Idempotente pero obligatorio antes de usar cualquier API de Santuario.
            Init.init()
        }
    }

    fun newDocumentBuilder() = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder()

    /**
     * Firma [elementToSign] (que debe tener un atributo de nombre local
     * `Id` con valor [elementId]) y anexa el `<Signature>` resultante como
     * hijo de ese mismo elemento (enveloped) — el patrón que usan
     * `SolicitaDescarga*`/`VerificaSolicitudDescarga`/
     * `PeticionDescargaMasivaTercerosEntrada`, confirmado contra dos
     * fuentes reales (documentación oficial del SAT y
     * `phpcfdi/sat-ws-descarga-masiva`, verificado con una e.firma real
     * 2026-09-04): RSA-SHA1, canonicalización **estándar** (no exclusiva —
     * distinta de [signWsSecurityHeader], que sí usa exclusiva para el
     * paso de Autenticación), un solo transform (`enveloped-signature`,
     * sin transform de canonicalización aparte), y `KeyInfo` con
     * `X509Data/X509IssuerSerial` (no `SecurityTokenReference`, eso es
     * solo para Autenticación).
     */
    fun signEnveloped(document: Document, elementToSign: Element, elementId: String, certificate: X509Certificate, privateKey: PrivateKey) {
        markIdAttribute(elementToSign)

        val signature = XMLSignature(
            document,
            "",
            XMLSignature.ALGO_ID_SIGNATURE_RSA_SHA1,
            Canonicalizer.ALGO_ID_C14N_OMIT_COMMENTS,
        )
        elementToSign.appendChild(signature.element)

        val transforms = Transforms(document)
        transforms.addTransform(Transforms.TRANSFORM_ENVELOPED_SIGNATURE)
        signature.addDocument("#$elementId", transforms, MessageDigestAlgorithm.ALGO_ID_DIGEST_SHA1)

        val x509Data = X509Data(document)
        x509Data.addIssuerSerial(issuerRfc4514(certificate), certificate.serialNumber)
        x509Data.addCertificate(certificate)
        signature.keyInfo.add(x509Data)

        signature.sign(privateKey)
    }

    /**
     * Nombre del emisor del certificado en formato RFC4514 — el mismo que
     * usa OpenSSL (y por ende `phpcfdi/credentials`). BouncyCastle's
     * `BCStyle` produce algo muy cercano pero con nombres cortos distintos
     * (`E=`, `STREET=`, `PostalCode=`, `UniqueIdentifier=` en vez de
     * `emailAddress=`, `street=`, `postalCode=`, `x500UniqueIdentifier=`) y
     * escapa comas como `\, ` en vez de `\2c` — normalizado a mano tras
     * comparar byte a byte contra la salida real de PHP con una e.firma real.
     */
    private fun issuerRfc4514(certificate: X509Certificate): String {
        val issuer = JcaX509CertificateHolder(certificate).issuer
        var result = BCStyle.INSTANCE.toString(issuer)
        result = Regex("(?<=^|,)E=").replace(result, "emailAddress=")
        result = Regex("(?<=^|,)STREET=").replace(result, "street=")
        result = Regex("(?<=^|,)PostalCode=").replace(result, "postalCode=")
        result = Regex("(?<=^|,)UniqueIdentifier=").replace(result, "x500UniqueIdentifier=")
        return result.replace("\\, ", "\\2c")
    }

    /**
     * Firma WS-Security del header de Autenticación
     * ([SatWsDescargaMasivaClient.autenticar]) — distinta de [signEnveloped]
     * en dos puntos, ambos parte del perfil WS-Security X.509 Token
     * Profile que exige el SAT ahí (verificado contra el SAT real; con la
     * forma "simple" de [signEnveloped] responde `a:InvalidSecurity` aunque
     * la firma en sí sea válida):
     *
     * - El nodo `<Signature>` se agrega como **hermano** de los elementos
     *   firmados dentro de [signatureParent] (el header `wsse:Security`),
     *   no como hijo anidado de ninguno de ellos — por eso NO lleva el
     *   transform enveloped-signature, solo canonicalización exclusiva.
     * - El `KeyInfo` referencia un `wsse:BinarySecurityToken` ya presente
     *   en el documento (por su `Id`) en vez de embeber el certificado
     *   directo.
     *
     * [elementsToSign] normalmente son dos: el Timestamp y el Body del SOAP
     * (bindings WCF típicos firman ambos juntos, no solo el Timestamp).
     */
    fun signWsSecurityHeader(
        document: Document,
        signatureParent: Element,
        elementsToSign: List<Pair<Element, String>>,
        binarySecurityTokenId: String,
        privateKey: PrivateKey,
    ) {
        elementsToSign.forEach { (element, _) -> markIdAttribute(element) }

        // RSA-SHA1/SHA1 (no SHA256): el protocolo de Descarga Masiva del SAT
        // es de ~2016, de la época en que WS-Security todavía usaba SHA1 por
        // default — probando contra el SAT real tras varias iteraciones con
        // SHA256 sin éxito.
        val signature = XMLSignature(
            document,
            "",
            XMLSignature.ALGO_ID_SIGNATURE_RSA_SHA1,
            Canonicalizer.ALGO_ID_C14N_EXCL_OMIT_COMMENTS,
        )
        signatureParent.appendChild(signature.element)

        // Sin comentarios (no TRANSFORM_C14N_EXCL_WITH_COMMENTS): debe
        // coincidir con el CanonicalizationMethod de arriba
        // (ALGO_ID_C14N_EXCL_OMIT_COMMENTS) — la inconsistencia entre ambos
        // era una causa real de InvalidSecurity contra el SAT.
        elementsToSign.forEach { (_, elementId) ->
            val transforms = Transforms(document)
            transforms.addTransform(Transforms.TRANSFORM_C14N_EXCL_OMIT_COMMENTS)
            signature.addDocument("#$elementId", transforms, MessageDigestAlgorithm.ALGO_ID_DIGEST_SHA1)
        }

        val securityTokenReference = document.createElementNS(WSSE_NS, "o:SecurityTokenReference")
        val reference = document.createElementNS(WSSE_NS, "o:Reference").also {
            it.setAttribute("URI", "#$binarySecurityTokenId")
            it.setAttribute("ValueType", X509_V3_VALUE_TYPE)
        }
        securityTokenReference.appendChild(reference)
        signature.keyInfo.addUnknownElement(securityTokenReference)

        signature.sign(privateKey)
    }

    private fun markIdAttribute(elementToSign: Element) {
        // `localName` solo viene poblado si el atributo se creó con un método
        // namespace-aware (setAttributeNS); con setAttribute("u:Id", ...) el
        // DOM lo trata como un nombre plano sin resolver el prefijo, así que
        // localName queda null y hay que mirar `name` y quitarle el prefijo
        // a mano — verificado contra el atributo wsu:Id real del Timestamp.
        val idAttribute = (0 until elementToSign.attributes.length)
            .map { elementToSign.attributes.item(it) as Attr }
            .firstOrNull { (it.localName ?: it.name.substringAfter(':')) == "Id" }
            ?: error("El elemento a firmar no tiene un atributo Id (buscado: local-name()='Id').")
        elementToSign.setIdAttributeNode(idAttribute, true)
    }
}
