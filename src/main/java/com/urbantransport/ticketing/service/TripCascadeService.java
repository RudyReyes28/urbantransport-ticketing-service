package com.urbantransport.ticketing.service;

import java.util.UUID;

/** Efecto de los cambios de estado de un viaje sobre sus órdenes y boletos. Se usa dentro de la transacción del evento. */
public interface TripCascadeService {

    /** El viaje salió: las órdenes pendientes de pago ya no pueden completarse. */
    void expirePendingOrders(UUID tripId);

    /** El viaje se eliminó: se cancela todo y se avisa y reembolsa lo ya cobrado. */
    void cancelTripOrders(UUID tripId);
}