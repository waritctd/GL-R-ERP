-- V216: a 30-second undo for a per-factory import step advance (spec 2026-11-15 §7.11 item 3, slice 5 task 5.3).
--
-- Advancing a ใบขอซื้อ row's import_step is forward-only and was final; /fulfilment now shows an undo snackbar for 30
-- seconds after an advance. To undo, the row must remember what the step WAS before the latest advance and WHEN that
-- advance happened (import_step_at is a backdatable DATE, not an instant, so it cannot answer "within 30 s").
--
--   step_undo_from*    the step and its stamp (date / by / note) as they were before the latest advance
--   step_advanced_at   the instant of the latest advance (database clock); NULL = nothing to undo
--
-- Written by ImportRequestRepository#advanceStep (every advance overwrites them: one level only) and cleared by
-- #undoStep. A revision's newly issued row starts with them NULL. Additive and nullable: no backfill, no existing
-- query changes, the frontend never reads them (the service answers 403/409 instead).
--
-- No ticket_event.kind is added: the undo is recorded as IMPORT_STEP_ADVANCED with a "เลิกทำ" message, so this
-- migration does not re-declare chk_event_kind (a full-list re-declaration races any concurrently numbered
-- migration that adds a kind).
--
-- Rollback: drop the six columns.

ALTER TABLE sales.import_request
    ADD COLUMN IF NOT EXISTS step_undo_from         VARCHAR(20),
    ADD COLUMN IF NOT EXISTS step_undo_from_at      DATE,
    ADD COLUMN IF NOT EXISTS step_undo_from_by_id   BIGINT,
    ADD COLUMN IF NOT EXISTS step_undo_from_by_name VARCHAR(200),
    ADD COLUMN IF NOT EXISTS step_undo_from_note    VARCHAR(500),
    ADD COLUMN IF NOT EXISTS step_advanced_at       TIMESTAMPTZ;

COMMENT ON COLUMN sales.import_request.step_undo_from IS
    'import_step before the latest advance (V216, the /fulfilment 30-second undo). NULL = nothing to undo.';
COMMENT ON COLUMN sales.import_request.step_advanced_at IS
    'When the latest advance happened (database clock). The undo is refused 30 s after it (V216).';
