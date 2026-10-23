package com.urbantransport.ticketing.dto;

import com.urbantransport.ticketing.domain.SeatStatus;
import lombok.Value;

@Value
public class SeatResponse {
    int number;
    String label;
    int row;
    int column;
    SeatStatus status;
}