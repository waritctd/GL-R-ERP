CREATE INDEX IF NOT EXISTS idx_profile_request_pending_employee
    ON hr.profile_change_request(employee_id)
    WHERE status = 'pending';

CREATE INDEX IF NOT EXISTS idx_profile_request_employee_requested
    ON hr.profile_change_request(employee_id, requested_at DESC, request_id DESC);
