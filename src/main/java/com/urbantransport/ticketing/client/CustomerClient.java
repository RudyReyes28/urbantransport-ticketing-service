package com.urbantransport.ticketing.client;

import java.util.Optional;

public interface CustomerClient {

    /** Busca al cliente por NIT con el token del usuario que vende. Vacío si no está registrado. */
    Optional<CustomerSummary> findByNit(String nit, String accessToken);
}