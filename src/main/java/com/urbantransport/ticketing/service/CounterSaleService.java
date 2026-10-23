package com.urbantransport.ticketing.service;

import com.urbantransport.ticketing.dto.CounterSaleRequest;
import com.urbantransport.ticketing.dto.OrderResponse;

import java.util.UUID;

public interface CounterSaleService {

    /**
     * @param userId      usuario que vende (del token)
     * @param branchId    sucursal del vendedor (del token)
     * @param accessToken JWT del vendedor, que se reenvía a customer para consultar el NIT
     */
    OrderResponse sell(UUID userId, UUID branchId, String accessToken, CounterSaleRequest request);
}