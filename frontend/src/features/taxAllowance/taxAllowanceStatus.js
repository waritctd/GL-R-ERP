import { declaredAllowanceTotal } from './taxAllowanceSchema.js';

// Status-badge + "current declaration" selection for the ล.ย.01 workflow (issue #387). Mirrors
// TaxAllowanceDeclarationStatus (backend/.../declaration/TaxAllowanceDeclarationStatus.java) exactly: PENDING
// / APPROVED / REJECTED / SUPERSEDED / EXPIRED / WITHDRAWN.

// SUPERSEDED/WITHDRAWN are terminal, non-current rows (a newer declaration replaced them, or the employee
// pulled the submission back) — they never surface as "the" status, only PENDING/ APPROVED/REJECTED/EXPIRED
// do.
const NON_CURRENT_STATUSES = new Set(['SUPERSEDED', 'WITHDRAWN']);

/** Picks the one declaration that represents "the employee's current standing" for a tax year, out of every row
 * `getMyTaxAllowanceDeclarations`/`getTaxAllowanceDeclarations` returns (which include full history — old
 * REJECTED rows, WITHDRAWN drafts, SUPERSEDED approvals). */
export function selectCurrentDeclaration(items = []) {
  const candidates = items.filter((item) => !NON_CURRENT_STATUSES.has(item.status));
  if (candidates.length === 0) return null;
  return [...candidates].sort((a, b) => String(b.submittedAt || '').localeCompare(String(a.submittedAt || '')))[0];
}

/** Picks the declaration a "resume" flow should pre-fill a form from when there is no CURRENT declaration
 * (`selectCurrentDeclaration` returned null) -- specifically, the most recently submitted WITHDRAWN row for
 * this tax year, if one exists. */
export function selectResumableDeclaration(items = []) {
  const withdrawn = items.filter((item) => item.status === 'WITHDRAWN');
  if (withdrawn.length === 0) return null;
  return [...withdrawn].sort((a, b) => String(b.submittedAt || '').localeCompare(String(a.submittedAt || '')))[0];
}

// hr.employee_tax_allowance. The two tables can legitimately disagree — see resolvePayrollAllowance below and
// TaxAllowanceReviewPage.jsx's own header comment for how the join is composed.

/** Stand-in for "the payroll month in question" when resolving which hr.employee_tax_allowance row applies. */
export function applicablePayrollMonth(taxYear, today = new Date()) {
  const currentYear = today.getFullYear();
  if (Number(taxYear) < currentYear) return 12;
  if (Number(taxYear) > currentYear) return 0;
  return today.getMonth() + 1;
}

/** Resolves what `hr.employee_tax_allowance` is doing for one employee, as of the month
 * `applicablePayrollMonth` picks, into exactly one of three shapes. Mirrors
 * `PayrollRepository#findTaxAllowancesByEmployee`'s `SELECT DISTINCT ON (employee_id) ... */
export function resolvePayrollAllowance(items = [], employeeId, taxYear, today = new Date()) {
  const month = applicablePayrollMonth(taxYear, today);
  const candidates = items.filter(
    (item) => Number(item.employeeId) === Number(employeeId) && Number(item.effectiveMonth) <= month,
  );
  if (candidates.length === 0) return null;

  const latestOf = (list) => list.reduce((a, b) => (Number(b.effectiveMonth) > Number(a.effectiveMonth) ? b : a));
  // A single employee/year CAN carry more than one dated row (V93 effective dating), so this always resolves
  // "the latest QUALIFYING row" rather than assuming the single latest row's status speaks for the whole year.
  const qualifying = candidates.filter((item) => item.verificationStatus !== 'EXPIRED_UNVERIFIED');
  if (qualifying.length > 0) return { applying: latestOf(qualifying) };
  return { expired: latestOf(candidates) };
}

// hr.employee_tax_allowance.verification_status vocabulary (V95, EmployeeTaxAllowanceDto #verificationStatus)
// — VERIFIED / GRANDFATHERED_UNVERIFIED / EXPIRED_UNVERIFIED, never null. This is the PAYROLL ROW's own review
// state, independent of whether a declaration exists for the same employee.
const PAYROLL_VERIFICATION_INFO = {
  VERIFIED: { label: 'ยืนยันแล้ว', tone: 'success' },
  GRANDFATHERED_UNVERIFIED: { label: 'ใช้ค่าเดิม — ยังไม่ยืนยัน', tone: 'warning' },
  EXPIRED_UNVERIFIED: { label: 'หมดอายุ — ไม่ใช้กับเงินเดือนแล้ว', tone: 'danger' },
};

/** `payrollVerificationInfo('VERIFIED')` etc. — the one place this copy lives (same "canonical map, do not
 * re-add a literal elsewhere" idiom as STATUS_SHORT_LABELS below). Falls back to the raw status string so a
 * value this map hasn't been taught about yet renders as itself, not blank. */
export function payrollVerificationInfo(status) {
  return PAYROLL_VERIFICATION_INFO[status] ?? { label: status || 'ไม่ทราบสถานะ', tone: 'neutral' };
}

// Compact "chip" copy per derived status KEY — not the raw backend `status` column (there is no backend status
// called APPROVED_UNAPPLIED or APPLIED; those two are this module's own split of APPROVED by `appliedAt`, see
// taxAllowanceStatusInfo below).
const STATUS_SHORT_LABELS = {
  NONE: 'ยังไม่ได้ยื่น',
  PENDING: 'รอ HR ตรวจสอบ',
  REJECTED: 'ปฏิเสธ',
  EXPIRED: 'หมดอายุ',
  APPROVED_UNAPPLIED: 'ยังไม่ใช้กับเงินเดือน',
  APPLIED: 'ใช้กับเงินเดือนแล้ว',
  // "register shows what payroll actually uses" (2026-08) — NOT a tax_allowance_declaration.status value (there
  // is no declaration at all in this branch); see taxAllowanceStatusInfo below.
  GRANDFATHERED_APPLIED: 'ใช้ค่าเดิม-ยังไม่ยืนยัน',
  // Sibling of GRANDFATHERED_APPLIED just above — the OTHER no-declaration payroll state (F6 review remediation:
  // HR needs a chip to find people whose old allowance silently STOPPED applying, not just the ones where it is
  // still applying unreviewed — the reverse case matters at least as much).
  GRANDFATHERED_EXPIRED: 'ค่าเดิม-หมดอายุแล้ว',
};

/** Short chip label for a status KEY, e.g. `taxAllowanceStatusShortLabel('EXPIRED')` — the one place this short
 * copy lives. An unrecognised key falls back to itself so a status this map hasn't been taught about yet
 * renders as its own name instead of blank. */
export function taxAllowanceStatusShortLabel(key) {
  return STATUS_SHORT_LABELS[key] ?? key;
}

/** Six distinct states per issue #387 screen 1, plus two more (2026-08, "register shows what payroll actually
 * uses") for an employee with NO declaration but a live hr.employee_tax_allowance row — each with its own Thai
 * copy and StatusBadge tone. */
export function taxAllowanceStatusInfo(declaration, payrollResolution) {
  if (!declaration) {
    if (payrollResolution?.applying) {
      // VERIFIED or GRANDFATHERED_UNVERIFIED — PayrollRepository#findTaxAllowancesByEmployee applies BOTH to
      // withholding (only EXPIRED_UNVERIFIED is excluded — see resolvePayrollAllowance above), so this employee's
      // tax is being reduced RIGHT NOW by a figure no declaration backs.
      return { label: 'ใช้ค่าลดหย่อนเดิม — ยังไม่ได้ยืนยัน', tone: 'warning', key: 'GRANDFATHERED_APPLIED' };
    }
    if (payrollResolution?.expired) {
      // No longer applied to payroll (excluded by that same WHERE clause) — but a real, now-lapsed figure existed,
      // so this must not silently collapse into the same ยังไม่ได้ยื่น a genuinely empty employee gets below.
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
        // Month 1 is "the whole tax year", not "from January": since the whole-year ruling (2026-08-31) every
        // promotion writes 1, so naming the month would put a meaningless "ตั้งแต่เดือน 1" on every current
        // declaration.
        label: Number(appliedEffectiveMonth) === 1
          ? 'ใช้กับเงินเดือนแล้ว ทั้งปีภาษี'
          : `ใช้กับเงินเดือนแล้ว ตั้งแต่เดือน ${appliedEffectiveMonth ?? '-'}`,
        // Deliberately NOT the same tone as "approved, not yet applied" — issue #387: "the approved-but-unapplied
        // state must not read as done". `success` here is genuinely terminal-good; the unapplied state below stays
        // `info` so it never reads the same.
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

/** Whether the declared total and the stored payroll total disagree — the ONE gate behind the "ยอดไม่ตรงกัน"
 * flag, read by both the register's summary row (TaxAllowanceReviewPage's `status` column) and its expanded
 * reconciliation panel (TaxAllowanceBreakdown). */
export function hasAllowanceDisagreement(declaration, payrollAllowance) {
  if (!declaration || !payrollAllowance || payrollAllowance.verificationStatus === 'EXPIRED_UNVERIFIED') {
    return false;
  }
  return declaredAllowanceTotal(declaration) !== declaredAllowanceTotal({ allowances: payrollAllowance.allowances });
}
