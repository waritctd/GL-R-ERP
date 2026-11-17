-- V211: visits (spec 2026-11-15 §7.6, slice 2). A rep plans a visit, then ticks it done or cancels it. A visit may
-- be to a designer or owner before any deal exists, so ticket_id is nullable. Its own table rather than a widened
-- sales.deal_activity: deal_activity rows are past, always on a deal, and feed the quiet clock and the stage gate —
-- a planned visit must not. Marking a visit DONE when it is on a deal also inserts a deal_activity SITE_VISIT row
-- (VisitService), which is the one path that resets the quiet clock.
--
-- Additive: a new table, no backfill, nothing existing changes. Written by POST /api/sales/visits(/{id}/done|cancel),
-- read by GET /api/sales/visits(/team), /api/sales/my-work and /api/sales/team-overview.
CREATE TABLE IF NOT EXISTS sales.visit (
    visit_id      BIGSERIAL PRIMARY KEY,
    owner_id      BIGINT NOT NULL REFERENCES hr.employee(employee_id),
    ticket_id     BIGINT REFERENCES sales.ticket(ticket_id),
    contact_name  TEXT NOT NULL CHECK (btrim(contact_name) <> ''),
    planned_date  DATE NOT NULL,
    -- Optional time of day (the m-visit-plan dialog's เวลา ไม่บังคับ). Not in the plan's column list; added because the
    -- approved design asks for it and the list shows it ("14:00 · ตามใบเสนอราคา").
    planned_time  TIME,
    status        TEXT NOT NULL DEFAULT 'PLANNED' CHECK (status IN ('PLANNED', 'DONE', 'CANCELLED')),
    done_at       TIMESTAMPTZ,
    note          TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_visit_done_at CHECK ((status = 'DONE') = (done_at IS NOT NULL))
);

CREATE INDEX IF NOT EXISTS idx_visit_owner_date ON sales.visit (owner_id, planned_date);
-- The team week view reads every owner's visits by date.
CREATE INDEX IF NOT EXISTS idx_visit_planned_date ON sales.visit (planned_date);

COMMENT ON TABLE sales.visit IS
    'Planned / done / cancelled customer visits (spec 2026-11-15 §7.6). DONE on a deal also writes deal_activity SITE_VISIT.';
