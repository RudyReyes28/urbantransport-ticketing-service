package com.urbantransport.ticketing.client;

import com.urbantransport.ticketing.config.CustomerClientProperties;
import com.urbantransport.ticketing.exception.ApiException;
import com.urbantransport.ticketing.exception.CustomerUnavailableException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.util.Optional;

@Slf4j
@Component
public class CustomerClientImpl implements CustomerClient {

    private static final String UNAVAILABLE_MESSAGE =
            "El servicio de clientes no está disponible, intenta más tarde";

    private final RestClient restClient;
    private final CircuitBreaker circuitBreaker;

    public CustomerClientImpl(CustomerClientProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(properties.connectTimeout()).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());
        this.restClient = RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory)
                .build();

        CustomerClientProperties.CircuitBreakerProperties settings = properties.circuitBreaker();
        this.circuitBreaker = CircuitBreaker.of("customer", CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(settings.slidingWindowSize())
                .minimumNumberOfCalls(settings.minimumCalls())
                .failureRateThreshold(settings.failureRateThreshold())
                .waitDurationInOpenState(settings.waitInOpenState())
                .recordExceptions(CustomerUnavailableException.class)
                .build());
        this.circuitBreaker.getEventPublisher()
                .onStateTransition(event -> log.warn("Circuit breaker de customer: {}", event.getStateTransition()));
    }

    @Override
    public Optional<CustomerSummary> findByNit(String nit, String accessToken) {
        try {
            return circuitBreaker.executeSupplier(() -> get(nit, accessToken));
        } catch (CallNotPermittedException | CustomerUnavailableException exception) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, UNAVAILABLE_MESSAGE);
        }
    }

    private Optional<CustomerSummary> get(String nit, String accessToken) {
        try {
            return restClient.get()
                    .uri("/customers/by-nit/{nit}", nit)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .exchange((request, response) -> {
                        if (response.getStatusCode().value() == HttpStatus.NOT_FOUND.value()) {
                            return Optional.<CustomerSummary>empty();
                        }
                        if (response.getStatusCode().is5xxServerError()) {
                            throw new CustomerUnavailableException("Customer respondió " + response.getStatusCode());
                        }
                        if (response.getStatusCode().isError()) {
                            log.error("Customer rechazó la consulta de cliente: {}", response.getStatusCode());
                            throw new ApiException(HttpStatus.BAD_GATEWAY, "No se pudo consultar al cliente, intenta más tarde");
                        }
                        return Optional.ofNullable(response.bodyTo(CustomerSummary.class));
                    });
        } catch (ResourceAccessException exception) {
            throw new CustomerUnavailableException("No se pudo contactar a customer", exception);
        }
    }
}