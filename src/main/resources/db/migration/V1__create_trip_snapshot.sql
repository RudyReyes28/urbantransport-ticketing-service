-- Copia local de los viajes REGULAR (se llena con eventos de trip-service)
CREATE TABLE trip_snapshot (
                               trip_id               UUID          PRIMARY KEY,
                               route_id              UUID          NOT NULL,
                               origin_branch_id      UUID          NOT NULL,
                               destination_branch_id UUID          NOT NULL,
                               origin_name           VARCHAR(120)  NOT NULL,
                               destination_name      VARCHAR(120)  NOT NULL,
                               bus_id                UUID          NOT NULL,
                               bus_plate             VARCHAR(20)   NOT NULL,
                               capacity              INTEGER       NOT NULL,
                               seat_rows             INTEGER       NOT NULL,
                               seat_columns          INTEGER       NOT NULL,
                               aisle_after_column    INTEGER       NOT NULL,
                               ticket_price          NUMERIC(10,2) NOT NULL,
                               departure_at          TIMESTAMP     NOT NULL,
                               estimated_arrival     TIMESTAMP,
                               status                VARCHAR(12)   NOT NULL,
    -- occurredAt del último evento aplicado (Instant): solo se aplican eventos más nuevos
                               last_event_at         TIMESTAMPTZ   NOT NULL,
                               CONSTRAINT ck_snapshot_status CHECK (status IN ('SCHEDULED','IN_TRANSIT','COMPLETED','DELETED')),
                               CONSTRAINT ck_snapshot_price  CHECK (ticket_price >= 0),
                               CONSTRAINT ck_snapshot_layout CHECK (
                                   seat_rows >= 1
                                       AND seat_columns BETWEEN 1 AND 6
                                       AND aisle_after_column BETWEEN 0 AND seat_columns - 1
                                       AND capacity = seat_rows * seat_columns
                                   )
);

CREATE INDEX ix_trip_snapshot_search ON trip_snapshot (status, departure_at);
CREATE INDEX ix_trip_snapshot_origin ON trip_snapshot (origin_branch_id);