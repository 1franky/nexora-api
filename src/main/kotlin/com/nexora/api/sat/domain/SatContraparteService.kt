package com.nexora.api.sat.domain

import com.nexora.api.common.domain.BusinessRuleException
import com.nexora.api.common.domain.NotFoundException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/** RFC de persona física (13) o moral (12), formato estándar del SAT. */
private val RFC_PATTERN = Regex("^[A-ZÑ&]{3,4}\\d{6}[A-Z0-9]{3}$")

@Service
class SatContraparteService(
    private val contraparteRepository: SatContraparteRfcRepository,
) {

    fun listForUser(userId: UUID): List<SatContraparteRfc> = contraparteRepository.findAllByUserIdOrderByCreatedAtAsc(userId)

    @Transactional
    fun create(userId: UUID, rfc: String, alias: String?): SatContraparteRfc {
        val normalizedRfc = rfc.trim().uppercase()
        if (!RFC_PATTERN.matches(normalizedRfc)) {
            throw BusinessRuleException("'$normalizedRfc' no tiene el formato de un RFC válido.")
        }
        if (contraparteRepository.existsByUserIdAndRfc(userId, normalizedRfc)) {
            throw BusinessRuleException("Ya tienes registrado el RFC '$normalizedRfc'.")
        }
        val contraparte = SatContraparteRfc(userId = userId, rfc = normalizedRfc, alias = alias?.trim()?.takeIf { it.isNotEmpty() })
        return contraparteRepository.save(contraparte)
    }

    @Transactional
    fun delete(userId: UUID, id: UUID) {
        val contraparte = contraparteRepository.findByIdAndUserId(id, userId)
            ?: throw NotFoundException("RFC de contraparte no encontrado.")
        contraparteRepository.delete(contraparte)
    }
}
