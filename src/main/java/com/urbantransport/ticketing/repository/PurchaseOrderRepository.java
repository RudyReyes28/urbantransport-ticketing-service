package com.urbantransport.ticketing.repository;

import com.urbantransport.ticketing.domain.OrderStatus;
import com.urbantransport.ticketing.domain.PurchaseOrderEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PurchaseOrderRepository extends JpaRepository<PurchaseOrderEntity, UUID> {

    long countByCustomerIdAndStatus(UUID customerId, OrderStatus status);

    Optional<PurchaseOrderEntity> findByCustomerIdAndIdempotencyKey(UUID customerId, String idempotencyKey);
}