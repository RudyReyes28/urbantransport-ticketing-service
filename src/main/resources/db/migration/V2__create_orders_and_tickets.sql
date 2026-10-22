-- Una compra (en línea o en mostrador) con N boletos
CREATE TABLE purchase_orders (
                                 id               UUID          PRIMARY KEY,
                                 trip_id          UUID          NOT NULL REFERENCES trip_snapshot (trip_id),
                                 channel          VARCHAR(10)   NOT NULL,
                                 customer_id      UUID          NOT NULL,
                                 customer_nit     VARCHAR(20)   NOT NULL,
                                 buyer_email      VARCHAR(254),
                                 branch_id        UUID          NOT NULL,
                                 sold_by_user_id  UUID,
                                 total            NUMERIC(12,2) NOT NULL,
                                 status           VARCHAR(12)   NOT NULL,
                                 failure_reason   VARCHAR(40),
                                 idempotency_key  VARCHAR(64),
                                 payment_date     DATE          NOT NULL,
                                 created_at       TIMESTAMP     NOT NULL DEFAULT now(),
                                 expires_at       TIMESTAMP,
                                 confirmed_at     TIMESTAMP,
                                 CONSTRAINT ck_orders_nit_format CHECK (customer_nit ~ '^[0-9A-Z]+$'),
    CONSTRAINT ck_orders_channel    CHECK (channel IN ('ONLINE','COUNTER')),
    CONSTRAINT ck_orders_status     CHECK (status IN ('PENDING','CONFIRMED','FAILED','EXPIRED','CANCELLED')),
    CONSTRAINT ck_orders_total      CHECK (total >= 0),
    CONSTRAINT ck_orders_channel_fields CHECK (
        (channel = 'ONLINE'
            AND sold_by_user_id IS NULL
            AND expires_at IS NOT NULL)
        OR
        (channel = 'COUNTER'
            AND sold_by_user_id IS NOT NULL
            AND expires_at IS NULL
            AND status IN ('CONFIRMED','CANCELLED'))
    )
);

CREATE UNIQUE INDEX uq_orders_idempotency
    ON purchase_orders (customer_id, idempotency_key) WHERE idempotency_key IS NOT NULL;
CREATE INDEX ix_orders_expiry   ON purchase_orders (expires_at)  WHERE status = 'PENDING';
CREATE INDEX ix_orders_pending  ON purchase_orders (customer_id) WHERE status = 'PENDING';
CREATE INDEX ix_orders_customer ON purchase_orders (customer_id, created_at DESC);
CREATE INDEX ix_orders_trip     ON purchase_orders (trip_id);

-- Un asiento = un boleto
CREATE TABLE tickets (
                         id             UUID          PRIMARY KEY,
                         order_id       UUID          NOT NULL REFERENCES purchase_orders (id),
                         trip_id        UUID          NOT NULL REFERENCES trip_snapshot (trip_id),
                         seat_number    INTEGER       NOT NULL,
                         seat_label     VARCHAR(6)    NOT NULL,
                         customer_id    UUID          NOT NULL,
                         customer_nit   VARCHAR(20)   NOT NULL,
                         price_paid     NUMERIC(10,2) NOT NULL,
                         channel        VARCHAR(10)   NOT NULL,
                         branch_id      UUID          NOT NULL,
                         status         VARCHAR(12)   NOT NULL,
                         cancel_reason  VARCHAR(30),
                         created_at     TIMESTAMP     NOT NULL DEFAULT now(),
                         cancelled_at   TIMESTAMP,
                         CONSTRAINT ck_tickets_nit_format CHECK (customer_nit ~ '^[0-9A-Z]+$'),
    CONSTRAINT ck_tickets_seat       CHECK (seat_number > 0),
    CONSTRAINT ck_tickets_price      CHECK (price_paid >= 0),
    CONSTRAINT ck_tickets_channel    CHECK (channel IN ('ONLINE','COUNTER')),
    CONSTRAINT ck_tickets_status     CHECK (status IN ('PENDING','CONFIRMED','CANCELLED','REFUNDED'))
);

-- Garantía real contra compras concurrentes: un asiento activo por viaje
CREATE UNIQUE INDEX uq_active_seat
    ON tickets (trip_id, seat_number) WHERE status IN ('PENDING','CONFIRMED');

CREATE INDEX ix_tickets_customer ON tickets (customer_id, created_at DESC);
CREATE INDEX ix_tickets_trip     ON tickets (trip_id);
CREATE INDEX ix_tickets_order    ON tickets (order_id);