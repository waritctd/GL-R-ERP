-- V233: one feedback request can have several requesters (the admin adds colleagues who asked for the same thing).
-- Additive: a new table; hr.feedback_request.reporter_employee_id stays the person who raised it and is always a
-- requester. Backfilled so every existing request lists its reporter.
CREATE TABLE IF NOT EXISTS hr.feedback_requester (
    feedback_id  BIGINT NOT NULL REFERENCES hr.feedback_request(feedback_id) ON DELETE CASCADE,
    employee_id  BIGINT NOT NULL REFERENCES hr.employee(employee_id),
    added_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (feedback_id, employee_id)
);

CREATE INDEX IF NOT EXISTS idx_feedback_requester_employee ON hr.feedback_requester (employee_id);

INSERT INTO hr.feedback_requester (feedback_id, employee_id, added_at)
SELECT feedback_id, reporter_employee_id, created_at FROM hr.feedback_request
ON CONFLICT DO NOTHING;
