-- V212: expected payments (spec 2026-11-15 §7.7, slice 3 task 3.1). Per deal, a small rep-entered schedule of cash
-- the rep expects: kind (deposit / on delivery / balance), amount, and the ISO week (its Monday, Asia/Bangkok) it is
-- expected in. Feeds the rep's เงินที่จะเข้า card and the team's cash KPI (SalesBoardService#cashForMonth).
--
-- Rep-entered, not seeded: quotations carry no structured payment-terms field to seed from (plan decision 2).
-- RECEIVED is set when a receipt fills the row: the Express receipts matcher (V213, ReceiptImportService) or a hand-typed
-- payment recorded by account. Either way it links the receipt it matched
-- — so RECEIVED <=> receipt_id IS NOT NULL. CANCELLED is the soft delete (DELETE on an OPEN row); a RECEIVED
-- row cannot be cancelled (409), it is the record of cash that came in.
--
-- Additive: a new table, no backfill, nothing existing changes. Written by POST/PUT/DELETE
-- /api/tickets/{id}/expected-payments(/{epId}); read by that GET, /api/sales/my-work and /api/sales/team-overview.
CREATE TABLE IF NOT EXISTS sales.expected_payment (
    expected_payment_id BIGSERIAL PRIMARY KEY,
    ticket_id           BIGINT NOT NULL REFERENCES sales.ticket(ticket_id),
    kind                TEXT NOT NULL CHECK (kind IN ('DEPOSIT', 'ON_DELIVERY', 'BALANCE')),
    amount              NUMERIC(14,2) NOT NULL CHECK (amount > 0),
    expected_week       DATE NOT NULL CHECK (extract(isodow FROM expected_week) = 1),
    status              TEXT NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'RECEIVED', 'CANCELLED')),
    receipt_id          BIGINT REFERENCES sales.payment_receipt(receipt_id),
    created_by          BIGINT NOT NULL REFERENCES hr.employee(employee_id),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_expected_payment_received_receipt CHECK ((status = 'RECEIVED') = (receipt_id IS NOT NULL))
);

CREATE INDEX IF NOT EXISTS idx_expected_payment_ticket ON sales.expected_payment (ticket_id, expected_week);
-- The month cash read filters on expected_week across every deal in scope.
CREATE INDEX IF NOT EXISTS idx_expected_payment_week ON sales.expected_payment (expected_week) WHERE status <> 'CANCELLED';

COMMENT ON TABLE sales.expected_payment IS
    'Rep-entered expected cash per deal (spec 2026-11-15 §7.7). RECEIVED when a receipt fills the row (Express matcher or hand-typed payment).';
