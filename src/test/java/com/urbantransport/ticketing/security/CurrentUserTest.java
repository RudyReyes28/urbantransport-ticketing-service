package com.urbantransport.ticketing.security;

import com.urbantransport.ticketing.exception.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CurrentUserTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID CUSTOMER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID BRANCH_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Test
    void customerId_readsClaim() {
        assertEquals(CUSTOMER_ID, CurrentUser.customerId(jwt("customerId", CUSTOMER_ID.toString())));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "  ")
    void customerId_missing_throwsForbidden(String value) {
        Jwt jwt = jwt("customerId", value);

        assertApiError(HttpStatus.FORBIDDEN, "El token no pertenece a un cliente", () -> CurrentUser.customerId(jwt));
    }

    @Test
    void userId_readsSubject() {
        assertEquals(USER_ID, CurrentUser.userId(jwt("roles", List.of("CASHIER"))));
    }

    @Test
    void branchId_readsClaim() {
        assertEquals(BRANCH_ID, CurrentUser.branchId(jwt("branchId", BRANCH_ID.toString())));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "  ")
    void branchId_missing_throwsForbidden(String value) {
        Jwt jwt = jwt("branchId", value);

        assertApiError(HttpStatus.FORBIDDEN, "El token no tiene una sucursal asignada",
                () -> CurrentUser.branchId(jwt));
    }

    @Test
    void nit_isNormalized() {
        assertEquals("1234567K", CurrentUser.nit(jwt("nit", " 1234567-k ")));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "  ")
    void nit_missing_throwsForbidden(String value) {
        Jwt jwt = jwt("nit", value);

        assertApiError(HttpStatus.FORBIDDEN, "El token no incluye el NIT del cliente", () -> CurrentUser.nit(jwt));
    }

    @Test
    void email_isTrimmed() {
        assertEquals("ana@mail.com", CurrentUser.email(jwt("email", " ana@mail.com ")));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "  ")
    void email_missing_returnsNull(String value) {
        assertNull(CurrentUser.email(jwt("email", value)));
    }

    @Test
    void isSystemAdmin_trueOnlyWithThatRole() {
        assertTrue(CurrentUser.isSystemAdmin(jwt("roles", List.of("SYSTEM_ADMIN"))));
        assertFalse(CurrentUser.isSystemAdmin(jwt("roles", List.of("BRANCH_ADMIN"))));
        assertFalse(CurrentUser.isSystemAdmin(jwt("email", "ana@mail.com")));
    }

    private static Jwt jwt(String claim, Object value) {
        return Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject(USER_ID.toString())
                .claims(claims -> {
                    if (value != null) {
                        claims.put(claim, value);
                    }
                })
                .build();
    }

    private static void assertApiError(HttpStatus status, String message, Executable action) {
        ApiException exception = assertThrows(ApiException.class, action);
        assertEquals(status, exception.getStatus());
        assertEquals(message, exception.getMessage());
    }
}
