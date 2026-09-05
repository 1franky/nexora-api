package com.nexora.api.sat.web

import com.nexora.api.common.domain.NotFoundException
import com.nexora.api.sat.domain.CfdiInvoiceRepository
import com.nexora.api.sat.domain.CfdiInvoiceSpecifications
import com.nexora.api.sat.domain.CfdiPdfService
import com.nexora.api.sat.domain.CfdiTipo
import com.nexora.api.sat.domain.SatCertificateService
import com.nexora.api.sat.domain.SatContraparteService
import com.nexora.api.sat.domain.SatSyncService
import com.nexora.api.user.security.NexoraUserDetails
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import java.time.Instant
import java.util.UUID

@Tag(name = "SAT", description = "Conexión de la e.firma y descarga de CFDI (facturas) del SAT — plan-integracion-sat.md.")
@RestController
@RequestMapping("/api/v1/sat")
class SatController(
    private val certificateService: SatCertificateService,
    private val syncService: SatSyncService,
    private val cfdiInvoiceRepository: CfdiInvoiceRepository,
    private val contraparteService: SatContraparteService,
    private val cfdiPdfService: CfdiPdfService,
) {

    @Operation(
        summary = "Conectar (o reemplazar) la e.firma",
        description = "Sube .cer + .key + contraseña. Valida el certificado y hace un login de prueba contra el SAT antes de guardar nada cifrado. Dispara la primera sincronización en background.",
    )
    @PostMapping("/certificate", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun connect(
        @AuthenticationPrincipal principal: NexoraUserDetails,
        @RequestPart("cer") cer: MultipartFile,
        @RequestPart("key") key: MultipartFile,
        @RequestParam("password") @NotBlank password: String,
    ): ResponseEntity<SatCertificateResponse> {
        val certificate = certificateService.connect(principal.userId, cer.bytes, key.bytes, password)
        syncService.syncIncrementalAsync(requireNotNull(certificate.id))
        return ResponseEntity.status(HttpStatus.CREATED).body(SatCertificateResponse.from(certificate))
    }

    @Operation(summary = "Consultar el estado de la conexión SAT (RFC, vigencia, última sincronización)")
    @GetMapping("/certificate")
    fun getCertificate(@AuthenticationPrincipal principal: NexoraUserDetails): SatCertificateResponse =
        SatCertificateResponse.from(certificateService.getOwned(principal.userId))

    @Operation(summary = "Desconectar la e.firma", description = "Borra el material sensible de forma permanente. No borra las facturas ya descargadas.")
    @DeleteMapping("/certificate")
    fun disconnect(@AuthenticationPrincipal principal: NexoraUserDetails): ResponseEntity<Void> {
        certificateService.revoke(principal.userId)
        return ResponseEntity.noContent().build()
    }

    @Operation(
        summary = "Forzar una sincronización",
        description = "Sin body (o body vacío): trae lo nuevo desde la última sync. Con `desde`/`hasta`: trae ese rango explícito, sin importar la última sync (plan, sección 6.1 — para buscar facturas antiguas).",
    )
    @PostMapping("/sync")
    fun sync(
        @AuthenticationPrincipal principal: NexoraUserDetails,
        @RequestBody(required = false) request: SyncRequest?,
    ): ResponseEntity<Void> {
        val certificate = certificateService.getOwned(principal.userId)
        val certificateId = requireNotNull(certificate.id)
        if (request?.desde != null && request.hasta != null) {
            syncService.syncRangeAsync(certificateId, request.desde, request.hasta)
        } else {
            syncService.syncIncrementalAsync(certificateId)
        }
        return ResponseEntity.accepted().build()
    }

    @Operation(summary = "Listar facturas", description = "Todos los filtros son opcionales. `texto` busca por RFC, nombre de emisor/receptor y folio fiscal (UUID).")
    @GetMapping("/invoices")
    fun listInvoices(
        @AuthenticationPrincipal principal: NexoraUserDetails,
        @Parameter(description = "EMITIDAS o RECIBIDAS.") @RequestParam(required = false) tipo: CfdiTipo?,
        @RequestParam(required = false) desde: Instant?,
        @RequestParam(required = false) hasta: Instant?,
        @RequestParam(required = false) texto: String?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "25") size: Int,
    ): Page<CfdiInvoiceResponse> {
        val pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "fechaEmision"))
        val trimmedTexto = texto?.trim()?.takeIf { it.isNotEmpty() }
        val spec = CfdiInvoiceSpecifications.search(principal.userId, tipo, desde, hasta, trimmedTexto)
        return cfdiInvoiceRepository.findAll(spec, pageable).map(CfdiInvoiceResponse::from)
    }

    @Operation(summary = "Descargar el XML crudo de una factura")
    @GetMapping("/invoices/{id}/xml")
    fun downloadXml(
        @AuthenticationPrincipal principal: NexoraUserDetails,
        @Parameter(description = "Id de la factura (no el UUID fiscal).") @PathVariable id: UUID,
    ): ResponseEntity<ByteArray> {
        val invoice = ownedInvoice(principal.userId, id)
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_XML)
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"${invoice.uuidFiscal}.xml\"")
            .body(invoice.xmlContent)
    }

    @Operation(
        summary = "Descargar la representación impresa (PDF) de una factura",
        description = "Se genera al vuelo a partir del XML ya guardado — no es el documento fiscal en sí (eso es el XML), es la versión legible que normalmente se comparte/imprime.",
    )
    @GetMapping("/invoices/{id}/pdf")
    fun downloadPdf(
        @AuthenticationPrincipal principal: NexoraUserDetails,
        @Parameter(description = "Id de la factura (no el UUID fiscal).") @PathVariable id: UUID,
    ): ResponseEntity<ByteArray> {
        val invoice = ownedInvoice(principal.userId, id)
        val pdf = cfdiPdfService.render(invoice.xmlContent)
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_PDF)
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"${invoice.uuidFiscal}.pdf\"")
            .body(pdf)
    }

    private fun ownedInvoice(userId: UUID, invoiceId: UUID) =
        cfdiInvoiceRepository.findById(invoiceId)
            .filter { it.userId == userId }
            .orElseThrow { NotFoundException("Factura no encontrada.") }

    @Operation(
        summary = "Listar los RFC de contraparte registrados",
        description = "El SAT exige el RFC del emisor específico para descargar RECIBIDAS (B12) — cada sync trae recibidas de cada uno de estos RFC.",
    )
    @GetMapping("/contrapartes")
    fun listContrapartes(@AuthenticationPrincipal principal: NexoraUserDetails): List<SatContraparteResponse> =
        contraparteService.listForUser(principal.userId).map(SatContraparteResponse::from)

    @Operation(summary = "Registrar un RFC de contraparte")
    @PostMapping("/contrapartes")
    fun createContraparte(
        @AuthenticationPrincipal principal: NexoraUserDetails,
        @Valid @RequestBody request: CreateSatContraparteRequest,
    ): ResponseEntity<SatContraparteResponse> {
        val contraparte = contraparteService.create(principal.userId, request.rfc, request.alias)
        return ResponseEntity.status(HttpStatus.CREATED).body(SatContraparteResponse.from(contraparte))
    }

    @Operation(summary = "Eliminar un RFC de contraparte", description = "No borra las facturas ya descargadas de ese RFC, solo deja de sincronizarlo.")
    @DeleteMapping("/contrapartes/{id}")
    fun deleteContraparte(
        @AuthenticationPrincipal principal: NexoraUserDetails,
        @Parameter(description = "Id del RFC de contraparte.") @PathVariable id: UUID,
    ): ResponseEntity<Void> {
        contraparteService.delete(principal.userId, id)
        return ResponseEntity.noContent().build()
    }
}
