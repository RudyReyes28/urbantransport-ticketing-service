package com.urbantransport.ticketing.exception;

/** Customer no respondió o respondió con error de servidor: es el único fallo que cuenta para abrir el circuito. */
public class CustomerUnavailableException extends RuntimeException {

    public CustomerUnavailableException(String message) {
        super(message);
    }

    public CustomerUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}