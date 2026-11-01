package com.urbantransport.ticketing.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CorrelationIdFilterTest {

    private static final String CORRELATION_ID = "corr-123";

    private final CorrelationIdFilter filter = new CorrelationIdFilter();
    private final MockHttpServletResponse response = new MockHttpServletResponse();
    private final AtomicReference<String> mdcDuringRequest = new AtomicReference<>();
    private final FilterChain capturingChain = (req, res) -> mdcDuringRequest.set(MDC.get(CorrelationIdFilter.MDC_KEY));

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void incomingHeader_isReusedInMdcAndResponse() throws Exception {
        filter.doFilter(request(CORRELATION_ID), response, capturingChain);

        assertEquals(CORRELATION_ID, mdcDuringRequest.get());
        assertEquals(CORRELATION_ID, response.getHeader(CorrelationIdFilter.HEADER));
        assertNull(MDC.get(CorrelationIdFilter.MDC_KEY));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "   ")
    void missingHeader_generatesNewUuid(String header) throws Exception {
        filter.doFilter(request(header), response, capturingChain);

        String generated = response.getHeader(CorrelationIdFilter.HEADER);
        assertDoesNotThrow(() -> UUID.fromString(generated));
        assertEquals(generated, mdcDuringRequest.get());
        assertNull(MDC.get(CorrelationIdFilter.MDC_KEY));
    }

    @Test
    void failingRequest_stillClearsMdc() {
        FilterChain failingChain = (req, res) -> {
            throw new ServletException("fallo en el controller");
        };

        assertThrows(ServletException.class, () -> filter.doFilter(request(CORRELATION_ID), response, failingChain));

        assertNull(MDC.get(CorrelationIdFilter.MDC_KEY));
    }

    private static MockHttpServletRequest request(String correlationId) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/trips/available");
        if (correlationId != null) {
            request.addHeader(CorrelationIdFilter.HEADER, correlationId);
        }
        return request;
    }
}
