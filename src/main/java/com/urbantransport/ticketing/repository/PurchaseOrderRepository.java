package com.urbantransport.ticketing.repository;

import com.urbantransport.ticketing.domain.OrderStatus;
import com.urbantransport.ticketing.domain.PurchaseOrderEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PurchaseOrderRepository extends JpaRepository<PurchaseOrderEntity, UUID> {

    long countByCustomerIdAndStatus(UUID customerId, OrderStatus status);

    Optional<PurchaseOrderEntity> findByCustomerIdAndIdempotencyKey(UUID customerId, String idempotencyKey);

    /** Bloquea la fila hasta el fin de la transacción: pago y expiración no pueden cambiarla a la vez. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from PurchaseOrderEntity o where o.id = :id")
    Optional<PurchaseOrderEntity> findByIdForUpdate(@Param("id") UUID id);

    @Query("""
            select o.id from PurchaseOrderEntity o
            where o.status = :status and o.expiresAt < :now
            order by o.expiresAt
            """)
    List<UUID> findExpiredPendingIds(@Param("status") OrderStatus status,
                                     @Param("now") LocalDateTime now,
                                     Pageable pageable);

    /** Bloquea todas las órdenes del viaje en ese estado, en orden de id para que el bloqueo sea siempre igual. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from PurchaseOrderEntity o where o.tripId = :tripId and o.status in :statuses order by o.id")
    List<PurchaseOrderEntity> findByTripIdAndStatusInForUpdate(@Param("tripId") UUID tripId,
                                                               @Param("statuses") Collection<OrderStatus> statuses);
}