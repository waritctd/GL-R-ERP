-- V219: the deal lifecycle dialogs rebuilt to the owner-approved drawings (2026-11-16). Additive only.
--
-- MIGRATION NUMBERING: V219. Verified free on origin/develop and every local/remote branch and worktree
-- (`git ls-tree` over each) immediately before writing this file; the highest is V218. Flyway out-of-order is off
-- and validate-on-migrate is false on prod (CLAUDE.md), so the IMAGE that carries this must also carry every lower
-- pending migration, or those are silently skipped.
--
-- 1. sales.ticket.pause_return_on — วันที่จะกลับมาดู. A pause (ON_HOLD or DORMANT, the two EXISTING lifecycles) may
--    name the date the rep wants the deal back. From that Asia/Bangkok date the paused deal appears in its owner's
--    ควรตามต่อ and counts in the manager's quiet figures (QuietRule#returningSql) until it is resumed (which clears
--    the column) or re-paused with a later date. Nullable, no default, no backfill: every existing pause named no
--    date, and a NULL date behaves exactly as a pause did before this migration.
--
-- 2. chk_ticket_cancel_reason widened with the three drawn reasons. The four V57 codes stay valid (already-
--    cancelled deals carry them and keep their labels); no row is rewritten and no code is removed.
--
-- 3. imported_value's comment: the create dialog's มูลค่าโดยประมาณ is written to the same column. It already means
--    "the rep's stated value, shown on the board until a quotation gives one" (V215); the create dialog is now a
--    second writer of that same fact. Comment only.
--
-- Rollback (reverse order): restore V215's comment on imported_value; re-declare chk_ticket_cancel_reason with the
-- V57 list (fails while a row carries a new code — set those to 'OTHER' first); DROP COLUMN pause_return_on.

ALTER TABLE sales.ticket ADD COLUMN IF NOT EXISTS pause_return_on DATE;

COMMENT ON COLUMN sales.ticket.pause_return_on IS
    'วันที่จะกลับมาดู: from this Asia/Bangkok date a paused (ON_HOLD/DORMANT) deal returns to its owner''s follow-up list. NULL = no date (the pause behaves as before V219). Cleared on resume.';

ALTER TABLE sales.ticket DROP CONSTRAINT IF EXISTS chk_ticket_cancel_reason;
ALTER TABLE sales.ticket ADD CONSTRAINT chk_ticket_cancel_reason CHECK (
    cancel_reason IS NULL OR cancel_reason IN (
        'OWNER_CANCELLED','PROJECT_SUSPENDED','BUDGET_CANCELLED','OTHER',
        'DUPLICATE_DEAL','WRONG_CUSTOMER_OR_PROJECT','NO_REAL_PROJECT'
    )
);

COMMENT ON COLUMN sales.ticket.imported_value IS
    'The rep''s stated deal value (THB): from the one-time pipeline import, or the create dialog''s มูลค่าโดยประมาณ. The board shows it when no quotation gives a value.';
