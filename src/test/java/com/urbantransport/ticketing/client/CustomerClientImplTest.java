package com.urbantransport.ticketing.client;

import com.urbantransport.ticketing.config.CustomerClientProperties;
import com.urbantransport.ticketing.exception.ApiException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class CustomerClientImplTest {

    private static final String BASE_URL = "http://customer.test";
    private static final String NIT = "12345678";
    private static final String TOKEN = "token-del-cajero";
    private static final String NIT_URL = BASE_URL + "/customers/by-nit/" + NIT;
    private static final UUID CUSTOMER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String UNAVAILABLE = "El servicio de clientes no está disponible, intenta más tarde";
    private static final String CUSTOMER_ERROR = "No se pudo consultar al cliente, intenta más tarde";

    private MockRestServiceServer server;
    private CustomerClientImpl customerClient;
    private CircuitBreaker circuitBreaker;

    @BeforeEach
    void setUp() {
        customerClient = new CustomerClientImpl(properties(BASE_URL));
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
        ReflectionTestUtils.setField(customerClient, "restClient", builder.build());
        circuitBreaker = (CircuitBreaker) ReflectionTestUtils.getField(customerClient, "circuitBreaker");
    }

    @AfterEach
    void verifyRequests() {
        server.verify();
    }

    @Test
    void registeredCustomer_isReturnedForwardingUserToken() {
        server.expect(requestTo(NIT_URL))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer " + TOKEN))
                .andRespond(withSuccess("""
                        {"id":"%s","nit":"%s","fullName":"Ana López","generic":false,"hasAccount":true,"extra":1}
                        """.formatted(CUSTOMER_ID, NIT), MediaType.APPLICATION_JSON));

        Optional<CustomerSummary> customer = customerClient.findByNit(NIT, TOKEN);

        assertEquals(Optional.of(new CustomerSummary(CUSTOMER_ID, NIT, "Ana López", false, true)), customer);
    }

    @Test
    void unknownNit_returnsEmpty() {
        server.expect(requestTo(NIT_URL)).andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertEquals(Optional.empty(), customerClient.findByNit(NIT, TOKEN));
    }

    @Test
    void emptyBody_returnsEmpty() {
        server.expect(requestTo(NIT_URL)).andRespond(withSuccess());

        assertEquals(Optional.empty(), customerClient.findByNit(NIT, TOKEN));
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 409})
    void clientError_throwsBadGateway(int status) {
        server.expect(requestTo(NIT_URL)).andRespond(withStatus(HttpStatus.valueOf(status)));

        assertApiError(HttpStatus.BAD_GATEWAY, CUSTOMER_ERROR, () -> customerClient.findByNit(NIT, TOKEN));
    }

    @ParameterizedTest
    @ValueSource(ints = {500, 502, 503})
    void serverError_throwsServiceUnavailable(int status) {
        server.expect(requestTo(NIT_URL)).andRespond(withStatus(HttpStatus.valueOf(status)));

        assertApiError(HttpStatus.SERVICE_UNAVAILABLE, UNAVAILABLE, () -> customerClient.findByNit(NIT, TOKEN));
    }

    @Test
    void timeout_throwsServiceUnavailable() {
        server.expect(requestTo(NIT_URL)).andRespond(withException(new SocketTimeoutException("Read timed out")));

        assertApiError(HttpStatus.SERVICE_UNAVAILABLE, UNAVAILABLE, () -> customerClient.findByNit(NIT, TOKEN));
    }

    @Test
    void openCircuit_failsFastWithoutCallingCustomer() {
        circuitBreaker.transitionToOpenState();

        assertApiError(HttpStatus.SERVICE_UNAVAILABLE, UNAVAILABLE, () -> customerClient.findByNit(NIT, TOKEN));
    }

    @Test
    void repeatedServerErrors_openCircuit() {
        server.expect(ExpectedCount.times(2), requestTo(NIT_URL)).andRespond(withServerError());

        assertApiError(HttpStatus.SERVICE_UNAVAILABLE, UNAVAILABLE, () -> customerClient.findByNit(NIT, TOKEN));
        assertApiError(HttpStatus.SERVICE_UNAVAILABLE, UNAVAILABLE, () -> customerClient.findByNit(NIT, TOKEN));

        assertEquals(CircuitBreaker.State.OPEN, circuitBreaker.getState());
        assertApiError(HttpStatus.SERVICE_UNAVAILABLE, UNAVAILABLE, () -> customerClient.findByNit(NIT, TOKEN));
    }

    @Test
    void repeatedClientErrors_doNotOpenCircuit() {
        server.expect(ExpectedCount.times(2), requestTo(NIT_URL)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertApiError(HttpStatus.BAD_GATEWAY, CUSTOMER_ERROR, () -> customerClient.findByNit(NIT, TOKEN));
        assertApiError(HttpStatus.BAD_GATEWAY, CUSTOMER_ERROR, () -> customerClient.findByNit(NIT, TOKEN));

        assertEquals(CircuitBreaker.State.CLOSED, circuitBreaker.getState());
    }

    @Test
    void repeatedNotFound_doNotOpenCircuit() {
        server.expect(ExpectedCount.times(2), requestTo(NIT_URL)).andRespond(withStatus(HttpStatus.NOT_FOUND));

        customerClient.findByNit(NIT, TOKEN);
        customerClient.findByNit(NIT, TOKEN);

        assertEquals(CircuitBreaker.State.CLOSED, circuitBreaker.getState());
    }

    @Test
    void unreachableCustomer_withRealHttpClient_throwsServiceUnavailable() {
        CustomerClientImpl unreachable = new CustomerClientImpl(properties("http://localhost:1"));

        assertApiError(HttpStatus.SERVICE_UNAVAILABLE, UNAVAILABLE, () -> unreachable.findByNit(NIT, TOKEN));
    }

    private static CustomerClientProperties properties(String baseUrl) {
        return new CustomerClientProperties(baseUrl, Duration.ofSeconds(1), Duration.ofSeconds(1),
                new CustomerClientProperties.CircuitBreakerProperties(2, 2, 50f, Duration.ofSeconds(30)));
    }

    private static void assertApiError(HttpStatus status, String message, Executable action) {
        ApiException exception = assertThrows(ApiException.class, action);
        assertEquals(status, exception.getStatus());
        assertEquals(message, exception.getMessage());
    }
}
