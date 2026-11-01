package com.urbantransport.ticketing.job;

import com.urbantransport.ticketing.service.OrderExpirationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderExpirationJobTest {

    @Mock
    private OrderExpirationService orderExpirationService;

    private OrderExpirationJob job;

    @BeforeEach
    void setUp() {
        job = new OrderExpirationJob(orderExpirationService);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 3})
    void run_expiresOverdueOrders(int expired) {
        when(orderExpirationService.expireOverdueOrders()).thenReturn(expired);

        job.run();

        verify(orderExpirationService).expireOverdueOrders();
    }
}
