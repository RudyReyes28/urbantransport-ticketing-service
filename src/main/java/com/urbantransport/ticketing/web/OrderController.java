package com.urbantransport.ticketing.web;

import com.urbantransport.ticketing.dto.CreateOrderRequest;
import com.urbantransport.ticketing.dto.OrderResponse;
import com.urbantransport.ticketing.security.CurrentUser;
import com.urbantransport.ticketing.service.OrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    /** 202: la orden queda pendiente; el resultado del pago se consulta con GET /orders/{id}. */
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize("hasRole('CUSTOMER')")
    public OrderResponse create(@AuthenticationPrincipal Jwt jwt,
                                @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                @Valid @RequestBody CreateOrderRequest request) {
        return orderService.create(CurrentUser.customerId(jwt), CurrentUser.nit(jwt), CurrentUser.email(jwt),
                request, idempotencyKey);
    }

    @GetMapping("/{orderId}")
    @PreAuthorize("hasRole('CUSTOMER')")
    public OrderResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID orderId) {
        return orderService.getForCustomer(orderId, CurrentUser.customerId(jwt));
    }
}