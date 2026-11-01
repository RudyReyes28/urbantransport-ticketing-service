package com.urbantransport.ticketing.web;

import com.urbantransport.ticketing.domain.TicketStatus;
import com.urbantransport.ticketing.dto.PageResponse;
import com.urbantransport.ticketing.dto.TicketDetailResponse;
import com.urbantransport.ticketing.dto.TripTicketResponse;
import com.urbantransport.ticketing.security.CurrentUser;
import com.urbantransport.ticketing.service.TicketQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class TicketController {

    private final TicketQueryService ticketQueryService;

    @GetMapping("/tickets/me")
    @PreAuthorize("hasRole('CUSTOMER')")
    public PageResponse<TicketDetailResponse> mine(@AuthenticationPrincipal Jwt jwt,
                                                   @RequestParam(required = false) TicketStatus status,
                                                   @RequestParam(defaultValue = "0") int page,
                                                   @RequestParam(defaultValue = "10") int size) {
        return ticketQueryService.listMine(CurrentUser.customerId(jwt), status, page, size);
    }

    @GetMapping("/tickets/by-nit/{nit}")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','BRANCH_ADMIN','CASHIER')")
    public PageResponse<TicketDetailResponse> byNit(@AuthenticationPrincipal Jwt jwt,
                                                    @PathVariable String nit,
                                                    @RequestParam(required = false) TicketStatus status,
                                                    @RequestParam(defaultValue = "0") int page,
                                                    @RequestParam(defaultValue = "10") int size) {
        return ticketQueryService.listByNit(nit, jwt.getTokenValue(), status, page, size);
    }

    @GetMapping("/trips/{tripId}/tickets")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','BRANCH_ADMIN','CASHIER')")
    public List<TripTicketResponse> tripTickets(@AuthenticationPrincipal Jwt jwt,
                                                @PathVariable UUID tripId,
                                                @RequestParam(required = false) TicketStatus status) {
        UUID branchScope = CurrentUser.isSystemAdmin(jwt) ? null : CurrentUser.branchId(jwt);
        return ticketQueryService.listTripTickets(tripId, branchScope, status);
    }
}