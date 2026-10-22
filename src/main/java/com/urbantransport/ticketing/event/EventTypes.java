package com.urbantransport.ticketing.event;

public final class EventTypes {

    public static final String AGGREGATE_ORDER = "Order";
    public static final String AGGREGATE_TICKET = "Ticket";

    // Publicados por ticketing (ticket-events)
    public static final String PAYMENT_REQUESTED = "PaymentRequested";
    public static final String REFUND_REQUESTED = "RefundRequested";
    public static final String TICKET_PURCHASED = "TicketPurchased";
    public static final String TICKET_CANCELLED = "TicketCancelled";

    // Consumidos de trip-service (trip-events)
    public static final String TRIP_SCHEDULED = "TripScheduled";
    public static final String TRIP_UPDATED = "TripUpdated";
    public static final String TRIP_DELETED = "TripDeleted";
    public static final String TRIP_STARTED = "TripStarted";
    public static final String TRIP_COMPLETED = "TripCompleted";

    // Consumidos de customer-service (payment-events)
    public static final String PAYMENT_COMPLETED = "PaymentCompleted";
    public static final String PAYMENT_FAILED = "PaymentFailed";
    public static final String REFUND_COMPLETED = "RefundCompleted";

    // Tipos de referencia que viajan en PaymentRequested / RefundRequested
    public static final String REFERENCE_ORDER = "ORDER";
    public static final String REFERENCE_TICKET = "TICKET";

    private EventTypes() {
    }
}