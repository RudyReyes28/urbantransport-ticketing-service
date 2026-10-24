package com.urbantransport.ticketing.service;

import com.urbantransport.ticketing.event.PaymentResultEvent;

import java.util.UUID;

public interface OrderPaymentService {

    void handlePaymentCompleted(UUID eventId, PaymentResultEvent.PaymentResultPayload payload);

    void handlePaymentFailed(UUID eventId, PaymentResultEvent.PaymentResultPayload payload);
}