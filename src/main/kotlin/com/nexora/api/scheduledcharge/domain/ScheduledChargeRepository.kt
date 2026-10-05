package com.nexora.api.scheduledcharge.domain

import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import java.time.LocalDate
import java.util.UUID

interface ScheduledChargeRepository : JpaRepository<ScheduledCharge, UUID> {
    fun findAllByUserId(userId: UUID): List<ScheduledCharge>
    fun findAllByUserIdAndAccountId(userId: UUID, accountId: UUID): List<ScheduledCharge>
    fun findByIdAndUserId(id: UUID, userId: UUID): ScheduledCharge?

    /** Ids de los cargos activos con alguna ocurrencia vencida a [today] — el job los procesa uno por uno. */
    @Query("select c.id from ScheduledCharge c where c.status = 'ACTIVE' and c.nextRunDate <= :today")
    fun findDueIds(today: LocalDate): List<UUID>

    /**
     * SELECT ... FOR UPDATE: si el job diario y una request (alta/reanudar)
     * procesan el mismo cargo a la vez, el segundo espera y ve el cursor ya
     * avanzado — nunca se registra dos veces la misma ocurrencia.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from ScheduledCharge c where c.id = :id")
    fun findByIdForUpdate(id: UUID): ScheduledCharge?
}
