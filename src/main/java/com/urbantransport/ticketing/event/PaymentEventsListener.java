package com.urbantransport.ticketing.event;

import com.urbantransport.ticketing.service.OrderPaymentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentEventsListener {

    private final JsonMapper jsonMapper;
    private final OrderPaymentService orderPaymentService;

    @KafkaListener(topics = "${ticketing.kafka.payment-events-topic}")
    public void onEvent(String message) {
        EventHeader header = jsonMapper.readValue(message, EventHeader.class);
        String type = header.eventType() == null ? "" : header.eventType();

        switch (type) {
            case EventTypes.PAYMENT_COMPLETED -> {
                PaymentResultEvent event = jsonMapper.readValue(message, PaymentResultEvent.class);
                orderPaymentService.handlePaymentCompleted(event.eventId(), event.payload());
            }
            case EventTypes.PAYMENT_FAILED -> {
                PaymentResultEvent event = jsonMapper.readValue(message, PaymentResultEvent.class);
                orderPaymentService.handlePaymentFailed(event.eventId(), event.payload());
            }
            case EventTypes.REFUND_COMPLETED -> {
                RefundCompletedEvent event = jsonMapper.readValue(message, RefundCompletedEvent.class);
                orderPaymentService.handleRefundCompleted(event.eventId(), event.payload());
            }
            default -> log.debug("Evento {} ignorado", type);
        }
    }
}