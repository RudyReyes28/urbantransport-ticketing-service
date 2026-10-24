package com.urbantransport.ticketing.service;

import com.urbantransport.ticketing.dto.CreateOrderRequest;
import com.urbantransport.ticketing.dto.OrderResponse;

import java.util.UUID;

public interface OrderService {

    /**
     * Crea una orden pendiente de pago y pide el cobro a customer. Con la misma idempotencyKey del mismo cliente
     * devuelve la orden ya creada.
     */
    OrderResponse create(UUID customerId, String customerNit, String buyerEmail, CreateOrderRequest request,
                         String idempotencyKey);

    /** Solo el dueño puede verla; para cualquier otro la orden "no existe". */
    OrderResponse getForCustomer(UUID orderId, UUID customerId);
}