package com.nexora.api.sat.domain

import org.apache.xml.security.Init
import org.apache.xml.security.algorithms.MessageDigestAlgorithm
import org.apache.xml.security.c14n.Canonicalizer
import org.apache.xml.security.signature.XMLSignature
import org.apache.xml.security.transforms.Transforms
import org.w3c.dom.Attr
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.security.PrivateKey
import java.security.cert.X509Certificate
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Firma XML (XMLDSig, enveloped) reutilizable para los 4 pasos del
 * protocolo del SAT (plan-integracion-sat.md, sección 3): tanto el
 * `Timestamp` de autenticación como el `<des:solicitud>`/`<des:peticion>`
 * de cada paso posterior se firman con el mismo esquema —
 * RSA-SHA256 + canonicalización C14N exclusiva + certificado embebido en
 * `KeyInfo` (el SAT valida contra ese certificado, no contra uno propio).
 *
 * Se apoya en Apache Santuario (`org.apache.santuario:xmlsec`), la
 * implementación de referencia de XML Security for Java — no reinventa la
 * canonicalización ni el cálculo del digest, ambos notoriamente fáciles de
 * hacer mal a mano.
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
     * `Id` con valor [elementId] — con o sin prefijo de namespace, p.ej.
     * tanto `Id="..."` como `wsu:Id="..."` del Timestamp de autenticación)
     * y anexa el nodo `<Signature>` resultante como hijo de ese mismo
     * elemento (enveloped signature — el patrón que usa el SAT en los 4
     * pasos, a diferencia de una signature separada apuntando por URI
     * externa).
     *
     * Se busca el atributo por nombre local y se marca con
     * `setIdAttributeNode` (no `setIdAttribute("Id", ...)`): este último
     * busca por nombre completo exacto y falla con `DOMException
     * NOT_FOUND_ERR` cuando el atributo real tiene prefijo (`wsu:Id` !=
     * `Id`) — se descubrió probando contra el SAT real.
     */
    fun signEnveloped(document: Document, elementToSign: Element, elementId: String, certificate: X509Certificate, privateKey: PrivateKey) {
        val signature = buildSignature(document, elementToSign, elementId)
        signature.addKeyInfo(certificate)
        signature.sign(privateKey)
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

    private fun buildSignature(document: Document, elementToSign: Element, elementId: String): XMLSignature {
        markIdAttribute(elementToSign)

        val signature = XMLSignature(
            document,
            "",
            XMLSignature.ALGO_ID_SIGNATURE_RSA_SHA256,
            Canonicalizer.ALGO_ID_C14N_EXCL_OMIT_COMMENTS,
        )
        elementToSign.appendChild(signature.element)

        // Sin comentarios, misma razón que en signWsSecurityHeader: debe
        // coincidir con el CanonicalizationMethod (ALGO_ID_C14N_EXCL_OMIT_COMMENTS).
        val transforms = Transforms(document)
        transforms.addTransform(Transforms.TRANSFORM_ENVELOPED_SIGNATURE)
        transforms.addTransform(Transforms.TRANSFORM_C14N_EXCL_OMIT_COMMENTS)
        signature.addDocument("#$elementId", transforms, MessageDigestAlgorithm.ALGO_ID_DIGEST_SHA256)
        return signature
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
