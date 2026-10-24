package com.urbantransport.ticketing.event;

import lombok.Value;

import java.math.BigDecimal;
import java.util.UUID;

@Value
public class RefundRequestedPayload {
    UUID ticketId;
    UUID customerId;
    String customerNit;
    BigDecimal amount;
    UUID orderId;
    String seatLabel;
}