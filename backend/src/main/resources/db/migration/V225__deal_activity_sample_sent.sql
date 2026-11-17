-- V225: ส่งตัวอย่าง (sample sent) becomes a deal activity of its own kind — slice 6d, the owner-approved prototype's
-- item 6.7, owner answer 1 (2026-11-17). Additive only.
--
-- MIGRATION NUMBERING: V225. Written as V223 (free when first checked on 2026-11-17: highest everywhere was V222);
-- renumbered the same day before merge, by controller ruling, because two other open PRs pushed their own V223 in the
-- meantime — #1350 claude/analytics-phase1-schema (V223__analytics_schema_phase1) and #1351
-- feat/undo-order-confirmation (V223__order_confirmation_undone_event_kind). V223 and V224 are left to those two;
-- this one goes above both. Re-checked on 2026-11-17 after a fresh fetch: origin/develop's highest is V222, every
-- other remote branch and open PR (#1350, #1351, #1300, #1299) is at or below V223, and no branch carries a V224 or
-- V225. This file had not been applied to any database outside the integration tests.
-- Flyway out-of-order is off and validate-on-migrate is false on prod (CLAUDE.md), so the IMAGE that carries this must
-- also carry every lower pending migration (V223, V224), or those are silently skipped.
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
