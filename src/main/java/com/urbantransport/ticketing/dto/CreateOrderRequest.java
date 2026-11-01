package com.urbantransport.ticketing.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record CreateOrderRequest(
        @NotNull UUID tripId,
        @NotEmpty List<@NotNull @Positive Integer> seats,
        LocalDate paymentDate) {
}