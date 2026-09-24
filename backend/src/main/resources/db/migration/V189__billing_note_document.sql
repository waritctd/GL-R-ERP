-- ใบวางบิล (billing note) — GLA-99 step 3. A CUSTOMER-level cover sheet ("รวบรวมยอดค้างชำระจากใบ
-- แจ้งหนี้หรือรายการซื้อขายหลายๆ ยอด มารวมไว้ในใบเดียว" — CEO, via Ploy) that rolls several ALREADY-
-- ISSUED documents (remaining invoices, deposit notices — possibly across several of the customer's
-- deals) into one billed total for one credit cycle. Two typed variants share this exact layout —
-- ค่าสินค้า (goods) and ค่าขนส่ง (freight) — distinguished only by the `type` column, per the owner's
-- own two-sheet reference form (backend/src/main/resources/templates/billing_note_template.xls,
-- built from the ค่าขนส่ง sheet only — see BillingNoteRenderer's own Javadoc for why one template
-- serves both types).
--
-- Numbering: the SAME shared sales.document_sequence sequence the STORED remaining invoice (V188)
-- draws from (doc_type 'AR_GLR', format GLR<yy><5-digit seq>, versioned -<n> suffix) — see
-- th.co.glr.hr.common.ArGlrSequence, moved out of RemainingInvoiceRepository in this same branch so
-- both document families share one implementation, not two copies.
--
-- chk_event_kind (sales.ticket_event) is DELIBERATELY NOT touched by this migration. Issuing a
-- billing note writes the EXISTING TicketEventKind.DOCUMENT_ISSUED kind (one event per DISTINCT
-- ticket referenced by the note's own lines) — the identical choice V188's own header comment
-- documents for the remaining invoice, and for the identical reason: this is "a document was
-- issued that concerns this deal", which DOCUMENT_ISSUED already means, not a new business event
-- that needs its own vocabulary entry. chk_event_related_document (V184) is equally untouched —
-- the event carries no related-document type/id, matching how DepositNoticeService#requestRevision
-- and RemainingInvoiceService's own REVISION_REQUESTED events already do the same thing.

-- ─────────────────────────────────────────────────────────────────────────────────────────────
-- 0. hr.employee.can_issue_billing_note — per-employee grant (owner ruling, Ploy 2026-09-19)
-- ─────────────────────────────────────────────────────────────────────────────────────────────
-- Same shape as can_create_quotation (V165): a plain boolean capability, read live at decision
-- time by EmployeeAuthRepository#canIssueBillingNote, never inferred from the session principal.
-- Must work for ANY role, including plain `employee` (ภิญญดา, QC&ISO, is `employee` today) — see
-- BillingNoteService's own Javadoc for the full write/read gate this participates in. Defaults
-- FALSE for everyone; granting it to the four named holders on record is a hand-applied data change
-- on the target DB at ship time, not something this migration should hardcode.
ALTER TABLE hr.employee ADD COLUMN can_issue_billing_note BOOLEAN NOT NULL DEFAULT FALSE;

-- ─────────────────────────────────────────────────────────────────────────────────────────────
-- 1. sales.billing_note — one row per (customer, issued cover sheet), DRAFT -> ISSUED -> {
--    SUPERSEDED (revised) | CANCELLED (voided, lines released for re-billing) }.
-- ─────────────────────────────────────────────────────────────────────────────────────────────
CREATE TABLE sales.billing_note (
    id                      BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    -- customers.customer, NOT sales.customer -- V41 moved the customer-related tables into their
    -- own schema; sales.customer has not existed since.
    customer_id             BIGINT NOT NULL REFERENCES customers.customer(customer_id),
    type                    VARCHAR(10) NOT NULL,

    -- Mirrors sales.remaining_invoice's own base_number/version/doc_number shape (V188) exactly:
    -- base_number is minted ONCE at first issue from the shared AR_GLR sequence and kept across
    -- every revision; doc_number = base_number || '-' || version, recomputed together so the two
    -- can never drift. Both NULL while status = 'DRAFT'.
    base_number             VARCHAR(20),
    version                 INT NOT NULL DEFAULT 1,
    doc_number              VARCHAR(30),
    -- DRAFT -> ISSUED -> { SUPERSEDED (revised) | CANCELLED (voided) | SETTLED (every referenced
    -- source document fully paid) }. SETTLED is a THIRD terminal state alongside CANCELLED/
    -- SUPERSEDED, reached two ways (owner ruling C1, 2026-09-20, REPLACING this migration's own
    -- original B2 "MANUAL lines never block settlement" rule before it shipped to any database):
    --   (a) AUTOMATICALLY, on read, but ONLY when the note has at least one non-MANUAL line AND
    --       every non-MANUAL line's source is currently fully paid — an all-MANUAL note (the
    --       ค่าขนส่ง case) can NEVER reach this path, because "every non-MANUAL line is paid" is
    --       vacuously true with zero such lines, which is exactly the bug C1 closes;
    --   (b) EXPLICITLY, via BillingNoteService#markSettled (a human marking an all-MANUAL note
    --       paid) — the only transition that populates settled_by_id/settled_by_name/settled_at
    --       below; the automatic path (a) leaves them NULL (updated_at already records when).
    -- See BillingNoteRepository#reconcileSettlementForCustomer/#reconcileSettlementForNote's own
    -- Javadoc for the recompute-on-read mechanism and BillingNoteRepository#markSettled for (b).
    status                  VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    superseded_by_id        BIGINT REFERENCES sales.billing_note(id),
    -- The ISSUED note this row was created to CORRECT (owner ruling B1, 2026-09-20) — set only by
    -- BillingNoteService#revise, at the moment the correction DRAFT is created; NULL for a
    -- brand-new draft that starts its OWN revision chain (not a correction of anything). This is
    -- the explicit "which chain am I part of, and what is my own direct predecessor" pointer B1
    -- requires: "issue() must find its predecessor via the chain, never via findLiveIssued
    -- (customer, type)". Kept set forever once assigned (through ISSUED/SUPERSEDED/CANCELLED too)
    -- as permanent lineage, not cleared once consumed.
    revision_of_id          BIGINT REFERENCES sales.billing_note(id),
    cancel_reason           TEXT,
    cancelled_by_id         BIGINT,
    cancelled_by_name       VARCHAR(200),
    cancelled_at            TIMESTAMPTZ,

    -- ── Dialog fields ───────────────────────────────────────────────────────────────────────
    bill_date               DATE,
    payment_due_note        VARCHAR(200),
    -- Recorded when the customer signs on paper (mark-received) — see #2's own note column for
    -- why this is NOT what the printed document shows (the rendered file always ships BLANK
    -- ผู้วางบิล/ผู้รับวางบิล/วันนัดชำระเงิน signature lines for physical signing; these columns are
    -- this system's own record of that having happened, read back by a later UI, not re-printed).
    payment_appointment_date DATE,
    received_by_name        VARCHAR(200),
    received_at             TIMESTAMPTZ,
    note                    TEXT,

    -- ── Customer snapshot (frozen at DRAFT creation, refreshed on updateDraft, frozen for good
    -- at issue) — same discipline sales.remaining_invoice's own customer_name/tax_id/branch/
    -- address columns already follow (V188), via DepositNoticeService#resolveCustomerHeader. ────
    customer_name           VARCHAR(200),
    customer_tax_id         VARCHAR(20),
    customer_branch         VARCHAR(200),
    customer_address        TEXT,

    -- VAT-INCLUSIVE sum of every line's own (VAT-inclusive) amount — GLA-107 answer 1: การเงิน
    -- amounts show what the customer actually pays, and every source this note can reference
    -- (an ISSUED remaining invoice's grand_total, an ISSUED deposit notice's total_payable) is
    -- already VAT-inclusive, so no separate VAT line exists on this document (matches the owner's
    -- own form footer, "ราคานี้เป็นราคาที่รวมภาษีแล้ว").
    total_amount             NUMERIC(15,2) NOT NULL DEFAULT 0,

    created_by_id            BIGINT,
    created_by_name          VARCHAR(200),
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    issued_by_id              BIGINT,
    issued_by_name            VARCHAR(200),
    issued_at                 TIMESTAMPTZ,

    -- Populated ONLY by BillingNoteService#markSettled (C1's explicit path, above) — the automatic
    -- recompute-on-read settlement leaves all three NULL forever, deliberately (see
    -- BillingNoteRepository#reconcileSettlementForCustomer's own "no audit trail" reasoning, which
    -- C1 does not disturb for that path). No CHECK constraint ties these to status = 'SETTLED':
    -- an automatically-settled note is SETTLED with all three NULL, which is valid and expected.
    settled_by_id             BIGINT,
    settled_by_name           VARCHAR(200),
    settled_at                TIMESTAMPTZ,

    CONSTRAINT chk_billing_note_type CHECK (type IN ('GOODS', 'FREIGHT')),
    CONSTRAINT chk_billing_note_status CHECK (status IN ('DRAFT', 'ISSUED', 'SUPERSEDED', 'CANCELLED', 'SETTLED')),
    -- A DRAFT has no minted identity; anything past DRAFT does — mirrors
    -- chk_remaining_invoice_issued_fields (V188) / chk_import_request_issued_fields (V154).
    CONSTRAINT chk_billing_note_issued_fields
        CHECK (
            (status = 'DRAFT'  AND base_number IS NULL     AND doc_number IS NULL     AND issued_at IS NULL)
         OR (status <> 'DRAFT' AND base_number IS NOT NULL AND doc_number IS NOT NULL AND issued_at IS NOT NULL)
        ),
    CONSTRAINT chk_billing_note_version_positive CHECK (version >= 1),
    CONSTRAINT chk_billing_note_cancel_fields
        CHECK (
            (status = 'CANCELLED' AND cancel_reason IS NOT NULL AND cancelled_by_id IS NOT NULL AND cancelled_at IS NOT NULL)
         OR (status <> 'CANCELLED' AND cancel_reason IS NULL AND cancelled_by_id IS NULL AND cancelled_at IS NULL)
        )
);

CREATE INDEX ix_billing_note_customer ON sales.billing_note(customer_id);

CREATE UNIQUE INDEX ux_billing_note_doc_number
    ON sales.billing_note(doc_number)
    WHERE doc_number IS NOT NULL;

-- Owner ruling B1 (2026-09-20), REPLACING this migration's own original (customer_id, type)-scoped
-- pair before either index was ever applied to any database (confirmed against every remote
-- branch and develop's own max version before editing this file in place — see the PR body):
-- a customer gets a NEW billing note every CREDIT CYCLE, so MANY ISSUED notes of the same
-- (customer, type) legitimately coexist at once (one per cycle) — the original
-- ux_billing_note_customer_type_issued/_draft pair was wrong, not merely incomplete, because it
-- capped a customer+type to exactly one live note EVER, which is the "one bill per customer,
-- forever" model, not the real "one bill per credit cycle" one. revise() is a CORRECTION of a
-- single cycle's own bill, never a new cycle, so live-uniqueness belongs to the REVISION CHAIN
-- (this row's own base_number, or — before one is minted — the specific predecessor a correction
-- draft names via revision_of_id above), not to (customer_id, type).
--
-- At most one live correction DRAFT per predecessor being corrected. A brand-new (non-correction)
-- draft has revision_of_id NULL and is UNCONSTRAINED here — several independent brand-new drafts
-- for the same (customer, type) may coexist (e.g. preparing next cycle's bill while the current
-- one is still ISSUED-but-unsettled); each becomes its own new chain once issued. The DB-level
-- guard that actually prevents double billing regardless of how many drafts exist is per-LINE
-- (ux_billing_note_line_source_live below), not this index.
CREATE UNIQUE INDEX ux_billing_note_revision_of_draft
    ON sales.billing_note(revision_of_id)
    WHERE status = 'DRAFT' AND revision_of_id IS NOT NULL;

-- At most one ISSUED row per CHAIN (base_number) — the real DB-level backstop for "issue() must
-- find its predecessor via the chain" (B1): base_number is shared across every revision of one
-- chain, so this index refuses two rows of the SAME chain ever both reading ISSUED at once, the
-- identical "index over app code" safety net the original (now-replaced) per-(customer,type)
-- index provided, just correctly re-scoped. base_number is NULL for every DRAFT (chk_billing_note_
-- issued_fields), so this index is silently inert for drafts and only ever bites ISSUED rows.
CREATE UNIQUE INDEX ux_billing_note_base_number_issued
    ON sales.billing_note(base_number)
    WHERE status = 'ISSUED';

-- ─────────────────────────────────────────────────────────────────────────────────────────────
-- 2. sales.billing_note_line — snapshot lines, frozen at DRAFT creation/refresh, immutable once
--    the parent is ISSUED (a correction goes through #revise, a brand-new DRAFT + line set).
-- ─────────────────────────────────────────────────────────────────────────────────────────────
CREATE TABLE sales.billing_note_line (
    id                      BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    billing_note_id         BIGINT NOT NULL REFERENCES sales.billing_note(id) ON DELETE CASCADE,
    seq                     INT NOT NULL,
    source_type             VARCHAR(20) NOT NULL,
    -- NULL for MANUAL rows (freight/external tax invoices the caller types in by hand); the
    -- sales.remaining_invoice(id) or sales.deposit_notice(deposit_notice_id) row otherwise. No
    -- FK — same "a frozen snapshot must survive its source row's own lifecycle" reasoning V188's
    -- own customer_quotation_id/deposit_notice_id columns document; every value this document
    -- prints is already snapshotted into the columns below, so a live dereference is never needed.
    source_id               BIGINT,
    -- The deal this line's source document belongs to (NULL for a MANUAL row with no deal of its
    -- own) — read-only traceability, and what BillingNoteService#issue events against (one
    -- DOCUMENT_ISSUED event per DISTINCT ticket_id among a note's own lines).
    ticket_id               BIGINT REFERENCES sales.ticket(ticket_id),

    doc_number              VARCHAR(30) NOT NULL,
    doc_date                DATE,
    due_date                DATE,
    amount                  NUMERIC(15,2) NOT NULL,
    note                    TEXT,

    -- Denormalized copy of the PARENT's own status at the time this line last changed ownership —
    -- kept in sync by BillingNoteRepository (a plain second UPDATE inside the same transaction as
    -- every parent-status write, never a trigger) because a Postgres index cannot reference another
    -- table's column. This is what makes "no double billing" (below) a real, enforced invariant
    -- rather than a service-level courtesy check, the same "index over app code" upgrade V188's own
    -- P1 review finding made for the remaining invoice's one-live-per-deal rule.
    --
    -- 'RELEASED' (neither DRAFT nor ISSUED) covers three cases, all meaning "this row no longer
    -- holds a live claim on its source document": (a) the parent was CANCELLED (spec: "lines
    -- released for re-billing"); (b) the parent was SUPERSEDED — but note a SUPERSEDED row's lines
    -- move to RELEASED at the moment its REPLACEMENT draft is created (see (c)), not at supersede
    -- time, so the predecessor never double-claims against its own successor; (c) a #revise() is
    -- IN PROGRESS — the predecessor's lines release the instant the correction DRAFT is created
    -- (transferring "live" ownership of those same sources to the new draft immediately), and are
    -- RESTORED to 'ISSUED' if that draft is later deleted before being issued. This is a
    -- deliberately different timing from V188's remaining invoice (whose ISSUED predecessor stays
    -- fully live until the replacement actually issues) because THIS invariant is scoped to
    -- individual LINES, not to a whole ticket — two billing notes racing to claim the exact same
    -- remaining invoice is the real risk (below); a note correcting its own prior claim on its own
    -- same lines is not that risk and must not trip the same guard.
    note_status              VARCHAR(20) NOT NULL DEFAULT 'DRAFT',

    CONSTRAINT chk_billing_note_line_seq_positive CHECK (seq >= 1),
    CONSTRAINT chk_billing_note_line_source_type CHECK (source_type IN ('REMAINING_INVOICE', 'DEPOSIT_NOTICE', 'MANUAL')),
    CONSTRAINT chk_billing_note_line_note_status CHECK (note_status IN ('DRAFT', 'ISSUED', 'RELEASED')),
    -- MANUAL rows carry no source row to double-claim; every other type must.
    CONSTRAINT chk_billing_note_line_source_id
        CHECK ((source_type = 'MANUAL') = (source_id IS NULL))
);

CREATE INDEX ix_billing_note_line_parent ON sales.billing_note_line(billing_note_id);
CREATE INDEX ix_billing_note_line_ticket ON sales.billing_note_line(ticket_id) WHERE ticket_id IS NOT NULL;

-- NO DOUBLE BILLING: one (source_type, source_id) may appear on at most ONE live (DRAFT|ISSUED)
-- note at a time. MANUAL rows (source_id NULL) are exempt by the partial index's own NULL
-- exclusion — there is nothing to double-claim. Enforced at the database, not merely in
-- BillingNoteService's own candidate-filtering query, so a race between two callers drafting a
-- note against the same remaining invoice is refused by Postgres itself, not merely made unlikely.
CREATE UNIQUE INDEX ux_billing_note_line_source_live
    ON sales.billing_note_line(source_type, source_id)
    WHERE source_id IS NOT NULL AND note_status IN ('DRAFT', 'ISSUED');
