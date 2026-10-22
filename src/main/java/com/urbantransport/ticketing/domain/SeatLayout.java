package com.urbantransport.ticketing.domain;

/**
 * Distribución de asientos de un bus. Se numeran por fila, de izquierda a derecha:
 * con 4 columnas, el asiento 7 es la fila 2, columna C ("2C").
 *
 * @param aisleAfterColumn cuántas columnas hay a la izquierda del pasillo (0 = sin pasillo)
 */
public record SeatLayout(int rows, int columns, int aisleAfterColumn) {

    public int capacity() {
        return rows * columns;
    }

    public boolean contains(int seatNumber) {
        return seatNumber >= 1 && seatNumber <= capacity();
    }

    /** Fila empezando en 1. */
    public int rowOf(int seatNumber) {
        return (seatNumber - 1) / columns + 1;
    }

    /** Columna empezando en 1 (A = 1). */
    public int columnOf(int seatNumber) {
        return (seatNumber - 1) % columns + 1;
    }

    public String labelOf(int seatNumber) {
        return rowOf(seatNumber) + String.valueOf((char) ('A' + columnOf(seatNumber) - 1));
    }
}