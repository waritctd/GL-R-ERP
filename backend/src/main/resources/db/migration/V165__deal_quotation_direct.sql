-- Quotation v2 -- direct deal quotation (bypass the pricing chain).
--
-- Owner ruling (Ploy, 2026-09-09): for this release the pricing-request -> factory-quote ->
-- CEO-costing chain is BYPASSED. Sales creates a deal, adds items straight onto a quotation,
-- types unit price + discount, the system does the arithmetic, ผึ้ง (sales_manager) or ราม (ceo)
-- approves, and the approved PDF is emailed to the rep and the creator. See
-- docs/sales/quotation-v2-plan.md (repo root) for the full spec this migration implements ("Data" section).
--
-- MIGRATION NUMBERING: origin/develop tops out at V164 (PR #899, leave rule warn-unpaid), so V165
-- is the next free number ABOVE develop's max -- a gap would let a later, lower-numbered migration
-- be silently skipped on the prod deploy (validate-on-migrate is false there; see CLAUDE.md).
-- PR #895 (thickness) still carries its own V164/V165 and must renumber upward before it merges;
-- a comment on that PR says so.
--
-- Owner extends the EXISTING sales.quotation / sales.quotation_item aggregate (same precedent as
-- V74's Step 4 chain, and the same renderer -- QuotationRenderer is reused unmodified via
-- th.co.glr.hr.dealquotation.DealQuotationRenderAdapter, a separate seam so a later renderer
-- redesign slice touches one class, not this one). A new `origin = 'DEAL_DIRECT'` column tags
-- every row this feature writes; every read in th.co.glr.hr.dealquotation.DealQuotationRepository
-- filters on it, exactly as CustomerQuotationRepository already filters
-- `pricing_request_id IS NOT NULL` -- the two features' rows never see each other. The legacy
-- ticket-level quotation list (TicketRepository.findQuotationsByTicketId, rendered by
-- DealLegacyQuotations.jsx) is widened in the same PR to exclude `origin = 'DEAL_DIRECT'` so a
-- direct quotation does not appear twice.
--
-- Why `deposit_percent SMALLINT` is a NEW column rather than reusing `deposit_pct NUMERIC(5,4)`:
-- that column (V27, kept alive by V55's `ADD COLUMN IF NOT EXISTS` no-op) stores a FRACTION --
-- 0.3000 for 30% -- so it can represent 50% (0.5000) fine as a fraction, but this feature's own
-- input is a plain integer percentage (30 / 50 / any other whole number sales types in), and nothing
-- here needs the legacy fractional representation or its 4-decimal scale. A fresh, honestly-named
-- SMALLINT sidesteps the ambiguity rather than overloading a column two different features would
-- then read two different ways.

-- ── sales.quotation: direct-deal-quotation columns (all nullable unless stated; additive) ──
ALTER TABLE sales.quotation
    ADD COLUMN origin                 VARCHAR(16),
    ADD COLUMN created_by             BIGINT REFERENCES hr.employee(employee_id),
    ADD COLUMN sales_rep_id           BIGINT REFERENCES hr.employee(employee_id),
    ADD COLUMN submitted_at           TIMESTAMPTZ,
    ADD COLUMN submitted_by           BIGINT REFERENCES hr.employee(employee_id),
    ADD COLUMN approved_at            TIMESTAMPTZ,
    ADD COLUMN approved_by            BIGINT REFERENCES hr.employee(employee_id),
    -- Last decision (approve OR reject) -- on REJECT the row goes back to DRAFT and
    -- approval_note is the reason shown to the rep; DealQuotationService clears it on the next
    -- submit so a stale rejection reason never lingers on a resubmitted draft.
    ADD COLUMN approval_decided_at    TIMESTAMPTZ,
    ADD COLUMN approval_decided_by    BIGINT REFERENCES hr.employee(employee_id),
    ADD COLUMN approval_note          TEXT,
    ADD COLUMN dept_code              VARCHAR(20),
    ADD COLUMN unit_code              VARCHAR(20),
    ADD COLUMN deposit_percent        SMALLINT,
    ADD COLUMN remainder_mode         VARCHAR(20),
    ADD COLUMN credit_days            SMALLINT,
    ADD COLUMN validity_days          SMALLINT,
    ADD COLUMN updated_at             TIMESTAMPTZ;

CREATE INDEX idx_quotation_origin ON sales.quotation(origin) WHERE origin IS NOT NULL;
CREATE INDEX idx_quotation_deal_direct_ticket ON sales.quotation(ticket_id)
    WHERE origin = 'DEAL_DIRECT';
CREATE INDEX idx_quotation_deal_direct_status ON sales.quotation(doc_status)
    WHERE origin = 'DEAL_DIRECT';

-- Widen chk_quotation_doc_status (V52, re-declared V74) to add the two new statuses this
-- feature's status machine needs: PENDING_APPROVAL (submitted, awaiting sales_manager/ceo) and
-- APPROVED (terminal-until-revised). DROP + re-ADD, per this table's existing convention.
ALTER TABLE sales.quotation DROP CONSTRAINT IF EXISTS chk_quotation_doc_status;
ALTER TABLE sales.quotation ADD CONSTRAINT chk_quotation_doc_status CHECK (doc_status IN (
    'DRAFT','READY_TO_ISSUE','PENDING_APPROVAL','APPROVED','ISSUED','SENT','SUPERSEDED',
    'CANCELLED','EXPIRED','ACCEPTED','REJECTED','REVISION_REQUESTED'
));

-- ── sales.quotation_item: direct-deal-quotation columns ──────────────────────────────────
ALTER TABLE sales.quotation_item
    ADD COLUMN location_label         VARCHAR(255),
    ADD COLUMN catalog_price_id       BIGINT REFERENCES price_catalog.product_prices(price_id) ON DELETE SET NULL,
    ADD COLUMN product_code           TEXT,
    ADD COLUMN thickness_mm           NUMERIC(6,2),
    ADD COLUMN sqm_per_piece          NUMERIC(10,6),
    ADD COLUMN quantity_mode          VARCHAR(10),
    ADD COLUMN area_sqm               NUMERIC(12,2),
    ADD COLUMN pieces_input           INTEGER,
    ADD COLUMN wastage_mode           VARCHAR(10),
    ADD COLUMN wastage_value          NUMERIC(10,2),
    ADD COLUMN pieces_per_box         SMALLINT,
    ADD COLUMN pieces_before_wastage  INTEGER,
    ADD COLUMN pieces_after_wastage   INTEGER,
    ADD COLUMN boxes                  INTEGER,
    ADD COLUMN discount_pct           NUMERIC(5,2),
    ADD COLUMN origin_country         VARCHAR(40),
    ADD COLUMN lead_time_min_days     SMALLINT,
    ADD COLUMN lead_time_max_days     SMALLINT;

CREATE INDEX idx_quotation_item_catalog_price_id ON sales.quotation_item(catalog_price_id)
    WHERE catalog_price_id IS NOT NULL;

-- ── hr.employee_signature: the approver's signature image, anchored into the ผู้อนุมัติ box ──
CREATE TABLE hr.employee_signature (
    employee_id  BIGINT PRIMARY KEY REFERENCES hr.employee(employee_id),
    mime_type    VARCHAR(40) NOT NULL,
    image        BYTEA NOT NULL,
    uploaded_by  BIGINT REFERENCES hr.employee(employee_id),
    uploaded_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ── hr.employee.can_create_quotation: per-employee grant (owner ruling, Ploy 2026-09-09) ──
-- ภิญญดา (employee 144, role qc / QC&ISO) must be able to create deal quotations without being
-- given the `sales` or `sales_manager` role. This is a CAPABILITY, in the exact shape of the
-- existing `is_admin` capability (see EmployeeAuthRepository.isAdmin / AuthResponse.admin):
-- a plain boolean column, read live at decision time, never inferred from the session principal.
-- Defaults FALSE for everyone; this migration does NOT set it for anyone -- granting it to 144 is
-- a hand-applied data change on the target DB, not something a migration should hardcode.
ALTER TABLE hr.employee ADD COLUMN can_create_quotation BOOLEAN NOT NULL DEFAULT FALSE;
