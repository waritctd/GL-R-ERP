-- Review remediation (COMMIT 4): attachments were implemented at the wrong workflow level, plus
-- an audit defect in factory-quote attachment deletion.
--
-- NUMBERING — why this is V69 and not V66.
--
-- Checked live on 2026-07-20 against every place a version number could collide, same method
-- V67's header used:
--   - This branch's own tip already claims V65, V67, V68 (V66 deliberately skipped by V67 — see
--     that migration's header for the full trail).
--   - V66 is STILL claimed by the untracked file V66__special_money_request_schema.sql sitting in
--     the parallel worktree .claude/worktrees/special-money (feat/special-money-requests, not yet
--     merged) — re-confirmed present at authoring time.
--   - origin/main / hosted prod / hosted UAT have not moved past where V67's header already
--     recorded them; V69 is the first free slot after this branch's own history.
--
-- WHAT THIS MIGRATION DOES
--
-- 1. Sales-level Pricing Request attachments did not exist at all — only Import-uploaded raw
--    factory-quote evidence (an earlier commit) existed. Sales may now optionally attach
--    supporting files to the Pricing Request itself (zero attachments remains valid); Import can
--    mark which of those to include when it sends the factory email
--    (include_in_factory_email). This is a NEW table, not a reuse of hr.file_attachment, per the
--    approved remediation plan — it is fully domain-owned by sales.pricing_request and needs no
--    generic domain/owner_id indirection.
--
-- 2. Audit defect: FactoryQuoteService.deleteAttachment allowed deletion whenever the parent
--    pricing request was in MUTABLE_STATUSES, which included READY_FOR_CEO_REVIEW — so evidence
--    backing an already-submitted costing could be destroyed, and deletion physically removed the
--    file via Files.deleteIfExists. hr.file_attachment (shared across domains — leave requests
--    also use it) gains deleted_at/deleted_by/delete_reason so a permitted deletion becomes an
--    audited tombstone (metadata row kept, file kept on disk) instead of physically destroying
--    either. The application layer additionally refuses deletion outright once the quote revision
--    is READY_FOR_COSTING, or the attachment's quote is referenced by a costing that has already
--    been SUBMITTED.

CREATE TABLE sales.pricing_request_attachment (
    pricing_request_attachment_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    pricing_request_id BIGINT NOT NULL REFERENCES sales.pricing_request(pricing_request_id) ON DELETE CASCADE,
    file_name VARCHAR(255) NOT NULL,
    file_path VARCHAR(1000) NOT NULL,
    mime_type VARCHAR(100),
    file_size BIGINT,
    include_in_factory_email BOOLEAN NOT NULL DEFAULT FALSE,
    uploaded_by BIGINT REFERENCES hr.employee(employee_id) ON DELETE SET NULL,
    uploaded_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_pricing_request_attachment_request
    ON sales.pricing_request_attachment (pricing_request_id, uploaded_at DESC);

-- Supports FactoryQuoteService.attemptSend's "which attachments does Import want in this
-- factory email" lookup, run fresh at actual-send time (not enqueue time), so a late change to
-- the include flag is honoured up to the moment the worker really calls the mail provider.
CREATE INDEX idx_pricing_request_attachment_included
    ON sales.pricing_request_attachment (pricing_request_id)
    WHERE include_in_factory_email = TRUE;

ALTER TABLE hr.file_attachment
    ADD COLUMN deleted_at TIMESTAMPTZ,
    ADD COLUMN deleted_by BIGINT REFERENCES hr.employee(employee_id) ON DELETE SET NULL,
    ADD COLUMN delete_reason TEXT;
