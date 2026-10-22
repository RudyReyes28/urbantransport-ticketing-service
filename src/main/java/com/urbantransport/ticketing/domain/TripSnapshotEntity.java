package com.urbantransport.ticketing.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "trip_snapshot")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TripSnapshotEntity {

    /** Lo asigna trip-service; aquí no se genera. */
    @Id
    @Column(name = "trip_id")
    private UUID tripId;

    @Column(name = "route_id", nullable = false)
    private UUID routeId;

    @Column(name = "origin_branch_id", nullable = false)
    private UUID originBranchId;

    @Column(name = "destination_branch_id", nullable = false)
    private UUID destinationBranchId;

    @Column(name = "origin_name", nullable = false, length = 120)
    private String originName;

    @Column(name = "destination_name", nullable = false, length = 120)
    private String destinationName;

    @Column(name = "bus_id", nullable = false)
    private UUID busId;

    @Column(name = "bus_plate", nullable = false, length = 20)
    private String busPlate;

    @Column(nullable = false)
    private int capacity;

    @Column(name = "seat_rows", nullable = false)
    private int seatRows;

    @Column(name = "seat_columns", nullable = false)
    private int seatColumns;

    @Column(name = "aisle_after_column", nullable = false)
    private int aisleAfterColumn;

    @Column(name = "ticket_price", nullable = false, precision = 10, scale = 2)
    private BigDecimal ticketPrice;

    @Column(name = "departure_at", nullable = false)
    private LocalDateTime departureAt;

    @Column(name = "estimated_arrival")
    private LocalDateTime estimatedArrival;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private TripStatus status;

    /** occurredAt del último evento aplicado. */
    @Column(name = "last_event_at", nullable = false)
    private Instant lastEventAt;

    public SeatLayout seatLayout() {
        return new SeatLayout(seatRows, seatColumns, aisleAfterColumn);
    }

    /** Vendible: programado y que aún no sale. "now" va en hora de Guatemala. */
    public boolean isSellable(LocalDateTime now) {
        return status == TripStatus.SCHEDULED && departureAt.isAfter(now);
    }
}