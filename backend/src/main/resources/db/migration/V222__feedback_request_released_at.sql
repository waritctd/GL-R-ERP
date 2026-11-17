-- When a feedback request first reached RELEASED. The reporter gets the "ใช้งานได้แล้ว" email only that first
-- time; moving back a step and releasing again is bell only. NULL = never released.
ALTER TABLE hr.feedback_request ADD COLUMN released_at TIMESTAMPTZ;
