-- ข้อ 15 commission documentation gate — recording pre-ERP paper approvals (owner, 2026-07-30).
--
-- MIGRATION NUMBERING: V102. `origin/main` holds V95-V101 (payroll classification/HR declarations
-- + customer-return-requested-amount, merged as 8ad180bf). Verified free in every worktree under
-- .claude/worktrees/ plus the sibling checkouts (GL-R-ERP-payroll-hr, GL-R-ERP-payroll-tax)
-- immediately before writing this file. Re-verify before merging if time has passed.
--
-- BACKGROUND (see this branch's PR body for the full
-- investigation): ข้อ 15 requires customer-receipt evidence on file before a commission is paid.
-- CommissionService#submit/#createFromDeal have always enforced this for every commission created
-- through the application (a tax-invoice attachment is mandatory before the row can even be
-- written). The one place that requirement did NOT apply is the 2026 historical commission import
-- (per team memory "2026-commission-historical-import", 2026-07-24): 1,100 sales.invoice_details
-- rows (1,083 SALE + 17 CLAWBACK + the ADJUSTMENT/MANAGER manual entries, which need no invoice at
-- all) were written directly via SQL to true up real prod to the accountant's reconciled totals,
-- for deals that predate this ERP. The owner's own words: "the invoice was before we built the
-- ERP, it's already all approved, so I would like to record it." These are genuine PAPER
-- approvals that happened before the system existed to scan and attach them — not missing
-- evidence, and not something to silently forfeit or silently keep gap-free by accident.
--
-- Production scope, verified read-only against real prod immediately before writing this
-- migration:
--
--   kind        | status   | records | with attachment | invoice, no attachment
--   ------------|----------|---------|------------------|------------------------
--   SALE        | APPROVED |  1083   |        0         |          1083
--   CLAWBACK    | APPROVED |    17   |        0         |            17
--   ADJUSTMENT  | APPROVED |    21   |        0         | 0 (no invoice — out of scope, V84)
--   MANAGER     | APPROVED |    11   |        0         | 0 (no invoice — out of scope, V84)
--
-- All 1,132 production commission records are APPROVED; none has an attachment; all span
-- 2025-12 through 2026-07 and are entirely pre-ERP. Manual kinds (ADJUSTMENT/MANAGER/STOCK_BONUS/
-- INCENTIVE) need nothing here — V84's chk_commission_manual_fields already enforces
-- invoice_id IS NULL for them, so they were never candidates for this column. The backfill below
-- therefore marks exactly the 1,100 SALE+CLAWBACK rows (the WHERE clause only matches rows with an
-- invoice_details row at all and neither evidence column set — the 32 manual-kind records have no
-- invoice_id and never reach an invoice_details row in the first place).
--
-- WHAT THIS MIGRATION DOES: records a second, explicit evidence route alongside the existing
-- file-attachment one -- it does NOT weaken or bypass ข้อ 15, and does NOT gate/hold payment
-- (retrofitting a hold-then-release mechanism was considered and rejected — see the handoff's
-- "CONCLUSION" section). Every invoice_details row must show ONE of: a scanned attachment on file
-- (invoice_attachment_id), OR a recorded provenance for why no attachment exists
-- (evidence_provenance, from a closed, named list — see the CHECK below). Going forward, every row
-- written through the application already has an attachment (unchanged), so evidence_provenance is
-- expected to stay NULL for all new rows unless a future, equally-explicit exception is added.
ALTER TABLE sales.invoice_details
    ADD COLUMN IF NOT EXISTS evidence_provenance VARCHAR(32);

-- Vocabulary (2026-07-30, extended after cross-checking against the `uat` branch's own fix,
-- `fix/uat-seed-invoice-evidence` @ 36925ae8): each value means a SPECIFIC, non-overlapping thing.
-- Add a new one only with the same rigor -- a real reason no attachment exists, distinct from
-- every existing value, documented here and in the column comment below.
--
--   PRE_ERP_PAPER_APPROVAL -- a REAL paper approval predating this ERP (the ~1,100 production
--                             rows this migration backfills, below). A genuine evidence route.
--   PENDING_ATTACHMENT     -- transient, internal, present only DURING creation (see
--                             CommissionRepository#PENDING_ATTACHMENT_SENTINEL) -- cleared the
--                             moment the real file lands. Not a real evidence route; allow-listed
--                             only because the CHECK is not deferrable (see below).
--   UAT_SEED                -- synthetic, non-production `uat`-profile seed data
--                             (db/migration-uat/V903__uat_sales.sql,
--                             V910__uat_golden_pcr_deal.sql) -- no receipt exists at all, real or
--                             otherwise. Deliberately distinct from PRE_ERP_PAPER_APPROVAL: those
--                             are genuine paper approvals, these are fixture rows with nothing
--                             behind them -- an audit column must not blur "real historical
--                             approval" with "test data," even though both lack a scanned file.
--   DEMO_SEED                -- reserved, not yet consumed by any migration. db/migration-demo/V21
--                             has the identical no-attachment insert shape and is currently safe
--                             only because it runs at version 21, before this constraint exists --
--                             version luck, not design. Adding this value now, unused, costs
--                             nothing and means a future renumbering of the demo seed above V102
--                             fails loudly at migration time (needs this value added to its
--                             insert) rather than the CHECK just happening to still not apply.
COMMENT ON COLUMN sales.invoice_details.evidence_provenance IS
    'Records WHY an invoice has no scanned attachment on file, when that is legitimate. '
    'NULL for the normal case (a real attachment is on file via invoice_attachment_id). '
    'Closed vocabulary, enforced by chk_invoice_details_evidence_present -- see this migration''s '
    'inline comments for the full description of each value: PRE_ERP_PAPER_APPROVAL (real, '
    '~1,100 pre-ERP paper approvals from the 2026 historical commission import, 2026-07-24), '
    'PENDING_ATTACHMENT (transient internal write-path sentinel -- see '
    'CommissionRepository#PENDING_ATTACHMENT_SENTINEL''s Javadoc, must never be visible outside '
    'the single transaction that writes and clears it), UAT_SEED (synthetic uat-profile fixture '
    'rows, no receipt exists), DEMO_SEED (reserved for the demo-profile seed, not yet in use). '
    'This column NEVER substitutes for evidence going forward on any real commission -- '
    'CommissionService#submit/#createFromDeal still hard-require a real attachment for every '
    'commission created through the app.';

-- OWNER DECISION 2026-07-30 (REVERSES the earlier "owner backfills manually, by hand, after
-- reviewing the row count" plan): the backfill runs INLINE, inside this migration, not as a
-- separate manual step. Reason it cannot wait: CommissionService#updateDeductions (the
-- sales-manager/CEO "edit deductions" review path) only blocks VOID/REJECTED commissions
-- (CommissionRepository.java, updateDeductions gate) -- every one of the 1,100 historical rows is
-- APPROVED, which is NOT blocked. A PATCH /api/commissions/{id}/deductions on any one of them
-- calls CommissionRepository#updateDeductions, a plain UPDATE against sales.invoice_details that
-- would violate chk_invoice_details_evidence_present (a CHECK re-validates on every UPDATE to a
-- row, even one touching an unrelated column -- see InvoiceEvidenceProvenanceIntegrationTest). That
-- throws DataIntegrityViolationException, which is NOT an ApiException, so it is not caught by this
-- codebase's ApiException handler and surfaces as an opaque, unexplained 500 to whoever clicked
-- "save" -- for a routine edit to a routine, already-approved record. Running the backfill inline
-- means that window never opens at all: by the time this migration commits, every existing row
-- already satisfies the CHECK, so there is no interval, however short, during which an ordinary
-- edit 500s. The UPDATE is idempotent by construction (WHERE ... IS NULL), so replaying it (e.g.
-- while iterating on this migration before it is first applied anywhere) is always safe.
UPDATE sales.invoice_details
   SET evidence_provenance = 'PRE_ERP_PAPER_APPROVAL'
 WHERE invoice_attachment_id IS NULL
   AND evidence_provenance IS NULL
   AND invoice_number NOT LIKE 'DEMO-%';

-- Demo seed rows get their own category rather than being stamped as genuine paper approvals.
-- db/migration-demo/V21 inserts DEMO-INV-* with no attachment, and it runs at version 21 -- long
-- before this file -- so it CANNOT supply evidence_provenance itself: the column does not exist yet
-- at that point. The relabelling therefore has to happen here, after the column is added. Without
-- it the backfill above would mark synthetic demo data PRE_ERP_PAPER_APPROVAL, which on production
-- means a real paper approval predating the ERP -- blurring the exact distinction this vocabulary
-- exists to preserve, and leaving DEMO_SEED reserved but never used.
UPDATE sales.invoice_details
   SET evidence_provenance = 'DEMO_SEED'
 WHERE invoice_attachment_id IS NULL
   AND evidence_provenance IS NULL
   AND invoice_number LIKE 'DEMO-%';

-- CHECK design (F4 tightening): the constraint used during development accepted ANY non-null
-- string for evidence_provenance, including '', ' ', or 'x' -- it could not actually distinguish a
-- real provenance from noise. Tightened to a closed allow-list instead of a blank/whitespace
-- guard, because the vocabulary genuinely is closed today (see the vocabulary comment and column
-- comment above): PRE_ERP_PAPER_APPROVAL (real historical evidence) and PENDING_ATTACHMENT
-- (transient write-path sentinel -- see CommissionRepository#PENDING_ATTACHMENT_SENTINEL's
-- Javadoc, must satisfy this same CHECK for the single statement in which it is written) are the
-- two values THIS migration's own write paths ever produce; UAT_SEED and DEMO_SEED are allow-
-- listed for non-production seed migrations that run AFTER this one by version (db/migration-uat,
-- db/migration-demo) -- this core migration never writes either itself. Any other value,
-- including blank/whitespace, is rejected -- proven wrong-way-round in
-- InvoiceEvidenceProvenanceIntegrationTest.
--
-- NOT VALID -- deliberately DROPPED (F7 follow-on): the version of this migration originally
-- planned to add this CHECK as NOT VALID, to grandfather the 1,100 existing violating rows without
-- Postgres scanning (and refusing to add the constraint over) them at ADD CONSTRAINT time. That is
-- no longer necessary: the backfill above runs BEFORE this ADD CONSTRAINT, in the same migration
-- transaction, and its WHERE clause (invoice_attachment_id IS NULL AND evidence_provenance IS
-- NULL) is exactly the negation of this CHECK's condition -- by construction, backfilling covers
-- EVERY row that could possibly violate it. There is no row left for NOT VALID to protect, so a
-- plain (validated) ADD CONSTRAINT is correct here: it is provably safe (Postgres's own
-- validation scan will find zero violators), and it leaves the constraint immediately fully
-- enforced with no follow-up "VALIDATE CONSTRAINT" migration ever required, and no "any UPDATE to
-- an un-backfilled legacy row 500s" landmine for anyone to hit before that follow-up lands.
-- NULL-SEMANTICS TRAP (found by this migration's own test suite going red, not guessed): `x IN
-- (a, b)` evaluates to NULL -- not FALSE -- when x IS NULL (three-valued logic: `NULL = a` is
-- NULL, `NULL = b` is NULL, `NULL OR NULL` is NULL). Postgres CHECK constraints treat a NULL
-- result as SATISFIED, exactly like TRUE -- only an explicit FALSE rejects a row. So
-- `evidence_provenance IN (...)` alone, without first testing IS NOT NULL, would have let the
-- exact NULL/NULL row this constraint exists to reject sail straight through: `invoice_
-- attachment_id IS NOT NULL` is FALSE (a real boolean, IS NOT NULL is never NULL), but `FALSE OR
-- NULL` is NULL, not FALSE. The explicit `evidence_provenance IS NOT NULL AND ...` below forces a
-- real FALSE for that case instead.
ALTER TABLE sales.invoice_details
    ADD CONSTRAINT chk_invoice_details_evidence_present CHECK (
        invoice_attachment_id IS NOT NULL
        OR (evidence_provenance IS NOT NULL
            AND evidence_provenance IN (
                'PRE_ERP_PAPER_APPROVAL', 'PENDING_ATTACHMENT', 'UAT_SEED', 'DEMO_SEED'
            ))
    );
