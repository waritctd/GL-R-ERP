-- V223: ส่งตัวอย่าง (sample sent) becomes a deal activity of its own kind — slice 6d, the owner-approved prototype's
-- item 6.7, owner answer 1 (2026-11-17). Additive only.
--
-- MIGRATION NUMBERING: V223, the next free version. Checked on 2026-11-17 before writing: the highest version on
-- origin/develop, origin/main and every remote branch (git ls-remote --heads origin + git ls-tree on each; the open PRs
-- #1349 chore/deps-2026-11-17, #1336/#1334/#1333/#1332/#1331 dependabot, #1300 docs/onprem-runbook and #1299
-- chore/second-sales-quick-login among them) is V222__feedback_request_released_at; no local worktree carries a V223.
-- Flyway out-of-order is off and validate-on-migrate is false on prod (CLAUDE.md), so the IMAGE that carries this must
-- also carry every lower pending migration, or those are silently skipped.
--
-- chk_deal_activity_kind (V83) is re-declared with one more value, SAMPLE_SENT, and every V83 value kept: no row is
-- rewritten and no code is removed. th.co.glr.hr.ticket.DealActivityKind#VALID is the Java side and must name the
-- same list — DealActivitySampleSentIntegrationTest reads this constraint back from the live database and compares.
-- A SAMPLE_SENT row is written through the same POST /api/tickets/{id}/activities as a call (TicketService#addActivity,
-- same permission rule); its note — what was sent — is required by the service, not by this constraint, so the
-- column stays nullable for every other kind.
--
-- Rollback: re-declare chk_deal_activity_kind with the V83 list (fails while a SAMPLE_SENT row exists — change those
-- rows to 'OTHER' first, keeping the note).

ALTER TABLE sales.deal_activity DROP CONSTRAINT IF EXISTS chk_deal_activity_kind;
ALTER TABLE sales.deal_activity ADD CONSTRAINT chk_deal_activity_kind CHECK (kind IN (
    'CALL', 'MEETING', 'SITE_VISIT', 'EMAIL', 'MESSAGE', 'QUOTATION_FOLLOWUP', 'OTHER', 'SAMPLE_SENT'
));
