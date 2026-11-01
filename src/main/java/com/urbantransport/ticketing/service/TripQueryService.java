package com.urbantransport.ticketing.service;

import com.urbantransport.ticketing.dto.PageResponse;
import com.urbantransport.ticketing.dto.SeatMapResponse;
import com.urbantransport.ticketing.dto.SoldSummaryResponse;
import com.urbantransport.ticketing.dto.TripResponse;

import java.time.LocalDate;
import java.util.UUID;

public interface TripQueryService {

    PageResponse<TripResponse> searchAvailable(UUID originBranchId, UUID destinationBranchId, LocalDate date,
                                               int page, int size);

    SeatMapResponse getSeatMap(UUID tripId);

    SoldSummaryResponse soldSummary(UUID tripId);
}