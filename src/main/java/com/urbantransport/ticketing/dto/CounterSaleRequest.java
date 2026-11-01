package com.urbantransport.ticketing.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record CounterSaleRequest(
        @NotNull UUID tripId,
        @NotEmpty List<@NotNull @Positive Integer> seats,
        @Size(max = 20) String customerNit,
        LocalDate paymentDate) {
}