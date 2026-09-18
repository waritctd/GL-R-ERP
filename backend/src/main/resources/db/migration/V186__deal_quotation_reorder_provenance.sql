-- GLA-74 part 1: "สร้างจากใบเดิม" (สั่งเหมือนเดิม) -- clone an APPROVED direct-deal quotation into a
-- new, independent DRAFT reorder. Distinct from a REVISION (parent_quotation_id, which drives
-- supersede()/hasOpenRevision()/the NEEDS_REWORK_PREDICATE "แก" bucket): a clone's source stays
-- APPROVED forever -- cloning never supersedes anything, neither on clone creation nor when the
-- clone itself is later approved. See DealQuotationService#createReorder for the full rule.
--
-- MIGRATION NUMBERING: this is V186. V184 and V185 are claimed by the concurrent, UNMERGED
-- feat/import-request-per-factory branch (worktree .claude/worktrees/ir-per-factory) -- V184 is
-- that branch's own migration; V185 is reserved for it too. Merge order is therefore
-- V184 -> V185 -> V186: this file's own chk_event_kind re-declaration below must carry forward
-- whatever that branch's V184 added, or V186 landing after it silently undoes it. Verified V186
-- free by listing this worktree's own backend/src/main/resources/db/migration (tops out at
-- V183__ticket_item_stock_sale_price.sql) immediately before writing this file.

-- derived_from_quotation_id: nullable, AUDIT ONLY ("what was this cloned from"). Deliberately
-- NEVER read by supersede()/hasOpenRevision()/NEEDS_REWORK_PREDICATE -- those all key on
-- parent_quotation_id instead, which stays NULL on a clone by construction. ON DELETE left at the
-- default (RESTRICT): there is no DELETE anywhere on sales.quotation (parent_quotation_id has the
-- identical posture), so a clone can never dangle.
ALTER TABLE sales.quotation
    ADD COLUMN derived_from_quotation_id BIGINT REFERENCES sales.quotation(quotation_id);

COMMENT ON COLUMN sales.quotation.derived_from_quotation_id IS
    'GLA-74 part 1: the APPROVED quotation this row was cloned from via the reorder action (audit only). NULL on every ordinary create/revision. Distinct from parent_quotation_id: a clone source is never superseded, so this column is never consulted by supersede, hasOpenRevision, or the needs-rework predicate.';

-- Re-declare chk_event_kind (following V39/V48/V50/V51/V52/V53/V54/V56/V76/V78's own precedent)
-- to add DEAL_QUOTATION_REORDERED -- the ticket_event kind DealQuotationService#createReorder
-- writes. A genuinely NEW kind, not a reuse of REVISION_REQUESTED: DealHistoryPanel.jsx's
-- EVENT_KIND_LABEL maps REVISION_REQUESTED to "ขอแก้ไข" (asked for a fix), which would misdescribe
-- a reorder -- the source is never revised, rejected, or touched by it. Matches
-- TicketEventKind.java's full current REAL (non-notification-only) constant list -- V78's own list
-- plus this one addition -- exactly; it deliberately EXCLUDES the four DEAL_QUOTATION_*
-- notification-only kinds (DEAL_QUOTATION_SUBMITTED/APPROVED/REJECTED/REVISION_SUBMITTED), which
-- TicketEventKind.java's own comment says are never passed into TicketRepository#addEvent* and so
-- never need a place in this CHECK. Never edit V39/V48/V50/V51/V52/V53/V54/V56/V76/V78 in place.
--
-- IMPORT_STEP_ADVANCED and IMPORT_REQUEST_EMAIL_SENT (last two lines below) are NOT this
-- feature's own values -- both belong to feat/import-request-per-factory (worktree
-- .claude/worktrees/ir-per-factory), owned by that branch's V184
-- (V184__import_request_per_factory_progress.sql; IMPORT_STEP_ADVANCED is already in that file's
-- own chk_event_kind re-declaration as of this writing, IMPORT_REQUEST_EMAIL_SENT is expected
-- there too but may not have landed in that worktree yet -- the spelling here is agreed with that
-- branch regardless). That branch also re-declares this same constraint from V78's list, and,
-- being V184 (with V185 reserved for it as well), is numbered to merge BEFORE this V186 -- see the
-- "MIGRATION NUMBERING" note above for the full V184 -> V185 -> V186 order. Without carrying both
-- values forward here, this migration would silently DROP them from the constraint on any
-- environment where V186 applies after V184 (V186 re-declares the WHOLE constraint, so it REPLACES
-- whatever V184 left, not adds to it) -- production out-of-order/queued-migration behaviour is
-- exactly this repo's own documented trap (see CLAUDE.md's "A lower migration version merged after
-- a higher one is SILENTLY SKIPPED on prod" -- the analogous risk here is a HIGHER-numbered
-- migration silently UNDOING a lower one's constraint widening, not a skip, but the root cause is
-- the same: two migrations touching the same full-replace CHECK constraint with no coordination).
-- Carrying both here is harmless whether or not feat/import-request-per-factory has fully landed
-- yet: as of this writing no Java constant named IMPORT_STEP_ADVANCED or
-- IMPORT_REQUEST_EMAIL_SENT exists on THIS branch (V184 has not merged here), so these two CHECK
-- values simply have no current writer on this branch -- a CHECK constraint listing a value
-- nothing yet writes is inert, not wrong.
ALTER TABLE sales.ticket_event DROP CONSTRAINT IF EXISTS chk_event_kind;
ALTER TABLE sales.ticket_event ADD CONSTRAINT chk_event_kind CHECK (kind IN (
    'CREATED','SUBMITTED','PICKED_UP','PRICE_PROPOSED','APPROVED','REJECTED',
    'QUOTATION_ISSUED','COMMENTED','CLOSED','CANCELLED','EDITED',
    'DOCUMENT_ISSUED','REVISION_REQUESTED','PRICE_REVISED',
    'CUSTOMER_CONFIRMED','DEPOSIT_NOTICE_ISSUED','DEPOSIT_PAID',
    'IR_ISSUED','IR_SENT','SHIPPING','GOODS_RECEIVED',
    'AWAITING_FINAL_PAYMENT','FULLY_PAID','PRICE_OVERRIDDEN',
    'STAGE_CHANGED','MARKED_LOST','REOPENED',
    'ON_HOLD','DORMANT','RESUMED','POLICY_CHANGED',
    'QUOTATION_SENT','QUOTATION_ACCEPTED','QUOTATION_REJECTED',
    'PAYMENT_RECORDED','BILLING_UPDATED',
    'STOCK_RESERVED','DELIVERY_RECORDED','DELIVERY_COMPLETED',
    'CLOSE_CONFIRMED','CLOSE_CONFIRM_REVOKED',
    'ORDER_CONFIRMED_FROM_QUOTATION',
    'DEAL_QUOTATION_REORDERED',
    'IMPORT_STEP_ADVANCED',
    'IMPORT_REQUEST_EMAIL_SENT'
));
