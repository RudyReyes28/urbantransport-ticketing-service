package com.urbantransport.ticketing.security;

import com.urbantransport.ticketing.exception.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.UUID;

public final class CurrentUser {

    private CurrentUser() {
    }

    /** El customerId viaja firmado en el token; un token sin ese claim no pertenece a un cliente. */
    public static UUID customerId(Jwt jwt) {
        String value = jwt.getClaimAsString("customerId");
        if (value == null || value.isBlank()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "El token no pertenece a un cliente");
        }
        return UUID.fromString(value);
    }

    /** Id del usuario (claim "sub"). */
    public static UUID userId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }

    /** Sucursal del personal; un token sin sucursal no puede vender en mostrador. */
    public static UUID branchId(Jwt jwt) {
        String value = jwt.getClaimAsString("branchId");
        if (value == null || value.isBlank()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "El token no tiene una sucursal asignada");
        }
        return UUID.fromString(value);
    }
}