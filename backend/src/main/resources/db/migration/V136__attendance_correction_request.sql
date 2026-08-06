SET search_path = hr, public;

-- ---------------------------------------------------------------------
-- ATTENDANCE CORRECTION: employee-filed request to fix a missed
-- clock-in/clock-out scan, CEO-only approval (no manager stage -- product
-- decision, see AttendanceCorrectionService#requireCeo).
--
-- On approval, AttendanceCorrectionService writes a MANUAL/MANUAL_ENTRY
-- hr.attendance_punch row (the CHECK/'MANUAL' slots V7 already reserved for
-- exactly this) and marks the affected hr.attendance_daily row
-- is_manual_override = TRUE via AttendanceDailyRepository's override-guarded
-- upsert, so a later device re-sync/backfill can never silently clobber it.
-- ---------------------------------------------------------------------

CREATE TABLE hr.attendance_correction_request (
    request_id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    employee_id           BIGINT NOT NULL REFERENCES hr.employee(employee_id),
    work_date             DATE NOT NULL,
    correction_type       VARCHAR(10) NOT NULL,
    requested_check_in    TIMESTAMPTZ,
    requested_check_out   TIMESTAMPTZ,
    reason                TEXT NOT NULL,
    status                VARCHAR(20) NOT NULL DEFAULT 'SUBMITTED',
    requested_by_id       BIGINT NOT NULL REFERENCES hr.employee(employee_id),
    requested_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    reviewed_by_id        BIGINT REFERENCES hr.employee(employee_id),
    reviewed_at           TIMESTAMPTZ,
    reviewer_note         TEXT,
    cancelled_at          TIMESTAMPTZ,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT chk_attendance_correction_type CHECK (
        correction_type IN ('CHECK_IN', 'CHECK_OUT', 'BOTH')
    ),
    CONSTRAINT chk_attendance_correction_status CHECK (
        status IN ('SUBMITTED', 'APPROVED', 'REJECTED', 'CANCELLED')
    ),
    -- Which of requested_check_in/requested_check_out must be populated is fully determined by
    -- correction_type -- exactly the pair the type names must be present, and the other must be
    -- absent, so the service layer never has to guess which field(s) a CHECK_IN/CHECK_OUT row
    -- actually carries.
    CONSTRAINT chk_attendance_correction_fields_match_type CHECK (
        (correction_type = 'CHECK_IN'  AND requested_check_in  IS NOT NULL AND requested_check_out IS NULL)
        OR (correction_type = 'CHECK_OUT' AND requested_check_out IS NOT NULL AND requested_check_in  IS NULL)
        OR (correction_type = 'BOTH'      AND requested_check_in  IS NOT NULL AND requested_check_out IS NOT NULL)
    ),
    CONSTRAINT chk_attendance_correction_checkout_after_checkin CHECK (
        requested_check_in IS NULL OR requested_check_out IS NULL OR requested_check_out >= requested_check_in
    ),
    CONSTRAINT chk_attendance_correction_reason_nonblank CHECK (btrim(reason) <> '')
);

COMMENT ON TABLE hr.attendance_correction_request IS
  'Employee-filed request to record the correct clock-in/clock-out time for a day a scan was missed. CEO-only approval, no manager stage -- see AttendanceCorrectionService.';

-- At most one OPEN (SUBMITTED) request per employee per day -- a second correction for the same
-- day must wait for the first to be decided (approved/rejected) or withdrawn (cancelled), same
-- shape as leave's ux_leave_once_per_employment-style guard against a race producing duplicates.
CREATE UNIQUE INDEX ux_attendance_correction_one_open_per_employee_day
    ON hr.attendance_correction_request (employee_id, work_date)
    WHERE status = 'SUBMITTED';

CREATE INDEX idx_attendance_correction_employee_date
    ON hr.attendance_correction_request(employee_id, work_date DESC);

CREATE INDEX idx_attendance_correction_status
    ON hr.attendance_correction_request(status, work_date DESC);
