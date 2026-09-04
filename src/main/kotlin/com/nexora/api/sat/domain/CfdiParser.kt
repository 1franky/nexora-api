package com.nexora.api.sat.domain

import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.xpath.XPathConstants
import javax.xml.xpath.XPathFactory

/**
 * XML de un CFDI → [CfdiInvoice] (plan-integracion-sat.md, sección 5-6).
 * Usa `local-name()` en vez de namespaces fijos porque el SAT ha tenido
 * varias versiones de CFDI (3.3, 4.0) con distinto namespace URI pero la
 * misma forma — así este parser no se rompe si aparece un XML de una
 * versión anterior en el historial descargado.
 */
@Component
class CfdiParser {

    private val xPath = XPathFactory.newInstance().newXPath()
    private val documentBuilderFactory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }

    /** [ownerRfc] es el RFC de la e.firma conectada — decide si el CFDI es EMITIDAS o RECIBIDAS para este usuario. */
    fun parse(userId: java.util.UUID, xmlBytes: ByteArray, ownerRfc: String): CfdiInvoice {
        val document = documentBuilderFactory.newDocumentBuilder().parse(xmlBytes.inputStream())

        val comprobante = xPath.evaluate("/*", document, XPathConstants.NODE) as org.w3c.dom.Element
        val emisorRfc = attr(document, "//*[local-name()='Emisor']/@Rfc")
        val receptorRfc = attr(document, "//*[local-name()='Receptor']/@Rfc")

        val uuid = attr(document, "//*[local-name()='TimbreFiscalDigital']/@UUID")
        require(uuid.isNotBlank()) { "El CFDI no tiene UUID (TimbreFiscalDigital) — ¿es un XML sin timbrar?" }

        // Suma en BigDecimal de punta a punta — pasar por Double perdería
        // precisión en montos fiscales (y de paso, la escala del literal
        // original), inaceptable aunque sea "solo" para mostrar el dato.
        val ivaTotal = xPath.evaluate("//*[local-name()='Traslado' and @Impuesto='002']/@Importe", document, XPathConstants.NODESET)
            .let { it as org.w3c.dom.NodeList }
            .let { nodes -> (0 until nodes.length).map { BigDecimal(nodes.item(it).textContent) } }
            .fold(BigDecimal.ZERO) { acc, importe -> acc.add(importe) }

        return CfdiInvoice(
            userId = userId,
            uuidFiscal = uuid,
            tipo = if (emisorRfc.equals(ownerRfc, ignoreCase = true)) CfdiTipo.EMITIDAS else CfdiTipo.RECIBIDAS,
            rfcEmisor = emisorRfc,
            nombreEmisor = attr(document, "//*[local-name()='Emisor']/@Nombre").ifBlank { null },
            rfcReceptor = receptorRfc,
            nombreReceptor = attr(document, "//*[local-name()='Receptor']/@Nombre").ifBlank { null },
            fechaEmision = parseFecha(comprobante.getAttribute("Fecha")),
            subtotal = comprobante.getAttribute("SubTotal").toBigDecimalOrZero(),
            iva = ivaTotal,
            total = comprobante.getAttribute("Total").toBigDecimalOrZero(),
            moneda = comprobante.getAttribute("Moneda").ifBlank { "MXN" },
            formaPago = comprobante.getAttribute("FormaPago").ifBlank { null },
            metodoPago = comprobante.getAttribute("MetodoPago").ifBlank { null },
            usoCfdi = attr(document, "//*[local-name()='Receptor']/@UsoCFDI").ifBlank { null },
            xmlContent = xmlBytes,
        )
    }

    private fun attr(document: org.w3c.dom.Document, expression: String): String =
        (xPath.evaluate(expression, document, XPathConstants.STRING) as String).trim()

    /** El SAT usa fecha local sin zona (ej. "2026-01-15T12:30:00") — se toma como hora de Ciudad de México (UTC-6, sin horario de verano desde 2022). */
    private fun parseFecha(raw: String): Instant =
        LocalDateTime.parse(raw).toInstant(ZoneOffset.ofHours(-6))

    private fun String.toBigDecimalOrZero(): BigDecimal = if (isBlank()) BigDecimal.ZERO else BigDecimal(this)
}
