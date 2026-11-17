-- When a feedback request first reached RELEASED. The reporter gets the "ใช้งานได้แล้ว" email only that first
-- time; moving back a step and releasing again is bell only. NULL = never released.
ALTER TABLE hr.feedback_request ADD COLUMN released_at TIMESTAMPTZ;

-- Requests already done before this column existed count as released, so moving one back and releasing it again
-- does not send a second done email. updated_at is the closest record of when that happened.
UPDATE hr.feedback_request SET released_at = updated_at WHERE status = 'RELEASED';
