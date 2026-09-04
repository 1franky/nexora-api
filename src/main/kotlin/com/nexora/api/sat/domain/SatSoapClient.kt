package com.nexora.api.sat.domain

import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.time.Instant

/**
 * Cliente del Web Service oficial de Descarga Masiva de CFDI del SAT
 * (plan-integracion-sat.md, sección 3) — los 4 pasos del protocolo.
 * Interfaz separada de [SatWsDescargaMasivaClient] (la implementación real)
 * a propósito: no se puede llamar al SAT real en tests/CI, así que
 * [SatSyncService] se prueba con esta interfaz mockeada.
 *
 * Todos los métodos reciben [certificate]/[privateKey] porque cada llamada
 * del protocolo (no solo la autenticación) va firmada individualmente con
 * la e.firma del usuario — ver [SatXmlSignatureService].
 */
interface SatSoapClient {

    /** Paso 1: intercambia certificado+llave por un token de sesión de vida corta (~5 min). */
    fun autenticar(certificate: X509Certificate, privateKey: PrivateKey): String

    /** Paso 2: pide la descarga por RFC y rango de fechas. Devuelve el `IdSolicitud` del SAT. */
    fun solicitarDescarga(
        token: String,
        rfc: String,
        tipo: CfdiTipo,
        desde: Instant,
        hasta: Instant,
        certificate: X509Certificate,
        privateKey: PrivateKey,
    ): SatSolicitudResult

    /** Paso 3: consulta el estado de una solicitud ya hecha. */
    fun verificarSolicitud(
        token: String,
        idSolicitud: String,
        rfc: String,
        certificate: X509Certificate,
        privateKey: PrivateKey,
    ): SatVerificacionResult

    /** Paso 4: descarga un paquete ya listo — devuelve el `.zip` crudo (el SAT lo entrega en base64; ya decodificado aquí). */
    fun descargarPaquete(
        token: String,
        idPaquete: String,
        rfc: String,
        certificate: X509Certificate,
        privateKey: PrivateKey,
    ): ByteArray
}

data class SatSolicitudResult(
    val idSolicitud: String?,
    val codigoEstatus: String,
    val mensaje: String,
    val exitosa: Boolean,
)

data class SatVerificacionResult(
    val estado: SatDownloadRequestStatus,
    val codigoEstatus: String,
    val mensaje: String,
    val idsPaquetes: List<String>,
)

/** El SAT rechazó la autenticación o una solicitud (certificado revocado, contraseña de la e.firma inválida, límite de solicitudes excedido, etc.). */
class SatProtocolException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
