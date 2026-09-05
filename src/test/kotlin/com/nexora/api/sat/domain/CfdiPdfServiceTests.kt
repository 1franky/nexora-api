package com.nexora.api.sat.domain

import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertTrue

private const val EMISOR_RFC = "EKU9003173C9"
private const val RECEPTOR_RFC = "XAXX010101000"

/** Mismo fixture (con Conceptos + Impuestos + Timbre completo) que [com.nexora.api.support.testCfdiXml]. */
private fun cfdiXmlCompleto(uuid: String = UUID.randomUUID().toString()): String = """
    <cfdi:Comprobante xmlns:cfdi="http://www.sat.gob.mx/cfd/4" xmlns:tfd="http://www.sat.gob.mx/TimbreFiscalDigital"
        Version="4.0" Fecha="2026-01-15T12:30:00" SubTotal="1000.00" Total="1160.00" Moneda="MXN"
        FormaPago="03" MetodoPago="PUE" TipoDeComprobante="I" LugarExpedicion="06600"
        Sello="firmaDePruebaDelComprobante123456" NoCertificado="30001000000500003416">
      <cfdi:Emisor Rfc="$EMISOR_RFC" Nombre="Emisor de Prueba" RegimenFiscal="601"/>
      <cfdi:Receptor Rfc="$RECEPTOR_RFC" Nombre="Receptor de Prueba" UsoCFDI="G03" RegimenFiscalReceptor="616" DomicilioFiscalReceptor="06600"/>
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

class CfdiPdfServiceTests {

    private val service = CfdiPdfService()

    @Test
    fun `genera un PDF valido (encabezado PDF) a partir de un CFDI con conceptos e impuestos`() {
        val pdf = service.render(cfdiXmlCompleto().toByteArray())

        assertTrue(pdf.isNotEmpty())
        val header = String(pdf.copyOfRange(0, 5), Charsets.US_ASCII)
        assertTrue(header.startsWith("%PDF-"), "el archivo generado debe empezar con la cabecera %PDF- (fue: '$header')")
    }

    @Test
    fun `no revienta con un CFDI sin conceptos ni impuestos (fixture minimo de otros tests)`() {
        // Igual que el fixture mínimo de CfdiParserTests, sin Conceptos — no debe fallar, solo omitir esa sección.
        val xmlMinimo = """
            <cfdi:Comprobante xmlns:cfdi="http://www.sat.gob.mx/cfd/4" xmlns:tfd="http://www.sat.gob.mx/TimbreFiscalDigital"
                Version="4.0" Fecha="2026-01-15T12:30:00" SubTotal="1000.00" Total="1160.00" Moneda="MXN" Sello="s">
              <cfdi:Emisor Rfc="$EMISOR_RFC" Nombre="Emisor de Prueba"/>
              <cfdi:Receptor Rfc="$RECEPTOR_RFC" Nombre="Receptor de Prueba" UsoCFDI="G03"/>
              <cfdi:Complemento>
                <tfd:TimbreFiscalDigital UUID="${UUID.randomUUID()}" FechaTimbrado="2026-01-15T12:31:00"/>
              </cfdi:Complemento>
            </cfdi:Comprobante>
        """.trimIndent()

        val pdf = service.render(xmlMinimo.toByteArray())
        assertTrue(pdf.isNotEmpty())
    }
}
