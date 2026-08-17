-- CEO discount-approval workflow, Phase 2 (owner ruling 2026-08-16). Phase 1 (#805, which made
-- sales.pricing_decision_item.minimum_selling_price_per_requested_unit auto-populate as the
-- approved selling price) and the V109 engine swap (#811) are already merged.
--
-- Replaces CustomerQuotationService's hard 422 refusal of ANY below-CEO-minimum discount with an
-- approval gate: Sales may SAVE a discounted line; the quotation may not be ISSUED until every
-- discounted line is approved by the CEO. Approval is PER LINE (owner's explicit choice over
-- per-quotation) and, critically, bound to the EXACT price it was granted for — see the table
-- comment and CustomerQuotationService/DiscountApprovalRepository for the full mechanism.
--
-- MIGRATION NUMBERING: this is V155. V153 (catalog-priceable-basis) and V154
-- (import_request_document, on the not-yet-merged wip/import-request-stored-aggregate branch —
-- a concurrent session's work) are taken. Re-verified V155 free immediately before writing this
-- file via `git ls-tree -r --name-only <ref> -- backend/src/main/resources/db/migration` over
-- every local AND remote branch (`git for-each-ref refs/heads refs/remotes`), plus a `find` for
-- V155*/V156* across every on-disk worktree's migration directory (tracked and untracked). Nothing
-- anywhere used V155 at the time of writing. Re-verify again before merging if time has passed —
-- this repo has had two migration-version collisions in the same week.

-- Append-only history: a row is never mutated after it is decided (APPROVED/REJECTED), and a
-- price change after a decision always INSERTS a new PENDING row rather than reopening the old
-- one. This is what makes "bound to the exact price it was granted for" true by construction —
-- CustomerQuotationService's issue() gate only ever asks "does an APPROVED row exist whose
-- approved_final_unit_price equals THIS item's CURRENT final_unit_price", never "was this item
-- ever approved at some point".
CREATE TABLE sales.quotation_item_discount_approval (
    discount_approval_id       BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    -- Bare REFERENCES (no ON DELETE action -> RESTRICT), deliberately NOT "ON DELETE CASCADE"
    -- like most child rows of sales.quotation_item: an approval decision is an audit record and
    -- must outlive the line it was granted against if that line is ever removed by some future
    -- feature. sales.quotation_item rows are never deleted today (only doc_status changes), so
    -- this is defensive rather than load-bearing right now.
    quotation_item_id          BIGINT NOT NULL REFERENCES sales.quotation_item(quotation_item_id),
    status                      VARCHAR(20) NOT NULL DEFAULT 'PENDING'
                                    CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    -- The price Sales is asking to sell this line at, per requested unit — same basis and scale
    -- as sales.quotation_item.final_unit_price. Fixed forever once the row exists; approve()/
    -- reject() never touch this column, only status/decided_*/approved_final_unit_price/
    -- rejection_reason.
    requested_final_unit_price NUMERIC(18,4) NOT NULL CHECK (requested_final_unit_price >= 0),
    -- Bare REFERENCES here too, and deliberately NOT "ON DELETE SET NULL" the way
    -- sales.pricing_decision.approved_by is (V72) — SET NULL on either of these columns would
    -- violate chk_discount_approval_decided_fields below the moment it fired (APPROVED/REJECTED
    -- both require decided_by IS NOT NULL), aborting the employee delete anyway. Stating the
    -- action explicitly as RESTRICT avoids relying on that CHECK interaction and says outright:
    -- an employee who requested or decided a discount cannot be hard-deleted while the record
    -- exists. hr.employee rows are soft-deleted (is_active) everywhere else in this codebase, so
    -- this is defence in depth, not a behaviour anything currently exercises.
    requested_by                BIGINT NOT NULL REFERENCES hr.employee(employee_id),
    requested_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
    decided_by                  BIGINT REFERENCES hr.employee(employee_id),
    decided_at                  TIMESTAMPTZ,
    -- THE critical invariant column (owner's explicit instruction): set ONLY on APPROVED, and
    -- always equal to requested_final_unit_price at the moment of approval — a separate column
    -- rather than merely "requested_final_unit_price WHERE status = APPROVED" so the issue-gate
    -- comparison reads as "was THIS price approved" on its own terms, and stays correct even if a
    -- future change ever let an approval carry a counter-offer different from what was asked.
    approved_final_unit_price   NUMERIC(18,4) CHECK (approved_final_unit_price >= 0),
    -- Mandatory on REJECTED (see the CHECK below) — the CEO's reason, shown back to Sales.
    rejection_reason            TEXT,
    CONSTRAINT chk_discount_approval_decided_fields CHECK (
        (status = 'PENDING'
            AND decided_by IS NULL AND decided_at IS NULL
            AND approved_final_unit_price IS NULL AND rejection_reason IS NULL)
        OR (status = 'APPROVED'
            AND decided_by IS NOT NULL AND decided_at IS NOT NULL
            AND approved_final_unit_price IS NOT NULL AND rejection_reason IS NULL)
        OR (status = 'REJECTED'
            AND decided_by IS NOT NULL AND decided_at IS NOT NULL
            AND approved_final_unit_price IS NULL
            AND rejection_reason IS NOT NULL AND btrim(rejection_reason) <> '')
    )
);

CREATE INDEX idx_discount_approval_quotation_item ON sales.quotation_item_discount_approval(quotation_item_id);

-- Application-level race guard (DiscountApprovalRepository#ensureRequested uses this as its ON
-- CONFLICT target): at most one PENDING ask per (item, exact price) at a time, so two
-- near-simultaneous saves of the same discounted price cannot create duplicate CEO requests.
-- Does NOT prevent a fresh PENDING row at the SAME price after that row is later
-- APPROVED/REJECTED (a genuine re-request) — the predicate is scoped to status = 'PENDING' only.
CREATE UNIQUE INDEX uq_discount_approval_pending_item_price
    ON sales.quotation_item_discount_approval(quotation_item_id, requested_final_unit_price)
    WHERE status = 'PENDING';

COMMENT ON TABLE sales.quotation_item_discount_approval IS
    'CEO discount-approval workflow (Phase 2, owner ruling 2026-08-16), per LINE not per quotation. Append-only history: a price change after a decision always inserts a NEW row rather than mutating the old one, so an approval stays bound to the EXACT price it was granted for. CustomerQuotationService.issue() only accepts a line whose current final_unit_price has a matching APPROVED row (approved_final_unit_price = the CURRENT price) — approving one price never carries over to a different one. See CustomerQuotationService/DiscountApprovalRepository/DiscountApprovalService for the full state machine.';
