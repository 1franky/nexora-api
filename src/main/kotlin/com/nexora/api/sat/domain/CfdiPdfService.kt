package com.nexora.api.sat.domain

import com.google.zxing.BarcodeFormat
import com.google.zxing.client.j2se.MatrixToImageWriter
import com.google.zxing.qrcode.QRCodeWriter
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder
import org.springframework.stereotype.Component
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.NodeList
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.xpath.XPathConstants
import javax.xml.xpath.XPathFactory

/**
 * Representación impresa (PDF) de un CFDI, a partir del mismo XML que ya
 * guarda [CfdiInvoice.xmlContent] — no hace falta persistir nada nuevo: el
 * conceptos y los datos de timbrado se vuelven a leer del XML original cada
 * vez que se pide el PDF (bajo demanda, no en la sync).
 *
 * Usa `local-name()` en el XPath por el mismo motivo que [CfdiParser]: no
 * atarse a un namespace URI de una versión de CFDI en particular.
 */
@Component
class CfdiPdfService {

    private val xPath = XPathFactory.newInstance().newXPath()
    private val documentBuilderFactory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
    private val fechaFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss")

    fun render(xmlBytes: ByteArray): ByteArray {
        val document = documentBuilderFactory.newDocumentBuilder().parse(xmlBytes.inputStream())
        val comprobante = xPath.evaluate("/*", document, XPathConstants.NODE) as Element

        val emisor = ComprobanteParte(
            rfc = attr(document, "//*[local-name()='Emisor']/@Rfc"),
            nombre = attr(document, "//*[local-name()='Emisor']/@Nombre").ifBlank { null },
            regimenFiscal = attr(document, "//*[local-name()='Emisor']/@RegimenFiscal").ifBlank { null },
        )
        val receptor = ComprobanteParte(
            rfc = attr(document, "//*[local-name()='Receptor']/@Rfc"),
            nombre = attr(document, "//*[local-name()='Receptor']/@Nombre").ifBlank { null },
            regimenFiscal = attr(document, "//*[local-name()='Receptor']/@RegimenFiscalReceptor").ifBlank { null },
            usoCfdi = attr(document, "//*[local-name()='Receptor']/@UsoCFDI").ifBlank { null },
            domicilioFiscal = attr(document, "//*[local-name()='Receptor']/@DomicilioFiscalReceptor").ifBlank { null },
        )

        val conceptos = (xPath.evaluate("//*[local-name()='Conceptos']/*[local-name()='Concepto']", document, XPathConstants.NODESET) as NodeList)
            .let { nodes -> (0 until nodes.length).map { nodes.item(it) as Element } }
            .map {
                Concepto(
                    cantidad = it.getAttribute("Cantidad").toBigDecimalOrZero(),
                    unidad = it.getAttribute("Unidad").ifBlank { it.getAttribute("ClaveUnidad") },
                    descripcion = it.getAttribute("Descripcion").ifBlank { "—" },
                    valorUnitario = it.getAttribute("ValorUnitario").toBigDecimalOrZero(),
                    importe = it.getAttribute("Importe").toBigDecimalOrZero(),
                )
            }

        val traslados = sumarImpuestos(document, "Traslados", "Traslado")
        val retenciones = sumarImpuestos(document, "Retenciones", "Retencion")

        val timbre = Timbre(
            uuid = attr(document, "//*[local-name()='TimbreFiscalDigital']/@UUID"),
            fechaTimbrado = attr(document, "//*[local-name()='TimbreFiscalDigital']/@FechaTimbrado").ifBlank { null },
            selloCfd = attr(document, "//*[local-name()='TimbreFiscalDigital']/@SelloCFD").ifBlank { null },
            noCertificadoSat = attr(document, "//*[local-name()='TimbreFiscalDigital']/@NoCertificadoSAT").ifBlank { null },
            selloSat = attr(document, "//*[local-name()='TimbreFiscalDigital']/@SelloSAT").ifBlank { null },
            rfcProvCertif = attr(document, "//*[local-name()='TimbreFiscalDigital']/@RfcProvCertif").ifBlank { null },
        )

        val datos = CfdiDatos(
            serie = comprobante.getAttribute("Serie").ifBlank { null },
            folio = comprobante.getAttribute("Folio").ifBlank { null },
            fecha = comprobante.getAttribute("Fecha"),
            sello = comprobante.getAttribute("Sello"),
            noCertificado = comprobante.getAttribute("NoCertificado").ifBlank { null },
            lugarExpedicion = comprobante.getAttribute("LugarExpedicion").ifBlank { null },
            metodoPago = comprobante.getAttribute("MetodoPago").ifBlank { null },
            formaPago = comprobante.getAttribute("FormaPago").ifBlank { null },
            moneda = comprobante.getAttribute("Moneda").ifBlank { "MXN" },
            subtotal = comprobante.getAttribute("SubTotal").toBigDecimalOrZero(),
            descuento = comprobante.getAttribute("Descuento").ifBlank { null }?.toBigDecimalOrZero(),
            total = comprobante.getAttribute("Total").toBigDecimalOrZero(),
            emisor = emisor,
            receptor = receptor,
            conceptos = conceptos,
            traslados = traslados,
            retenciones = retenciones,
            timbre = timbre,
        )

        val qrCode = qrCodeBase64(verificationUrl(datos))
        return htmlToPdf(buildHtml(datos, qrCode))
    }

    /** URL oficial de verificación de un CFDI en el portal del SAT — formato documentado, usado en el QR de toda representación impresa. */
    private fun verificationUrl(datos: CfdiDatos): String {
        val totalConSeisDecimales = datos.total.setScale(6, RoundingMode.HALF_UP).toPlainString()
        val ultimos8DelSello = datos.sello.takeLast(8)
        return "https://verificacfdi.facturaelectronica.sat.gob.mx/default.aspx" +
            "?id=${datos.timbre.uuid}&re=${datos.emisor.rfc}&rr=${datos.receptor.rfc}&tt=$totalConSeisDecimales&fe=$ultimos8DelSello"
    }

    /**
     * Solo los nodos que cuelgan DIRECTO del Impuestos de nivel Comprobante
     * (no de cada Concepto) — mismo motivo que en [CfdiParser.parse]: sumar
     * ambos niveles duplicaría el importe.
     */
    private fun sumarImpuestos(document: Document, contenedor: String, tag: String): List<ImpuestoResumen> {
        val nodes = xPath.evaluate(
            "/*/*[local-name()='Impuestos']/*[local-name()='$contenedor']/*[local-name()='$tag' and @Impuesto]",
            document,
            XPathConstants.NODESET,
        ) as NodeList
        return (0 until nodes.length).map { nodes.item(it) as Element }
            .groupBy { it.getAttribute("Impuesto") to it.getAttribute("TasaOCuota") }
            .map { (clave, elementos) ->
                val (impuesto, tasa) = clave
                ImpuestoResumen(
                    nombre = nombreImpuesto(impuesto),
                    tasa = tasa,
                    importe = elementos.fold(BigDecimal.ZERO) { acc, el -> acc.add(el.getAttribute("Importe").toBigDecimalOrZero()) },
                )
            }
    }

    private fun nombreImpuesto(clave: String): String = when (clave) {
        "001" -> "ISR"
        "002" -> "IVA"
        "003" -> "IEPS"
        else -> clave
    }

    private fun attr(document: Document, expression: String): String = (xPath.evaluate(expression, document, XPathConstants.STRING) as String).trim()

    private fun String.toBigDecimalOrZero(): BigDecimal = if (isBlank()) BigDecimal.ZERO else BigDecimal(this)

    private fun qrCodeBase64(content: String): String {
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 220, 220)
        val image = MatrixToImageWriter.toBufferedImage(matrix)
        val out = ByteArrayOutputStream()
        javax.imageio.ImageIO.write(image, "PNG", out)
        return Base64.getEncoder().encodeToString(out.toByteArray())
    }

    private fun money(value: BigDecimal, moneda: String): String =
        "$${value.setScale(2, RoundingMode.HALF_UP).toPlainString()} $moneda"

    private fun formatFecha(raw: String?): String =
        raw?.let { runCatching { LocalDateTime.parse(it).format(fechaFormatter) }.getOrDefault(it) } ?: "—"

    private fun buildHtml(d: CfdiDatos, qrCodeBase64: String): String {
        val conceptosRows = d.conceptos.joinToString("") {
            """
            <tr>
              <td>${it.cantidad.stripTrailingZeros().toPlainString()}</td>
              <td>${it.unidad}</td>
              <td>${it.descripcion}</td>
              <td class="num">${money(it.valorUnitario, d.moneda)}</td>
              <td class="num">${money(it.importe, d.moneda)}</td>
            </tr>
            """.trimIndent()
        }
        fun impuestoRow(it: ImpuestoResumen, negativo: Boolean): String {
            val tasaTexto = if (it.tasa.isNotBlank()) " (${BigDecimal(it.tasa).multiply(BigDecimal(100)).stripTrailingZeros().toPlainString()}%)" else ""
            val signo = if (negativo) "-" else ""
            return """<tr><td>${it.nombre}$tasaTexto</td><td class="num">$signo${money(it.importe, d.moneda)}</td></tr>"""
        }
        val impuestosRows = d.traslados.joinToString("") { impuestoRow(it, negativo = false) } +
            d.retenciones.joinToString("") { impuestoRow(it, negativo = true) }

        return """
        <html>
        <head>
        <style>
          body { font-family: Helvetica, Arial, sans-serif; font-size: 10px; color: #1a1a1a; }
          h1 { font-size: 16px; margin-bottom: 2px; }
          .subtitle { color: #666; margin-bottom: 16px; }
          .row { display: flex; }
          .box { border: 1px solid #ccc; border-radius: 4px; padding: 10px; width: 48%; margin-right: 2%; vertical-align: top; }
          table { width: 100%; border-collapse: collapse; margin-top: 12px; }
          th, td { border-bottom: 1px solid #ddd; padding: 5px; text-align: left; font-size: 9px; }
          th { background: #f2f2f2; }
          td.num, th.num { text-align: right; }
          .totales { width: 260px; float: right; margin-top: 10px; }
          .totales td { border: none; padding: 3px 5px; }
          .total-final { font-size: 13px; font-weight: bold; border-top: 1px solid #333; }
          .footer { clear: both; margin-top: 40px; padding-top: 10px; border-top: 1px solid #ccc; font-size: 8px; color: #555; }
          .qr { float: left; margin-right: 12px; }
          .field { margin-bottom: 4px; }
          .label { color: #777; }
        </style>
        </head>
        <body>
          <h1>Comprobante Fiscal Digital por Internet</h1>
          <div class="subtitle">Folio fiscal (UUID): ${d.timbre.uuid}</div>

          <div class="row">
            <div class="box">
              <strong>Emisor</strong>
              <div class="field">${d.emisor.nombre ?: "—"}</div>
              <div class="field"><span class="label">RFC:</span> ${d.emisor.rfc}</div>
              <div class="field"><span class="label">Régimen fiscal:</span> ${d.emisor.regimenFiscal ?: "—"}</div>
              <div class="field"><span class="label">Lugar de expedición:</span> ${d.lugarExpedicion ?: "—"}</div>
            </div>
            <div class="box">
              <strong>Receptor</strong>
              <div class="field">${d.receptor.nombre ?: "—"}</div>
              <div class="field"><span class="label">RFC:</span> ${d.receptor.rfc}</div>
              <div class="field"><span class="label">Uso CFDI:</span> ${d.receptor.usoCfdi ?: "—"}</div>
              <div class="field"><span class="label">Régimen fiscal receptor:</span> ${d.receptor.regimenFiscal ?: "—"}</div>
            </div>
          </div>

          <div class="row" style="margin-top: 10px;">
            <div class="box">
              <div class="field"><span class="label">Serie / Folio:</span> ${d.serie ?: "—"} ${d.folio ?: ""}</div>
              <div class="field"><span class="label">Fecha de emisión:</span> ${formatFecha(d.fecha)}</div>
              <div class="field"><span class="label">Método de pago:</span> ${d.metodoPago ?: "—"}</div>
              <div class="field"><span class="label">Forma de pago:</span> ${d.formaPago ?: "—"}</div>
            </div>
            <div class="box">
              <div class="field"><span class="label">Fecha de timbrado:</span> ${formatFecha(d.timbre.fechaTimbrado)}</div>
              <div class="field"><span class="label">No. de certificado SAT:</span> ${d.timbre.noCertificadoSat ?: "—"}</div>
              <div class="field"><span class="label">No. de certificado emisor:</span> ${d.noCertificado ?: "—"}</div>
              <div class="field"><span class="label">RFC proveedor de certificación:</span> ${d.timbre.rfcProvCertif ?: "—"}</div>
            </div>
          </div>

          <table>
            <thead>
              <tr><th>Cant.</th><th>Unidad</th><th>Descripción</th><th class="num">Valor unitario</th><th class="num">Importe</th></tr>
            </thead>
            <tbody>
              $conceptosRows
            </tbody>
          </table>

          <table class="totales">
            <tr><td>Subtotal</td><td class="num">${money(d.subtotal, d.moneda)}</td></tr>
            ${if (d.descuento != null) """<tr><td>Descuento</td><td class="num">-${money(d.descuento, d.moneda)}</td></tr>""" else ""}
            $impuestosRows
            <tr class="total-final"><td>Total</td><td class="num">${money(d.total, d.moneda)}</td></tr>
          </table>

          <div class="footer">
            <img class="qr" src="data:image/png;base64,$qrCodeBase64" width="90" height="90"/>
            <div><strong>Sello digital del CFDI:</strong><br/>${d.sello.chunked(90).joinToString("<br/>")}</div>
            <div style="margin-top: 6px;"><strong>Sello del SAT:</strong><br/>${(d.timbre.selloSat ?: "—").chunked(90).joinToString("<br/>")}</div>
            <div style="margin-top: 6px;">Este documento es una representación impresa de un CFDI, generada por Nexora a partir del XML timbrado por el SAT.</div>
          </div>
        </body>
        </html>
        """.trimIndent()
    }

    private fun htmlToPdf(html: String): ByteArray {
        val out = ByteArrayOutputStream()
        PdfRendererBuilder().apply {
            useFastMode()
            withHtmlContent(html, null)
            toStream(out)
        }.run()
        return out.toByteArray()
    }
}

private data class ComprobanteParte(
    val rfc: String,
    val nombre: String?,
    val regimenFiscal: String? = null,
    val usoCfdi: String? = null,
    val domicilioFiscal: String? = null,
)

private data class Concepto(
    val cantidad: BigDecimal,
    val unidad: String,
    val descripcion: String,
    val valorUnitario: BigDecimal,
    val importe: BigDecimal,
)

private data class ImpuestoResumen(val nombre: String, val tasa: String, val importe: BigDecimal)

private data class Timbre(
    val uuid: String,
    val fechaTimbrado: String?,
    val selloCfd: String?,
    val noCertificadoSat: String?,
    val selloSat: String?,
    val rfcProvCertif: String?,
)

private data class CfdiDatos(
    val serie: String?,
    val folio: String?,
    val fecha: String,
    val sello: String,
    val noCertificado: String?,
    val lugarExpedicion: String?,
    val metodoPago: String?,
    val formaPago: String?,
    val moneda: String,
    val subtotal: BigDecimal,
    val descuento: BigDecimal?,
    val total: BigDecimal,
    val emisor: ComprobanteParte,
    val receptor: ComprobanteParte,
    val conceptos: List<Concepto>,
    val traslados: List<ImpuestoResumen>,
    val retenciones: List<ImpuestoResumen>,
    val timbre: Timbre,
)
