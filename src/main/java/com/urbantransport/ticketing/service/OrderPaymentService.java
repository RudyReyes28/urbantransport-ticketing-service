package com.urbantransport.ticketing.service;

import com.urbantransport.ticketing.event.PaymentResultEvent;
import com.urbantransport.ticketing.event.RefundCompletedEvent;

import java.util.UUID;

public interface OrderPaymentService {

    void handlePaymentCompleted(UUID eventId, PaymentResultEvent.PaymentResultPayload payload);

    void handlePaymentFailed(UUID eventId, PaymentResultEvent.PaymentResultPayload payload);

    /** El reembolso de un boleto cancelado se completó: el boleto pasa a REFUNDED. */
    void handleRefundCompleted(UUID eventId, RefundCompletedEvent.RefundCompletedPayload payload);
}