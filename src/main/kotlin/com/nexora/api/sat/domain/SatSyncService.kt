package com.nexora.api.sat.domain

import com.nexora.api.common.domain.NotFoundException
import com.nexora.api.config.SatProperties
import com.nexora.api.notification.domain.Notification
import com.nexora.api.notification.domain.NotificationRepository
import com.nexora.api.notification.domain.NotificationType
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Service
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Orquesta el flujo completo de descarga masiva (plan-integracion-sat.md,
 * sección 3 y 6): Solicitud → poll de Verificación → Descarga → parseo →
 * guardado, para EMITIDAS y RECIBIDAS. Se usa tanto para la sync
 * automática incremental ([syncIncrementalAsync], cron diario vía
 * [SatSyncScheduler]) como para la sync manual por rango de fechas
 * explícito ([syncRangeAsync], sección 6.1 — buscar historial antiguo no
 * es un caso especial, es el mismo método con un rango distinto).
 */
@Service
class SatSyncService(
    private val certificateRepository: SatCertificateRepository,
    private val downloadRequestRepository: SatDownloadRequestRepository,
    private val cfdiInvoiceRepository: CfdiInvoiceRepository,
    private val certificateService: SatCertificateService,
    private val soapClient: SatSoapClient,
    private val cfdiParser: CfdiParser,
    private val notificationRepository: NotificationRepository,
    private val properties: SatProperties,
    private val contraparteRepository: SatContraparteRfcRepository,
) {

    private val log = LoggerFactory.getLogger(SatSyncService::class.java)

    /** Desde la última sync exitosa (o [SatProperties.initialSyncMonths] atrás si es la primera vez) hasta ahora. */
    @Async
    fun syncIncrementalAsync(satCertificateId: UUID) {
        val certificate = certificateRepository.findById(satCertificateId).orElse(null) ?: return
        val desde = certificate.lastSyncAt ?: Instant.now().minus(properties.initialSyncMonths * 30, ChronoUnit.DAYS)
        runSyncAndNotify(satCertificateId, desde, Instant.now())
    }

    /** Sync manual por rango de fechas explícito (plan, sección 6.1) — para traer historial que quedó fuera de la ventana automática. */
    @Async
    fun syncRangeAsync(satCertificateId: UUID, desde: Instant, hasta: Instant) {
        runSyncAndNotify(satCertificateId, desde, hasta)
    }

    private fun runSyncAndNotify(satCertificateId: UUID, desde: Instant, hasta: Instant) {
        val certificate = certificateRepository.findById(satCertificateId).orElse(null) ?: return
        try {
            val nuevas = syncNow(satCertificateId, desde, hasta)
            notify(
                userId = certificate.userId,
                type = NotificationType.SAT_SYNC_COMPLETED,
                title = "Sincronización SAT completada",
                message = if (nuevas > 0) "Se descargaron $nuevas factura(s) nueva(s)." else "No hay facturas nuevas en el rango consultado.",
            )
        } catch (e: Exception) {
            log.error("Falló la sincronización SAT del certificado {}: {}", satCertificateId, e.message, e)
            notify(
                userId = certificate.userId,
                type = NotificationType.SAT_SYNC_FAILED,
                title = "No se pudo sincronizar con el SAT",
                message = e.message ?: "Error desconocido — intenta de nuevo más tarde.",
            )
        }
    }

    /**
     * Bloqueante (incluye el polling del protocolo, puede tardar varios
     * minutos) — llamar siempre desde [syncIncrementalAsync]/[syncRangeAsync],
     * nunca desde un hilo de request HTTP. A propósito sin @Transactional
     * envolvente: mantener una transacción de BD abierta durante minutos de
     * llamadas HTTP externas agotaría el pool de conexiones — cada
     * `repository.save()` ya es transaccional por su cuenta.
     */
    fun syncNow(satCertificateId: UUID, desde: Instant, hasta: Instant): Int {
        val certificate = certificateRepository.findById(satCertificateId)
            .orElseThrow { NotFoundException("e.firma no encontrada.") }
        val (x509, privateKey) = certificateService.decryptCredentials(certificate)

        // Aislado por sub-tarea (EMITIDAS, y una por cada RFC de contraparte para
        // RECIBIDAS) para que un SatProtocolException de una no tire el resultado
        // de las demás ni deje lastSyncAt sin avanzar — antes (con
        // CfdiTipo.entries.sumOf sin try/catch) cualquier fallo abortaba la
        // función entera antes de llegar a guardar lastSyncAt, así que cada sync
        // repetía EMITIDAS desde cero cada vez. Un error inesperado (no
        // SatProtocolException) sigue propagándose y abortando todo, como antes.
        var nuevas = try {
            downloadAndStore(certificate, x509, privateKey, CfdiTipo.EMITIDAS, desde, hasta)
        } catch (e: SatProtocolException) {
            log.warn("Sincronización SAT (EMITIDAS) no se pudo completar para el certificado {}: {}", satCertificateId, e.message)
            0
        }

        // RECIBIDAS: el SAT exige el RFC del emisor específico en cada solicitud
        // (ver SatWsDescargaMasivaClient.solicitarDescarga) — se sincroniza una
        // vez por cada contraparte que el propio usuario registró (B12). Sin
        // ninguna registrada, simplemente no hay nada que pedir: no es un error.
        val contrapartes = contraparteRepository.findAllByUserIdOrderByCreatedAtAsc(certificate.userId)
        for (contraparte in contrapartes) {
            nuevas += try {
                downloadAndStore(certificate, x509, privateKey, CfdiTipo.RECIBIDAS, desde, hasta, rfcContraparte = contraparte.rfc)
            } catch (e: SatProtocolException) {
                log.warn(
                    "Sincronización SAT (RECIBIDAS de {}) no se pudo completar para el certificado {}: {}",
                    contraparte.rfc, satCertificateId, e.message,
                )
                0
            }
        }

        certificate.lastSyncAt = Instant.now()
        certificateRepository.save(certificate)
        return nuevas
    }

    private fun downloadAndStore(
        certificate: SatCertificate,
        x509: X509Certificate,
        privateKey: PrivateKey,
        tipo: CfdiTipo,
        desde: Instant,
        hasta: Instant,
        rfcContraparte: String? = null,
    ): Int {
        val downloadRequest = downloadRequestRepository.save(
            SatDownloadRequest(
                satCertificateId = requireNotNull(certificate.id),
                tipo = tipo,
                fechaInicio = desde,
                fechaFin = hasta,
                rfcContraparte = rfcContraparte,
            ),
        )

        val token = try {
            soapClient.autenticar(x509, privateKey)
        } catch (e: SatProtocolException) {
            certificateService.markAuthenticationError(requireNotNull(certificate.id))
            fail(downloadRequest, e.message)
            throw e
        }

        // Se marca la solicitud como ERROR antes de relanzar — si no, la fila se
        // queda en PENDIENTE para siempre (el catch de este SatProtocolException
        // vive en syncNow, ya fuera de esta función, donde no hay acceso a
        // downloadRequest). Para RECIBIDAS sin rfcContraparte (no debería pasar:
        // syncNow ya solo llama una vez por cada contraparte registrada), el SAT
        // sigue rechazando la solicitud igual que siempre — ver
        // SatWsDescargaMasivaClient.solicitarDescarga.
        val solicitud = try {
            soapClient.solicitarDescarga(token, certificate.rfc, tipo, desde, hasta, x509, privateKey, rfcContraparte)
        } catch (e: SatProtocolException) {
            fail(downloadRequest, e.message)
            throw e
        }
        if (!solicitud.exitosa || solicitud.idSolicitud == null) {
            // Incluye CodEstatus además del Mensaje: el SAT puede devolver un
            // Mensaje de éxito genérico ("Solicitud Aceptada") en respuestas que
            // en realidad no traen IdSolicitud — el código es la única forma de
            // distinguir esos casos al leer el error guardado.
            fail(downloadRequest, "CodEstatus=${solicitud.codigoEstatus}: ${solicitud.mensaje}")
            return 0
        }
        downloadRequest.idSolicitudSat = solicitud.idSolicitud
        downloadRequest.estado = SatDownloadRequestStatus.EN_PROCESO
        downloadRequestRepository.save(downloadRequest)

        val verificacion = pollVerificacion(token, solicitud.idSolicitud, certificate.rfc, x509, privateKey)
        downloadRequest.estado = verificacion.estado
        if (verificacion.estado != SatDownloadRequestStatus.TERMINADA) {
            fail(downloadRequest, "CodEstatus=${verificacion.codigoEstatus}: ${verificacion.mensaje}")
            return 0
        }
        downloadRequest.idsPaquetes = verificacion.idsPaquetes.joinToString(",")
        downloadRequestRepository.save(downloadRequest)

        return verificacion.idsPaquetes.sumOf { idPaquete ->
            val zipBytes = soapClient.descargarPaquete(token, idPaquete, certificate.rfc, x509, privateKey)
            storeInvoices(certificate.userId, certificate.rfc, unzipCfdiXmls(zipBytes))
        }
    }

    /**
     * Siempre espera antes de cada intento, incluido el primero: verificar
     * justo después de que SolicitaDescarga aceptó la solicitud (mismo
     * segundo) hace que el SAT todavía no tenga el folio listo para
     * consultar — responde con un EstadoSolicitud vacío/no reconocido
     * (mapEstado lo clasifica como ERROR, ver SatWsDescargaMasivaClient) y un
     * Mensaje genérico ("Solicitud Aceptada", el de la respuesta anterior),
     * así que el poll se rendía en el primer intento sin haber esperado nada
     * — confirmado en producción: EMITIDAS terminaba en ~1s con ese mensaje,
     * en vez de tardar los minutos reales que toma el SAT en procesar.
     */
    private fun pollVerificacion(token: String, idSolicitud: String, rfc: String, x509: X509Certificate, privateKey: PrivateKey): SatVerificacionResult {
        var attempts = 0
        var result: SatVerificacionResult
        do {
            Thread.sleep(properties.pollIntervalSeconds * 1000)
            result = soapClient.verificarSolicitud(token, idSolicitud, rfc, x509, privateKey)
            attempts++
        } while (
            result.estado in listOf(SatDownloadRequestStatus.PENDIENTE, SatDownloadRequestStatus.EN_PROCESO) &&
            attempts < properties.maxPollAttempts
        )
        return result
    }

    /** Idempotente: un CFDI ya guardado (mismo UUID fiscal) no se duplica ni se re-descarga su contenido. */
    private fun storeInvoices(userId: UUID, ownerRfc: String, xmls: List<ByteArray>): Int {
        var nuevas = 0
        for (xmlBytes in xmls) {
            val invoice = try {
                cfdiParser.parse(userId, xmlBytes, ownerRfc)
            } catch (e: Exception) {
                log.warn("No se pudo parsear un CFDI del paquete descargado: {}", e.message)
                continue
            }
            if (cfdiInvoiceRepository.findByUserIdAndUuidFiscal(userId, invoice.uuidFiscal) == null) {
                cfdiInvoiceRepository.save(invoice)
                nuevas++
            }
        }
        return nuevas
    }

    private fun fail(downloadRequest: SatDownloadRequest, message: String?) {
        downloadRequest.estado = SatDownloadRequestStatus.ERROR
        downloadRequest.errorMessage = message
        downloadRequestRepository.save(downloadRequest)
    }

    private fun notify(userId: UUID, type: NotificationType, title: String, message: String) {
        notificationRepository.save(Notification(userId = userId, type = type, title = title, message = message, forDate = LocalDate.now()))
    }
}
