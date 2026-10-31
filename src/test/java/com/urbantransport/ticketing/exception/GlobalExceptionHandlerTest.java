package com.urbantransport.ticketing.exception;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GlobalExceptionHandlerTest {

    private static final String PATH = "/orders";

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();
    private final MockHttpServletRequest request = new MockHttpServletRequest("POST", PATH);

    @Test
    void apiException_keepsItsStatusAndMessage() {
        ResponseEntity<ErrorResponse> response = handler.handleApi(
                new ApiException(HttpStatus.NOT_FOUND, "El viaje no existe"), request);

        assertError(response, HttpStatus.NOT_FOUND, "El viaje no existe");
    }

    @Test
    void validationErrors_areJoinedByField() {
        BindingResult bindingResult = new BeanPropertyBindingResult(Map.of(), "request");
        bindingResult.addError(new FieldError("request", "tripId", "no debe ser nulo"));
        bindingResult.addError(new FieldError("request", "seats", "no debe estar vacío"));
        MethodArgumentNotValidException exception = mock(MethodArgumentNotValidException.class);
        when(exception.getBindingResult()).thenReturn(bindingResult);

        ResponseEntity<ErrorResponse> response = handler.handleValidation(exception, request);

        assertError(response, HttpStatus.BAD_REQUEST, "tripId: no debe ser nulo; seats: no debe estar vacío");
    }

    @Test
    void unreadableBody_returnsBadRequest() {
        HttpMessageNotReadableException exception = new HttpMessageNotReadableException("JSON inválido",
                new MockHttpInputMessage(new byte[0]));

        ResponseEntity<ErrorResponse> response = handler.handleUnreadable(exception, request);

        assertError(response, HttpStatus.BAD_REQUEST, "Cuerpo de la petición inválido");
    }

    @Test
    void integrityViolation_returnsConflict() {
        ResponseEntity<ErrorResponse> response = handler.handleIntegrity(
                new DataIntegrityViolationException("duplicate key value"), request);

        assertError(response, HttpStatus.CONFLICT, "El registro ya existe o viola una restricción");
    }

    @Test
    void typeMismatch_namesTheParameter() {
        MethodArgumentTypeMismatchException exception = mock(MethodArgumentTypeMismatchException.class);
        when(exception.getName()).thenReturn("tripId");

        ResponseEntity<ErrorResponse> response = handler.handleTypeMismatch(exception, request);

        assertError(response, HttpStatus.BAD_REQUEST, "Parámetro inválido: tripId");
    }

    @Test
    void seatsUnavailable_returnsConflictWithTheSeats() {
        ResponseEntity<SeatConflictResponse> response = handler.handleSeatsUnavailable(
                new SeatsUnavailableException(List.of(3, 7)), request);

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        SeatConflictResponse body = response.getBody();
        assertNotNull(body);
        assertNotNull(body.getTimestamp());
        assertEquals(409, body.getStatus());
        assertEquals("Los asientos 3, 7 ya no están disponibles", body.getMessage());
        assertEquals(PATH, body.getPath());
        assertEquals(List.of(3, 7), body.getUnavailableSeats());
    }

    private static void assertError(ResponseEntity<ErrorResponse> response, HttpStatus status, String message) {
        assertEquals(status, response.getStatusCode());
        ErrorResponse body = response.getBody();
        assertNotNull(body);
        assertNotNull(body.getTimestamp());
        assertEquals(status.value(), body.getStatus());
        assertEquals(message, body.getMessage());
        assertEquals(PATH, body.getPath());
    }
}
