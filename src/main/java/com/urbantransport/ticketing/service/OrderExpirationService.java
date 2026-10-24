package com.urbantransport.ticketing.service;

public interface OrderExpirationService {

    /** Expira las órdenes pendientes cuyo plazo venció y libera sus asientos. Devuelve cuántas expiró. */
    int expireOverdueOrders();
}