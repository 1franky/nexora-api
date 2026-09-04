package com.nexora.api.sat.domain

import org.apache.xml.security.Init
import org.apache.xml.security.algorithms.MessageDigestAlgorithm
import org.apache.xml.security.c14n.Canonicalizer
import org.apache.xml.security.signature.XMLSignature
import org.apache.xml.security.transforms.Transforms
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
        init {
            // Idempotente pero obligatorio antes de usar cualquier API de Santuario.
            Init.init()
        }
    }

    fun newDocumentBuilder() = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder()

    /**
     * Firma [elementToSign] (que debe tener un atributo `Id` con valor
     * [elementId]) y anexa el nodo `<Signature>` resultante como hijo de
     * ese mismo elemento (enveloped signature — el patrón que usa el SAT
     * en los 4 pasos, a diferencia de una signature separada apuntando por
     * URI externa).
     */
    fun signEnveloped(document: Document, elementToSign: Element, elementId: String, certificate: X509Certificate, privateKey: PrivateKey) {
        elementToSign.setIdAttribute("Id", true)

        val signature = XMLSignature(
            document,
            "",
            XMLSignature.ALGO_ID_SIGNATURE_RSA_SHA256,
            Canonicalizer.ALGO_ID_C14N_EXCL_OMIT_COMMENTS,
        )
        elementToSign.appendChild(signature.element)

        val transforms = Transforms(document)
        transforms.addTransform(Transforms.TRANSFORM_ENVELOPED_SIGNATURE)
        transforms.addTransform(Transforms.TRANSFORM_C14N_EXCL_WITH_COMMENTS)
        signature.addDocument("#$elementId", transforms, MessageDigestAlgorithm.ALGO_ID_DIGEST_SHA256)

        signature.addKeyInfo(certificate)
        signature.sign(privateKey)
    }
}
