-- V248: warehouse delivery trips. The warehouse manager groups invoiced delivery rounds into a dated trip on a named
-- route; sales reads the plan on the round. A round sits on at most one trip as PLANNED; DELAYED and REMOVED rows stay
-- as history so sales can be told the round is waiting to be re-planned.
--
-- Rollback: drop sales.delivery_trip_round, then sales.delivery_trip.

CREATE TABLE sales.delivery_trip (
    delivery_trip_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    delivery_date    DATE NOT NULL,
    route            VARCHAR(120) NOT NULL,
    time_slot        VARCHAR(120),
    vehicle          VARCHAR(120),
    note             VARCHAR(500),
    status           VARCHAR(12) NOT NULL DEFAULT 'OPEN'
        CONSTRAINT chk_delivery_trip_status CHECK (status IN ('OPEN','DONE','CANCELLED')),
    created_by       BIGINT NOT NULL REFERENCES hr.employee(employee_id),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE sales.delivery_trip_round (
    delivery_trip_id    BIGINT NOT NULL REFERENCES sales.delivery_trip(delivery_trip_id) ON DELETE CASCADE,
    delivery_request_id BIGINT NOT NULL REFERENCES sales.delivery_request(delivery_request_id) ON DELETE CASCADE,
    state               VARCHAR(12) NOT NULL DEFAULT 'PLANNED'
        CONSTRAINT chk_delivery_trip_round_state CHECK (state IN ('PLANNED','DELAYED','REMOVED','DELIVERED')),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (delivery_trip_id, delivery_request_id)
);
CREATE UNIQUE INDEX ux_delivery_trip_round_planned ON sales.delivery_trip_round(delivery_request_id)
    WHERE state = 'PLANNED';
CREATE INDEX idx_delivery_trip_round_request ON sales.delivery_trip_round(delivery_request_id);
