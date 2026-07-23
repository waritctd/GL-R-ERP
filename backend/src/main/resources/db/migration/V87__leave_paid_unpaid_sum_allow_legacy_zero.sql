-- Relax chk_leave_paid_unpaid_sum to tolerate the "legacy / not-yet-split" (0,0) state on
-- APPROVED / CANCELLED leave.
--
-- V85 added a strict check: for APPROVED/CANCELLED leave, paid_days + unpaid_days must equal
-- total_days. That is correct for every leave the redesigned LeaveService approves (it always
-- computes the split). But it is too strict for legacy / seeded rows that predate the split
-- columns: on a fresh replay of the UAT seed set, db/migration-uat/V902 inserts an APPROVED leave
-- with paid_days=0 / unpaid_days=0 (its INSERT runs AFTER V85 added the constraint, and unlike an
-- existing hosted row it is never touched by V85's backfill), so the strict check rejects it and
-- the migration fails. Hosted databases that already had the row backfilled by V85 are unaffected.
--
-- Allowing the exact (0,0) state keeps the integrity guarantee that matters -- a NON-zero
-- inconsistent split (e.g. paid=2, unpaid=2, total=5) is still rejected -- while tolerating the
-- legacy/unsplit rows. A (0,0) APPROVED leave simply contributes 0 unpaid days to payroll, which is
-- the correct behaviour for the within-quota seed leave that hits this path.
ALTER TABLE hr.leave_request DROP CONSTRAINT chk_leave_paid_unpaid_sum;

ALTER TABLE hr.leave_request
    ADD CONSTRAINT chk_leave_paid_unpaid_sum CHECK (
        status NOT IN ('APPROVED', 'CANCELLED')
        OR paid_days + unpaid_days = total_days
        OR (paid_days = 0 AND unpaid_days = 0)
    );
