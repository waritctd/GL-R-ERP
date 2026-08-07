import { declaredAllowanceTotal } from './taxAllowanceSchema.js';

// Status-badge + "current declaration" selection for the ล.ย.01 workflow (issue #387). Mirrors
// TaxAllowanceDeclarationStatus (backend/.../declaration/TaxAllowanceDeclarationStatus.java)
// exactly: PENDING / APPROVED / REJECTED / SUPERSEDED / EXPIRED / WITHDRAWN. `appliedAt` is a FLAG
// on an APPROVED row, not a status of its own — see that enum's javadoc — so "applied" here is
// derived the same way the backend documents it (`appliedAt != null`), not a 7th status string.
//
// Same idiom as `requestStatus`/`leaveStatusLabel`/`overtimeStatusLabel` in utils/format.js
// ("Canonical source; do not re-add a page-local map elsewhere") — kept in this feature folder
// rather than format.js because the label additionally depends on `appliedAt`/`appliedEffectiveMonth`/
// `reviewerNote`, not just the bare status string those helpers key off.

// SUPERSEDED/WITHDRAWN are terminal, non-current rows (a newer declaration replaced them, or the
// employee pulled the submission back) — they never surface as "the" status, only PENDING/
// APPROVED/REJECTED/EXPIRED do. Mirrors how ProfilePage/EmployeeSelfService never show a
// cancelled/rejected request as if it were still live.
const NON_CURRENT_STATUSES = new Set(['SUPERSEDED', 'WITHDRAWN']);

/**
 * Picks the one declaration that represents "the employee's current standing" for a tax year, out
 * of every row `getMyTaxAllowanceDeclarations`/`getTaxAllowanceDeclarations` returns (which include
 * full history — old REJECTED rows, WITHDRAWN drafts, SUPERSEDED approvals). The most recently
 * submitted non-terminal row wins; `items` is expected pre-sorted newest-first (both hrApi and
 * mockApi return it that way), but this re-sorts defensively rather than trusting that ordering.
 */
export function selectCurrentDeclaration(items = []) {
  const candidates = items.filter((item) => !NON_CURRENT_STATUSES.has(item.status));
  if (candidates.length === 0) return null;
  return [...candidates].sort((a, b) => String(b.submittedAt || '').localeCompare(String(a.submittedAt || '')))[0];
}

/**
 * Picks the declaration a "resume" flow should pre-fill a form from when there is no CURRENT
 * declaration (`selectCurrentDeclaration` returned null) -- specifically, the most recently
 * submitted WITHDRAWN row for this tax year, if one exists. Same defensive newest-first re-sort on
 * `submittedAt` as `selectCurrentDeclaration` above, for the same reason (trust the shape, not the
 * ordering).
 *
 * This is PREFILL ONLY and changes nothing about "current standing": `NON_CURRENT_STATUSES` and
 * `selectCurrentDeclaration` are untouched by this function, so `selectCurrentDeclaration` keeps
 * returning null after a withdrawal and `taxAllowanceStatusInfo(null)` keeps reporting the honest
 * `ยังไม่ได้ยื่น` badge -- a withdrawn declaration genuinely is not the employee's current standing.
 * The only caller that should ever read this is TaxAllowancePage's `defaultValues` memo (fixing the
 * defect where withdrawing a PENDING declaration wiped every value the employee had typed, directly
 * contradicting the PENDING banner's "ยกเลิกการยื่นเพื่อแก้ไข" and the withdraw dialog's "กลับมาแก้ไข"
 * promises) -- never `statusInfo`/`canStartEdit`/`evidenceMode`, which must keep reading `current`.
 *
 * Deliberately excludes SUPERSEDED: a superseded row was replaced by a newer APPROVED declaration,
 * so resuming from it would resurrect stale figures the employee no longer stands behind. Only
 * WITHDRAWN is a user-initiated "I want this back".
 */
export function selectResumableDeclaration(items = []) {
  const withdrawn = items.filter((item) => item.status === 'WITHDRAWN');
  if (withdrawn.length === 0) return null;
  return [...withdrawn].sort((a, b) => String(b.submittedAt || '').localeCompare(String(a.submittedAt || '')))[0];
}

// ─────────────────────────────────────────────────────────────────────────────
// hr.employee_tax_allowance (what PayrollCalculator ACTUALLY reads) — a second table, wholly
// separate from tax_allowance_declaration above. "Register shows what payroll actually uses"
// (2026-08): every UI surface used to read only the declaration table; `GET
// /api/payroll/tax-allowances` (PayrollReconciliationDtos.EmployeeTaxAllowanceDto) had no caller at
// all, so nobody could see whether payroll was applying a figure no declaration ever backed. The two
// tables can legitimately disagree — see resolvePayrollAllowance below and
// TaxAllowanceReviewPage.jsx's own header comment for how the join is composed.
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Stand-in for "the payroll month in question" when resolving which hr.employee_tax_allowance row
 * applies. `PayrollRepository#findTaxAllowancesByEmployee` is called once per actual payroll run,
 * for one concrete month — but this register is scoped by tax YEAR (the `?year=` selector on
 * TaxAllowanceReviewPage), not by one payroll run, so there is no single month handed in here. This
 * picks the most defensible stand-in: the real calendar month when `taxYear` is the current year
 * (what payroll is applying RIGHT NOW), December when `taxYear` is a past year (the last state that
 * year's payroll ever resolved to), and month 0 — which no `effective_month` (1-12) can ever be
 * `<=`, so it always resolves to "nothing yet" — for a future tax year nobody has been paid in yet.
 */
export function applicablePayrollMonth(taxYear, today = new Date()) {
  const currentYear = today.getFullYear();
  if (Number(taxYear) < currentYear) return 12;
  if (Number(taxYear) > currentYear) return 0;
  return today.getMonth() + 1;
}

/**
 * Resolves what `hr.employee_tax_allowance` is doing for one employee, as of the month
 * `applicablePayrollMonth` picks, into exactly one of three shapes. Mirrors
 * `PayrollRepository#findTaxAllowancesByEmployee`'s `SELECT DISTINCT ON (employee_id) ... WHERE
 * effective_month <= :month AND verification_status <> 'EXPIRED_UNVERIFIED' ORDER BY employee_id,
 * effective_month DESC` — including the exclusion, not just the dating resolution. `items` is `GET
 * /api/payroll/tax-allowances?year=`'s response, which (per that endpoint's own doc comment) is ONE
 * ROW PER `effective_month`, not pre-resolved to one row per employee the way the declaration
 * register already is. V93's effective dating means an employee can genuinely carry more than one
 * dated row in a tax year (e.g. an old GRANDFATHERED_UNVERIFIED row from before this system,
 * followed later by a declaration-applied one) — so when the LATEST-dated row is itself
 * EXPIRED_UNVERIFIED, the real query does not fall through to "nothing applies", it falls BACK to
 * the next-latest qualifying row, exactly like this does. Getting this resolution wrong would
 * misreport the exact figure this feature exists to surface honestly.
 *
 *   { applying: row } — this row is what PayrollCalculator uses right now to reduce withholding
 *                        (VERIFIED or GRANDFATHERED_UNVERIFIED both apply — see
 *                        PayrollRepository's own comment on why).
 *   { expired: row }  — nothing currently applies, but the latest row on or before the month in
 *                        question is EXPIRED_UNVERIFIED and no earlier qualifying row exists either
 *                        — distinct from never having had one at all.
 *   null               — no hr.employee_tax_allowance row exists for this employee/year/month.
 */
export function resolvePayrollAllowance(items = [], employeeId, taxYear, today = new Date()) {
  const month = applicablePayrollMonth(taxYear, today);
  const candidates = items.filter(
    (item) => Number(item.employeeId) === Number(employeeId) && Number(item.effectiveMonth) <= month,
  );
  if (candidates.length === 0) return null;

  const latestOf = (list) => list.reduce((a, b) => (Number(b.effectiveMonth) > Number(a.effectiveMonth) ? b : a));
  // A single employee/year CAN carry more than one dated row (V93 effective dating), so this always
  // resolves "the latest QUALIFYING row" rather than assuming the single latest row's status speaks
  // for the whole year. A MIXED state -- one dated row EXPIRED_UNVERIFIED while another,
  // differently-dated row for the same employee/year is not -- IS reachable, and this fallback is
  // load-bearing for it, not a defensive no-op.
  //
  // (2026-08-08 correction, Opus review F4: this comment previously claimed the mix was NOT
  // reachable, reasoning that "the only two mutators that ever change verification_status,
  // PayrollRepository#markTaxAllowanceVerified and #expireTaxAllowanceVerification, both run WHERE
  // employee_id AND tax_year with no effective_month filter" and so always flip every dated row
  // together. That enumeration is now wrong on its own terms: PayrollRepository#upsertTaxAllowances'
  // ON CONFLICT DO UPDATE is a THIRD mutator of verification_status, and it operates PER ROW -- it
  // can reset one dated row from VERIFIED back to GRANDFATHERED_UNVERIFIED while a sibling row for a
  // different effective_month, untouched by that call, keeps whatever status it already had (see
  // that method's own doc comment). A brand-new dated row inserted for a month that never existed
  // before ALSO starts at GRANDFATHERED_UNVERIFIED (the column default) regardless of any sibling
  // row's status, including an already-EXPIRED_UNVERIFIED one. Do not delete this fallback as dead
  // code -- it is reachable today.)
  const qualifying = candidates.filter((item) => item.verificationStatus !== 'EXPIRED_UNVERIFIED');
  if (qualifying.length > 0) return { applying: latestOf(qualifying) };
  return { expired: latestOf(candidates) };
}

// hr.employee_tax_allowance.verification_status vocabulary (V95, EmployeeTaxAllowanceDto
// #verificationStatus) — VERIFIED / GRANDFATHERED_UNVERIFIED / EXPIRED_UNVERIFIED, never null. This
// is the PAYROLL ROW's own review state, independent of whether a declaration exists for the same
// employee. VERIFIED rows normally coincide with an APPLIED declaration
// (TaxAllowanceDeclarationService#apply upserts the payroll row AND marks it VERIFIED in one
// transaction), so a caller with a declaration in hand should keep reading THAT status, not this one
// (see taxAllowanceStatusInfo below) — this is for the payroll row considered on its own, in the
// register's expanded-row reconciliation panel (TaxAllowanceBreakdown).
const PAYROLL_VERIFICATION_INFO = {
  VERIFIED: { label: 'ยืนยันแล้ว', tone: 'success' },
  GRANDFATHERED_UNVERIFIED: { label: 'ใช้ค่าเดิม — ยังไม่ยืนยัน', tone: 'warning' },
  EXPIRED_UNVERIFIED: { label: 'หมดอายุ — ไม่ใช้กับเงินเดือนแล้ว', tone: 'danger' },
};

/** `payrollVerificationInfo('VERIFIED')` etc. — the one place this copy lives (same "canonical map,
 * do not re-add a literal elsewhere" idiom as STATUS_SHORT_LABELS below). Falls back to the raw
 * status string so a value this map hasn't been taught about yet renders as itself, not blank. */
export function payrollVerificationInfo(status) {
  return PAYROLL_VERIFICATION_INFO[status] ?? { label: status || 'ไม่ทราบสถานะ', tone: 'neutral' };
}

// Compact "chip" copy per derived status KEY — not the raw backend `status` column (there is no
// backend status called APPROVED_UNAPPLIED or APPLIED; those two are this module's own split of
// APPROVED by `appliedAt`, see taxAllowanceStatusInfo below). A filter chip stands for a whole
// bucket of declarations, not one row in hand, so it cannot reuse the long-form `label` below where
// that form is per-declaration text (APPLIED's `label` interpolates one specific effective month;
// a chip has no single month to show). This is the one place the short copy lives — callers that
// need a label for a status KEY with no declaration in hand (TaxAllowanceReviewPage's STATUS_CHIPS)
// read it via `taxAllowanceStatusShortLabel` instead of keeping their own literals, which is how
// APPROVED_UNAPPLIED and EXPIRED ended up reading differently on the register than on the badge.
const STATUS_SHORT_LABELS = {
  NONE: 'ยังไม่ได้ยื่น',
  PENDING: 'รอ HR ตรวจสอบ',
  REJECTED: 'ปฏิเสธ',
  EXPIRED: 'หมดอายุ',
  APPROVED_UNAPPLIED: 'ยังไม่ใช้กับเงินเดือน',
  APPLIED: 'ใช้กับเงินเดือนแล้ว',
  // "register shows what payroll actually uses" (2026-08) — NOT a tax_allowance_declaration.status
  // value (there is no declaration at all in this branch); see taxAllowanceStatusInfo below. Short
  // enough to sit in the filter chip row next to the six above; deliberately worded differently from
  // the long-form badge label, same "short != long" split EXPIRED already has.
  GRANDFATHERED_APPLIED: 'ใช้ค่าเดิม-ยังไม่ยืนยัน',
  // Sibling of GRANDFATHERED_APPLIED just above — the OTHER no-declaration payroll state (F6 review
  // remediation: HR needs a chip to find people whose old allowance silently STOPPED applying, not
  // just the ones where it is still applying unreviewed — the reverse case matters at least as much).
  GRANDFATHERED_EXPIRED: 'ค่าเดิม-หมดอายุแล้ว',
};

/**
 * Short chip label for a status KEY, e.g. `taxAllowanceStatusShortLabel('EXPIRED')` — the one
 * place this short copy lives. A chip needs a label for every status a filter row can show BEFORE
 * any one declaration is in view — most obviously NONE, which `taxAllowanceStatusInfo` can only
 * ever produce by being handed `null`, not a key — so this stays a separate, declaration-free
 * lookup rather than a field tacked onto that function's return value (review-remediation: a
 * `.shortLabel` field lived there briefly and had zero callers — `taxAllowanceStatusInfo` is keyed
 * off a full declaration, which every chip-building caller lacks by construction, so nothing could
 * ever have read it). An unrecognised key falls back to itself so a status this map hasn't been
 * taught about yet renders as its own name instead of blank.
 */
export function taxAllowanceStatusShortLabel(key) {
  return STATUS_SHORT_LABELS[key] ?? key;
}

/**
 * Six distinct states per issue #387 screen 1, plus two more (2026-08, "register shows what payroll
 * actually uses") for an employee with NO declaration but a live hr.employee_tax_allowance row —
 * each with its own Thai copy and StatusBadge tone.
 *
 * `payrollResolution`, when passed, is `resolvePayrollAllowance`'s result for this employee (see
 * above) — the figure PayrollCalculator is ACTUALLY applying, read from a wholly different table
 * (hr.employee_tax_allowance) than `declaration` (hr.tax_allowance_declaration). It is only
 * consulted when `declaration` is null: a declaration that exists is always authoritative for this
 * badge (a VERIFIED payroll row normally coincides with an APPLIED declaration — see
 * PAYROLL_VERIFICATION_INFO's own comment above), and the payroll row's own figure / verification
 * state / deadline / any disagreement with the declared total surface separately, in the expanded
 * row (TaxAllowanceBreakdown) — never folded into this summary badge.
 */
export function taxAllowanceStatusInfo(declaration, payrollResolution) {
  if (!declaration) {
    if (payrollResolution?.applying) {
      // VERIFIED or GRANDFATHERED_UNVERIFIED — PayrollRepository#findTaxAllowancesByEmployee
      // applies BOTH to withholding (only EXPIRED_UNVERIFIED is excluded — see
      // resolvePayrollAllowance above), so this employee's tax is being reduced RIGHT NOW by a
      // figure no declaration backs. That is the exact gap this feature exists to surface — warning,
      // never neutral/success, so it never reads as "nothing to see" or "all done".
      return { label: 'ใช้ค่าลดหย่อนเดิม — ยังไม่ได้ยืนยัน', tone: 'warning', key: 'GRANDFATHERED_APPLIED' };
    }
    if (payrollResolution?.expired) {
      // No longer applied to payroll (excluded by that same WHERE clause) — but a real, now-lapsed
      // figure existed, so this must not silently collapse into the same ยังไม่ได้ยื่น a genuinely
      // empty employee gets below.
      return {
        label: 'เคยมีค่าลดหย่อนเดิม — หมดอายุแล้ว ไม่ใช้กับเงินเดือน',
        tone: 'danger',
        key: 'GRANDFATHERED_EXPIRED',
      };
    }
    return { label: 'ยังไม่ได้ยื่น', tone: 'neutral', key: 'NONE' };
  }
  const { status, appliedAt, appliedEffectiveMonth, reviewerNote } = declaration;
  if (status === 'PENDING') {
    return { label: 'รอ HR ตรวจสอบ', tone: 'warning', key: 'PENDING' };
  }
  if (status === 'REJECTED') {
    return { label: 'ปฏิเสธ', tone: 'danger', key: 'REJECTED', note: reviewerNote || null };
  }
  if (status === 'EXPIRED') {
    return { label: 'หมดอายุ — ต้องยืนยันใหม่', tone: 'danger', key: 'EXPIRED' };
  }
  if (status === 'APPROVED') {
    if (appliedAt) {
      return {
        label: `ใช้กับเงินเดือนแล้ว ตั้งแต่เดือน ${appliedEffectiveMonth ?? '-'}`,
        // Deliberately NOT the same tone as "approved, not yet applied" — issue #387: "the
        // approved-but-unapplied state must not read as done". `success` here is genuinely
        // terminal-good; the unapplied state below stays `info` so it never reads the same.
        tone: 'success',
        key: 'APPLIED',
      };
    }
    return {
      label: 'อนุมัติแล้ว — ยังไม่ใช้กับเงินเดือน',
      tone: 'info',
      key: 'APPROVED_UNAPPLIED',
    };
  }
  return { label: status || 'ไม่ทราบสถานะ', tone: 'neutral', key: status || 'UNKNOWN' };
}

/**
 * Whether the declared total and the stored payroll total disagree — the ONE gate behind the
 * "ยอดไม่ตรงกัน" flag, read by both the register's summary row (TaxAllowanceReviewPage's `status`
 * column) and its expanded reconciliation panel (TaxAllowanceBreakdown). Before this function
 * existed the two carried their OWN, slightly different condition and nothing caught them
 * disagreeing with EACH OTHER: the summary row already excluded an EXPIRED_UNVERIFIED
 * `payrollAllowance`, the expanded panel did not — so a declaration next to a now-lapsed payroll row
 * with a different total silently got the flag in the panel but not on the row above it.
 *
 * Deliberately takes the RESOLVED row (`resolvePayrollAllowance(...)?.applying ?? ...?.expired ??
 * null`), not the `{ applying } | { expired } | null` wrapper shape: every current caller already
 * has exactly that flattened row in hand (TaxAllowanceReviewPage's `buildRow`, TaxAllowanceBreakdown's
 * own `payrollAllowance` prop), so re-deriving the wrapper would mean threading `payrollResolution`
 * somewhere that doesn't otherwise need it. `verificationStatus` alone is enough to tell the two
 * shapes apart: by construction, `resolvePayrollAllowance` only ever returns `{ expired: row }` when
 * EVERY qualifying candidate was excluded — i.e. `row.verificationStatus === 'EXPIRED_UNVERIFIED'`
 * always holds there and never holds for an `applying` row (see that function's own filter).
 *
 * An EXPIRED_UNVERIFIED row never counts, even when its total plainly differs from the declaration:
 * that row is, by definition, excluded from `PayrollRepository#findTaxAllowancesByEmployee`'s WHERE
 * clause, so it is not currently reducing anyone's withholding at all — comparing its stale figure
 * against the current declaration would answer the wrong question, and the fact that actually
 * matters there ("this stopped applying") already has its own explicit badge
 * (`payrollVerificationInfo('EXPIRED_UNVERIFIED')`) rather than needing this flag too.
 */
export function hasAllowanceDisagreement(declaration, payrollAllowance) {
  if (!declaration || !payrollAllowance || payrollAllowance.verificationStatus === 'EXPIRED_UNVERIFIED') {
    return false;
  }
  return declaredAllowanceTotal(declaration) !== declaredAllowanceTotal({ allowances: payrollAllowance.allowances });
}
