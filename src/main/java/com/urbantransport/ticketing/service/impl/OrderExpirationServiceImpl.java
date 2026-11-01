package com.urbantransport.ticketing.service.impl;

import com.urbantransport.ticketing.domain.CancelReason;
import com.urbantransport.ticketing.domain.OrderStatus;
import com.urbantransport.ticketing.domain.PurchaseOrderEntity;
import com.urbantransport.ticketing.repository.PurchaseOrderRepository;
import com.urbantransport.ticketing.service.OrderExpirationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** Cada orden se expira en su propia transacción: si una falla, las demás siguen. */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderExpirationServiceImpl implements OrderExpirationService {

    private static final int BATCH_SIZE = 100;

    private final PurchaseOrderRepository purchaseOrderRepository;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    @Override
    public int expireOverdueOrders() {
        LocalDateTime now = LocalDateTime.now(clock);
        List<UUID> ids = purchaseOrderRepository.findExpiredPendingIds(
                OrderStatus.PENDING, now, PageRequest.of(0, BATCH_SIZE));

        int expired = 0;
        for (UUID id : ids) {
            try {
                if (Boolean.TRUE.equals(transactionTemplate.execute(status -> expire(id, now)))) {
                    expired++;
                }
            } catch (Exception exception) {
                log.warn("No se pudo expirar la orden {}: {}", id, exception.toString());
            }
        }
        return expired;
    }

    /** Se vuelve a leer con bloqueo: si mientras tanto llegó el pago, la orden ya no está pendiente. */
    private boolean expire(UUID orderId, LocalDateTime now) {
        PurchaseOrderEntity order = purchaseOrderRepository.findByIdForUpdate(orderId).orElse(null);
        if (order == null || order.getStatus() != OrderStatus.PENDING
                || order.getExpiresAt() == null || !order.getExpiresAt().isBefore(now)) {
            return false;
        }
        order.setStatus(OrderStatus.EXPIRED);
        order.cancelPendingTickets(CancelReason.ORDER_EXPIRED, now);
        return true;
    }
}