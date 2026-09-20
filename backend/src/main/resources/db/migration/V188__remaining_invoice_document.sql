-- ใบแจ้งหนี้ส่วนที่เหลือ (remaining invoice) as a STORED, versioned document — GLA-99 step 2.
--
-- Today (develop, V187) this document is entirely stateless: DepositNoticeService
-- #getRemainingInvoiceOptions/#getRemainingInvoiceXlsx compute and render it fresh on every call,
-- nothing is stored, and the printed number is a placeholder ("GLR" + Thai-year%100 + %03d(ticketId))
-- that is never minted, never unique, and can repeat across re-downloads. This migration adds the
-- table half of turning it into a real controlled document: DRAFT -> ISSUED -> SUPERSEDED, a real
-- minted number shared with the future ใบวางบิล (billing note, GLA-99 step 3/V189), and a frozen
-- snapshot so an issued invoice's file never silently changes when the deal it came from is edited
-- later. Content rules (item/deduction sourcing, VAT, 22-row capacity, negative-net refusal, etc.)
-- are UNCHANGED — see DepositNoticeService's own "Remaining Invoice" section header comment — this
-- migration only gives that already-correct computation somewhere durable to freeze into.
--
-- MIGRATION NUMBERING: checked against all three sources V154's own header describes (git log
-- --all --diff-filter=A, every worktree under .claude/worktrees, and — not reachable from here —
-- real production's own hr.flyway_schema_history) immediately before authoring this file. develop
-- was at V187 with nothing claiming V188 anywhere. Re-check before merge if this branch sits.
--
-- Deliberately does NOT touch sales.ticket_event.chk_event_kind (plan instruction, GLA-99 step 2):
-- issuing/revising a remaining invoice is recorded with the EXISTING TicketEventKind.DOCUMENT_ISSUED
-- kind (the same one DepositNoticeService#issue already writes for the sibling deposit-notice
-- document), so no CHECK constraint needs re-declaring here. Nor does it touch
-- chk_event_related_document (V184) — RemainingInvoiceService logs its event with no related-
-- document type/id (documentType=null), matching how DepositNoticeService#requestRevision already
-- does for REVISION_REQUESTED; adding REMAINING_INVOICE to that list is left for a later branch
-- if/when something actually needs to look up "which remaining invoice did this event produce".

-- ─────────────────────────────────────────────────────────────────────────────────────────────
-- 1. sales.remaining_invoice — one row per (deal, issued document), DRAFT -> ISSUED -> SUPERSEDED
-- ─────────────────────────────────────────────────────────────────────────────────────────────
CREATE TABLE sales.remaining_invoice (
    id                     BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ticket_id              BIGINT NOT NULL REFERENCES sales.ticket(ticket_id) ON DELETE CASCADE,
    -- Which accepted CustomerQuotation this invoice was sourced from (see resolveRemainingInvoice's
    -- own Javadoc for the qualification rule) — NULL for the pre-PCR-chain legacy fallback path
    -- (resolveLegacy), which predates CustomerQuotation existing at all. No FK: sales.quotation
    -- rows are never deleted, but this stays consistent with deposit_notice_id below, which for the
    -- same "frozen snapshot must survive its source being edited/removed" reason cannot be a hard FK
    -- either (a hard FK would forbid deleting a row this table only ever READS at issue time).
    customer_quotation_id  BIGINT,
    -- Which deposit notice's item snapshot this invoice's items/deduction were sourced from (ruling
    -- D11) — NULL when items came straight from the quotation (bypass-policy deal, no deposit
    -- notice to match) or from the pre-PCR-chain legacy fallback. Traceability only; the actual
    -- content is already frozen into this row's own snapshot columns/items below, so a caller never
    -- needs to dereference this to render the file.
    deposit_notice_id      BIGINT REFERENCES sales.deposit_notice(deposit_notice_id),

    -- "GLR<yy><5-digit seq>" (e.g. GLR6900001), minted ONCE at first issue from the shared
    -- sales.document_sequence (doc_type 'AR_GLR', V29's generic per-type-per-year sequence — the
    -- SAME table/mechanism DepositNoticeRepository#nextDocNumber and
    -- ImportRequestRepository#nextDocNumber already use, doc_type 'DEPOSIT_NOTICE'/'IMPORT_REQUEST'
    -- respectively). A revision KEEPS this value — only doc_number's own "-<version>" suffix moves.
    -- The future ใบวางบิล (V189) shares doc_type 'AR_GLR' so the two documents' numbers can never
    -- collide with each other, only with themselves.
    --
    -- R5 (Opus review, GLA-99 step 2 review-round-1, 2026-09-20): documenting the LEGACY collision
    -- risk this Javadoc's own class-header comment flags but never quantifies. The stateless
    -- predecessor (DepositNoticeService#getRemainingInvoiceXlsx, removed by O1 — see this
    -- migration's own header) printed "GLR" + Thai-year%100 + %03d(ticket_id), with NO version
    -- suffix and NO minimum-width cap on the ticket id half (Java's %03d only pads UP, never
    -- truncates). The two formats can only be STRING-IDENTICAL when both sides are the same total
    -- length: this table's base_number is always exactly 10 characters ("GLR" + 2 + 5-digit
    -- zero-padded seq), while the legacy string is 8 characters for ticket ids 1-999, 9 for
    -- 1,000-9,999, and — the one width that can actually coincide — 10 characters for ticket ids
    -- 10,000-99,999 (5 digits, same as this table's own seq width). So: ticket ids BELOW 10,000
    -- cannot collide at all (different string length); ticket ids 10,000-99,999 COULD in principle
    -- produce a base_number textually equal to some legacy ticket's old printed number, in the
    -- same Thai year. This is a residual/theoretical risk, not a live bug, for two reasons: (1)
    -- the legacy number was NEVER STORED anywhere (stateless — "nothing to migrate", above), so
    -- there is no persisted row it could collide WITH; (2) every doc_number a caller actually SEES
    -- is base_number || '-' || version — the "-<version>" suffix a legacy number never carried —
    -- so the two are visually distinguishable on sight even in the coincidence case. Only the bare
    -- base_number column, which this table never prints standalone, carries the theoretical clash.
    base_number            VARCHAR(20),
    version                INT NOT NULL DEFAULT 1,
    -- base_number || '-' || version, e.g. "GLR6900001-1" then "GLR6900001-2" on revision. Minted/
    -- computed together with base_number at issue — never independently, so the two can never drift.
    doc_number             VARCHAR(30),
    status                 VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    -- Set on the OLD row when a correction is issued (mirrors sales.import_request.superseded_by_id,
    -- V154) — a nullable self-reference rather than a status-only marker, so a reader of an archived
    -- copy can follow the chain forward to what replaced it.
    superseded_by_id       BIGINT REFERENCES sales.remaining_invoice(id),

    -- ── Dialog fields (RemainingInvoiceDialog.jsx's own H9/deposit-ref/H7/หมายเหตุ inputs) ─────
    reference               VARCHAR(255),  -- H9: quotation number (default) or a free-text customer PO
    deposit_reference       VARCHAR(255),  -- the "หัก มัดจำ" row's own reference suffix
    doc_date                DATE,          -- H7
    notes                   TEXT[] NOT NULL DEFAULT '{}',  -- selected หมายเหตุ template lines, verbatim

    -- ── Customer snapshot (frozen at DRAFT creation, refreshed on updateDraft, frozen for good at
    -- issue) — same discipline sales.deposit_notice's own customer_name/tax_id/address already
    -- follow (V12), via the SAME resolveCustomerHeader lookup (DepositNoticeService, widened
    -- package-private this branch) so the two documents can never source a customer's
    -- address/tax id/branch differently. ─────────────────────────────────────────────────────
    customer_name           VARCHAR(200),
    customer_tax_id         VARCHAR(20),
    customer_branch         VARCHAR(200),
    customer_address        TEXT,
    project_name            VARCHAR(200),

    -- ── Money snapshot — items_total/deposit_deduction/net/vat mirror
    -- RemainingInvoiceOptionsDto's own itemsTotal/depositAmount/netAmount/vatAmount fields exactly
    -- (same NUMERIC(15,2) precision sales.deposit_notice already uses for the equivalent columns).
    -- grand_total is the VAT-INCLUSIVE total (= net + vat) — what a future ใบวางบิล references,
    -- per the plan's own note. ──────────────────────────────────────────────────────────────────
    items_total             NUMERIC(15,2),
    deposit_deduction       NUMERIC(15,2),
    net_amount              NUMERIC(15,2),
    vat_amount               NUMERIC(15,2),
    grand_total              NUMERIC(15,2),

    -- ── Audit ────────────────────────────────────────────────────────────────────────────────
    created_by_id            BIGINT,
    created_by_name          VARCHAR(200),
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    issued_by_id             BIGINT,
    issued_by_name           VARCHAR(200),
    issued_at                TIMESTAMPTZ,

    CONSTRAINT chk_remaining_invoice_status
        CHECK (status IN ('DRAFT', 'ISSUED', 'SUPERSEDED')),
    -- A DRAFT has no minted identity at all; anything past DRAFT has a full one. Mirrors
    -- sales.import_request's own chk_import_request_issued_fields (V154) — stops a half-issued row
    -- from existing rather than relying on the service to always set every field together.
    CONSTRAINT chk_remaining_invoice_issued_fields
        CHECK (
            (status = 'DRAFT'  AND base_number IS NULL     AND doc_number IS NULL     AND issued_at IS NULL)
         OR (status <> 'DRAFT' AND base_number IS NOT NULL AND doc_number IS NOT NULL AND issued_at IS NOT NULL)
        ),
    CONSTRAINT chk_remaining_invoice_version_positive CHECK (version >= 1)
);

CREATE INDEX ix_remaining_invoice_ticket ON sales.remaining_invoice(ticket_id);
CREATE INDEX ix_remaining_invoice_deposit_notice ON sales.remaining_invoice(deposit_notice_id)
    WHERE deposit_notice_id IS NOT NULL;

-- The issued number is the document's identity and must be unique across the whole table; a DRAFT
-- (doc_number IS NULL) is excluded by the partial index, matching
-- ux_import_request_doc_number's (V154) identical reasoning.
CREATE UNIQUE INDEX ux_remaining_invoice_doc_number
    ON sales.remaining_invoice(doc_number)
    WHERE doc_number IS NOT NULL;

-- At most one ISSUED and at most one DRAFT per DEAL, full stop — NOT per (ticket_id,
-- customer_quotation_id) as this migration originally read. P1 (Opus review, GLA-99 step 2
-- review-round-2, 2026-09-20): owner ruling O2 ("ONE live remaining invoice per DEAL, not per
-- chain") was, until this fix, enforced only in application code
-- (RemainingInvoiceService#createDraft/revise's own SELECT-then-INSERT checks) — a real invariant
-- in prose, but not one the database itself could ever refuse. Scoping these two partial unique
-- indexes down to ticket_id alone (dropping customer_quotation_id from the key) makes O2 an actual
-- DB constraint: two ISSUED rows, or two DRAFT rows, for the same ticket_id can never both exist,
-- regardless of which quotation chain either belongs to. TWO indexes, not one over "status <>
-- 'SUPERSEDED'", for the identical reason V154's own header comment gives at length: a correction
-- must be preparable as a DRAFT while the previous version is still ISSUED (the old one does not
-- stop being live until the replacement is actually issued) — that is exactly one DRAFT + one
-- ISSUED coexisting, which two separate partial indexes allow and one combined index would not.
--
-- This also RETIRES the previous residual-gap paragraph here about NULL customer_quotation_id
-- (the pre-PCR-chain legacy fallback path, resolveLegacy) escaping the old two-column unique
-- index: with customer_quotation_id no longer part of either index's key, that gap cannot recur —
-- ticket_id alone is never NULL, so every row is caught regardless of its quotation chain.
CREATE UNIQUE INDEX ux_remaining_invoice_ticket_issued
    ON sales.remaining_invoice(ticket_id)
    WHERE status = 'ISSUED';
CREATE UNIQUE INDEX ux_remaining_invoice_ticket_draft
    ON sales.remaining_invoice(ticket_id)
    WHERE status = 'DRAFT';

-- ─────────────────────────────────────────────────────────────────────────────────────────────
-- 2. sales.remaining_invoice_item — snapshot lines, frozen at DRAFT creation/refresh, immutable
--    once the parent is ISSUED (the service never mutates an item row after issue; a correction
--    goes through #revise, which inserts a brand-new DRAFT + item set instead).
-- ─────────────────────────────────────────────────────────────────────────────────────────────
-- Deliberately mirrors sales.deposit_notice_item's own column shape (V12) and
-- RemainingInvoiceItemDto's field order exactly — a line sourced from either a
-- CustomerQuotationItemDto or a DepositNoticeItemDto already maps onto that DTO without inventing a
-- third convention (see RemainingInvoiceItemDto's own Javadoc); this table just gives that same
-- shape somewhere durable to live once snapshotted. The deposit deduction ("หัก มัดจำ") row is
-- NOT stored here as a synthetic item — RemainingInvoiceRenderer already renders it from the
-- parent's own deposit_deduction/deposit_reference columns as a SEPARATE step (see toXlsx's own
-- "Deposit deduction row" section), so duplicating it into this table would be a second copy of the
-- same number that could drift from the first.
CREATE TABLE sales.remaining_invoice_item (
    id                     BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    remaining_invoice_id   BIGINT NOT NULL REFERENCES sales.remaining_invoice(id) ON DELETE CASCADE,
    seq                    INT           NOT NULL,
    description            TEXT          NOT NULL,
    qty                    NUMERIC(12,2),
    unit                   VARCHAR(30),
    unit_price             NUMERIC(15,2),
    discount_label         VARCHAR(100),
    net_unit_price         NUMERIC(15,2),
    amount                 NUMERIC(15,2) NOT NULL,

    CONSTRAINT chk_remaining_invoice_item_seq_positive CHECK (seq >= 1)
);

CREATE INDEX ix_remaining_invoice_item_parent ON sales.remaining_invoice_item(remaining_invoice_id);
