-- V224: ส่งตัวอย่าง (sample sent) becomes a deal activity of its own kind — slice 6d, the owner-approved prototype's
-- item 6.7, owner answer 1 (2026-11-17). Additive only.
--
-- MIGRATION NUMBERING: V224 (final, controller ruling 2026-11-17). Written as V223, briefly renumbered V225 while two
-- open PRs also held a V223; since then #1350 merged to develop with V223__analytics_schema_phase1, so develop's
-- highest is V223 and V224 is the next free number. #1351 (V223__order_confirmation_undone_event_kind, still open) is
-- asked to take V225, above this one. Production must never be handed a number above an unapplied one: with
-- out-of-order off and validate-on-migrate false (CLAUDE.md), a lower version deployed after a higher one is silently
-- skipped, so the image that carries this must also carry V223. This file had not been applied to any database
-- outside the integration tests.
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
