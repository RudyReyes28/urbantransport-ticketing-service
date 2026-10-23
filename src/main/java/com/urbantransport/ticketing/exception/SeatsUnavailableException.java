package com.urbantransport.ticketing.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.stream.Collectors;

/** Uno o más asientos ya no están libres; lleva la lista para que el frontend los marque. */
@Getter
public class SeatsUnavailableException extends ApiException {

    private final List<Integer> seats;

    public SeatsUnavailableException(List<Integer> seats) {
        super(HttpStatus.CONFLICT, "Los asientos " + seats.stream().map(String::valueOf)
                .collect(Collectors.joining(", ")) + " ya no están disponibles");
        this.seats = seats;
    }
}