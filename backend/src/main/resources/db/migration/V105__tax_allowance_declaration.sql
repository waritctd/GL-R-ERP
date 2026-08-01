SET search_path = hr, public;

-- ---------------------------------------------------------------------
-- ค่าลดหย่อน self-declaration staging table (PR A of the tax-allowance feature).
--
-- THE PROBLEM: GET /api/payroll/tax-allowances?year=2026 returns ZERO declarations, verified live.
-- There is no UI, so nobody declares, so every employee's withholding is computed on the bare
-- statutory floor (personal ฿60,000 + SSO). Anyone with a spouse, children, RMF/SSF, insurance or a
-- home loan is over-withheld.
--
-- THE GOVERNING CONSTRAINT: PayrollRepository#findTaxAllowancesByEmployee filters
-- verification_status <> 'EXPIRED_UNVERIFIED', and that column DEFAULTs to
-- 'GRANDFATHERED_UNVERIFIED' -- so ANY insert into hr.employee_tax_allowance changes tax on the very
-- next preview. The owner requires declarations NOT to affect payroll until HR explicitly applies
-- them, per employee. Therefore declarations live here, in a brand-new table that PayrollCalculator
-- and PayrollRepository#findTaxAllowancesByEmployee never read. Only the per-employee Apply action
-- (PayrollService/TaxAllowanceDeclarationService#apply, this PR's service layer) writes into
-- hr.employee_tax_allowance, reusing three dormant V95 methods (upsertTaxAllowances,
-- markTaxAllowanceVerified, setTaxAllowanceVerificationDeadline) that have had zero callers since
-- V95 landed. PayrollCalculator.java and the SQL in #findTaxAllowancesByEmployee are UNTOUCHED by
-- this migration and by every line of Java this PR adds -- see PayrollService's own git history / PR
-- body for the zero-diff proof.
--
-- Workflow: employee submits (PENDING, payroll unaffected) -> HR approves (APPROVED, payroll STILL
-- unaffected -- applied_at is a FLAG, not a status: approved-but-unapplied and approved-and-applied
-- are both APPROVED, distinguished by applied_at IS NULL) -> HR applies per employee (writes
-- hr.employee_tax_allowance, VERIFIED, payroll now uses it from the applied effective month onward).
-- A later PR adds the scheduled expiry job that flips VERIFIED rows to EXPIRED_UNVERIFIED past
-- verification_deadline -- the query already excludes that status, so no payroll-side change will be
-- needed when that job lands.
--
-- Surrogate PRIMARY KEY (declaration_id BIGINT GENERATED ALWAYS AS IDENTITY), not a natural key on
-- (employee_id, tax_year, ...): hr.file_attachment.owner_id is a single BIGINT and a later PR's
-- evidence upload needs one scalar id to point at. hr.file_attachment already carries a generic
-- idx_file_attachment_domain_owner(domain, owner_id) index (V33), so no new index is needed on this
-- table for that -- a future evidence PR just needs domain = 'tax-allowance-evidence' and
-- owner_id = declaration_id, both already efficient.
-- ---------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS hr.tax_allowance_declaration (
    declaration_id                     BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    employee_id                        BIGINT NOT NULL REFERENCES hr.employee(employee_id) ON DELETE CASCADE,
    tax_year                           SMALLINT NOT NULL,
    -- ล.ย.01 ข้อ 2.2 effective dating, same meaning as hr.employee_tax_allowance.effective_month
    -- (V93): the declaration applies from this payroll month onward, once applied.
    effective_month                    SMALLINT NOT NULL DEFAULT 1,

    -- The same 16 declarable amount columns as hr.employee_tax_allowance (V73/V93/V99), so promotion
    -- at Apply time is a straight column copy -- see chk_tad_non_negative below, mirroring
    -- chk_eta_non_negative exactly.
    spouse_allowance                   NUMERIC(12,2) NOT NULL DEFAULT 0,
    child_allowance                    NUMERIC(12,2) NOT NULL DEFAULT 0,
    parent_care_allowance              NUMERIC(12,2) NOT NULL DEFAULT 0,
    disabled_care_allowance            NUMERIC(12,2) NOT NULL DEFAULT 0,
    maternity_allowance                NUMERIC(12,2) NOT NULL DEFAULT 0,
    life_insurance_allowance           NUMERIC(12,2) NOT NULL DEFAULT 0,
    health_insurance_allowance         NUMERIC(12,2) NOT NULL DEFAULT 0,
    parent_health_insurance_allowance  NUMERIC(12,2) NOT NULL DEFAULT 0,
    rmf_allowance                      NUMERIC(12,2) NOT NULL DEFAULT 0,
    ssf_allowance                      NUMERIC(12,2) NOT NULL DEFAULT 0,
    pension_insurance_allowance        NUMERIC(12,2) NOT NULL DEFAULT 0,
    thai_esg_allowance                 NUMERIC(12,2) NOT NULL DEFAULT 0,
    home_loan_interest_allowance       NUMERIC(12,2) NOT NULL DEFAULT 0,
    education_donation                 NUMERIC(12,2) NOT NULL DEFAULT 0,
    general_donation                   NUMERIC(12,2) NOT NULL DEFAULT 0,
    political_donation                 NUMERIC(12,2) NOT NULL DEFAULT 0,

    -- Per-head counts backing the capped allowances (V93/V95), mirrored exactly for the same reason.
    child_count                        SMALLINT NOT NULL DEFAULT 0,
    child_count_double                 SMALLINT NOT NULL DEFAULT 0,
    disabled_care_count                SMALLINT NOT NULL DEFAULT 0,
    disability_card_holder             BOOLEAN NOT NULL DEFAULT FALSE,
    parent_care_count                  SMALLINT NOT NULL DEFAULT 0,

    document_reference                 TEXT,

    -- ---- Workflow (new to this table; hr.employee_tax_allowance has no equivalent) ----
    status                              TEXT NOT NULL DEFAULT 'PENDING',
    -- Who actually submitted the row: the declaring employee for a self-service submission, or the
    -- HR user for an on-behalf creation (on_behalf = TRUE distinguishes the two; employee_id above is
    -- always the BENEFICIARY of the declaration, never the submitter, for an on-behalf row).
    submitted_by_id                     BIGINT REFERENCES hr.employee(employee_id) ON DELETE SET NULL,
    submitted_at                        TIMESTAMPTZ NOT NULL DEFAULT now(),
    on_behalf                           BOOLEAN NOT NULL DEFAULT FALSE,
    reviewed_by_id                      BIGINT REFERENCES hr.employee(employee_id) ON DELETE SET NULL,
    reviewed_at                         TIMESTAMPTZ NULL,
    reviewer_note                       TEXT NULL,
    -- applied_at is a FLAG, not a status (see header comment) -- deliberately NOT folded into `status`
    -- so "approved, not yet applied" (the whole point of decision #2's rollout safety) is representable.
    applied_at                          TIMESTAMPTZ NULL,
    applied_by_id                       BIGINT REFERENCES hr.employee(employee_id) ON DELETE SET NULL,
    applied_effective_month             SMALLINT NULL,
    -- Declaration-level expiry (owner decision #10: declarations expire and stop reducing tax until
    -- re-verified). Column only, in this PR -- the scheduled job that populates/acts on expires_on
    -- and flips status to EXPIRED is a later PR. Deliberately distinct from
    -- hr.employee_tax_allowance.verification_deadline (V95), which THIS PR's Apply action DOES set
    -- (via the dormant setTaxAllowanceVerificationDeadline) -- that column drives the ALREADY-LIVE
    -- expireTaxAllowanceVerification machinery on the parent table; expires_on/expired_at below drive
    -- a not-yet-built job on THIS table.
    expires_on                          DATE NULL,
    expired_at                          TIMESTAMPTZ NULL,
    reverified_at                       TIMESTAMPTZ NULL,
    reverified_by_id                    BIGINT REFERENCES hr.employee(employee_id) ON DELETE SET NULL,
    -- Set when a LATER declaration for the same (employee_id, tax_year) is approved, per decision #7
    -- ("a new submission supersedes the previous once approved. UI shows current only"). References
    -- this same table (the newer row), so history survives instead of being overwritten.
    superseded_by_id                    BIGINT REFERENCES hr.tax_allowance_declaration(declaration_id) ON DELETE SET NULL,

    -- Mirrors chk_eta_non_negative (V73) exactly -- a declaration must never hold a shape the parent
    -- table would reject at promotion time; discovering that at Apply, months after the employee
    -- typed it, would be exactly the kind of silent-until-too-late failure this table exists to avoid.
    CONSTRAINT chk_tad_non_negative CHECK (
        spouse_allowance >= 0 AND child_allowance >= 0 AND parent_care_allowance >= 0 AND
        disabled_care_allowance >= 0 AND maternity_allowance >= 0 AND life_insurance_allowance >= 0 AND
        health_insurance_allowance >= 0 AND parent_health_insurance_allowance >= 0 AND rmf_allowance >= 0 AND
        ssf_allowance >= 0 AND pension_insurance_allowance >= 0 AND thai_esg_allowance >= 0 AND
        home_loan_interest_allowance >= 0 AND education_donation >= 0 AND general_donation >= 0 AND
        political_donation >= 0
    ),
    -- Mirrors chk_eta_counts_non_negative (V95/V99, post-PVD-removal) exactly, including the
    -- child_count_double <= child_count invariant.
    CONSTRAINT chk_tad_counts_non_negative CHECK (
        child_count >= 0 AND child_count_double >= 0 AND disabled_care_count >= 0
        AND child_count_double <= child_count
    ),
    -- Mirrors chk_eta_parent_care_count_valid (V95) exactly: 4 qualifying parents is the statutory
    -- maximum (two biological + two parents-in-law).
    CONSTRAINT chk_tad_parent_care_count_valid CHECK (parent_care_count BETWEEN 0 AND 4),
    CONSTRAINT chk_tad_effective_month CHECK (effective_month BETWEEN 1 AND 12),
    CONSTRAINT chk_tad_applied_effective_month_valid CHECK (
        applied_effective_month IS NULL OR applied_effective_month BETWEEN 1 AND 12
    ),
    CONSTRAINT chk_tad_status_valid CHECK (
        status IN ('PENDING', 'APPROVED', 'REJECTED', 'SUPERSEDED', 'EXPIRED', 'WITHDRAWN')
    ),
    -- Decision #6: approval is whole-declaration (approve-all / reject-all + reason). A REJECTED row
    -- with no reason recorded would leave the employee with no idea what to fix.
    CONSTRAINT chk_tad_rejected_has_reason CHECK (
        status <> 'REJECTED' OR reviewer_note IS NOT NULL
    ),
    -- The three apply columns move together: either none of them are set (never applied) or all
    -- three are (applied). Prevents a half-applied row -- e.g. applied_at set but
    -- applied_effective_month NULL, which would make PayrollService's PROCESSED-month guard
    -- unanswerable for that row.
    CONSTRAINT chk_tad_applied_consistent CHECK (
        (applied_at IS NULL AND applied_by_id IS NULL AND applied_effective_month IS NULL)
        OR (applied_at IS NOT NULL AND applied_by_id IS NOT NULL AND applied_effective_month IS NOT NULL)
    ),
    CONSTRAINT chk_tad_expired_consistent CHECK (
        (status = 'EXPIRED') = (expired_at IS NOT NULL)
    )
);

-- Two partial unique indexes that turn "forgot to supersede" into a hard error rather than a silent
-- double-apply. Neither is deferrable, so approving a newer declaration MUST set the previous
-- APPROVED row to SUPERSEDED first, in the same transaction (TaxAllowanceDeclarationService#approve).
CREATE UNIQUE INDEX IF NOT EXISTS uq_tad_one_pending_per_employee_year
    ON hr.tax_allowance_declaration (employee_id, tax_year) WHERE status = 'PENDING';
CREATE UNIQUE INDEX IF NOT EXISTS uq_tad_one_approved_per_employee_year
    ON hr.tax_allowance_declaration (employee_id, tax_year) WHERE status = 'APPROVED';

CREATE INDEX IF NOT EXISTS idx_tad_employee_year_submitted
    ON hr.tax_allowance_declaration (employee_id, tax_year, submitted_at DESC);
CREATE INDEX IF NOT EXISTS idx_tad_status_year
    ON hr.tax_allowance_declaration (status, tax_year);
-- Expiry-sweep index for the later PR's scheduled job: only an APPLIED (applied_at IS NOT NULL),
-- still-APPROVED declaration can ever expire.
CREATE INDEX IF NOT EXISTS idx_tad_expiry_sweep
    ON hr.tax_allowance_declaration (expires_on) WHERE status = 'APPROVED' AND applied_at IS NOT NULL;

COMMENT ON TABLE hr.tax_allowance_declaration IS
  'Employee self-declared ค่าลดหย่อน (แบบ ล.ย.01), staged separately from hr.employee_tax_allowance so a declaration never affects payroll until HR explicitly applies it per employee (TaxAllowanceDeclarationService#apply). See this migration''s header comment for the full workflow and the reason for the staging table.';
COMMENT ON COLUMN hr.tax_allowance_declaration.applied_at IS
  'A FLAG, not a status. Approved-but-unapplied and approved-and-applied are both status=APPROVED; applied_at IS NULL distinguishes them. This is decision #2''s rollout safety -- do not fold this into `status`.';
COMMENT ON COLUMN hr.tax_allowance_declaration.expires_on IS
  'Declaration-level expiry (owner decision #10). Column only in this PR; the scheduled job that populates and acts on it is a later PR. Distinct from hr.employee_tax_allowance.verification_deadline, which Apply already sets via the pre-existing V95 verification machinery.';
