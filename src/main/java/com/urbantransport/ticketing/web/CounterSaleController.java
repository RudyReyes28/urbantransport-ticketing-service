package com.urbantransport.ticketing.web;

import com.urbantransport.ticketing.dto.CounterSaleRequest;
import com.urbantransport.ticketing.dto.OrderResponse;
import com.urbantransport.ticketing.security.CurrentUser;
import com.urbantransport.ticketing.service.CounterSaleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/counter-sales")
@RequiredArgsConstructor
public class CounterSaleController {

    private final CounterSaleService counterSaleService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('BRANCH_ADMIN','CASHIER')")
    public OrderResponse sell(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CounterSaleRequest request) {
        return counterSaleService.sell(CurrentUser.userId(jwt), CurrentUser.branchId(jwt),
                jwt.getTokenValue(), request);
    }
}