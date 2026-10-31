package com.urbantransport.ticketing.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SeatLayoutTest {

    private final SeatLayout layout = new SeatLayout(10, 4, 2);

    @Test
    void capacity_isRowsTimesColumns() {
        assertEquals(40, layout.capacity());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 20, 40})
    void seatInsideCapacity_isContained(int seat) {
        assertTrue(layout.contains(seat));
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 41})
    void seatOutsideCapacity_isNotContained(int seat) {
        assertFalse(layout.contains(seat));
    }

    @ParameterizedTest
    @CsvSource({"1, 1, 1, 1A", "4, 1, 4, 1D", "7, 2, 3, 2C", "40, 10, 4, 10D"})
    void seatNumber_mapsToRowColumnAndLabel(int seat, int row, int column, String label) {
        assertEquals(row, layout.rowOf(seat));
        assertEquals(column, layout.columnOf(seat));
        assertEquals(label, layout.labelOf(seat));
    }
}
