package com.urbantransport.ticketing.exception;

import lombok.Value;

import java.time.Instant;
import java.util.List;

@Value
public class SeatConflictResponse {
    Instant timestamp;
    int status;
    String message;
    String path;
    List<Integer> unavailableSeats;
}