package com.urbantransport.ticketing.domain;

import java.util.EnumSet;
import java.util.Set;

public enum TicketStatus {
    PENDING, CONFIRMED, CANCELLED, REFUNDED;

    /** Estados que ocupan el asiento (los mismos del índice uq_active_seat). */
    public static final Set<TicketStatus> ACTIVE = EnumSet.of(PENDING, CONFIRMED);
}