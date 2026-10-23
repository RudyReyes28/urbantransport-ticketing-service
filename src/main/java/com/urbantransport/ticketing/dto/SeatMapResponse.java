package com.urbantransport.ticketing.dto;

import com.urbantransport.ticketing.domain.SeatLayout;
import com.urbantransport.ticketing.domain.SeatStatus;
import com.urbantransport.ticketing.domain.TripSnapshotEntity;
import lombok.Value;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Value
public class SeatMapResponse {
    UUID tripId;
    String originName;
    String destinationName;
    LocalDateTime departureAt;
    BigDecimal ticketPrice;
    /** false si el viaje ya no se puede comprar (salió, terminó o ya pasó su hora). */
    boolean open;
    int rows;
    int columns;
    int aisleAfterColumn;
    int capacity;
    int availableSeats;
    List<SeatResponse> seats;

    public static SeatMapResponse fromEntity(TripSnapshotEntity trip, Set<Integer> occupied, boolean open) {
        SeatLayout layout = trip.seatLayout();
        List<SeatResponse> seats = new ArrayList<>(layout.capacity());
        for (int number = 1; number <= layout.capacity(); number++) {
            seats.add(new SeatResponse(number, layout.labelOf(number), layout.rowOf(number),
                    layout.columnOf(number), occupied.contains(number) ? SeatStatus.OCCUPIED : SeatStatus.FREE));
        }
        return new SeatMapResponse(trip.getTripId(), trip.getOriginName(), trip.getDestinationName(),
                trip.getDepartureAt(), trip.getTicketPrice(), open, layout.rows(), layout.columns(),
                layout.aisleAfterColumn(), layout.capacity(), layout.capacity() - occupied.size(), seats);
    }
}