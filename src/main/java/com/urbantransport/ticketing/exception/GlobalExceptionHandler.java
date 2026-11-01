package com.urbantransport.ticketing.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.Instant;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApi(ApiException exception, HttpServletRequest request) {
        return build(exception.getStatus(), exception.getMessage(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException exception,
                                                          HttpServletRequest request) {
        String message = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return build(HttpStatus.BAD_REQUEST, message, request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException exception,
                                                          HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "Cuerpo de la petición inválido", request);
    }

    private ResponseEntity<ErrorResponse> build(HttpStatus status, String message, HttpServletRequest request) {
        return ResponseEntity.status(status)
                .body(new ErrorResponse(Instant.now(), status.value(), message, request.getRequestURI()));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleIntegrity(DataIntegrityViolationException exception,
                                                         HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, "El registro ya existe o viola una restricción", request);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException exception,
                                                            HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "Parámetro inválido: " + exception.getName(), request);
    }


    @ExceptionHandler(SeatsUnavailableException.class)
    public ResponseEntity<SeatConflictResponse> handleSeatsUnavailable(SeatsUnavailableException exception,
                                                                       HttpServletRequest request) {
        return ResponseEntity.status(exception.getStatus())
                .body(new SeatConflictResponse(Instant.now(), exception.getStatus().value(),
                        exception.getMessage(), request.getRequestURI(), exception.getSeats()));
    }
}