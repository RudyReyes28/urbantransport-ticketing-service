package com.urbantransport.ticketing.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "tickets")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TicketEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private PurchaseOrderEntity order;

    /** Solo lectura: el id de la orden, sin cargar la orden. */
    @Column(name = "order_id", insertable = false, updatable = false)
    private UUID orderId;

    @Column(name = "trip_id", nullable = false)
    private UUID tripId;

    @Column(name = "seat_number", nullable = false)
    private int seatNumber;

    /** Etiqueta impresa al vender, p. ej. "2C". Se guarda para que el boleto no cambie nunca. */
    @Column(name = "seat_label", nullable = false, length = 6)
    private String seatLabel;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Column(name = "customer_nit", nullable = false, length = 20)
    private String customerNit;

    @Column(name = "price_paid", nullable = false, precision = 10, scale = 2)
    private BigDecimal pricePaid;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private SalesChannel channel;

    @Column(name = "branch_id", nullable = false)
    private UUID branchId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private TicketStatus status;

    @Column(name = "cancel_reason", length = 30)
    private String cancelReason;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;
}