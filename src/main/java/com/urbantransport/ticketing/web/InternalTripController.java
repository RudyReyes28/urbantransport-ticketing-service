package com.urbantransport.ticketing.web;

import com.urbantransport.ticketing.dto.SoldSummaryResponse;
import com.urbantransport.ticketing.service.TripQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Solo para otros servicios: el gateway bloquea /internal/**. Trip-service reenvía el JWT del administrador. */
@RestController
@RequestMapping("/internal/trips")
@RequiredArgsConstructor
public class InternalTripController {

    private final TripQueryService tripQueryService;

    @GetMapping("/{tripId}/sold-summary")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','BRANCH_ADMIN')")
    public SoldSummaryResponse soldSummary(@PathVariable UUID tripId) {
        return tripQueryService.soldSummary(tripId);
    }
}