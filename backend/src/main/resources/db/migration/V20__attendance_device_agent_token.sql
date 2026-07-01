-- Issue #22: per-device attendance agent tokens with rotation.
-- Replaces the single static shared token: each device carries its own secret, stored only as a
-- SHA-256 hash. Rotating one device's token (HR endpoint) never affects the others, and a leak is
-- scoped to that one device/site.

ALTER TABLE hr.attendance_device
    ADD COLUMN agent_token_hash       CHAR(64),
    ADD COLUMN agent_token_rotated_at TIMESTAMPTZ,
    ADD CONSTRAINT chk_attendance_device_token_hash_hex
        CHECK (agent_token_hash IS NULL OR agent_token_hash ~ '^[0-9a-f]{64}$');

COMMENT ON COLUMN hr.attendance_device.agent_token_hash IS
  'SHA-256 hex of this device''s agent token. NULL = not yet provisioned (falls back to the legacy shared token during rollout).';
COMMENT ON COLUMN hr.attendance_device.agent_token_rotated_at IS
  'When the per-device agent token was last issued/rotated.';
