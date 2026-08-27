-- ใบขอซื้อ / Import Request (form F-SM-001 (04)) as a STORED document.
--
-- ⚠️ WIP — NOT READY TO MERGE. There is no service and no controller for this table yet, so
-- merging it would apply a schema change to production for something nothing reads or writes. It
-- lives on a branch to be durable, not to ship. See the "what still has to be built" list at the
-- bottom.
--
-- MIGRATION NUMBERING: this is V154, and it is the FOURTH number this file has carried.
--
-- It was written as V150 after `git log --all --diff-filter=A` came back clean. That check was not
-- wrong; it was INCOMPLETE, and the number was taken out from under it three times:
--   V150 -> V151  V150__ceo_pricing_decision_simplify merged to origin/main mid-session.
--   V151 -> V153  V151__catalog_country_normalization AND V152__pricing_formula_engine_wiring both
--                 already existed, UNCOMMITTED, in other active worktrees — invisible to any
--                 ref-based check.
--   V153 -> V154  V153__catalog_priceable_basis merged to origin/main. (That file had itself been
--                 renumbered from V152 for the same reason, which is the tell: this is systemic,
--                 not bad luck.)
--
-- So `git log --all` is NOT sufficient in this repo. There are ~20 concurrent worktrees under
-- .claude/worktrees, and a migration sitting uncommitted in one is a real claim no ref knows about.
-- Check ALL THREE sources:
--     git log --all --diff-filter=A -- 'backend/src/main/resources/db/migration/V*.sql'
--     for w in $(git worktree list --porcelain | awk '/^worktree /{print $2}'); do \
--         ls "$w"/backend/src/main/resources/db/migration/ 2>/dev/null; done
--     real production's own hr.flyway_schema_history
-- and re-check immediately before merge. This number WILL go stale again if this branch sits.
--
-- ─────────────────────────────────────────────────────────────────────────────────────────────
-- What this migration is for
-- ─────────────────────────────────────────────────────────────────────────────────────────────
-- The ใบขอซื้อ already GENERATES (PR #812) and is downloadable from the deal page (PR #816), but
-- statelessly: nothing is stored, no number is minted, and the caller types the ReF. No. That was a
-- deliberate staging decision — it let the form ship without a schema change against a production
-- Flyway history with six known checksum mismatches. This table is the other half: a recorded,
-- numbered, supersedable document.
--
-- The document being modelled is the business's real controlled form, F-SM-001 (04), supplied by
-- the owner with a filled example (IR69068, Padana, 6/3/26). Every column maps to a field that form
-- actually prints; the form's own field names are quoted in the column comments.
--
-- ─────────────────────────────────────────────────────────────────────────────────────────────
-- ONE IR PER BRAND -- owner ruling
-- ─────────────────────────────────────────────────────────────────────────────────────────────
-- F-SM-001 carries "Brand:" as a single HEADER field, not a per-line column, so a deal whose items
-- span two brands produces two forms. Owner: "one ir per one brand, if one deal has multiple brand
-- it has to be generated into multiple ir too". Hence (ticket_id, brand), not one row per ticket.
--
-- Deliberately NOT sales.factory_purchase_order revived under a new name. That aggregate is
-- per-FACTORY and models a supplier PO ledger the owner ruled out of scope (commit ebaf6888); this
-- is per-BRAND and models the business's own request form. Different documents. What IS worth
-- reusing, when the fulfilment axis eventually moves down onto this table, is
-- TicketRepository#deriveImportStatus's rollup arithmetic — its monotonic guard and delivery-axis
-- firewall are load-bearing and should not be re-derived.
--
-- ─────────────────────────────────────────────────────────────────────────────────────────────
-- 1. sales.ticket.required_by_note -- "กำหนดวันที่ต้องการของ", owned by SALES
-- ─────────────────────────────────────────────────────────────────────────────────────────────
-- The one field on this form that is not import's to fill. Owner ruling: sales supplies it, and may
-- do so once the order is already confirmed (DealStage.ORDER_RECEIVED onward). It lives on the
-- ticket, not the IR, because it is one customer deadline for the whole deal — a deal with two brand
-- IRs has one required-by date, and asking sales to restate it per brand invites the two to
-- disagree. Each IR SNAPSHOTS it at issue (see import_request.required_by_note below).
--
-- TEXT, not DATE, on purpose: the owner's filled example reads "Within 21/5/26", a commitment phrase
-- and not a parseable date. A DATE column could not hold it, and coercing it would silently change
-- what the customer was told.
--
-- Until this column exists, DealFulfilmentPanel's IR block deliberately does NOT offer import an
-- input for it (PR #816) — it prints blank for handwriting rather than have one department fill in
-- another's field.
ALTER TABLE sales.ticket
    ADD COLUMN IF NOT EXISTS required_by_note VARCHAR(200);

COMMENT ON COLUMN sales.ticket.required_by_note IS
    'F-SM-001 "กำหนดวันที่ต้องการของ" -- free text (e.g. "Within 21/5/26"), set by the deal owner from ORDER_RECEIVED onward, snapshotted onto each import request at issue.';

-- ─────────────────────────────────────────────────────────────────────────────────────────────
-- 2. sales.import_request -- one row per (deal, brand)
-- ─────────────────────────────────────────────────────────────────────────────────────────────
-- Lifecycle DRAFT -> ISSUED -> SUPERSEDED, and doc_number is minted ONLY on DRAFT->ISSUED, exactly
-- as sales.deposit_notice does. That ordering is why a draft state exists: the owner asked for the
-- generated document to be correctable "in case our generation has some error on the early stages",
-- and a draft discarded before issue costs neither a sequence number nor an audit record of a
-- controlled document that was never really raised. After issue, a wrong document is corrected by a
-- new version that SUPERSEDES it — an issued F-SM-001 is an ISO controlled form and must not be
-- silently overwritten.
CREATE TABLE sales.import_request (
    import_request_id     BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ticket_id             BIGINT       NOT NULL REFERENCES sales.ticket(ticket_id) ON DELETE CASCADE,
    -- "Brand" (header). NOT NULL: the form has no unbranded variant and the owner confirmed the
    -- field is necessary. The service refuses to raise an IR for a deal whose items carry no usable
    -- brand rather than inventing a placeholder. Note sales.ticket_item.brand is itself NOT NULL
    -- (V8), so the unusable case is BLANK, not null.
    brand                 VARCHAR(120) NOT NULL,
    version               INT          NOT NULL DEFAULT 1,
    status                VARCHAR(20)  NOT NULL DEFAULT 'DRAFT',
    -- "ReF. No." -- IR<yy><nnn>, e.g. IR69068 = IR + Thai year 2569 + sequence 068. Minted from the
    -- shared sales.document_sequence (doc_type 'IMPORT_REQUEST') that V29 deliberately kept generic
    -- when it split sales.document into per-type tables. NULL until issued.
    --
    -- OVERRIDABLE, per owner ruling ("the ir number should be able to be overriden too"). When an
    -- override is supplied the sequence is NOT advanced, so a number typed ahead of the counter
    -- cannot later be minted a second time — ux_import_request_doc_number refuses the duplicate.
    doc_number            VARCHAR(30),
    -- "Request date" -- set at issue, not at draft creation, so the printed date is the date the
    -- document was actually raised.
    issue_date            DATE,

    -- ── Snapshots frozen at issue ───────────────────────────────────────────────────────────
    -- Same discipline as sales.deposit_notice's customer snapshot: once issued, later edits to the
    -- deal must not retroactively alter a signed controlled document.
    customer_name         VARCHAR(200),  -- "สั่งมาให้"
    project_name          VARCHAR(200),  -- the "Project : ..." line in the form's body
    requested_by_name     VARCHAR(200),  -- "Requested by" (table column) -- the deal's sales rep
    required_by_note      VARCHAR(200),  -- "กำหนดวันที่ต้องการของ" -- snapshot of ticket.required_by_note
    deposit_received_date DATE,          -- "วันที่ได้รับมัดจำ" -- derived from the payment track

    -- ── Import-owned, all optional ──────────────────────────────────────────────────────────
    -- Owner ruling: import fills these and every one may be left blank. The approval blocks are
    -- PRINT-ONLY -- the form is generated with them empty for wet signature, exactly as the paper
    -- process works today. Nothing here gates the fulfilment status, and no new role gate is
    -- introduced for "Buyer or Senior Buyer" or "General Manager or Managing Director"; turning that
    -- sequence into an enforced workflow is a separate decision the owner explicitly deferred.
    vessel_eta_note       VARCHAR(200),  -- "กำหนดเรือเข้าโดยประมาณ"
    checked_by_name       VARCHAR(200),  -- "Checked By" (Buyer or Senior Buyer)
    checked_date          DATE,          -- "Checked date"
    approved_by_name      VARCHAR(200),  -- "Approve By" (General Manager or Managing Director)
    approved_date         DATE,          -- "Approve date"

    -- ── Audit ───────────────────────────────────────────────────────────────────────────────
    created_by_id         BIGINT,
    created_by_name       VARCHAR(200),
    issued_by_id          BIGINT,
    issued_by_name        VARCHAR(200),  -- "Request By" (footer) -- the import staffer who raised it
    -- Set on the OLD row when a correction is issued, so the chain is followable forward. A
    -- nullable self-reference rather than a status-only marker: "superseded" without saying by what
    -- leaves a reader of an archived PDF unable to find the document that replaced it.
    superseded_by_id      BIGINT REFERENCES sales.import_request(import_request_id),
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    issued_at             TIMESTAMPTZ,

    CONSTRAINT chk_import_request_status
        CHECK (status IN ('DRAFT', 'ISSUED', 'SUPERSEDED')),
    -- A DRAFT has no number and no issue date; anything past DRAFT has both. This is what stops a
    -- half-issued row existing at all, rather than relying on the service to always set both.
    CONSTRAINT chk_import_request_issued_fields
        CHECK (
            (status = 'DRAFT'  AND doc_number IS NULL     AND issue_date IS NULL)
         OR (status <> 'DRAFT' AND doc_number IS NOT NULL AND issue_date IS NOT NULL)
        ),
    CONSTRAINT chk_import_request_version_positive CHECK (version >= 1)
);

CREATE INDEX ix_import_request_ticket ON sales.import_request(ticket_id);

-- The issued number is the document's identity and must be unique across the whole table; drafts
-- (doc_number IS NULL) are excluded by the partial index rather than by relying on NULLs comparing
-- unequal, so the intent is stated rather than inferred from SQL null semantics.
CREATE UNIQUE INDEX ux_import_request_doc_number
    ON sales.import_request(doc_number)
    WHERE doc_number IS NOT NULL;

-- At most one ISSUED and at most one DRAFT per (deal, brand) — TWO indexes, not one over
-- "status <> 'SUPERSEDED'".
--
-- The single-index version was wrong and this is why: a correction must be prepared as a DRAFT
-- while the previous version is still ISSUED, because the old form does not stop being the live
-- request until its replacement is actually raised. That is the same rule CustomerQuotationService
-- follows for a re-issue through the CEO chain — "the customer held a real offer for the whole time
-- the new chain ran, and if the CEO had refused the new price there was something to fall back to"
-- — and it applies with more force here, since a factory may already be acting on the issued IR.
-- One index over non-superseded rows forbids exactly that pair and would have made revisions
-- impossible to build, discovered only when the revise path was written.
--
-- Enforced in the database rather than by a service-side "check then insert", which races.
CREATE UNIQUE INDEX ux_import_request_ticket_brand_issued
    ON sales.import_request(ticket_id, brand)
    WHERE status = 'ISSUED';

CREATE UNIQUE INDEX ux_import_request_ticket_brand_draft
    ON sales.import_request(ticket_id, brand)
    WHERE status = 'DRAFT';

-- ─────────────────────────────────────────────────────────────────────────────────────────────
-- 3. sales.import_request_item -- the form's line table
-- ─────────────────────────────────────────────────────────────────────────────────────────────
-- Columns are exactly F-SM-001's: Item / Code / Size / จำนวน / หน่วยนับ. A snapshot of the deal's
-- lines at issue, not a live join -- the printed form must keep saying what it said when signed,
-- even if the deal's items are later edited.
--
-- Deliberately carries no price of any kind. The form has no price column, and this table is
-- readable by every role that may read the IR; a cost here would leak supplier pricing that
-- FactoryQuoteService/PricingDecisionService keep to import and the CEO.
CREATE TABLE sales.import_request_item (
    import_request_item_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    import_request_id      BIGINT NOT NULL
        REFERENCES sales.import_request(import_request_id) ON DELETE CASCADE,
    -- Which deal line this was snapshotted from. No FK and nullable on purpose: sales.ticket_item
    -- rows can be deleted by an item edit, and an issued form must survive that.
    ticket_item_id         BIGINT,
    seq                    INT           NOT NULL,  -- "Item"
    code                   VARCHAR(255)  NOT NULL,  -- "Code" (e.g. "Lithos Nero Nat")
    size                   VARCHAR(80),             -- "Size" (e.g. "60x60 cm")
    qty                    NUMERIC(12,2) NOT NULL,  -- "จำนวน"
    unit                   VARCHAR(30),             -- "หน่วยนับ" (e.g. "pcs", "แผ่น")
    -- The free-text sub-row the business writes under a line on the paper form (the owner's example
    -- reads "สั่งตามPO"). Optional. NOTE: a line WITH a note occupies TWO printed rows, which is why
    -- ImportRequestRenderer counts capacity in rows, not lines.
    note                   VARCHAR(255),

    CONSTRAINT chk_import_request_item_qty_positive CHECK (qty > 0),
    CONSTRAINT chk_import_request_item_seq_positive CHECK (seq >= 1)
);

CREATE INDEX ix_import_request_item_parent
    ON sales.import_request_item(import_request_id);

-- ─────────────────────────────────────────────────────────────────────────────────────────────
-- 4. ticket_event may now point at an import request
-- ─────────────────────────────────────────────────────────────────────────────────────────────
-- V58 CHECK-constrains related_document_type to a fixed list (there is no FK -- the target lives in
-- one of several tables). IMPORT_REQUEST joins it so the existing IR_ISSUED event can finally name
-- the document it produced instead of standing alone. Re-declared in full rather than patched,
-- matching how V78 last re-declared chk_event_kind.
--
-- RelatedDocumentType.java gains a matching IMPORT_REQUEST constant; its Javadoc currently says the
-- constant is absent "because an import request has no row of its own", which stops being true here.
ALTER TABLE sales.ticket_event DROP CONSTRAINT IF EXISTS chk_event_related_document;
ALTER TABLE sales.ticket_event ADD CONSTRAINT chk_event_related_document CHECK (
    (related_document_type IS NULL AND related_document_id IS NULL)
    OR (related_document_type IS NOT NULL AND related_document_id IS NOT NULL
        AND related_document_type IN (
            'QUOTATION','DEPOSIT_NOTICE','PAYMENT_RECEIPT','DELIVERY_RECORD','IMPORT_REQUEST'
        ))
);

-- ─────────────────────────────────────────────────────────────────────────────────────────────
-- WHAT STILL HAS TO BE BUILT before this can merge
-- ─────────────────────────────────────────────────────────────────────────────────────────────
--   * ImportRequestService write paths: createDrafts (one per brand), updateDraft, updateFooter,
--     issue (mint or accept an override), supersede, deleteDraft. None exist.
--   * A controller for them, and hrApi/mockApi mirroring (contract.test.js + serverContract.test.js
--     + docs/api/api-surface.json + docs/api/ui-reachable.json all police this — see PR #816).
--   * Real-DB authz tests, wrong-way-round, per CLAUDE.md: these are WRITES, unlike the read-only
--     endpoints already shipped.
--   * A decision on whether ticket-level fulfillment_status becomes a rollup across a deal's IRs.
--     That is what makes per-brand IRs able to sit at DIFFERENT steps, and it is a behaviour change
--     to the stage-12 state machine, not a schema question.
--   * Re-verify this migration number against all three sources listed at the top.
