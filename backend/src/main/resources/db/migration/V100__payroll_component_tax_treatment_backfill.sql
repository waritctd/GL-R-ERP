SET search_path = hr, public;

-- ---------------------------------------------------------------------
-- P0 fix (Opus review, 2026-07-30): the tax-treatment matrix has no writer.
--
-- PayrollCalculator#calculateClassified (task 2) 409s the ENTIRE payroll run for any employee with a
-- non-zero, non-SALARY component that has no stored hr.payroll_component_tax_treatment row. V95
-- creates the table; nothing before this migration ever inserts a row into it for a REAL employee --
-- PayrollRepository#upsertComponentTaxTreatment had zero callers in src/main, no controller mapping,
-- and no frontend. GET /api/payroll (the plain page load) falls through to preview() for a month with
-- no saved period, which classifies every active employee -- so payroll was unrunnable for anyone who
-- earns anything beyond bare salary (handoff section 9d: employee 10012 alone carries special_pay_1 =
-- 407 in production). This migration is layer 1 of the 3-layer fix (backfill here; HTTP surface on
-- PayrollController/PayrollService this same task; a screen on PayrollPage.jsx) -- a backfill ALONE
-- would leave the next new employee or new component just as dead, so this is necessary but not
-- sufficient by itself.
--
-- SCOPE: tax_year 2026 only, matching every other one-time backfill on this branch (V96's SSO
-- inclusion, V98's carry-forward) -- verified in task 1 that 2026-03 is the earliest payroll month on
-- record and only one tax year exists yet.
--
-- DEFAULTS, per the handoff's own table (section 1) and reasoned out where the handoff is silent:
--
--   1. BONUS_PAY, OTHER_ONE_OFF_PAY -> EXTRA_KNOWN_FREQUENCY. Not a guess -- the handoff names bonus
--      "the archetypal EXTRA_KNOWN_FREQUENCY example" outright, and OTHER_ONE_OFF_PAY is a one-off by
--      its own name ("confirmed one-off" is literally the handoff's EXTRA_KNOWN_FREQUENCY bucket).
--
--   2. OVERTIME_PAY, COMMISSION_PAY -> EXTRA_CUMULATIVE_ACTUAL. Also handoff-explicit: "OT and
--      irregular commission -> EXTRA_CUMULATIVE_ACTUAL". COMMISSION_PAY here is the auto-fed
--      CommissionService column (PayrollComponent's own javadoc), inherently irregular.
--
--   3. DIRECTOR_REMUNERATION -> REGULAR_REPROJECT. NOT the generic unknown-recurrence default below --
--      resolved, not merely unresolved-and-defaulted. Owner-confirmed directly (2026-07-29): "director
--      remuneration is every month but the pay may change once in a while." Paid every คราว makes it
--      เงินได้ที่จ่ายตามปกติ under ป.96/2543 ข้อ 2.1, not a เงินพิเศษ under ข้อ 2.5 -- the amount varying
--      occasionally is not an obstacle, because ข้อ 2.4 requires recomputing the withholding every คราว
--      from the actual year-to-date figure, which is exactly what REGULAR_REPROJECT already does. This
--      also matches PayrollCalculator's legacy calculate() engine, which hardcodes director
--      remuneration into its REGULAR limb (see that method's own comment on the two-limb split) -- the
--      two engines must not disagree about directors, and disagreeing here is exactly the kind of
--      divergence this branch has already shipped and had to fix twice.
--
--      CORRECTION (caught in review before this branch merged): an earlier version of this migration
--      defaulted DIRECTOR_REMUNERATION to EXTRA_CUMULATIVE_ACTUAL on the premise that some directors
--      might be paid annually rather than monthly, so their true recurrence was "unresolved" like any
--      other component with no carry-forward evidence. That premise is factually wrong for GL&R (see
--      the owner's statement above) and has been removed. DIRECTOR_REMUNERATION is resolved by the
--      owner's own words, not defaulted by absence of evidence -- unlike every component in bucket 4
--      below, which genuinely has none.
--
--   4. Every other classification-eligible component (SPECIAL_PAY_1..9, MEAL_ALLOWANCE,
--      PER_DIEM_TAXABLE) -> mined from hr.payroll_component_carry_forward (V98), which already answers
--      "does this exact employee/component pair recur" from the real accountant's ledger at a
--      70%-same-value rule: carry_forward = TRUE means REGULAR_REPROJECT (the handoff's own "fixed
--      recurring allowance" bucket), matching real evidence rather than a re-typed guess. Reused via a
--      JOIN against V98's table, not by re-transcribing its VALUES list, so the two migrations can
--      never silently drift apart.
--
--      REASONING CORRECTED (fourth Opus review, 2026-07-30): the previous wording here said "the 5
--      employees V98 could not resolve", understating this by roughly two orders of magnitude and
--      misdescribing WHOSE pairs land here. The 5 unresolvable workbook names (V98's own comment) are
--      irrelevant to this count -- they have no employee_id to CROSS JOIN against in Step 1 at all, so
--      they never reach this table in the first place. The real proportion, counted directly against
--      this migration's own Step 1/Step 2: 34 employees x 16 classification-eligible components = 544
--      pairs considered. V98's real per-employee evidence promotes exactly 44 of them (Step 2) to
--      REGULAR_REPROJECT. The remaining 544 - 44 = 500 pairs (roughly 92% of the whole matrix) rely on
--      a company-wide rule rather than per-employee-per-component evidence: 34 of those 500 are
--      DIRECTOR_REMUNERATION (resolved by the owner's own words, bucket 3 above), 68 are BONUS_PAY/
--      OTHER_ONE_OFF_PAY (resolved by the spec's own words, bucket 1 above), and the remaining 398 are
--      the genuinely-unknown-recurrence case this bucket exists for, defaulted to EXTRA_CUMULATIVE_ACTUAL
--      below. Recorded here so a reader sizing "how much of this backfill is a real per-employee fact
--      versus a company-wide guess" gets the true 500-of-544 answer, not a count that reads as a small
--      residual.
--
--      For every (employee, component) pair in that residual 398 -- i.e. NOT DIRECTOR_REMUNERATION,
--      NOT BONUS_PAY/OTHER_ONE_OFF_PAY, and NOT marked recurring by V98 -- the SAFE default is
--      EXTRA_CUMULATIVE_ACTUAL, not REGULAR_REPROJECT or EXTRA_KNOWN_FREQUENCY, for a concrete,
--      worked reason:
--        - REGULAR_REPROJECT assumes the amount recurs at the SAME figure every remaining month
--          (annualised x monthsRemaining). Wrong for a genuinely one-off payment, this causes ONE
--          large over-withholding spike the month it is paid -- inconvenient, but not a compliance
--          failure (over-withholding is refundable on the annual return).
--        - EXTRA_KNOWN_FREQUENCY assumes the payment happens ONCE this year (count = 1, per
--          PayrollTaxTreatment's own javadoc). If the component actually recurs monthly, each
--          occurrence is taxed independently against the regular-limb projection alone -- which does
--          NOT itself accumulate prior known-limb settlements -- so a genuinely recurring item
--          misclassified this way is systematically UNDER-withheld all year. That is a real
--          compliance risk, not a mere inconvenience.
--        - EXTRA_CUMULATIVE_ACTUAL taxes the TRUE cumulative actual paid to date, less tax already
--          withheld on that limb, every period. It never assumes future recurrence (unlike
--          REGULAR_REPROJECT) and never mis-counts a recurring item as one-off (unlike
--          EXTRA_KNOWN_FREQUENCY) -- it self-corrects regardless of the component's true pattern,
--          which is exactly why the handoff already reserves this bucket for "irregular" income.
--      This is the safest available default for a component whose true recurrence this migration
--      genuinely cannot know, and is documented here so a reviewer can tell it apart from a guess.
--
-- SSO exclusion for DIRECTOR_REMUNERATION is untouched by this migration -- that is a separate matrix
-- (hr.payroll_component_sso_inclusion) already correctly defaulted by V96.
--
-- SALARY and NON_TAXABLE_INCOME are deliberately excluded from this backfill: SALARY is locked to
-- REGULAR_REPROJECT in Java (PayrollClassifiedCalculationInput#treatmentOf) and in the V95 CHECK
-- constraint without ever needing a stored row; NON_TAXABLE_INCOME is explicitly out of scope for tax
-- treatment (PayrollCalculator#requireEveryNonZeroComponentClassified skips it, PayrollComponent's own
-- javadoc says so).
--
-- ON CONFLICT DO NOTHING throughout: this only fills in rows that do not exist yet, so it can never
-- clobber a classification HR has already set through the new HTTP surface between deploy and this
-- migration running, and is idempotent on re-run.
-- ---------------------------------------------------------------------

-- Step 1: company-wide safe defaults for every employee x classification-eligible component.
INSERT INTO hr.payroll_component_tax_treatment (employee_id, tax_year, component, tax_treatment, updated_by_id, updated_at)
SELECT e.employee_id,
       2026,
       c.component,
       CASE
           WHEN c.component = 'DIRECTOR_REMUNERATION' THEN 'REGULAR_REPROJECT'
           WHEN c.component IN ('BONUS_PAY', 'OTHER_ONE_OFF_PAY') THEN 'EXTRA_KNOWN_FREQUENCY'
           ELSE 'EXTRA_CUMULATIVE_ACTUAL'
       END,
       NULL,
       now()
  FROM hr.employee e
 CROSS JOIN hr.payroll_pay_component c
 WHERE c.component NOT IN ('SALARY', 'NON_TAXABLE_INCOME')
    ON CONFLICT (employee_id, tax_year, component) DO NOTHING;

-- Step 2: promote to REGULAR_REPROJECT wherever V98's real ledger evidence says this exact
-- employee/component pair actually recurs (>= 70% of the 4 sampled months, per V98's own rule).
--
-- GUARD (found by this migration's own replay test, PayrollComponentTaxTreatmentBackfillIntegrationTest
-- .replayingV100NeverOverwritesAnHrClassificationMadeBeforeItRuns): restricted to `updated_by_id IS
-- NULL`, i.e. ONLY a row still at the system-seeded default Step 1 just wrote (or that an earlier run
-- of this same idempotent migration wrote). Without this guard the UPDATE matches ANY row for that
-- employee/component/year -- including one HR already classified some other way through the matrix
-- screen after this migration first ran -- and would silently overwrite a genuine HR decision with the
-- carry-forward-derived default the next time this (idempotent, safe-to-rerun) migration executes.
-- `upsertComponentTaxTreatment` always stamps a real `updated_by_id` for an HR-driven write, so this
-- guard reliably distinguishes "nobody has ever touched this row" from "HR set this deliberately".
UPDATE hr.payroll_component_tax_treatment t
   SET tax_treatment = 'REGULAR_REPROJECT',
       updated_at = now()
  FROM hr.payroll_component_carry_forward cf
 WHERE cf.carry_forward = TRUE
   AND cf.employee_id = t.employee_id
   AND cf.tax_year = t.tax_year
   AND cf.component = t.component
   AND t.updated_by_id IS NULL
   AND t.tax_treatment IS DISTINCT FROM 'REGULAR_REPROJECT';
