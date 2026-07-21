-- Step 3 of the sales pricing-flow redesign: CEO Selling Price Decision.
--
-- MIGRATION NUMBERING: this is V72. Re-checked live via `git worktree list --porcelain` plus
-- listing every worktree's own backend/src/main/resources/db/migration directory immediately
-- before writing this file:
--   - This branch's own tree tops out at V71 (V71__pricing_request_revision_chain_uniqueness.sql,
--     Step 2, already committed at d17bcc0).
--   - .claude/worktrees/ot-retroactive (feat/ot-remove-advance-notice) has an untracked
--     V70__overtime_salary_basis_snapshot.sql.
--   - .claude/worktrees/payroll-recon (feat/payroll-reconciliation) has an untracked
--     V71__payroll_reconciliation_inputs.sql (a DIFFERENT V71 than this branch's own).
--   - .claude/worktrees/special-money (feat/special-money-requests) has an untracked
--     V66__special_money_request_schema.sql.
--   - No other open worktree (GL-R-ERP-employees, GL-R-ERP-main, flyway-audit,
--     nav-menu-grouping, profile-avatar-menu) has any migration file above V59.
-- V72 is free everywhere checked. As with every prior migration on this chain, the true
-- production-numbering conflict is tracked separately in
-- docs/flyway-version-collision-audit / docs/agent-handoffs/88_feat-sales-factory-quote-costing.md
-- §8. That production V55 divergence is now RESOLVED on this line: "quotation doc terms" was
-- adopted byte-identical as V55 and this chain's V55-V59 shifted to V56-V60 (see V74's header).
--
-- Re-verified again at the end of this task's session (same command, all worktrees re-listed):
-- payroll-recon's own migration had moved on to V73 in the meantime (a different agent's
-- concurrent progress) and special-money's V66 file was unchanged — neither collides with V72,
-- which remained free throughout. This confirms the numbers in worktrees other than this one are
-- live and can move between checks; re-verify again before merging if time has passed.

-- ─────────────────────────────────────────────────────────────────────────
-- New pricing_request statuses (design corrections 3+4): CEO_REVIEWING,
-- APPROVED_FOR_QUOTATION, COSTING_REVISION_REQUIRED. See PricingRequestStatus.java for the full
-- state-machine change (the READY_FOR_CEO_REVIEW -> COSTING_IN_PROGRESS direct reopen edge is
-- removed as part of the same change; the CHECK constraint below only governs valid values, not
-- transitions -- PricingRequestStatus.ALLOWED/canTransition is the transition authority).
ALTER TABLE sales.pricing_request
    DROP CONSTRAINT chk_pricing_request_status;
ALTER TABLE sales.pricing_request
    ADD CONSTRAINT chk_pricing_request_status CHECK (status IN (
        'DRAFT', 'SUBMITTED', 'IMPORT_REVIEWING', 'AWAITING_FACTORY_RESPONSE', 'COSTING_IN_PROGRESS',
        'READY_FOR_CEO_REVIEW', 'CEO_REVIEWING', 'APPROVED_FOR_QUOTATION', 'COSTING_REVISION_REQUIRED',
        'MORE_INFO_REQUIRED', 'CANCELLED', 'SUPERSEDED'
    ));

CREATE SEQUENCE sales.pricing_decision_code_seq START 1;

-- One CEO decision against exactly one SUBMITTED costing version (design correction: "every
-- decision references exactly one submitted costing version"). Versioned like pricing_costing:
-- a RETURNED decision stays readable as history; a fresh decision_version_no is minted each time
-- the CEO starts review again after a return-and-resubmit round trip.
CREATE TABLE sales.pricing_decision (
    pricing_decision_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    decision_code VARCHAR(40) NOT NULL UNIQUE,
    pricing_request_id BIGINT NOT NULL REFERENCES sales.pricing_request(pricing_request_id) ON DELETE CASCADE,
    pricing_costing_id BIGINT NOT NULL REFERENCES sales.pricing_costing(pricing_costing_id) ON DELETE RESTRICT,
    decision_version_no INTEGER NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    default_margin_pct NUMERIC(9,6),
    -- Selling-price currency and its pinned FX snapshot (design correction 6, "pin the FX"):
    -- landed cost is always THB; if the decision currency is not THB, the rate used to convert
    -- cost -> selling price is recorded here and reused for every recalculation of this decision
    -- version, never re-resolved from a possibly-since-changed sales.fx_rates row.
    currency VARCHAR(10) NOT NULL DEFAULT 'THB',
    fx_rate_used NUMERIC(18,6) NOT NULL DEFAULT 1,
    fx_source VARCHAR(30) NOT NULL DEFAULT 'THB',
    fx_effective_date DATE NOT NULL DEFAULT CURRENT_DATE,
    ceo_note TEXT,
    return_reason TEXT,
    client_request_id UUID,
    approve_client_request_id UUID,
    created_by BIGINT REFERENCES hr.employee(employee_id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    approved_by BIGINT REFERENCES hr.employee(employee_id) ON DELETE SET NULL,
    approved_at TIMESTAMPTZ,
    returned_at TIMESTAMPTZ,
    CONSTRAINT chk_pricing_decision_status CHECK (status IN ('DRAFT', 'APPROVED', 'RETURNED')),
    CONSTRAINT chk_pricing_decision_version CHECK (decision_version_no >= 1),
    CONSTRAINT uq_pricing_decision_version UNIQUE (pricing_request_id, decision_version_no)
);

-- Only one open (DRAFT, i.e. actively-being-reviewed) decision per pricing request at a time.
CREATE UNIQUE INDEX uq_pricing_decision_open_draft
    ON sales.pricing_decision (pricing_request_id)
    WHERE status = 'DRAFT';

-- Design correction 8 ("approval is idempotent and concurrency-safe"): the database-level half
-- of the guarantee -- at most one APPROVED decision can ever exist for a pricing request. The
-- application half is PricingDecisionRepository.approve()'s compare-and-set UPDATE plus a
-- pg_advisory_xact_lock(pricing_request_id) held for the duration of the approve() call; this
-- index is the backstop that makes the invariant true even if that lock were ever bypassed.
CREATE UNIQUE INDEX uq_pricing_decision_one_approved
    ON sales.pricing_decision (pricing_request_id)
    WHERE status = 'APPROVED';

CREATE UNIQUE INDEX uq_pricing_decision_creator_client_request
    ON sales.pricing_decision (created_by, client_request_id)
    WHERE client_request_id IS NOT NULL;

CREATE UNIQUE INDEX uq_pricing_decision_approver_client_request
    ON sales.pricing_decision (approved_by, approve_client_request_id)
    WHERE approve_client_request_id IS NOT NULL;

-- One row per pricing_request_item this decision covers, 1:1 with the SUBMITTED costing's own
-- pricing_costing_item rows (design correction: "every decision references exactly one submitted
-- costing version" -- enforced at the application layer by always sourcing items from
-- pricing_decision.pricing_costing_id's own item set).
CREATE TABLE sales.pricing_decision_item (
    pricing_decision_item_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    pricing_decision_id BIGINT NOT NULL REFERENCES sales.pricing_decision(pricing_decision_id) ON DELETE CASCADE,
    pricing_request_item_id BIGINT NOT NULL REFERENCES sales.pricing_request_item(pricing_request_item_id) ON DELETE RESTRICT,
    pricing_costing_item_id BIGINT NOT NULL REFERENCES sales.pricing_costing_item(pricing_costing_item_id) ON DELETE RESTRICT,
    -- ── Unit basis (design correction 1 -- the highest-risk item on this task) ──────────────
    -- Costing prices per PHYSICAL PIECE (pricing_costing_item.landed_cost_per_unit_thb); the
    -- customer is quoted per REQUESTED unit (ตร.ม./แผ่น/กล่อง/เมตร). Every column below states,
    -- in its own name, which basis it is expressed in -- nothing here is ambiguous THB-per-what.
    requested_unit_basis VARCHAR(30) NOT NULL,
    requested_quantity NUMERIC(18,4) NOT NULL,
    normalized_quantity_pieces NUMERIC(18,6) NOT NULL,
    -- Frozen at decision-item creation time, copied verbatim from the SUBMITTED costing item --
    -- never recomputed, per "never leave a submitted costing mutable" (design correction 3).
    frozen_landed_cost_per_piece_thb NUMERIC(18,4) NOT NULL,
    -- Derived once at creation as (costing item's total_landed_cost_thb / requested_quantity) --
    -- i.e. the SAME total cost re-expressed per requested unit instead of per piece, not a
    -- second independent conversion-factor computation. This is what makes a per-box request and
    -- a per-piece request at the same physical quantity land on the same total by construction.
    frozen_landed_cost_per_requested_unit_thb NUMERIC(18,4) NOT NULL,
    -- Selling-price currency, always equal to the parent decision's currency (enforced in
    -- PricingDecisionService, not by a cross-table CHECK -- Postgres cannot express that here).
    currency VARCHAR(10) NOT NULL,
    proposed_margin_pct NUMERIC(9,6),
    approved_margin_pct NUMERIC(9,6),
    -- Both selling-price columns are PER REQUESTED UNIT, in `currency` -- the customer-facing
    -- number. Never trust a client-supplied value into either column: both are always
    -- server-recomputed from frozen_landed_cost_per_requested_unit_thb * (1 + margin), converted
    -- through the parent decision's pinned fx_rate_used (design correction 7).
    proposed_selling_price_per_requested_unit NUMERIC(18,4),
    approved_selling_price_per_requested_unit NUMERIC(18,4),
    discount_ceiling_pct NUMERIC(9,6),
    minimum_selling_price_per_requested_unit NUMERIC(18,4),
    decision_note TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_pricing_decision_item_request_item UNIQUE (pricing_decision_id, pricing_request_item_id),
    CONSTRAINT chk_pricing_decision_item_unit_basis CHECK (
        requested_unit_basis IN ('PER_SQM', 'PER_PIECE', 'PER_BOX', 'PER_LINEAR_M')
    ),
    CONSTRAINT chk_pricing_decision_item_margins CHECK (
        (proposed_margin_pct IS NULL OR proposed_margin_pct > -1) AND
        (approved_margin_pct IS NULL OR approved_margin_pct > -1)
    ),
    CONSTRAINT chk_pricing_decision_item_prices_nonnegative CHECK (
        (proposed_selling_price_per_requested_unit IS NULL OR proposed_selling_price_per_requested_unit >= 0) AND
        (approved_selling_price_per_requested_unit IS NULL OR approved_selling_price_per_requested_unit >= 0) AND
        (minimum_selling_price_per_requested_unit IS NULL OR minimum_selling_price_per_requested_unit >= 0)
    )
);

CREATE INDEX idx_pricing_decision_request ON sales.pricing_decision(pricing_request_id, decision_version_no);
CREATE INDEX idx_pricing_decision_item_decision ON sales.pricing_decision_item(pricing_decision_id);
