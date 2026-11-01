package com.urbantransport.ticketing.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "purchase_orders")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PurchaseOrderEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "trip_id", nullable = false)
    private UUID tripId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private SalesChannel channel;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    /** Copia del NIT al momento de la venta. */
    @Column(name = "customer_nit", nullable = false, length = 20)
    private String customerNit;

    /** Nulo en mostrador. */
    @Column(name = "buyer_email", length = 254)
    private String buyerEmail;

    /** Sucursal a la que se atribuye el ingreso. */
    @Column(name = "branch_id", nullable = false)
    private UUID branchId;

    /** Usuario que vendió; solo en mostrador. */
    @Column(name = "sold_by_user_id")
    private UUID soldByUserId;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal total;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private OrderStatus status;

    @Column(name = "failure_reason", length = 40)
    private String failureReason;

    @Column(name = "idempotency_key", length = 64)
    private String idempotencyKey;

    @Column(name = "payment_date", nullable = false)
    private LocalDate paymentDate;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** Solo en línea. */
    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @Column(name = "confirmed_at")
    private LocalDateTime confirmedAt;

    @Builder.Default
    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL)
    private List<TicketEntity> tickets = new ArrayList<>();

    public void addTicket(TicketEntity ticket) {
        ticket.setOrder(this);
        tickets.add(ticket);
    }

    /** Confirma los boletos que seguían pendientes. */
    public void confirmPendingTickets() {
        tickets.stream()
                .filter(ticket -> ticket.getStatus() == TicketStatus.PENDING)
                .forEach(ticket -> ticket.setStatus(TicketStatus.CONFIRMED));
    }

    /** Cancela los boletos pendientes y libera sus asientos (salen del índice uq_active_seat). */
    public void cancelPendingTickets(String reason, LocalDateTime when) {
        tickets.stream()
                .filter(ticket -> ticket.getStatus() == TicketStatus.PENDING)
                .forEach(ticket -> {
                    ticket.setStatus(TicketStatus.CANCELLED);
                    ticket.setCancelReason(reason);
                    ticket.setCancelledAt(when);
                });
    }

    /**
     * Cancela los boletos activos (pendientes y confirmados). Devuelve solo los que estaban CONFIRMED, es decir,
     * los que ya se cobraron y requieren aviso y reembolso.
     */
    public List<TicketEntity> cancelActiveTickets(String reason, LocalDateTime when) {
        List<TicketEntity> charged = new ArrayList<>();
        for (TicketEntity ticket : tickets) {
            if (!TicketStatus.ACTIVE.contains(ticket.getStatus())) {
                continue;
            }
            if (ticket.getStatus() == TicketStatus.CONFIRMED) {
                charged.add(ticket);
            }
            ticket.setStatus(TicketStatus.CANCELLED);
            ticket.setCancelReason(reason);
            ticket.setCancelledAt(when);
        }
        return charged;
    }
}