-- Slice B1 ("kill the weekly report", handoff 103): reps keep standardized deal fields current
-- inside the ERP instead of a per-rep weekly Excel report, and the system enforces it via a
-- stage-advance gate (TicketService.updateStage) instead of trusting a spreadsheet.
--
-- Part 1: tracking fields directly on the deal.
--   win_probability: nullable override. NULL means "use the stage-derived default" — see
--   th.co.glr.hr.ticket.WinProbabilityDefaults for the mapping (owner-review assumption, not yet
--   confirmed against real deal outcomes — see that class's own Javadoc and handoff 103).
--   designer_name / owner_name / buyer_name: free-text contact names for the three counterparties
--   the S1-S20 flowchart already models (designer, owner, buyer) but the ticket previously had no
--   place to record. All nullable — a lightweight lead-stage deal may not know these yet.
ALTER TABLE sales.ticket
    ADD COLUMN win_probability SMALLINT
        CHECK (win_probability IS NULL OR (win_probability BETWEEN 0 AND 100)),
    ADD COLUMN designer_name TEXT,
    ADD COLUMN owner_name TEXT,
    ADD COLUMN buyer_name TEXT;

-- Part 2: deal_activity — the "did the rep actually follow up" record the stage-advance gate
-- checks against (at least one row logged since the deal's last STAGE_CHANGED event). Kind is
-- CHECK-constrained the same way every other short enum-like column in this schema is (e.g.
-- chk_ticket_entry_channel in V51) — see th.co.glr.hr.ticket.DealActivityKind for the Java side;
-- this is a first-pass list (owner-review assumption, same caveat as win_probability above).
CREATE TABLE sales.deal_activity (
    id              BIGSERIAL PRIMARY KEY,
    ticket_id       BIGINT NOT NULL REFERENCES sales.ticket(ticket_id),
    activity_date   DATE NOT NULL,
    kind            TEXT NOT NULL,
    note            TEXT,
    created_by_id   BIGINT NOT NULL REFERENCES hr.employee(employee_id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_deal_activity_kind CHECK (kind IN (
        'CALL', 'MEETING', 'SITE_VISIT', 'EMAIL', 'MESSAGE', 'QUOTATION_FOLLOWUP', 'OTHER'
    ))
);

-- The stage-advance gate and staleness check both filter by ticket + recency (created_at) — this
-- is the one index that serves both.
CREATE INDEX idx_deal_activity_ticket_date ON sales.deal_activity (ticket_id, activity_date DESC);
