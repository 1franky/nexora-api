package com.nexora.api.sat.domain

import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

private const val EMISOR_RFC = "EKU9003173C9"
private const val RECEPTOR_RFC = "XAXX010101000"

/** CFDI 4.0 de ejemplo (forma real del XML que descarga el SAT) — RFCs de prueba, no reales. */
private fun cfdiXml(emisorRfc: String = EMISOR_RFC, receptorRfc: String = RECEPTOR_RFC, uuid: String = UUID.randomUUID().toString()): String = """
    <cfdi:Comprobante xmlns:cfdi="http://www.sat.gob.mx/cfd/4" xmlns:tfd="http://www.sat.gob.mx/TimbreFiscalDigital"
        Version="4.0" Fecha="2026-01-15T12:30:00" SubTotal="1000.00" Total="1160.00" Moneda="MXN"
        FormaPago="03" MetodoPago="PUE" TipoDeComprobante="I">
      <cfdi:Emisor Rfc="$emisorRfc" Nombre="Emisor de Prueba SA de CV" RegimenFiscal="601"/>
      <cfdi:Receptor Rfc="$receptorRfc" Nombre="Receptor de Prueba" UsoCFDI="G03"/>
      <cfdi:Impuestos TotalImpuestosTrasladados="160.00">
        <cfdi:Traslados>
          <cfdi:Traslado Base="1000.00" Impuesto="002" TipoFactor="Tasa" TasaOCuota="0.160000" Importe="160.00"/>
        </cfdi:Traslados>
      </cfdi:Impuestos>
      <cfdi:Complemento>
        <tfd:TimbreFiscalDigital UUID="$uuid" FechaTimbrado="2026-01-15T12:31:00"/>
      </cfdi:Complemento>
    </cfdi:Comprobante>
""".trimIndent()

class CfdiParserTests {

    private val parser = CfdiParser()
    private val userId: UUID = UUID.randomUUID()

    @Test
    fun `parsea correctamente los campos principales de un CFDI 4-0`() {
        val uuid = UUID.randomUUID().toString()
        val invoice = parser.parse(userId, cfdiXml(uuid = uuid).toByteArray(), ownerRfc = EMISOR_RFC)

        assertEquals(uuid, invoice.uuidFiscal)
        assertEquals(EMISOR_RFC, invoice.rfcEmisor)
        assertEquals("Emisor de Prueba SA de CV", invoice.nombreEmisor)
        assertEquals(RECEPTOR_RFC, invoice.rfcReceptor)
        assertEquals("Receptor de Prueba", invoice.nombreReceptor)
        assertEquals(BigDecimal("1000.00"), invoice.subtotal)
        assertEquals(BigDecimal("160.00"), invoice.iva)
        assertEquals(BigDecimal("1160.00"), invoice.total)
        assertEquals("MXN", invoice.moneda)
        assertEquals("03", invoice.formaPago)
        assertEquals("PUE", invoice.metodoPago)
        assertEquals("G03", invoice.usoCfdi)
    }

    @Test
    fun `clasifica como EMITIDAS cuando el RFC del dueno de la e-firma es el emisor`() {
        val invoice = parser.parse(userId, cfdiXml().toByteArray(), ownerRfc = EMISOR_RFC)
        assertEquals(CfdiTipo.EMITIDAS, invoice.tipo)
    }

    @Test
    fun `clasifica como RECIBIDAS cuando el RFC del dueno de la e-firma es el receptor`() {
        val invoice = parser.parse(userId, cfdiXml().toByteArray(), ownerRfc = RECEPTOR_RFC)
        assertEquals(CfdiTipo.RECIBIDAS, invoice.tipo)
    }

    @Test
    fun `un CFDI sin TimbreFiscalDigital (sin UUID) se rechaza`() {
        val xmlSinTimbre = cfdiXml().replace(Regex("<cfdi:Complemento>.*</cfdi:Complemento>", RegexOption.DOT_MATCHES_ALL), "")
        assertFailsWith<IllegalArgumentException> {
            parser.parse(userId, xmlSinTimbre.toByteArray(), ownerRfc = EMISOR_RFC)
        }
    }

    @Test
    fun `con el traslado repetido a nivel concepto (como trae un CFDI real), el iva no se duplica`() {
        // Un CFDI real siempre trae el desglose de impuestos DOS veces: una vez
        // por cada Concepto (línea) y otra vez ya agregado en el Impuestos de
        // nivel Comprobante. Antes de este fix, el XPath global sumaba ambos.
        val xmlConConceptoYSuPropioImpuesto = cfdiXml().replace(
            "<cfdi:Receptor Rfc=\"$RECEPTOR_RFC\" Nombre=\"Receptor de Prueba\" UsoCFDI=\"G03\"/>",
            """
            <cfdi:Receptor Rfc="$RECEPTOR_RFC" Nombre="Receptor de Prueba" UsoCFDI="G03"/>
            <cfdi:Conceptos>
              <cfdi:Concepto ClaveProdServ="81111500" Cantidad="1" ClaveUnidad="E48" Descripcion="Servicio" ValorUnitario="1000.00" Importe="1000.00">
                <cfdi:Impuestos>
                  <cfdi:Traslados>
                    <cfdi:Traslado Base="1000.00" Impuesto="002" TipoFactor="Tasa" TasaOCuota="0.160000" Importe="160.00"/>
                  </cfdi:Traslados>
                </cfdi:Impuestos>
              </cfdi:Concepto>
            </cfdi:Conceptos>
            """.trimIndent(),
        )
        val invoice = parser.parse(userId, xmlConConceptoYSuPropioImpuesto.toByteArray(), ownerRfc = EMISOR_RFC)
        assertEquals(BigDecimal("160.00"), invoice.iva, "no debe sumar el traslado del concepto y el del comprobante como si fueran dos IVAs distintos")
    }

    @Test
    fun `sin traslados de IVA, el iva queda en cero en vez de fallar`() {
        val xmlSinImpuestos = cfdiXml().replace(Regex("<cfdi:Impuestos[^>]*>.*</cfdi:Impuestos>", RegexOption.DOT_MATCHES_ALL), "")
        val invoice = parser.parse(userId, xmlSinImpuestos.toByteArray(), ownerRfc = EMISOR_RFC)
        assertEquals(BigDecimal.ZERO, invoice.iva)
    }

    @Test
    fun `Moneda ausente cae por defecto a MXN`() {
        val xmlSinMoneda = cfdiXml().replace("Moneda=\"MXN\" ", "")
        val invoice = parser.parse(userId, xmlSinMoneda.toByteArray(), ownerRfc = EMISOR_RFC)
        assertEquals("MXN", invoice.moneda)
    }

    @Test
    fun `FormaPago ausente queda null, no vacio`() {
        val xmlSinFormaPago = cfdiXml().replace("FormaPago=\"03\" ", "")
        val invoice = parser.parse(userId, xmlSinFormaPago.toByteArray(), ownerRfc = EMISOR_RFC)
        assertNull(invoice.formaPago)
    }
}
