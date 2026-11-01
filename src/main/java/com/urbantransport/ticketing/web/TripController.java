package com.urbantransport.ticketing.web;

import com.urbantransport.ticketing.dto.PageResponse;
import com.urbantransport.ticketing.dto.SeatMapResponse;
import com.urbantransport.ticketing.dto.TripResponse;
import com.urbantransport.ticketing.service.TripQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.UUID;

/** Consulta pública: no requiere token. */
@RestController
@RequestMapping("/trips")
@RequiredArgsConstructor
public class TripController {

    private final TripQueryService tripQueryService;

    @GetMapping("/available")
    public PageResponse<TripResponse> available(
            @RequestParam(required = false) UUID originBranchId,
            @RequestParam(required = false) UUID destinationBranchId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        return tripQueryService.searchAvailable(originBranchId, destinationBranchId, date, page, size);
    }

    @GetMapping("/{tripId}/seats")
    public SeatMapResponse seats(@PathVariable UUID tripId) {
        return tripQueryService.getSeatMap(tripId);
    }
}