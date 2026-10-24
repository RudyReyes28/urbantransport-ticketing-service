package com.urbantransport.ticketing.domain;

public final class CancelReason {

    public static final String ORDER_EXPIRED = "ORDER_EXPIRED";
    public static final String PAYMENT_FAILED = "PAYMENT_FAILED";
    public static final String TRIP_STARTED = "TRIP_STARTED";
    public static final String TRIP_DELETED = "TRIP_DELETED";

    private CancelReason() {
    }
}