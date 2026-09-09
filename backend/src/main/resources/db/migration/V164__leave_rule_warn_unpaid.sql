SET search_path = hr, public;

-- ---------------------------------------------------------------------
-- §5 leave rules: WARN + unpaid instead of AUTO_REJECT for 10 of 17 codes (owner-approved change,
-- 2026-09-09)
-- ---------------------------------------------------------------------
--
-- Before this migration, LeaveService#autoRejectNote AUTO_REJECTed a request on any of 17
-- LeaveRuleCode gates, and AUTO_REJECTED was terminal (approve() requires SUBMITTED) -- so there was
-- no exception path at all for any of them, including gates that are missing-HR-data problems
-- (hire_date never recorded) rather than employee violations.
--
-- Owner ruling: 10 of the 17 codes stop BLOCKING. The request now goes through as SUBMITTED (the
-- normal human-approval flow), carrying a machine-readable warning that names the violated §5
-- section and states the affected days will be unpaid if approved -- see
-- LeaveRuleCode#enforcement()/LeaveRuleEnforcement, LeaveService#autoRejectNote's rewritten Javadoc,
-- and LeaveRuleMessages' WARN_UNPAID_TAIL block for the Java-side change this migration supports.
--
-- WARN + whole request unpaid (WARN_UNPAID_ALL): ADVANCE_NOTICE, EMERGENCY_TOLERANCE_EXHAUSTED,
-- SICK_CERTIFICATE_REQUIRED, SICK_NO_CERT_TOLERANCE_EXHAUSTED, SICK_CERTIFICATE_WINDOW,
-- PROBATION_NOT_PASSED, MIN_SERVICE_MONTHS.
--
-- WARN + only the EXCESS days unpaid (WARN_UNPAID_EXCESS): WEDDING_MAX_DAYS, FIRST_YEAR_MAX_DAYS,
-- MAX_CONSECUTIVE_DAYS (the last is DORMANT -- no seeded leave type has carried a non-NULL
-- max_consecutive_days since V120 -- implemented for completeness anyway, per this phase's brief).
--
-- STAYS BLOCKING, unchanged (BLOCK): ONCE_PER_EMPLOYMENT, RESIGNATION_GATE, CONTIGUOUS_LEAVE_PAIR,
-- HIRE_DATE_MISSING_PRORATED, HIRE_DATE_MISSING_MIN_SERVICE, PROBATION_HIRE_DATE_MISSING,
-- DEPARTMENT_COVERAGE. Owner's reasons, recorded here so they are not "fixed" later: the three
-- hire-date codes are missing data in HR's OWN records, not an employee violation -- telling the
-- employee they violated a policy and docking their pay would be false. DEPARTMENT_COVERAGE is an
-- operational rule protecting the department, not a pay rule. RESIGNATION_GATE and
-- CONTIGUOUS_LEAVE_PAIR protect handover and stop leave-stringing -- docking pay does not achieve
-- either. ONCE_PER_EMPLOYMENT is additionally backed by the DB unique index
-- ux_leave_once_per_employment (V116), which this migration deliberately does NOT drop.
--
-- OWNER RULING ON SEMANTICS (settled, implemented exactly):
--   1) Unpaid-by-rule days do NOT consume quota -- LeaveService#computeQuotaSplit excludes them from
--      the quota-eligible pool BEFORE the existing paid/unpaid-by-quota math, so they are neither
--      paid nor counted against annual_quota_days, sumUsedDays/sumOwnQuotaDaysUsed, or carry-forward.
--   2) The approver must see the warning before approving -- rule_warnings reaches LeaveRequestDto.
--   3) Dominance: if ANY WARN_UNPAID_ALL fired, unpaid_by_rule_days = total_days. Otherwise it is the
--      MAX of the WARN_UNPAID_EXCESS excess amounts. An ALL is never summed with an EXCESS.
--
-- NO BACKFILL, matching V131's identical no-backfill precedent for system_note_code/params: every
-- row created before this migration keeps rule_warnings NULL and unpaid_by_rule_days 0 -- re-deriving
-- either would require re-running autoRejectNote's decision against each row's PAST state (quota
-- balances, colleague schedules, etc. as they stood at submission time), which is not reliably
-- reconstructable now.
-- ---------------------------------------------------------------------

ALTER TABLE hr.leave_request
    ADD COLUMN rule_warnings jsonb,
    ADD COLUMN unpaid_by_rule_days NUMERIC(5,2) NOT NULL DEFAULT 0,
    ADD CONSTRAINT chk_leave_unpaid_by_rule_nonnegative CHECK (unpaid_by_rule_days >= 0),
    ADD CONSTRAINT chk_leave_unpaid_by_rule_le_total CHECK (unpaid_by_rule_days <= total_days);

COMMENT ON COLUMN hr.leave_request.rule_warnings IS
    '§5 WARN_UNPAID_* gates (owner-approved change, 2026-09-09): a jsonb ARRAY of '
    '{"code","params","messageTh"} objects -- one per LeaveRuleCode whose enforcement() is '
    'WARN_UNPAID_ALL/WARN_UNPAID_EXCESS and that fired for this request -- see LeaveRuleOutcome/'
    'LeaveRuleMessages and LeaveService#autoRejectNote''s dominance-rule Javadoc. NULL when no WARN '
    'gate fired (the common case) or for any row created before this migration (NO BACKFILL -- same '
    'precedent as V131''s system_note_code/system_note_params). Distinct from system_note_code/'
    'system_note_params (V131), which carry the single BLOCK reason behind an AUTO_REJECTED row -- a '
    'SUBMITTED request can carry rule_warnings and NEVER carries system_note_code (that column stays '
    'NULL unless the request was actually rejected).';
COMMENT ON COLUMN hr.leave_request.unpaid_by_rule_days IS
    'How many of total_days are unpaid because a WARN_UNPAID_* §5 gate fired, as opposed to ordinary '
    'quota exceedance -- see LeaveService#computeQuotaSplit''s Javadoc for the "excluded from the '
    'quota-eligible pool BEFORE the paid/unpaid-by-quota split" mechanism this drives. A SUBSET of '
    'unpaid_days (chk_leave_paid_unpaid_sum, V85/V87, is unaffected: paid_days + unpaid_days still '
    '= total_days for APPROVED/CANCELLED), never a third bucket. 0 (the default) means either no WARN '
    'gate fired, or the request predates this migration (NO BACKFILL). Dominance rule (owner ruling, '
    'not additive): if any WARN_UNPAID_ALL code fired this equals total_days; otherwise it is the MAX '
    'of the WARN_UNPAID_EXCESS codes'' excess amounts -- an ALL is never summed with an EXCESS. Days '
    'in this bucket earn no quota consumption at all (chk_leave_unpaid_by_rule_le_total keeps it from '
    'ever exceeding what the request actually asked for).';

-- ---------------------------------------------------------------------
-- Owner ruling #1's per-YEAR half: hr.leave_request_quota_year (V118) is the per-calendar-year
-- breakdown a cross-year request splits across (see that table's V118/V161 column comments) --
-- hr.leave_request.unpaid_by_rule_days above is the WHOLE-REQUEST sum, but LeaveRepository#
-- sumUsedDays (what a LATER request's own quota computation reads) sums THIS table's per-year
-- total_days, not the parent's. Without a per-year unpaid_by_rule_days column here too, a WARN-ed
-- request's full total_days would still count as "used" the next time sumUsedDays ran for the same
-- employee/type/year -- silently re-introducing quota consumption through the back door of a SECOND
-- request, exactly what ruling #1 forbids ("must not appear in sumUsedDays... consumption"). See
-- LeaveService#computeQuotaSplit/#allocateUnpaidByRuleAcrossYears for how this is populated
-- (latest-year-first, mirroring the per-request allocation) and LeaveRepository#sumUsedDays' V164
-- comment for the read-side fix this column exists to support.
-- ---------------------------------------------------------------------
ALTER TABLE hr.leave_request_quota_year
    ADD COLUMN unpaid_by_rule_days NUMERIC(5,2) NOT NULL DEFAULT 0,
    ADD CONSTRAINT chk_lrqy_unpaid_by_rule_nonnegative CHECK (unpaid_by_rule_days >= 0),
    ADD CONSTRAINT chk_lrqy_unpaid_by_rule_le_total CHECK (unpaid_by_rule_days <= total_days);

COMMENT ON COLUMN hr.leave_request_quota_year.unpaid_by_rule_days IS
    'V164 owner ruling #1: THIS YEAR''s slice of the parent hr.leave_request.unpaid_by_rule_days '
    '(see that column''s comment) -- a SUBSET of this row''s own unpaid_days, never a third bucket. '
    'LeaveRepository#sumUsedDays subtracts this from total_days so a WARN-ed request''s unpaid-by-rule '
    'days never reduce a LATER request''s remaining quota either. 0 for every pre-V164 row (NO '
    'BACKFILL, same precedent as this migration''s other new columns) and for any row where no '
    'WARN_UNPAID_* gate fired.';
