package com.urbantransport.ticketing.job;

import com.urbantransport.ticketing.service.OrderExpirationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderExpirationJob {

    private final OrderExpirationService orderExpirationService;

    @Scheduled(fixedDelayString = "${ticketing.expiration-interval-ms:30000}")
    public void run() {
        int expired = orderExpirationService.expireOverdueOrders();
        if (expired > 0) {
            log.info("Órdenes expiradas: {}", expired);
        }
    }
}