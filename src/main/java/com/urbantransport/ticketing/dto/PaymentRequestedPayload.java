package com.urbantransport.ticketing.event;

import lombok.Value;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Value
public class PaymentRequestedPayload {
    String referenceType;
    UUID referenceId;
    UUID customerId;
    String customerNit;
    BigDecimal amount;
    LocalDate paymentDate;
}