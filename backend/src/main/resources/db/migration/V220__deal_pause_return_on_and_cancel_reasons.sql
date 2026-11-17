-- V220: the deal lifecycle dialogs rebuilt to the owner-approved drawings (2026-11-16). Additive only.
--
-- MIGRATION NUMBERING: V220. Written as V219; renumbered on 2026-11-17 before merge because develop gained
-- V219__people_insights_teams first (it had been applied only to integration-test databases). An open PR also
-- proposes a V220 (feedback requests) and has been asked to take V221. Flyway out-of-order
-- is off and validate-on-migrate is false on prod (CLAUDE.md), so the IMAGE that carries this must also carry every
-- lower pending migration, or those are silently skipped. (Edited in place during review fix round 1 — it had not
-- been applied to any database outside the integration tests.)
--
-- 1. sales.ticket.pause_return_on — วันที่จะกลับมาดู. A pause (ON_HOLD or DORMANT, the two EXISTING lifecycles) may
--    name the date the rep wants the deal back. From that Asia/Bangkok date the paused deal appears in its owner's
--    ควรตามต่อ and counts in the manager's quiet figures (QuietRule#returningSql) until it is resumed (which clears
--    the column) or re-paused with a later date. Nullable, no default, no backfill: every existing pause named no
--    date, and a NULL date behaves exactly as a pause did before this migration.
--
-- 2. sales.ticket.estimated_value — มูลค่าโดยประมาณ from the create dialog, editable afterwards through the tracking
--    update. Its OWN column: imported_value stays the pipeline import's alone. Read as the LAST value fallback by the
--    same two consumers that read imported_value (the board and the dashboard forecast): quotation → imported_value →
--    estimated_value. NUMERIC(14,2), the precision of imported_value; > 0. Nullable, no default, no backfill.
--
-- 3. chk_ticket_cancel_reason widened with the three drawn reasons. The four V57 codes stay valid (already-
--    cancelled deals carry them and keep their labels); no row is rewritten and no code is removed.
--
-- Rollback (reverse order): re-declare chk_ticket_cancel_reason with the V57 list (fails while a row carries a new
-- code — set those to 'OTHER' first); DROP COLUMN estimated_value (with its constraint); DROP COLUMN pause_return_on.

ALTER TABLE sales.ticket ADD COLUMN IF NOT EXISTS pause_return_on DATE;

COMMENT ON COLUMN sales.ticket.pause_return_on IS
    'วันที่จะกลับมาดู: from this Asia/Bangkok date a paused (ON_HOLD/DORMANT) deal returns to its owner''s follow-up list. NULL = no date (the pause behaves as before V220). Cleared on resume.';

ALTER TABLE sales.ticket ADD COLUMN IF NOT EXISTS estimated_value NUMERIC(14, 2);

ALTER TABLE sales.ticket DROP CONSTRAINT IF EXISTS chk_ticket_estimated_value;
ALTER TABLE sales.ticket ADD CONSTRAINT chk_ticket_estimated_value
    CHECK (estimated_value IS NULL OR estimated_value > 0);

COMMENT ON COLUMN sales.ticket.estimated_value IS
    'มูลค่าโดยประมาณ (THB) typed by the rep (create dialog / tracking edit). The board and forecast show it only when no quotation and no imported_value give a value.';

ALTER TABLE sales.ticket DROP CONSTRAINT IF EXISTS chk_ticket_cancel_reason;
ALTER TABLE sales.ticket ADD CONSTRAINT chk_ticket_cancel_reason CHECK (
    cancel_reason IS NULL OR cancel_reason IN (
        'OWNER_CANCELLED','PROJECT_SUSPENDED','BUDGET_CANCELLED','OTHER',
        'DUPLICATE_DEAL','WRONG_CUSTOMER_OR_PROJECT','NO_REAL_PROJECT'
    )
);
