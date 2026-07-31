import React, { useEffect, useMemo, useRef, useState } from 'react';
import { api } from '../../api/index.js';
import { Button } from '../../components/common/Button.jsx';
import { CollapsibleSection } from '../../components/common/CollapsibleSection.jsx';
import { CompactStatRow } from '../../components/common/CompactStatRow.jsx';
import { ConfirmDialog } from '../../components/common/ConfirmDialog.jsx';
import { DataTable } from '../../components/common/DataTable.jsx';
import { DesktopOnlyNotice } from '../../components/common/DesktopOnlyNotice.jsx';
import { EmptyState } from '../../components/common/EmptyState.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { InfoTip } from '../../components/common/InfoTip.jsx';
import { FilterBar, FormGrid, PageStack, Panel } from '../../components/common/Layout.jsx';
import { PageHeader } from '../../components/common/PageHeader.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { useDialogFocus } from '../../hooks/useDialogFocus.js';
import { useIsMobile, useMediaQuery } from '../../hooks/useIsMobile.js';
import { cn } from '../../utils/cn.js';
import { formatMoney, formatShortDate, formatThaiMonthYearFromMonthInputValue, payrollStatusLabel as statusInfo } from '../../utils/format.js';

// Reproduces `.panel` for the detail sidebar, which must stay a real <aside>
// element (semantic landmark) — Layout.jsx's Panel always renders a <section>
// and has no `as` prop, so the exact utility string is duplicated here rather
// than wrapping a <section> inside an <aside> or changing the shared primitive.
const PANEL_CLASS = 'bg-surface border border-border rounded-md shadow-sm p-5';

// The payroll export files HR can generate for a processed period. `value` is the backend export
// slug (/api/payroll/{id}/export/{value}); `filePrefix` names the downloaded blob; `extension`
// matches PayrollExportKind's own file extension (the three statutory kinds are CP874 .txt,
// payroll-detail is a real xlsx workbook -- see PayrollController#export's javadoc).
const EXPORT_KINDS = [
  { value: 'kbank', label: 'KBank Payroll', filePrefix: 'PCT', extension: 'txt' },
  { value: 'pnd1', label: 'ภ.ง.ด.1', filePrefix: 'Pnd1', extension: 'txt' },
  { value: 'sso', label: 'ประกันสังคม (สปส.1-10)', filePrefix: 'SPS1-10', extension: 'txt' },
  { value: 'payroll-detail', label: 'รายละเอียดเงินเดือนรายเดือน (Excel)', filePrefix: 'PayrollDetail', extension: 'xlsx' },
];

function MoneyCode({ value }) {
  return <code className="payroll-money">{formatMoney(value)}</code>;
}

function createEmployeeColumn(selectedEmployeeId) {
  return {
    key: 'employee',
    header: 'พนักงาน',
    sortable: true,
    sortAccessor: (line) => line.employeeName,
    searchAccessor: (line) => line.employeeName,
    render: (line) => {
      const selected = Number(line.employeeId) === Number(selectedEmployeeId);
      // B4 fix (Opus review, 2026-07-31): moved from `.payroll-employee-cell`/`.payroll-selected-cue`
      // styles.css rules to Tailwind utilities at the call site (CLAUDE.md Tailwind-first) -- plain
      // flex/badge layout with no dynamic/cross-component coupling, so nothing here needed a
      // stylesheet rule in the first place.
      //
      // Defect 1 fix (Opus review, 2026-07-31): `min-w-0` on the inner span only lets the flex item
      // SHRINK -- it does nothing to the text inside once shrunk, so a name too long for the
      // available space just painted past its own right edge and under the `.payroll-selected-cue`
      // badge (a later sibling, so it paints on top). The legacy `.data-row > td > span/strong/small`
      // rule in styles.css used to backstop this with block+ellipsis, but that rule lives in
      // `@layer legacy` and Tailwind utilities always win over it (see styles-css-loses-to-tailwind-
      // utilities gotcha), so it can no longer be relied on here now that the cell is Tailwind-first.
      // `<strong>`/`<small>` are inline by default and render as two separate lines only because nothing
      // upstream declares `display: block` for them anymore -- each needs its own `block truncate` so
      // it clips with an ellipsis at ITS OWN width instead of overflowing into the badge.
      return (
        <span className="flex items-center justify-between gap-2.5 min-w-0">
          <span className="min-w-0 overflow-hidden">
            <strong className="block truncate">{line.employeeName}</strong>
            <small className="block truncate">{line.employeeCode} · {line.departmentName || '-'}</small>
            {/* Finding 1 fix (Opus review, 2026-07-30): list-level warning so a daily-rate employee
                with no/zero days-worked is visible WITHOUT having to open their detail panel first --
                the gap the ฿0-payslip bug actually lived in (hasPayrollInput below never submits a row
                with nothing entered, so HR could Process a whole month without ever noticing). Purely
                a nudge; PayrollService#requireEveryDailyRateEmployeeHasDaysWorked is the real
                enforcement. */}
            {line.payType === 'D' && !(Number(line.daysWorked) > 0) && (
              <small className="block truncate text-warning">⚠ ยังไม่ได้ระบุจำนวนวันทำงาน</small>
            )}
          </span>
          {selected ? (
            <span className="inline-flex flex-none items-center gap-1 whitespace-nowrap rounded-sm border border-info-border bg-info-bg px-1.5 py-0.5 text-2xs font-black text-info">
              <Icon name="check" size={13} />
              เลือกอยู่
            </span>
          ) : null}
        </span>
      );
    },
  };
}

const payrollMoneyColumns = [
  {
    key: 'grossEarnings',
    header: 'รายได้',
    heroLabel: 'รายได้รวม',
    heroAccessor: (period) => period?.totalGross,
    sortable: true,
    align: 'right',
    className: 'payroll-money-cell',
    isMoney: true,
    totalAccessor: (line) => Number(line.grossEarnings || 0),
    sortAccessor: (line) => Number(line.grossEarnings || 0),
    render: (line) => <MoneyCode value={line.grossEarnings} />,
  },
  {
    key: 'specialPayTotal',
    header: 'เงินพิเศษ',
    sortable: true,
    align: 'right',
    className: 'payroll-money-cell',
    isMoney: true,
    hideWhenZero: true,
    totalAccessor: (line) => Number(line.specialPayTotal || 0),
    sortAccessor: (line) => Number(line.specialPayTotal || 0),
    render: (line) => <MoneyCode value={line.specialPayTotal} />,
  },
  {
    key: 'otCommission',
    header: 'OT / Commission',
    sortable: true,
    align: 'right',
    className: 'payroll-money-cell',
    isMoney: true,
    hideWhenZero: true,
    totalAccessor: (line) => Number(line.overtimePay || 0) + Number(line.commissionPay || 0),
    sortAccessor: (line) => Number(line.overtimePay || 0) + Number(line.commissionPay || 0),
    render: (line) => <MoneyCode value={Number(line.overtimePay || 0) + Number(line.commissionPay || 0)} />,
  },
  {
    key: 'totalDeductions',
    header: 'เงินหัก',
    heroLabel: 'เงินหักรวม',
    heroAccessor: (period) => period?.totalDeductions,
    sortable: true,
    align: 'right',
    className: 'payroll-money-cell',
    isMoney: true,
    totalAccessor: (line) => Number(line.totalDeductions || 0),
    sortAccessor: (line) => Number(line.totalDeductions || 0),
    render: (line) => <MoneyCode value={line.totalDeductions} />,
  },
  {
    key: 'netPay',
    header: 'สุทธิ',
    heroLabel: 'ยอดโอนสุทธิ',
    heroAccessor: (period) => period?.totalNet,
    sortable: true,
    align: 'right',
    className: 'payroll-money-cell',
    isMoney: true,
    totalAccessor: (line) => Number(line.netPay || 0),
    sortAccessor: (line) => Number(line.netPay || 0),
    render: (line) => <MoneyCode value={line.netPay} />,
  },
];

function hasNonZeroMoney(rows, column) {
  if (!column.hideWhenZero) return true;
  return rows.some((line) => Number(column.totalAccessor?.(line) || 0) !== 0);
}

function toSatang(value) {
  const amount = Number(value || 0);
  if (!Number.isFinite(amount)) return 0;
  return Math.round(amount * 100);
}

function formatSatang(value) {
  return formatMoney(value / 100);
}

function sumPayrollSatang(rows, valueFor) {
  return rows.reduce((sum, row) => sum + toSatang(valueFor(row)), 0);
}

function buildPayrollReconciliation(rows, period) {
  const byColumn = Object.fromEntries(payrollMoneyColumns.map((column) => {
    const sumSatang = sumPayrollSatang(rows, column.totalAccessor);
    const heroSatang = column.heroAccessor ? toSatang(column.heroAccessor(period)) : null;
    return [column.key, {
      column,
      sumSatang,
      heroSatang,
      matchesHero: heroSatang == null || sumSatang === heroSatang,
      differenceSatang: heroSatang == null ? 0 : sumSatang - heroSatang,
    }];
  }));
  const mismatches = Object.values(byColumn).filter((item) => item.heroSatang != null && !item.matchesHero);
  return { byColumn, mismatches, rowCount: rows.length };
}

const thisMonth = new Date().toISOString().slice(0, 7);
const specialPayFields = [
  // Numbering ALIGNED to the accountant's workbook (2026.xlsx) on 2026-07-29 — see
  // PayrollService#specialPayDtos for why the system moved rather than the ledger.
  { key: 'specialPay1', label: 'พิเศษ 1 (ค่าครองชีพ)', defaultValue: '500' },
  { key: 'specialPay2', label: 'พิเศษ 2 (ค่าเช่าบ้าน)' },
  { key: 'specialPay3', label: 'พิเศษ 3 (เบี้ยเลี้ยงประจำ)' },
  { key: 'specialPay4', label: 'พิเศษ 4 (ค่าตำแหน่ง)' },
  { key: 'specialPay5', label: 'พิเศษ 5 (เบี้ยขยันประจำ)' },
  { key: 'specialPay6', label: 'พิเศษ 6 (ค่า GPRS)', defaultValue: '500' },
  { key: 'specialPay7', label: 'พิเศษ 7 (คอมมิชชั่น)' },
  { key: 'specialPay8', label: 'พิเศษ 8 (ทำได้ตาม KPI)' },
  { key: 'specialPay9', label: 'พิเศษ 9 (เงินรางวัล/เงินช่วยเหลืออื่นๆ)' },
];

// ค่าอาหาร and เบี้ยเลี้ยง (ตจว/ตปท) — V97. Real taxable columns in the accountant's ledger
// (2026.xlsx cols K and P) that the product had no field for until 2026-07-29. Not พิเศษ slots:
// the workbook doesn't number them as พิเศษ either.
const namedAllowanceFields = [
  { key: 'mealAllowance', label: 'ค่าอาหาร' },
  // เบี้ยเลี้ยง is entered as two amounts because มาตรา 42 exempts it only PARTIALLY — the portion
  // within the government rate is exempt, the excess is taxable. HR makes the split; the system
  // can't, without knowing destination and employee grade.
  { key: 'perDiemExempt', label: 'เบี้ยเลี้ยง — ส่วนที่ยกเว้นภาษี (ม.42)' },
  { key: 'perDiemTaxable', label: 'เบี้ยเลี้ยง — ส่วนเกิน (เสียภาษี)' },
];

// F2 fix (Opus review, 2026-07-30): the two มาตรา 42 limbs a เบี้ยเลี้ยง payment can be made under —
// see backend PerDiemBasis. Values must match that enum's names exactly; they are sent verbatim as
// `perDiemBasis` on the request. Required only when a per-diem amount (exempt or taxable) is actually
// entered — V97's chk_payroll_line_per_diem_basis_present CHECK otherwise rejects the Process insert.
const PER_DIEM_BASIS_OPTIONS = [
  { value: 'FLAT_RATE_S42_2', label: 'เหมาจ่ายตามอัตราราชการ (ม.42(2)) — ไม่ต้องมีใบเสร็จ' },
  { value: 'REIMBURSED_S42_1', label: 'จ่ายจริงตามหน้าที่ (ม.42(1)) — ต้องมีใบเสร็จ' },
];
// P1 fix (Opus review, 2026-07-30): เงินโบนัส / อื่นๆ — the dedicated one-off fields the backend has
// carried since V96 (task 2, 2026-07-29) with no input anywhere on this page. The spec calls
// bonusPay "the archetypal EXTRA_KNOWN_FREQUENCY" and names it explicitly in section 10's list of HR
// screens to build; until this fix HR could not pay a bonus through payroll at all.
const oneOffPayFields = [
  { key: 'bonusPay', label: 'เงินโบนัส' },
  { key: 'otherOneOffPay', label: 'เงินก้อนอื่นๆ (ครั้งเดียว)' },
];
// P1 fix (Opus review, 2026-07-30): which statutory cap applies to a legal-execution garnishment
// (handoff section 7 / backend PayrollGarnishmentType) — never selectable before this fix, so every
// garnishment silently used the SALARY cap (PayrollCalculator:746 defaults null to SALARY) even for a
// bonus, OT/diligence, or severance payout, making the BONUS 50% / OVERTIME 30% / SEVERANCE ฿300,000
// rules dead code. Values must match PayrollGarnishmentType's names exactly.
const GARNISHMENT_TYPE_OPTIONS = [
  { value: 'SALARY', label: 'เงินเดือน/ค่าจ้าง — สูงสุด 30% ต้องเหลือสุทธิ ≥ ฿20,000' },
  { value: 'BONUS', label: 'เงินโบนัส — สูงสุด 50% ของโบนัสงวดนี้' },
  { value: 'OVERTIME_OR_DILIGENCE', label: 'เบี้ยขยัน/ค่าล่วงเวลา — สูงสุด 30% ของยอดที่จ่ายงวดนี้' },
  { value: 'SEVERANCE', label: 'เงินตอบแทนกรณีออกจากงาน — ต้องเหลือสุทธิ ≥ ฿300,000' },
];
const specialPayKeys = specialPayFields.map((field) => field.key);
const namedAllowanceKeys = namedAllowanceFields.map((field) => field.key);
const oneOffPayKeys = oneOffPayFields.map((field) => field.key);
const incomeInputKeys = ['nonTaxableIncome'];
const deductionInputKeys = [
  'unpaidLeaveDays',
  'studentLoanDeduction',
  'legalExecutionDeduction',
  'otherPostTaxDeductions',
  // Reconciliation additions (2026-07-21, C4): the three pre-tax deductions the accountant's sheet
  // subtracts before tax (columns Z/AA/AB). Editable per run, like the other deduction inputs above —
  // unlike director remuneration below, which is fixed on the employee record.
  'warningLetterDeduction',
  'customerReturnDeduction',
  'otherPretaxDeduction',
];
// Daily-rate support (2026-07-30): days worked this period. Only meaningful for a pay_type = 'D'
// employee (hr.employee.current_salary is that employee's PER-DAY rate, not a monthly figure) --
// PayrollComponent.SALARY for such an employee is rate * daysWorked. One-off like bonusPay/
// otherOneOffPay above, re-entered fresh every run rather than carried forward, since a day count
// is specific to the month worked.
const dailyRateInputKeys = ['daysWorked'];
const payrollInputKeys = [
  ...specialPayKeys, ...namedAllowanceKeys, ...oneOffPayKeys, ...incomeInputKeys, ...deductionInputKeys,
  ...dailyRateInputKeys,
];

// P0 fix (Opus review, 2026-07-30): the withholding-tax classification matrix screen (spec sections 1
// and 10). Every non-SALARY, non-NON_TAXABLE_INCOME PayrollComponent HR can classify — labels mirror
// specialPayFields/namedAllowanceFields/oneOffPayFields above and PayrollComponent's own javadoc.
// SALARY is deliberately absent: it is locked to REGULAR_REPROJECT and never needs a stored row.
const TAX_TREATMENT_COMPONENTS = [
  { key: 'SPECIAL_PAY_1', label: 'พิเศษ 1 (ค่าครองชีพ)' },
  { key: 'SPECIAL_PAY_2', label: 'พิเศษ 2 (ค่าเช่าบ้าน)' },
  { key: 'SPECIAL_PAY_3', label: 'พิเศษ 3 (เบี้ยเลี้ยงประจำ)' },
  { key: 'SPECIAL_PAY_4', label: 'พิเศษ 4 (ค่าตำแหน่ง)' },
  { key: 'SPECIAL_PAY_5', label: 'พิเศษ 5 (เบี้ยขยันประจำ)' },
  { key: 'SPECIAL_PAY_6', label: 'พิเศษ 6 (ค่า GPRS)' },
  { key: 'SPECIAL_PAY_7', label: 'พิเศษ 7 (คอมมิชชั่น)' },
  { key: 'SPECIAL_PAY_8', label: 'พิเศษ 8 (ทำได้ตาม KPI)' },
  { key: 'SPECIAL_PAY_9', label: 'พิเศษ 9 (เงินรางวัล/เงินช่วยเหลืออื่นๆ)' },
  { key: 'OVERTIME_PAY', label: 'ค่าล่วงเวลา' },
  { key: 'COMMISSION_PAY', label: 'คอมมิชชั่น (ระบบคอมมิชชัน อัตโนมัติ)' },
  { key: 'MEAL_ALLOWANCE', label: 'ค่าอาหาร' },
  { key: 'PER_DIEM_TAXABLE', label: 'เบี้ยเลี้ยง — ส่วนเกิน (เสียภาษี)' },
  { key: 'BONUS_PAY', label: 'เงินโบนัส' },
  { key: 'OTHER_ONE_OFF_PAY', label: 'เงินก้อนอื่นๆ (ครั้งเดียว)' },
  { key: 'DIRECTOR_REMUNERATION', label: 'ค่าตอบแทนกรรมการ' },
];
// Values must match backend PayrollTaxTreatment's enum names exactly; '' means "not yet classified"
// (submits as null, resetting the stored row per PayrollRepository#upsertComponentTaxTreatment).
const TAX_TREATMENT_OPTIONS = [
  { value: '', label: 'ยังไม่จัดประเภท' },
  { value: 'REGULAR_REPROJECT', label: 'ประจำ (ข้อ 1(4)) — คำนวณใหม่ทุกงวดจากยอดที่จ่ายจริง' },
  { value: 'EXTRA_KNOWN_FREQUENCY', label: 'พิเศษทราบจำนวนครั้ง (ข้อ 1(5)) — เช่น โบนัสประจำปี' },
  { value: 'EXTRA_CUMULATIVE_ACTUAL', label: 'พิเศษไม่แน่นอน (ข้อ 1(6)) — เช่น OT, คอมมิชชันไม่ประจำ' },
];

function defaultSpecialPayValue(key, applyDefaults) {
  if (!applyDefaults) return '';
  return specialPayFields.find((field) => field.key === key)?.defaultValue ?? '';
}

function draftValue(value, fallback = '') {
  const amount = Number(value || 0);
  return amount > 0 ? String(amount) : fallback;
}

// Special-pay carry-forward (2026-07-23, extended to all 9 slots + meal allowance 2026-07-29): the
// recurring fields pre-filled from the employee's most recent prior processed payroll_line (via GET
// /api/payroll/suggested-inputs) when HR starts a brand-new run. Which of the nine specialPay slots
// actually carries is now PER EMPLOYEE, PER COMPONENT (hr.payroll_component_carry_forward, V98) —
// not a hardcoded slot exclusion here. An employee for whom a slot's carry_forward flag is off (or
// unset) simply gets 0 back from the suggestions endpoint for that key, same as any other
// non-recurring field. This is a client-side pre-fill convenience only: whatever value sits in the
// field when HR hits Preview/Process — carried, edited, or explicitly cleared to 0 — is submitted
// as-is via `payload()` below, same as today.
function indexSuggestionsByEmployee(rows) {
  const map = {};
  (rows || []).forEach((row) => {
    if (row && row.employeeId != null) map[row.employeeId] = row;
  });
  return map;
}

// A real carried figure from last month beats the hardcoded UAT demo default (500) below — the
// carried value is an actual prior figure, the hardcoded one is only a guess for when nothing else
// is known. Returns a numeric string, or null when there is nothing to carry for this key.
function suggestedFallback(suggestion, key) {
  if (!suggestion) return null;
  const amount = Number(suggestion[key] || 0);
  return amount > 0 ? String(amount) : null;
}

// Payroll input draft (2026-07-30): a saved-but-not-yet-processed value HR typed earlier this
// session (or a prior one) beats a mere carried-forward guess -- it is HR's own actual work, not a
// suggestion.
//
// DELIBERATELY NOT the same collapsing rule as suggestedFallback above. A suggestion is only ever
// consulted to fill in a genuinely blank/zero field, so collapsing "suggested 0" to "no
// suggestion" is harmless -- a suggested zero and no suggestion produce the identical outcome
// either way. A DRAFT is different: it is saved as a COMPLETE snapshot of the whole form for that
// employee (saveDraft() submits every field via normalizedAdjustment, not just the ones HR
// touched), so once ANY draft row exists for an employee, every field in it -- including one HR
// deliberately cleared down to zero -- is exactly what they saw on screen when they hit บันทึกร่าง,
// and must win over the suggestion. Collapsing that zero to null here would let a stale
// carry-forward suggestion silently resurrect over a value HR explicitly zeroed out (e.g. clearing
// a carried เบี้ยขยันประจำ that should not repeat this month) -- the bug this comment replaces.
//
// So: return '' (not null) for "draft row exists, field is 0" -- a real, non-nullish value the
// `??` chain at every call site will NOT fall through past, while still rendering as blank (not a
// literal "0"), preserving the "0 and blank read the same" DISPLAY convention every other field on
// this form already follows (see draftValue). Return null ONLY when there is no draft row at all
// for this employee (they have never saved one this session) -- that is the one case where falling
// through to the suggestion is correct.
//
// No field-level "was this key actually present" tracking is needed (the alternative the review
// suggested): a draft row is written wholesale, all fields at once, so presence is naturally an
// EMPLOYEE-level fact (does a row exist for employee+month), not a per-field one -- there is no
// saved-draft state where some fields are "there" and others are structurally missing.
function draftFallback(draft, key) {
  if (!draft) return null;
  const amount = Number(draft[key] || 0);
  return amount > 0 ? String(amount) : '';
}

// Withholding-tax override (V88) is NULLABLE/meaningful, so it cannot ride the generic
// draftValue/parsePayrollNumber machinery (which collapses ''<->0). A stored/carried 0 is a real
// override (withhold nothing) and must round-trip as "0"; only null/'' means "no override".
// Priority: the line's own persisted per-run value > a saved draft (2026-07-30) > last month's
// carried per-run value > blank. The standing employee override is deliberately NOT surfaced here
// — it re-applies server-side.
function overrideDraft(lineValue, suggestion, draft) {
  if (lineValue != null) return String(lineValue);
  if (draft && draft.withholdingTaxOverride != null) return String(draft.withholdingTaxOverride);
  if (suggestion && suggestion.withholdingTaxOverride != null) return String(suggestion.withholdingTaxOverride);
  return '';
}

function parsePayrollNumber(value) {
  if (value === '' || value === null || value === undefined) return 0;
  const amount = Number(value);
  return Number.isFinite(amount) ? amount : 0;
}

function blankAdjustment(employeeId, { applyDefaults = false } = {}) {
  return {
    employeeId,
    ...Object.fromEntries(specialPayKeys.map((key) => [key, defaultSpecialPayValue(key, applyDefaults)])),
    nonTaxableIncome: '',
    unpaidLeaveDays: '',
    studentLoanDeduction: '',
    legalExecutionDeduction: '',
    otherPostTaxDeductions: '',
    warningLetterDeduction: '',
    customerReturnDeduction: '',
    otherPretaxDeduction: '',
    // F2 fix (Opus review, 2026-07-30): ค่าอาหาร/เบี้ยเลี้ยง (V97) were defined in
    // namedAllowanceFields/payrollInputKeys but never actually defaulted or rendered -- HR had no way
    // to enter them at all. mealAllowance/perDiemExempt/perDiemTaxable are plain numeric fields like
    // the special-pay slots above; perDiemBasis is a nullable enum ('' = not yet chosen), same
    // treatment as withholdingTaxOverride below -- kept out of the numeric payrollInputKeys.
    mealAllowance: '',
    perDiemExempt: '',
    perDiemTaxable: '',
    perDiemBasis: '',
    // Daily-rate support (2026-07-30): one-off like bonusPay/otherOneOffPay above -- never carried
    // forward, re-entered fresh every run.
    daysWorked: '',
    // P1 fix (Opus review, 2026-07-30): เงินโบนัส/เงินก้อนอื่นๆ — plain numeric fields like the
    // special-pay slots above.
    bonusPay: '',
    otherOneOffPay: '',
    // P1 fix (Opus review, 2026-07-30): garnishmentType is a nullable enum ('' = not chosen, backend
    // defaults to SALARY), same treatment as perDiemBasis above. customerReturnAlreadyEarned is a
    // plain boolean (default false = "not yet earned", the pre-tax netting path) -- see
    // PayrollCalculator#calculateClassified's own comment on the flag for what each value means.
    garnishmentType: '',
    customerReturnAlreadyEarned: false,
    // Nullable per-run withholding override ('' = compute/standing). Kept out of the numeric keys below.
    withholdingTaxOverride: '',
  };
}

function adjustmentFromLine(line, { applyDefaults = false, suggestion = null, draft = null } = {}) {
  const adjustment = blankAdjustment(line.employeeId, { applyDefaults });
  (line.specialPays || []).forEach((item, index) => {
    const key = `specialPay${index + 1}`;
    // Priority: the line's own real value (already-submitted/persisted) > a saved draft
    // (2026-07-30, HR's own not-yet-processed work) > a carried figure from last month > the
    // hardcoded UAT demo default. A draft/suggestion only ever fills in for a genuinely blank/zero
    // field on a fresh run.
    const fallback = draftFallback(draft, key) ?? suggestedFallback(suggestion, key) ?? defaultSpecialPayValue(key, applyDefaults);
    adjustment[key] = draftValue(item.amount, fallback);
  });
  adjustment.nonTaxableIncome = draftValue(
    line.nonTaxableIncome,
    draftFallback(draft, 'nonTaxableIncome') ?? suggestedFallback(suggestion, 'nonTaxableIncome') ?? '');
  // Leave -> payroll unpaid-day deduction (2026-07-23): unpaidLeaveDays is event-driven (this
  // month's approved-beyond-quota leave, from GET /api/payroll/suggested-inputs), unlike the other
  // carried fields above which are prior-month recurring amounts -- but it pre-fills the same way:
  // a real line value (already-submitted/persisted) wins, otherwise a saved draft, otherwise the
  // suggestion. HR can still edit/clear it before Preview/Process, same as every other field here.
  adjustment.unpaidLeaveDays = draftValue(
    line.unpaidLeaveDays,
    draftFallback(draft, 'unpaidLeaveDays') ?? suggestedFallback(suggestion, 'unpaidLeaveDays') ?? '');
  adjustment.studentLoanDeduction = draftValue(
    line.studentLoanDeduction,
    draftFallback(draft, 'studentLoanDeduction') ?? suggestedFallback(suggestion, 'studentLoanDeduction') ?? '');
  adjustment.legalExecutionDeduction = draftValue(
    line.legalExecutionDeduction,
    draftFallback(draft, 'legalExecutionDeduction') ?? suggestedFallback(suggestion, 'legalExecutionDeduction') ?? '');
  adjustment.otherPostTaxDeductions = draftValue(line.otherPostTaxDeductions, draftFallback(draft, 'otherPostTaxDeductions') ?? '');
  adjustment.warningLetterDeduction = draftValue(line.warningLetterDeduction, draftFallback(draft, 'warningLetterDeduction') ?? '');
  // D1 fix (fourth reachability audit, 2026-07-30): hydrate from customerReturnRequested, NOT
  // customerReturnDeduction. The latter is the POST-TAX bookkeeping figure the backend persists --
  // it is 0 whenever customerReturnAlreadyEarned is false (the unearned amount is instead netted
  // pre-tax out of commission), so reading it back here silently wiped the form field AND the
  // checkbox below (which gates on this value being > 0) on every reload, and a reprocess of the
  // same month silently stopped re-applying the netting. customerReturnRequested always echoes what
  // was actually typed, regardless of the earned flag.
  adjustment.customerReturnDeduction = draftValue(line.customerReturnRequested, draftFallback(draft, 'customerReturnDeduction') ?? '');
  adjustment.otherPretaxDeduction = draftValue(line.otherPretaxDeduction, draftFallback(draft, 'otherPretaxDeduction') ?? '');
  // ค่าอาหาร carries forward like the other recurring fields above; the two เบี้ยเลี้ยง amounts and
  // the basis do not (V98's carry-forward flags only cover mealAllowance for this group -- see the
  // task-3 handoff fix 5) -- a per-diem is trip-driven, not a standing monthly figure, so re-entering
  // it (and its basis) fresh each run is correct, not a gap. A saved draft still fills in for all
  // three, same as it does for every other field here.
  adjustment.mealAllowance = draftValue(
    line.mealAllowance,
    draftFallback(draft, 'mealAllowance') ?? suggestedFallback(suggestion, 'mealAllowance') ?? '');
  adjustment.perDiemExempt = draftValue(line.perDiemExempt, draftFallback(draft, 'perDiemExempt') ?? '');
  adjustment.perDiemTaxable = draftValue(line.perDiemTaxable, draftFallback(draft, 'perDiemTaxable') ?? '');
  adjustment.perDiemBasis = line.perDiemBasis || draft?.perDiemBasis || '';
  // P1 fix (Opus review, 2026-07-30): bonusPay/otherOneOffPay do not carry forward (they are one-off
  // by definition, same reasoning as perDiemExempt/perDiemTaxable above) -- re-entered fresh each
  // run, though a saved draft still fills in.
  adjustment.bonusPay = draftValue(line.bonusPay, draftFallback(draft, 'bonusPay') ?? '');
  adjustment.otherOneOffPay = draftValue(line.otherOneOffPay, draftFallback(draft, 'otherOneOffPay') ?? '');
  // Daily-rate support (2026-07-30): survives a reload -- hydrate from the persisted line's own
  // daysWorked first, else a saved draft (2026-07-30). No carry-forward suggestion (a day count is
  // specific to the month worked, not a recurring figure).
  adjustment.daysWorked = draftValue(line.daysWorked, draftFallback(draft, 'daysWorked') ?? '');
  adjustment.garnishmentType = line.garnishmentType || draft?.garnishmentType || '';
  adjustment.customerReturnAlreadyEarned = Boolean(line.customerReturnAlreadyEarned) || Boolean(draft?.customerReturnAlreadyEarned);
  adjustment.withholdingTaxOverride = overrideDraft(line.withholdingTaxOverride, suggestion, draft);
  return adjustment;
}

function withholdingOverrideValue(input) {
  const raw = input.withholdingTaxOverride;
  return raw === '' || raw == null ? null : Number(raw);
}

// F2 fix (Opus review, 2026-07-30): perDiemBasis is a nullable enum, not an amount -- same reasoning
// as withholdingOverrideValue above. '' (never chosen) submits as null; a chosen value submits as its
// PerDiemBasis name verbatim (matches th.co.glr.hr.payroll.PerDiemBasis exactly).
function perDiemBasisValue(input) {
  const raw = input.perDiemBasis;
  return raw === '' || raw == null ? null : raw;
}

// P1 fix (Opus review, 2026-07-30): garnishmentType is a nullable enum, same reasoning as
// perDiemBasisValue above. '' (never chosen) submits as null, which PayrollEmployeeInputRequest's own
// javadoc says the backend defaults to SALARY -- reproducing the pre-existing single-rule behaviour
// exactly for a run where HR does not pick a type.
function garnishmentTypeValue(input) {
  const raw = input.garnishmentType;
  return raw === '' || raw == null ? null : raw;
}

function normalizedAdjustment(input) {
  return {
    employeeId: input.employeeId,
    ...Object.fromEntries(payrollInputKeys.map((key) => [key, parsePayrollNumber(input[key])])),
    // Nullable: '' -> null ("compute/standing"), a number (incl. 0) -> that override. Kept separate
    // from the numeric keys above precisely so a cleared field stays null instead of collapsing to 0.
    withholdingTaxOverride: withholdingOverrideValue(input),
    // F2 fix (Opus review, 2026-07-30): nullable enum, same reasoning as withholdingTaxOverride above.
    perDiemBasis: perDiemBasisValue(input),
    // P1 fix (Opus review, 2026-07-30): nullable enum (garnishmentType) and a plain boolean
    // (customerReturnAlreadyEarned) -- neither belongs in the numeric payrollInputKeys spread above.
    garnishmentType: garnishmentTypeValue(input),
    customerReturnAlreadyEarned: Boolean(input.customerReturnAlreadyEarned),
  };
}

function hasPayrollInput(input) {
  // An override-only adjustment must still be submitted -- especially an override of 0 (withhold
  // nothing), which the numeric-key check below would treat as "no input" and drop.
  if (withholdingOverrideValue(input) != null) return true;
  return payrollInputKeys.some((key) => parsePayrollNumber(input[key]) > 0);
}

function ReconciliationNote({ item }) {
  if (!item?.column?.heroLabel) return null;
  if (item.matchesHero) {
    return <small className="payroll-reconcile-note is-ok">ตรงกับ{item.column.heroLabel}</small>;
  }
  return (
    <small className="payroll-reconcile-note is-mismatch">
      ไม่ตรงกับ{item.column.heroLabel}: {formatSatang(item.heroSatang)}
    </small>
  );
}

function PayrollTotalsRow({ columns, reconciliation }) {
  return (
    <tr className="payroll-table payroll-total-row">
      {columns.map((column) => {
        if (column.key === 'employee') {
          return (
            <th key={column.key} scope="row">
              <span>รวมทั้งงวด</span>
              <small>{reconciliation.rowCount} คน</small>
            </th>
          );
        }
        const item = reconciliation.byColumn[column.key];
        return (
          <td key={column.key} className="text-right payroll-money-cell" data-label={column.header}>
            <code className="payroll-money">{formatSatang(item.sumSatang)}</code>
            <ReconciliationNote item={item} />
          </td>
        );
      })}
    </tr>
  );
}

function PayrollMobileSummaryRow({ reconciliation, columnCount }) {
  const gross = reconciliation.byColumn.grossEarnings;
  const deductions = reconciliation.byColumn.totalDeductions;
  const net = reconciliation.byColumn.netPay;
  const hasMismatch = reconciliation.mismatches.length > 0;
  return (
    <tr className="payroll-mobile-summary-row">
      <td colSpan={columnCount}>
        <div className={`payroll-mobile-summary-bar${hasMismatch ? ' is-mismatch' : ''}`}>
          <span>
            <small>รายได้</small>
            <b>{formatSatang(gross.sumSatang)}</b>
          </span>
          <span>
            <small>หัก</small>
            <b>{formatSatang(deductions.sumSatang)}</b>
          </span>
          <span>
            <small>สุทธิ</small>
            <b>{formatSatang(net.sumSatang)}</b>
          </span>
        </div>
      </td>
    </tr>
  );
}

function PayrollTotalsFooter({ columns, reconciliation }) {
  return (
    <>
      <PayrollTotalsRow columns={columns} reconciliation={reconciliation} />
      <PayrollMobileSummaryRow reconciliation={reconciliation} columnCount={columns.length} />
    </>
  );
}

function PayrollReconciliationAlert({ mismatches }) {
  if (mismatches.length === 0) return null;
  return (
    <div className="payroll-reconciliation-alert" role="alert">
      <Icon name="triangleAlert" size={16} />
      <span>
        ยอดรวมไม่ตรงกับสรุปด้านบน:
        {' '}
        {mismatches.map((item) => `${item.column.header} ${formatSatang(item.sumSatang)} ≠ ${formatSatang(item.heroSatang)}`).join(' · ')}
      </span>
    </div>
  );
}

function downloadBlob(blob, filename) {
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = filename;
  anchor.click();
  URL.revokeObjectURL(url);
}

export function PayrollPage({ showToast }) {
  const isMobile = useIsMobile();
  const [month, setMonth] = useState(thisMonth);
  const [period, setPeriod] = useState(null);
  const [adjustments, setAdjustments] = useState({});
  const [selectedEmployeeId, setSelectedEmployeeId] = useState(null);
  const [detailOpen, setDetailOpen] = useState(false);
  // B1 fix (Opus review, 2026-07-31): the detail panel has exactly two presentations, not three.
  // >=1440px is a persistent side panel next to the table; below that it is the fixed slide-over
  // overlay tablet already used. The query is the JS-side mirror of the CSS `payroll-wide:` custom
  // variant (`min-width: 1440px`, see index.css's `@custom-variant`) so the two can never disagree
  // about which mode is active.
  //
  // Item 1 fix (2026-07-31): this used to be Tailwind's own built-in `xl:` breakpoint (1280px,
  // `--breakpoint-xl: 80rem` in tailwindcss/theme.css) on the theory that was the panel's
  // content-fit floor. Measured live at :5211 with the panel open, it was not: at 1280px the table
  // needed 714px against ~598px actually available beside a 340px panel, hiding 116px -- the
  // money-critical "สุทธิ" (net pay) column -- behind a horizontal scrollbar. See index.css's
  // `payroll-wide` comment for the full measurement and why 1440px (not the bare ~1397-1400px
  // crossover, or the ~1360px first estimate) is the number used.
  const isDesktopPanel = useMediaQuery('(min-width: 1440px)');
  const isOverlayPanel = !isDesktopPanel;
  const detailPanelRef = useRef(null);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [confirmProcess, setConfirmProcess] = useState(false);
  // Leave -> payroll unpaid-day deduction (2026-07-23): the raw suggestions response, kept only so
  // the pending cancel-after-close correction credit (if any) can be shown as a hint next to the
  // unpaidLeaveDays field. Never re-applied to `adjustments` after the initial pre-fill in
  // applyPeriod -- HR's edits always win.
  const [suggestionsByEmployee, setSuggestionsByEmployee] = useState({});
  const [exportKind, setExportKind] = useState('kbank');
  const [payDate, setPayDate] = useState(`${thisMonth}-26`);
  // Payroll input draft (2026-07-30): true once HR has typed something since the last load/save --
  // a browser reload before this is saved still loses that in-between typing (the draft only
  // persists what was explicitly saved), so this is surfaced next to the Save Draft button rather
  // than silently autosaving.
  const [draftDirty, setDraftDirty] = useState(false);

  const selectedLine = useMemo(
    () => (period?.lines || []).find((line) => Number(line.employeeId) === Number(selectedEmployeeId)) || period?.lines?.[0] || null,
    [period, selectedEmployeeId],
  );
  const selectedAdjustment = selectedLine
    ? adjustments[selectedLine.employeeId] || adjustmentFromLine(selectedLine)
    : null;
  const selectedSuggestion = selectedLine ? suggestionsByEmployee[selectedLine.employeeId] : null;
  const pendingUnpaidLeaveCorrectionDays = Number(selectedSuggestion?.pendingUnpaidLeaveCorrectionDays || 0);
  const status = statusInfo(period?.status);
  // F1: guard the irreversible "Process Payroll" action against an empty period. PayrollService
  // #process (backend) does NOT need a period id -- it recomputes from
  // payrollRepository.findActiveEmployees() and calls saveProcessedPeriod regardless. If no period
  // was ever loaded/previewed for this month, `adjustments` is `{}`, so `payload().inputs` is `[]`
  // -- the backend still recomputes system-derived figures, but every HR-entered value (8 special
  // pays, non-taxable income, student loan, legal execution, unpaid-leave days, withholding
  // override) commits as zero. Worse, `status = 'PROCESSED'` is one-way (PayrollRepository only ever
  // writes PROCESSED, there is no un-process path) and OvertimeService#requirePayrollMonthOpen then
  // refuses OT approval for that month.
  //
  // MUST guard on `!(period?.lineCount > 0)`, NOT `!period?.id`: PayrollService#preview builds its
  // DTO with `id: null`, and `currentOrPreview` (backend) falls back to preview for any month with
  // no saved row -- so `period.id` is null for EVERY month that has never been processed, i.e. the
  // normal first run. Guarding on id would make first-time processing impossible. That is exactly
  // why the other action groups below (export/send) can key off `!period?.id` (they require an
  // already-*saved* period) and this one cannot. See PayrollPage.test.jsx's
  // "enables Process Payroll for a fresh (never-processed) period ... id === null" test, which pins
  // this down as the regression guard against "simplifying" it back to `!period?.id`.
  const emptyPeriod = !period || !(period.lineCount > 0);
  // Payroll input draft (2026-07-30): a draft is only ever consulted on load() for a brand-new,
  // never-touched run (same condition as the suggested-inputs/draft fetch in load() above) -- once
  // a period is PROCESSED, or VOID with real pre-loaded lines (like July's), the line's own value
  // is already the source of truth and always wins regardless, so saving a draft here would do
  // nothing useful. Hidden rather than shown-but-inert, so HR is not misled into thinking a save
  // did something for an already-touched period.
  const canSaveDraft = period?.status === 'PREVIEW' && !period?.id;
  const processBlockedReason = isMobile
    ? 'ปิดใช้งานบนหน้าจอมือถือ — การประมวลผลเงินเดือนย้อนกลับไม่ได้ กรุณาใช้เดสก์ท็อป'
    : emptyPeriod
      ? 'ยังไม่มีพนักงานในรอบเงินเดือนนี้ — กดคำนวณตัวอย่างหรือเลือกรอบเดือนที่มีข้อมูลก่อนประมวลผล'
      : null;

  function selectPayrollLine(line) {
    setSelectedEmployeeId(line.employeeId);
    setDetailOpen(true);
  }

  function closeDetailPanel() {
    setDetailOpen(false);
  }

  // B2 fix (Opus review, 2026-07-31): focus trap + Escape-to-close + focus-restoration, shared with
  // Modal.jsx via useDialogFocus (see that hook's own comment). `active` is false whenever the panel
  // is the >=1440px persistent side panel rather than the <1440px overlay -- a side panel must not
  // steal Tab or respond to Escape, since the rest of the page beside it is not actually hidden.
  useDialogFocus({ active: isOverlayPanel && detailOpen, containerRef: detailPanelRef, onClose: closeDetailPanel });

  async function load() {
    setLoading(true);
    try {
      const response = await api.payroll.current({ payrollMonth: month });
      const nextPeriod = response.period;
      // Special-pay carry-forward, and payroll input draft (2026-07-30): only fetch/apply either
      // when starting a fresh run for this month (PREVIEW with no processed/void period yet) — an
      // already-touched period (PROCESSED, or VOID like July's pre-loaded real inputs) already
      // reflects real, submitted values in the line itself and must never be overwritten by a
      // stale suggestion OR a stale draft. See adjustmentFromLine's precedence: the line's own
      // value always wins first regardless.
      let suggestionsByEmployee = {};
      let draftByEmployee = {};
      if (nextPeriod?.status === 'PREVIEW' && !nextPeriod?.id) {
        try {
          // Optional chaining keeps this resilient if a caller's api.payroll mock predates this
          // method (e.g. an older test double) — suggestions are a convenience pre-fill only and
          // must never block payroll from loading.
          const suggestionResponse = await api.payroll.suggestedInputs?.({ payrollMonth: month });
          suggestionsByEmployee = indexSuggestionsByEmployee(suggestionResponse?.suggestions);
        } catch {
          suggestionsByEmployee = {};
        }
        try {
          // Same resilience as suggestedInputs above -- a draft is a convenience restore only and
          // must never block payroll from loading.
          const draftResponse = await api.payroll.getInputDraft?.({ payrollMonth: month });
          draftByEmployee = indexSuggestionsByEmployee(draftResponse?.drafts);
        } catch {
          draftByEmployee = {};
        }
      }
      setSuggestionsByEmployee(suggestionsByEmployee);
      setDraftDirty(false);
      applyPeriod(nextPeriod, { applyUatDefaults: true, suggestionsByEmployee, draftByEmployee });
    } catch (error) {
      showToast('error', error.message || 'โหลดเงินเดือนไม่สำเร็จ');
    } finally {
      setLoading(false);
    }
  }

  function applyPeriod(nextPeriod, { applyUatDefaults = false, suggestionsByEmployee = {}, draftByEmployee = {} } = {}) {
    setPeriod(nextPeriod);
    const nextAdjustments = {};
    const applyDefaults = applyUatDefaults && nextPeriod?.status === 'PREVIEW';
    (nextPeriod?.lines || []).forEach((line) => {
      nextAdjustments[line.employeeId] = adjustmentFromLine(line, {
        applyDefaults,
        suggestion: suggestionsByEmployee[line.employeeId],
        draft: draftByEmployee[line.employeeId],
      });
    });
    setAdjustments(nextAdjustments);
    setSelectedEmployeeId((current) => current || nextPeriod?.lines?.[0]?.employeeId || null);
  }

  useEffect(() => {
    setSelectedEmployeeId(null);
    setDetailOpen(false);
    setPayDate(`${month}-26`); // default salary pay date is the 26th of the selected month
    load();
  }, [month]);

  function payload() {
    return {
      payrollMonth: `${month}-01`,
      inputs: Object.values(adjustments).map(normalizedAdjustment).filter(hasPayrollInput),
    };
  }

  async function preview() {
    setSaving(true);
    try {
      const response = await api.payroll.preview(payload());
      applyPeriod(response.period);
      showToast('success', 'คำนวณตัวอย่างเงินเดือนแล้ว');
    } catch (error) {
      showToast('error', error.message || 'คำนวณเงินเดือนไม่สำเร็จ');
    } finally {
      setSaving(false);
    }
  }

  // Live refresh (2026-07-24): recompute every system-derived figure — commission, overtime,
  // leave refund, director remuneration, tax/SSO — from current data and show it WITHOUT
  // committing. This differs from load()/api.payroll.current, which for an already-PROCESSED month
  // returns the saved snapshot (e.g. commission frozen from when it was processed) and so never
  // reflects data that changed afterwards. HR's on-screen inputs are preserved (payload() carries
  // them through); nothing is locked until "Process Payroll".
  async function refresh() {
    setSaving(true);
    try {
      const response = await api.payroll.preview(payload());
      applyPeriod(response.period);
      showToast('success', 'อัปเดตข้อมูลล่าสุดแล้ว (ยังไม่ได้ประมวลผล)');
    } catch (error) {
      showToast('error', error.message || 'รีเฟรชข้อมูลไม่สำเร็จ');
    } finally {
      setSaving(false);
    }
  }

  // Payroll input draft (2026-07-30): explicit save (not autosave, per the owner's steer) of
  // whatever is currently typed, so a browser reload (or a same-account login elsewhere) restores
  // it. Unfiltered by hasPayrollInput -- unlike payload() above, a draft must also capture an
  // employee HR has started but not finished (e.g. only a garnishment type picked with no amount
  // yet), not just what already qualifies for submission.
  async function saveDraft() {
    setSaving(true);
    try {
      await api.payroll.saveInputDraft({
        payrollMonth: `${month}-01`,
        inputs: Object.values(adjustments).map(normalizedAdjustment),
      });
      setDraftDirty(false);
      showToast('success', 'บันทึกร่างข้อมูลเงินเดือนแล้ว');
    } catch (error) {
      showToast('error', error.message || 'บันทึกร่างไม่สำเร็จ');
    } finally {
      setSaving(false);
    }
  }

  function process() {
    setConfirmProcess(true);
  }

  async function confirmProcessPayroll() {
    setSaving(true);
    try {
      const response = await api.payroll.process(payload());
      applyPeriod(response.period);
      // The backend clears hr.payroll_input_draft for this month once processed (see
      // PayrollService#process) -- there is nothing left to save a draft over now.
      setDraftDirty(false);
      showToast('success', 'ประมวลผลเงินเดือนเรียบร้อย');
      setConfirmProcess(false);
    } catch (error) {
      showToast('error', error.message || 'ประมวลผลเงินเดือนไม่สำเร็จ');
    } finally {
      setSaving(false);
    }
  }

  async function generateExportFile() {
    const kind = EXPORT_KINDS.find((item) => item.value === exportKind) || EXPORT_KINDS[0];
    const isDetailKind = kind.value === 'payroll-detail';
    // KBank/PND1/SSO genuinely need a processed, paid period -- unchanged. payroll-detail must
    // also work for a month that has never been processed (owner requirement, 2026-07-30: "July
    // 2026 is live and still unprocessed... HR's whole reason to want this file is to review the
    // month BEFORE committing it"), so it only requires SOME period to be loaded (lineCount > 0),
    // not a persisted id.
    if (isDetailKind ? !(period?.lineCount > 0) : !period?.id) return;
    setSaving(true);
    try {
      // The three statutory kinds return raw CP874 bytes; payroll-detail returns a real xlsx
      // workbook. Either way the result is fetched as a binary blob so the bytes survive the
      // download intact. payDate is the salary pay/transfer date (payroll-detail just stamps it
      // into the filename).
      const blob = period?.id
        ? await api.payroll.exportFile(period.id, kind.value, payDate || undefined)
        // No persisted period (a fresh, unprocessed month) -- POST the same payrollMonth/inputs
        // payload the Preview/Process buttons already send, so the workbook's figures are
        // guaranteed to equal whatever the on-screen preview shows for the same inputs.
        : await api.payroll.exportPreviewFile(payload(), kind.value, payDate || undefined);
      downloadBlob(blob, `${kind.filePrefix}-${month}.${kind.extension}`);
      showToast('success', `ดาวน์โหลดไฟล์ ${kind.label} แล้ว`);
    } catch (error) {
      showToast('error', error.message || 'ดาวน์โหลดไฟล์ไม่สำเร็จ');
    } finally {
      setSaving(false);
    }
  }

  async function downloadPayslipsZip() {
    if (!period?.id || period?.status !== 'PROCESSED') return;
    setSaving(true);
    try {
      const blob = await api.payroll.downloadPayslipsZip(period.id);
      downloadBlob(blob, `glr-payslips-${month}.zip`);
      showToast('success', 'ดาวน์โหลดสลิปเงินเดือนทั้งหมดแล้ว');
    } catch (error) {
      showToast('error', error.message || 'ดาวน์โหลดสลิปเงินเดือนทั้งหมดไม่สำเร็จ');
    } finally {
      setSaving(false);
    }
  }

  const periodLines = useMemo(() => period?.lines || [], [period?.lines]);
  const payrollReconciliation = useMemo(
    () => buildPayrollReconciliation(periodLines, period),
    [periodLines, period],
  );
  const employeeColumn = useMemo(() => createEmployeeColumn(selectedLine?.employeeId), [selectedLine?.employeeId]);
  const visiblePayrollColumns = useMemo(
    () => [
      employeeColumn,
      ...payrollMoneyColumns.filter((column) => hasNonZeroMoney(periodLines, column)),
    ],
    [employeeColumn, periodLines],
  );
  const visibleMoneyColumnCount = visiblePayrollColumns.filter((column) => column.isMoney).length;
  const payrollGridColumns = useMemo(
    () => [
      'minmax(150px, 1.1fr)',
      ...Array.from({ length: visibleMoneyColumnCount }, () => 'minmax(7.5rem, 0.86fr)'),
    ].join(' '),
    [visibleMoneyColumnCount],
  );

  const columns = visiblePayrollColumns;

  async function downloadPayslip(line) {
    if (!period?.id || !line?.id) return;
    setSaving(true);
    try {
      const blob = await api.payroll.downloadPayslip(period.id, line.id);
      downloadBlob(blob, `glr-payslip-${month}-${line.employeeCode}.pdf`);
      showToast('success', 'ดาวน์โหลดสลิปเงินเดือนแล้ว');
    } catch (error) {
      showToast('error', error.message || 'ดาวน์โหลดสลิปเงินเดือนไม่สำเร็จ');
    } finally {
      setSaving(false);
    }
  }

  async function distributePayslips() {
    if (!period?.id) return;
    setSaving(true);
    try {
      const response = await api.payroll.distributePayslips(period.id);
      showToast('success', `เริ่มส่งอีเมลสลิปเงินเดือนแล้ว (${response.queued || 0} รายการ, ส่งแล้ว ${response.alreadySent || 0})`);
    } catch (error) {
      showToast('error', error.message || 'ส่งอีเมลสลิปเงินเดือนไม่สำเร็จ');
    } finally {
      setSaving(false);
    }
  }

  function updateAdjustment(field, value) {
    if (!selectedLine) return;
    setAdjustments((current) => ({
      ...current,
      [selectedLine.employeeId]: {
        ...(current[selectedLine.employeeId] || blankAdjustment(selectedLine.employeeId)),
        [field]: value,
      },
    }));
    // Payroll input draft (2026-07-30): any typed change is not yet saved anywhere durable until
    // HR explicitly hits Save Draft (or Process, which persists it for real).
    setDraftDirty(true);
  }

  return (
    <PageStack>
      {isMobile && <DesktopOnlyNotice />}
      <PageHeader
        title="ประมวลผลเงินเดือน"
        subtitle="Payroll Processing"
        actions={(
          <div className="toolbar-actions">
            <label>
              รอบเดือน
              <input type="month" value={month} onChange={(event) => setMonth(event.target.value)} />
              {/* The native <input type="month"> always renders Gregorian and cannot show Buddhist
                  era; this is a read-only display of the same value, not a second control. */}
              <small className="font-normal text-text-muted">({formatThaiMonthYearFromMonthInputValue(month)})</small>
            </label>
            <Button type="button" variant="secondary" onClick={refresh} disabled={loading || saving} title="ดึงข้อมูลล่าสุด (คอมมิชชัน, OT, วันลา, ค่าตอบแทนกรรมการ) เข้ามาคำนวณใหม่ โดยยังไม่ประมวลผล">
              <Icon name="refresh" />
              รีเฟรช
            </Button>
            {/* Payroll input draft (2026-07-30): explicit save, not autosave (owner's steer) -- HR's
                in-progress inputs for a brand-new run had no persistent home before this and vanished
                on a browser reload. Hidden once the period is no longer a fresh, never-touched run
                (see canSaveDraft's own comment) -- saving one then would do nothing useful. */}
            {canSaveDraft && (
              <span className="inline-flex items-center gap-2">
                <Button type="button" variant="secondary" onClick={saveDraft} disabled={loading || saving} title="บันทึกข้อมูลที่กรอกไว้ชั่วคราว (ยังไม่ประมวลผล) เพื่อไม่ให้หายเมื่อโหลดหน้าใหม่">
                  <Icon name="save" />
                  บันทึกร่าง
                </Button>
                {draftDirty && (
                  <small className="text-xs font-semibold text-text-muted">มีการเปลี่ยนแปลงที่ยังไม่บันทึกร่าง</small>
                )}
              </span>
            )}
          </div>
        )}
      />

      <CompactStatRow
        items={[
          { key: 'gross', label: 'รายได้รวม', value: formatMoney(period?.totalGross), helper: 'Gross earnings' },
          { key: 'deductions', label: 'เงินหักรวม', value: formatMoney(period?.totalDeductions), helper: 'Deductions' },
          { key: 'net', label: 'ยอดโอนสุทธิ', value: formatMoney(period?.totalNet), helper: 'Net transfer' },
          { key: 'tax', label: 'ภาษี/ปกส.', value: formatMoney(Number(period?.totalWithholdingTax || 0) + Number(period?.totalSocialSecurity || 0)), helper: 'Tax + SSO' },
        ]}
      />

      {/* F2: `.payroll-actions`'s own `flex-direction: column; align-items: stretch; gap: 14px;` rule
          was dead — Tailwind utilities in `layer(utilities)` always beat `styles.css` in
          `layer(legacy)` (see index.css), and FilterBar hardcodes `flex flex-wrap gap-[10px]
          items-center`. Only `flex-direction: column` had no competing utility and actually applied;
          `align-items`/`gap` silently lost, so the status row and action groups shrank to content
          width and centred 10px apart instead of spanning full-width at 14px. Fixed at the call site
          (Tailwind-first per CLAUDE.md) — `cn` is `twMerge`, so these override FilterBar's base
          classes cleanly instead of fighting them. The dead rule was deleted from styles.css. */}
      <FilterBar className="payroll-actions flex-col items-stretch gap-[14px]">
        <div className="payroll-actions-groups">
          {/* Review — safe, read-only recompute. */}
          <div className="payroll-actions-group">
            <Button type="button" onClick={preview} disabled={loading || saving}>
              <Icon name="search" />
              คำนวณตัวอย่าง
            </Button>
          </div>

          {/* Export — statutory file generation, plus the detail xlsx which also works pre-process.
              Run (ประมวลผลเงินเดือน) is deliberately NOT in this toolbar — it is demoted to its own
              `payroll-process-region` below the Send group (B-series tablet-safety fix, 2026-07-31):
              the irreversible action gets visual distance from routine preview/export/send work
              rather than sharing a bordered group inline with them. */}
          <div className="payroll-actions-group">
            <select
              value={exportKind}
              onChange={(event) => setExportKind(event.target.value)}
              // Deliberately keyed on lineCount, NOT period?.id: HR must be able to pick
              // "payroll-detail" from this SAME dropdown before a month has ever been processed
              // (period.id is null) -- gating the select itself on period?.id would make that
              // option unreachable. The per-kind requirement (detail vs the three statutory
              // kinds) is instead enforced on the download button below.
              disabled={saving || !(period?.lineCount > 0)}
              aria-label="ประเภทไฟล์ที่จะสร้าง"
            >
              {EXPORT_KINDS.map((kind) => (
                <option key={kind.value} value={kind.value}>{kind.label}</option>
              ))}
            </select>
            <label className="inline-flex items-center gap-2 m-0 text-sm font-extrabold">
              วันที่จ่าย
              <input
                type="date"
                value={payDate}
                onChange={(event) => setPayDate(event.target.value)}
                disabled={saving || !(period?.lineCount > 0)}
              />
              <small className="font-normal text-text-muted">({formatShortDate(payDate)})</small>
            </label>
            <Button
              type="button"
              variant="secondary"
              onClick={generateExportFile}
              disabled={saving || (exportKind === 'payroll-detail' ? !(period?.lineCount > 0) : !period?.id)}
            >
              <Icon name="fileText" />
              ดาวน์โหลดไฟล์
            </Button>
          </div>

          {/* Send — notify employees. The bulk ZIP sits next to the email button so the
              review-then-send order is obvious: check the whole batch, THEN distribute it.
              Unlike the detail xlsx export above, this requires a genuinely PROCESSED period --
              a payslip is a document about money actually paid, and generating 30 of them from an
              uncommitted preview would let HR circulate paperwork for a run that never happened. */}
          <div className="payroll-actions-group">
            <Button
              type="button"
              variant="secondary"
              onClick={downloadPayslipsZip}
              disabled={!period?.id || period?.status !== 'PROCESSED' || saving}
            >
              <Icon name="fileText" />
              ดาวน์โหลดสลิปเงินเดือนทั้งหมด
            </Button>
            <Button type="button" variant="secondary" onClick={distributePayslips} disabled={!period?.id || saving}>
              <Icon name="mail" />
              ส่งอีเมลสลิปเงินเดือน
            </Button>
          </div>

          {/* Run — the irreversible action. Kept in its own region with the period state and pushed
              after routine preview/export/send work. It stays blocked below the 720px mobile
              breakpoint; tablet keeps it because the tablet table/detail contract is now usable. */}
          <section className="payroll-process-region" aria-labelledby="payroll-process-title">
            <div className="payroll-process-copy">
              <span id="payroll-process-title">ปิดรอบเงินเดือน</span>
              <small>
                <StatusBadge tone={status.tone}>{status.label}</StatusBadge>
                <span>{period?.lineCount || 0} คน</span>
              </small>
            </div>
            <div className="payroll-process-controls">
              <Button
                type="button"
                variant="danger"
                size="sm"
                onClick={process}
                disabled={loading || saving || isMobile || emptyPeriod}
                aria-describedby={processBlockedReason ? 'payroll-process-block-reason' : undefined}
                title={processBlockedReason || undefined}
              >
                <Icon name="check" size={15} />
                ประมวลผลเงินเดือน
              </Button>
              {/* Informational state, not an error: this explains why the button is
                  unavailable right now, so it reads in muted body colour rather than
                  danger red. Utilities at the call site, not a styles.css rule —
                  styles.css sits in @layer legacy and loses to any text utility a
                  future caller adds here. */}
              {processBlockedReason && (
                <span
                  id="payroll-process-block-reason"
                  role="note"
                  className="max-w-[34ch] text-xs font-semibold text-text-muted"
                >
                  {processBlockedReason}
                </span>
              )}
            </div>
          </section>
        </div>
      </FilterBar>

      {/* P0 fix (Opus review, 2026-07-30): the withholding-tax classification matrix screen. Without
          this, HR had no way to satisfy PayrollCalculator#calculateClassified's classification gate
          for any component beyond the V100 backfill defaults — a screen HR can actually use, per spec
          sections 1 and 10. Collapsed by default so it does not compete with the main payroll
          workflow above; one employee per sub-section, per the owner's "never a shrunken
          fifteen-column table" mobile decision (section 9c). */}
      <TaxTreatmentMatrixSection payrollMonth={month} showToast={showToast} />

      {/* B1/B4 fix (Opus review, 2026-07-31): layout moved to Tailwind utilities at the call site
          (CLAUDE.md Tailwind-first) instead of a `.payroll-workspace` styles.css rule -- two states,
          not three: below the `payroll-wide:` custom variant (the panel's overlay floor, see the
          isDesktopPanel comment above) the table is the only in-flow column; at >=1440px a second
          `minmax(340px, 0.35fr)` column holds the sticky side panel. `minmax(0, ...)` on both tracks
          deliberately, never a bare `1fr`/`0.35fr` -- a bare fr track floors at min-content and was
          the root cause of the panel-pushed-off-canvas regression this fix replaces.

          Defect 2 fix (Opus review, 2026-07-31): the floor above was briefly dropped to 300px (this
          comment already said 340px -- the code just never matched it), which squeezed the
          special-pay money inputs to 107px and wrapped every Thai label to 3 lines. 340px was
          already the measured content-fit floor referenced in the `max-width: 1439px` comment in
          styles.css, restoring it costs nothing on the "below the fold" problem B1 actually fixed --
          that was a THIRD stacked panel state at a stale 1500px breakpoint, unrelated to this
          track's width.

          Item 1 fix (2026-07-31): the "~630px natural width, comfortable room at every desktop width"
          claim this comment used to make here was wrong -- measured live, the table's actual grid
          minimum with the panel open is 714px (150px employee + 4 x 120px money columns + gaps), and
          at the previous 1280px breakpoint that left only ~598px beside the panel, hiding the
          "สุทธิ" (net pay) column behind a horizontal scrollbar with 116px unreachable. The `xl:`
          breakpoint was raised to `payroll-wide:` (1440px, see index.css) specifically because 714px
          does NOT fit beside a 340px panel until content width clears ~1070px, which this app's
          sidebar/gutters push out to a ~1397-1400px viewport in practice -- 1440px is that floor plus
          real margin. Verified at 1440px: table naturally grows past its 714px minimum (to ~758px in
          the current dataset) with zero columns hidden (see payrollResponsiveCss.test.js and this
          branch's PR body for the exact measurements). */}
      <section className="grid grid-cols-[minmax(0,1fr)] payroll-wide:grid-cols-[minmax(0,1fr)_minmax(340px,0.35fr)] gap-4 items-start">
        {/* B2 fix (Opus review, 2026-07-31): while the detail panel is a genuine overlay dialog
            (<1440px, open), the rest of the page behind the dim backdrop must be unreachable, not
            just visually dimmed -- otherwise Tab still walks a screen-reader/keyboard user out of
            the panel into content that is only hidden by a z-index. `inert` (not just aria-hidden)
            removes this region from both the accessibility tree and the tab order in one attribute.
            `'' : undefined`, not a boolean -- React 18 only renders the bare `inert` attribute for a
            truthy STRING value; `inert={true}` is silently dropped (verified in this repo's jsdom). */}
        <div
          className="payroll-table-region"
          style={{ '--payroll-table-columns': payrollGridColumns }}
          inert={isOverlayPanel && detailOpen ? '' : undefined}
        >
          <PayrollReconciliationAlert mismatches={payrollReconciliation.mismatches} />
          <DataTable
            columns={columns}
            rows={periodLines}
            getRowKey={(line) => line.employeeId}
            gridClassName="payroll-table"
            pageSize={Math.max(periodLines.length, 25)}
            searchable
            searchPlaceholder="ค้นหาพนักงาน"
            // B4 fix (Opus review, 2026-07-31): `cursor-pointer` is a Tailwind utility at the call
            // site (CLAUDE.md Tailwind-first) instead of a `.payroll-row { cursor: pointer }`
            // styles.css rule -- `.payroll-row.active`'s background/shadow stay in styles.css (theme
            // tokens, see the comment on that rule) since this is the one line that was genuinely a
            // one-property override.
            rowClassName={(line) => cn('payroll-row cursor-pointer', Number(line.employeeId) === Number(selectedLine?.employeeId) && 'active')}
            loading={loading}
            stickyHeader
            showPagination={false}
            onRowSelect={selectPayrollLine}
            isRowSelected={(line) => Number(line.employeeId) === Number(selectedLine?.employeeId)}
            footerRow={({ columns: renderedColumns }) => (
              <PayrollTotalsFooter columns={renderedColumns} reconciliation={payrollReconciliation} />
            )}
            emptyState={{
              icon: 'badgeDollar',
              title: 'ยังไม่มีข้อมูลเงินเดือน',
              description: 'เลือกรอบเดือนหรือกดรีเฟรชเพื่อคำนวณตัวอย่าง',
            }}
          />
        </div>

        {/* B2 fix (Opus review, 2026-07-31): a <button aria-hidden="true"> was the previous backdrop
            -- an interactive element deliberately hidden from the accessibility tree is exactly the
            anti-pattern Modal.jsx's own backdrop avoids. `role="presentation"` on a plain <div> (the
            established pattern, see Modal.jsx) stops this element being announced as anything at
            all, while a click still closes the panel via onClick same as before. */}
        <div
          className={cn('payroll-detail-backdrop', detailOpen && 'is-open')}
          role="presentation"
          onClick={closeDetailPanel}
        />

        <aside
          ref={detailPanelRef}
          // tabIndex={-1}: lets the panel itself take focus programmatically (useDialogFocus falls
          // back to it when the panel has no focusable children) without ever joining the Tab order.
          tabIndex={-1}
          // Defect 2 fix (Opus review, 2026-07-31): `@container` (Tailwind v4 has this built in, no
          // plugin) turns the panel itself into a container-query context so the two money-field
          // grids below can respond to the PANEL's own available width instead of the viewport's --
          // the panel is a sticky side column at >=1440px and a fixed-width overlay below that, so
          // its width is not a function of viewport width alone (a `sm:`/`md:` viewport breakpoint
          // would be lying about what's actually being measured). See the `@sm:grid-cols-2` classes
          // on payroll-detail-grid/payroll-special-grid below.
          className={cn(PANEL_CLASS, 'payroll-detail-panel', '@container', detailOpen && 'is-open')}
          // B2 fix (Opus review, 2026-07-31): dialog semantics apply ONLY in overlay mode (<1440px) --
          // at >=1440px this is a persistent side panel beside the table, not a dialog stealing focus,
          // so role/aria-modal/aria-labelledby must not be present there (an always-on role="dialog"
          // on a panel that never traps focus or blocks the rest of the page would be actively wrong).
          role={isOverlayPanel && detailOpen ? 'dialog' : undefined}
          aria-modal={isOverlayPanel && detailOpen ? 'true' : undefined}
          aria-labelledby={isOverlayPanel && detailOpen ? 'payroll-detail-title' : undefined}
        >
          {selectedLine && selectedAdjustment ? (
            <>
              <Panel.Header className="payroll-detail-panel-header">
                <div>
                  <h2 id="payroll-detail-title" className="m-0 text-lg">{selectedLine.employeeName}</h2>
                  <small>{selectedLine.employeeCode} · {selectedLine.departmentName || '-'}</small>
                </div>
                <div className="payroll-detail-header-actions">
                  <StatusBadge tone="info">{selectedLine.employeeCode}</StatusBadge>
                  {/* Rendered only in the <1440px overlay presentation. At >=1440px the panel is a
                      persistent side column whose visibility is NOT gated by `detailOpen` at all
                      (only the overlay's CSS transform states are), so a dismiss control there is
                      inert -- it would set `detailOpen=false` and nothing would visibly happen.

                      This is a conditional RENDER rather than a `display` toggle on purpose. The
                      previous attempt lived in styles.css (`.payroll-detail-close { display: none }`
                      plus an `inline-flex` override under `max-width: 1439px`) and was completely
                      dead: `buttonVariants`' own `inline-flex` sits in `layer(utilities)`, and
                      index.css declares `@layer theme, legacy, utilities`, so a Tailwind utility
                      always beats a styles.css rule for the same property on the same element (the
                      "styles.css loses to Tailwind utilities" gotcha this repo keeps re-learning).
                      Not rendering it also keeps it out of the tab order, which a `display: none`
                      would do but a stale CSS rule never did. */}
                  {isOverlayPanel && (
                  <Button
                    type="button"
                    variant="icon"
                    size="sm"
                    // Item 4b fix (2026-07-31): `variant="icon" size="sm"` resolves to a 36x36
                    // compound-variant box (Button.jsx) -- too small for the primary dismiss control
                    // on a full-height overlay a touch user must reach reliably. Fixed at THIS call
                    // site only (`w-11 min-h-[44px]` after `cn`'s twMerge wins the width/min-height
                    // conflict against the compound variant's `w-9 min-h-[36px]`, same as
                    // `buttonVariants`' own `className` override contract everywhere else) rather than
                    // in the shared `icon`+`sm` compound variant itself -- that variant is also used by
                    // DataTable's pagination arrows and TicketListPage's filter-panel close button,
                    // neither of which is a full-height overlay's primary dismiss control, so widening
                    // it there would be an unrequested, unreviewed change to two unrelated screens.
                    className="payroll-detail-close w-11 min-h-[44px]"
                    onClick={closeDetailPanel}
                    title="ปิดรายละเอียด"
                  >
                    <Icon name="close" size={16} />
                  </Button>
                  )}
                </div>
              </Panel.Header>

              {/* Defect 2 fix (Opus review, 2026-07-31): `grid-cols-1 @sm:grid-cols-2` at the call
                  site (Tailwind-first, replaces the old unconditional `repeat(2, ...)` in
                  styles.css) -- 1 column while the panel container is narrower than Tailwind's
                  `@sm` (24rem/384px) content-box floor, 2 once there's genuinely room. This is a
                  container query keyed off `@container` on the `<aside>` above, not a viewport
                  breakpoint -- see that comment for why viewport width is the wrong signal here. */}
              <div className="payroll-detail-grid grid grid-cols-1 gap-2.5 @sm:grid-cols-2">
                <MiniMetric label="ฐานเงินเดือน" value={formatMoney(selectedLine.baseSalary)} />
                <MiniMetric label="รายได้คิดภาษี" value={formatMoney(selectedLine.grossTaxableIncome)} />
                <MiniMetric label="ภาษีงวดนี้" value={formatMoney(selectedLine.withholdingTax)} />
                <MiniMetric label="เงินโอนสุทธิ" value={formatMoney(selectedLine.netPay)} />
              </div>

              {period?.id && selectedLine.id ? (
                <div className="payroll-detail-actions">
                  <Button type="button" variant="secondary" onClick={() => downloadPayslip(selectedLine)} disabled={saving}>
                    <Icon name="fileText" />
                    ดาวน์โหลดสลิป
                  </Button>
                </div>
              ) : null}

              {/* Daily-rate support (2026-07-30): shown only for a pay_type = 'D' employee --
                  hr.employee.current_salary is that employee's PER-DAY rate (not a monthly figure),
                  so "ฐานเงินเดือน" above is really rate * daysWorked and HR must type the day count
                  each run. Reachable from selectedLine.payType, echoed onto PayrollLineDto by the
                  backend precisely so this page can gate the field to the employees it applies to. */}
              {selectedLine.payType === 'D' && (
                <CollapsibleSection title="ค่าจ้างรายวัน" defaultOpen>
                  <FormGrid>
                    <label htmlFor="payroll-days-worked">
                      จำนวนวันทำงานในงวดนี้
                      <InfoTip
                        label="จำนวนวันทำงานในงวดนี้"
                        text={`พนักงานรายวัน — ค่าแรงงวดนี้ = อัตรารายวัน (${formatMoney(selectedLine.dailyRate)} บาท/วัน) x จำนวนวันทำงานที่กรอกด้านล่าง`}
                      />
                      <input
                        id="payroll-days-worked"
                        type="number"
                        min="0"
                        // Finding 4 fix (Opus review, 2026-07-30): frontend bound matching the
                        // backend's @Max(31)/chk_payroll_line_days_worked_range -- no real month has
                        // more than 31 days. Decimals stay legitimate (e.g. 21.5), so only the upper
                        // bound is added here, step is unchanged.
                        max="31"
                        step="0.5"
                        placeholder="0"
                        value={selectedAdjustment.daysWorked}
                        onChange={(event) => updateAdjustment('daysWorked', event.target.value)}
                      />
                    </label>
                    <small className="block">
                      อัตรารายวัน {formatMoney(selectedLine.dailyRate)} บาท/วัน
                      {parsePayrollNumber(selectedAdjustment.daysWorked) > 0 && (
                        <> — ค่าแรงงวดนี้โดยประมาณ {formatMoney(parsePayrollNumber(selectedAdjustment.daysWorked) * Number(selectedLine.dailyRate || 0))} บาท</>
                      )}
                    </small>
                    {/* Finding 1 fix (Opus review, 2026-07-30): cheap client-side warning mirroring the
                        server-side guard in PayrollService#requireEveryDailyRateEmployeeHasDaysWorked
                        -- the UI warning is not the enforcement (it can always be bypassed / this panel
                        may not even be opened), just an early nudge before HR hits Process, which then
                        refuses the run server-side regardless. */}
                    {!(parsePayrollNumber(selectedAdjustment.daysWorked) > 0) && (
                      <small className="block text-warning">
                        ⚠ ยังไม่ได้ระบุจำนวนวันทำงาน — พนักงานรายวันจะได้รับเงินเดือน ฿0 หากประมวลผลโดยไม่กรอกช่องนี้
                      </small>
                    )}
                  </FormGrid>
                </CollapsibleSection>
              )}

              <CollapsibleSection
                title="เงินพิเศษบริษัท"
                defaultOpen
                headerRight={<span className="collapsible-total">{formatMoney(specialPayKeys.reduce((sum, key) => sum + parsePayrollNumber(selectedAdjustment[key]), 0))}</span>}
              >
                {/* Defect 2 fix (Opus review, 2026-07-31): same `grid-cols-1 @sm:grid-cols-2` swap
                    as payroll-detail-grid above -- this is the grid the review measured at 107px
                    money inputs and 3-line labels with the panel pinned to 2 columns at every
                    width. At 1 column each label/input gets the panel's full content width, which
                    is what keeps every money input >=140px and every Thai label to <=2 lines at
                    the panel's current 340px floor (see the `<section>` grid-cols comment above)
                    without having to keep growing the panel indefinitely as more fields land here. */}
                <div className="payroll-special-grid grid grid-cols-1 gap-2.5 @sm:grid-cols-2">
                  {specialPayFields.map((field) => {
                    const inputId = `payroll-${field.key}`;
                    return (
                      <label key={field.key} htmlFor={inputId}>
                        {field.label}
                        <MoneyInput id={inputId} value={selectedAdjustment[field.key]} onChange={(value) => updateAdjustment(field.key, value)} />
                      </label>
                    );
                  })}
                </div>
              </CollapsibleSection>

              <CollapsibleSection
                title="ค่าอาหาร / เบี้ยเลี้ยง"
                defaultOpen={false}
                headerRight={(
                  <span className="collapsible-total">
                    {formatMoney(namedAllowanceKeys.reduce((sum, key) => sum + parsePayrollNumber(selectedAdjustment[key]), 0))}
                  </span>
                )}
              >
                <FormGrid>
                  <label htmlFor="payroll-meal-allowance">
                    ค่าอาหาร
                    <MoneyInput id="payroll-meal-allowance" value={selectedAdjustment.mealAllowance} onChange={(value) => updateAdjustment('mealAllowance', value)} />
                  </label>
                  <label htmlFor="payroll-per-diem-exempt">
                    เบี้ยเลี้ยง — ส่วนที่ยกเว้นภาษี (ม.42)
                    <InfoTip
                      label="เบี้ยเลี้ยง — ส่วนที่ยกเว้นภาษี (ม.42)"
                      text="ส่วนของเบี้ยเลี้ยงที่อยู่ภายในอัตราที่ได้รับการยกเว้นภาษีตามมาตรา 42 กรอกแยกจากส่วนเกิน"
                    />
                    <MoneyInput id="payroll-per-diem-exempt" value={selectedAdjustment.perDiemExempt} onChange={(value) => updateAdjustment('perDiemExempt', value)} />
                  </label>
                  <label htmlFor="payroll-per-diem-taxable">
                    เบี้ยเลี้ยง — ส่วนเกิน (เสียภาษี)
                    <InfoTip
                      label="เบี้ยเลี้ยง — ส่วนเกิน (เสียภาษี)"
                      text="ส่วนของเบี้ยเลี้ยงที่เกินอัตรายกเว้นตามมาตรา 42 ถือเป็นเงินได้ต้องเสียภาษีตามปกติ"
                    />
                    <MoneyInput id="payroll-per-diem-taxable" value={selectedAdjustment.perDiemTaxable} onChange={(value) => updateAdjustment('perDiemTaxable', value)} />
                  </label>
                  {/* F2 fix (Opus review, 2026-07-30): shown only once a per-diem amount is actually
                      entered -- HR classifying a basis for money nobody paid has nothing to classify,
                      same "only where money is actually moving" principle as tax-treatment classification
                      elsewhere on this page. Required the moment either amount above is non-zero, or
                      V97's chk_payroll_line_per_diem_basis_present CHECK rejects Process with a 500 (now
                      caught earlier server-side too -- see PayrollService#calculateLine). */}
                  {(parsePayrollNumber(selectedAdjustment.perDiemExempt) > 0 || parsePayrollNumber(selectedAdjustment.perDiemTaxable) > 0) && (
                    <label htmlFor="payroll-per-diem-basis">
                      ฐานเบี้ยเลี้ยง (มาตรา 42)
                      <InfoTip
                        label="ฐานเบี้ยเลี้ยง (มาตรา 42)"
                        text="เลือกว่าเบี้ยเลี้ยงนี้จ่ายแบบเหมาจ่ายตามอัตราราชการ (ไม่ต้องมีใบเสร็จ) หรือจ่ายจริงตามหน้าที่ (ต้องมีใบเสร็จ) จำเป็นต้องเลือกก่อนประมวลผลเงินเดือน"
                      />
                      <select
                        id="payroll-per-diem-basis"
                        value={selectedAdjustment.perDiemBasis}
                        onChange={(event) => updateAdjustment('perDiemBasis', event.target.value)}
                      >
                        <option value="">— เลือกฐาน —</option>
                        {PER_DIEM_BASIS_OPTIONS.map((option) => (
                          <option key={option.value} value={option.value}>{option.label}</option>
                        ))}
                      </select>
                    </label>
                  )}
                </FormGrid>
              </CollapsibleSection>

              {/* P1 fix (Opus review, 2026-07-30): bonusPay/otherOneOffPay have existed end-to-end on
                  the backend since V96 (task 2) with no input anywhere on this page — HR could not pay
                  a bonus through payroll at all. The spec calls bonusPay "the archetypal
                  EXTRA_KNOWN_FREQUENCY" component. */}
              <CollapsibleSection
                title="เงินก้อนพิเศษ (จ่ายครั้งเดียว)"
                defaultOpen={false}
                headerRight={<span className="collapsible-total">{formatMoney(oneOffPayKeys.reduce((sum, key) => sum + parsePayrollNumber(selectedAdjustment[key]), 0))}</span>}
              >
                <FormGrid>
                  {oneOffPayFields.map((field) => {
                    const inputId = `payroll-${field.key}`;
                    return (
                      <label key={field.key} htmlFor={inputId}>
                        {field.label}
                        <MoneyInput id={inputId} value={selectedAdjustment[field.key]} onChange={(value) => updateAdjustment(field.key, value)} />
                      </label>
                    );
                  })}
                </FormGrid>
              </CollapsibleSection>

              <CollapsibleSection title="รายได้ไม่คิดภาษี" defaultOpen={false}>
                <FormGrid>
                  <label htmlFor="payroll-non-taxable-income">
                    รายได้อื่นๆ (ไม่คิดภาษี)
                    <InfoTip label="รายได้อื่นๆ (ไม่คิดภาษี)" text="รายได้ส่วนนี้จะไม่ถูกนำไปรวมในฐานคำนวณภาษีเงินได้ของพนักงาน" />
                    <MoneyInput id="payroll-non-taxable-income" value={selectedAdjustment.nonTaxableIncome} onChange={(value) => updateAdjustment('nonTaxableIncome', value)} />
                  </label>
                </FormGrid>
              </CollapsibleSection>

              <CollapsibleSection title="รายการหักรายบุคคล" defaultOpen={false}>
                <FormGrid>
                  <label htmlFor="payroll-unpaid-leave-days">
                    วันลาไม่รับค่าจ้าง
                    <InfoTip label="วันลาไม่รับค่าจ้าง" text="ตัวเลขนี้ถูกกรอกล่วงหน้าจากวันลาที่อนุมัติเกินโควตาในเดือนนี้ (ระบบวันลา) สามารถแก้ไขได้ก่อนคำนวณ/ประมวลผล" />
                    <input id="payroll-unpaid-leave-days" type="number" min="0" step="0.25" placeholder="0" value={selectedAdjustment.unpaidLeaveDays} onChange={(event) => updateAdjustment('unpaidLeaveDays', event.target.value)} />
                    {Number(selectedLine.leaveRefundDays || 0) > 0 ? (
                      <small className="block text-warning">
                        ระบบคืนเครดิตวันลาไม่รับค่าจ้างค้างคืน {selectedLine.leaveRefundDays} วัน ({formatMoney(selectedLine.leaveDeductionRefund)}) ให้อัตโนมัติในงวดนี้แล้ว
                        จากการยกเลิกคำขอลาหลังปิดงวดเงินเดือนก่อนหน้า — ไม่ต้องกรอกตัวเลขด้านบนเพิ่ม
                      </small>
                    ) : pendingUnpaidLeaveCorrectionDays > 0 && (
                      <small className="block text-warning">
                        มีเครดิตวันลาไม่รับค่าจ้างค้างคืน {pendingUnpaidLeaveCorrectionDays} วัน จากการยกเลิกคำขอลาหลังปิดงวดเงินเดือน
                        ระบบจะคืนเครดิตนี้ให้อัตโนมัติในงวดเงินเดือนถัดไปที่ประมวลผลสำหรับพนักงานคนนี้
                      </small>
                    )}
                  </label>
                  <label htmlFor="payroll-student-loan-deduction">
                    หัก กยศ.
                    <InfoTip label="หัก กยศ." text="รายการหักภาระผูกพันกองทุนเงินให้กู้ยืมเพื่อการศึกษา หักหลังคำนวณภาษีแล้ว" />
                    <MoneyInput id="payroll-student-loan-deduction" value={selectedAdjustment.studentLoanDeduction} onChange={(value) => updateAdjustment('studentLoanDeduction', value)} />
                  </label>
                  <label htmlFor="payroll-legal-execution-deduction">
                    หักอายัดกรมบังคับคดี
                    <InfoTip label="หักอายัดกรมบังคับคดี" text="รายการหักตามคำสั่งอายัดเงินเดือนจากกรมบังคับคดี หักหลังคำนวณภาษีแล้ว" />
                    <MoneyInput id="payroll-legal-execution-deduction" value={selectedAdjustment.legalExecutionDeduction} onChange={(value) => updateAdjustment('legalExecutionDeduction', value)} />
                  </label>
                  {/* P1 fix (Opus review, 2026-07-30): never selectable before this fix, so every
                      garnishment silently used the SALARY cap (backend defaults null to SALARY) —
                      making the BONUS 50% / OVERTIME 30% / SEVERANCE ฿300,000 rules dead code. Shown
                      only once an amount is actually requested, same "nothing to classify until money
                      moves" principle as the per-diem basis selector above. */}
                  {parsePayrollNumber(selectedAdjustment.legalExecutionDeduction) > 0 && (
                    <label htmlFor="payroll-garnishment-type">
                      ประเภทเงินที่ถูกอายัด
                      <InfoTip
                        label="ประเภทเงินที่ถูกอายัด"
                        text="เลือกประเภทเงินที่ถูกอายัด เพื่อใช้เพดานตามกฎหมายที่ถูกต้อง (เงินเดือน/ค่าจ้าง เงินโบนัส เบี้ยขยัน/ค่าล่วงเวลา หรือเงินตอบแทนกรณีออกจากงาน) จำเป็นต้องเลือกก่อนประมวลผลเงินเดือนหากมียอดหักอายัด"
                      />
                      <select
                        id="payroll-garnishment-type"
                        value={selectedAdjustment.garnishmentType}
                        onChange={(event) => updateAdjustment('garnishmentType', event.target.value)}
                      >
                        <option value="">— เลือกประเภท (ค่าเริ่มต้น: เงินเดือน/ค่าจ้าง) —</option>
                        {GARNISHMENT_TYPE_OPTIONS.map((option) => (
                          <option key={option.value} value={option.value}>{option.label}</option>
                        ))}
                      </select>
                    </label>
                  )}
                  <label htmlFor="payroll-other-post-tax-deductions">
                    หักอื่น ๆ หลังภาษี
                    <MoneyInput id="payroll-other-post-tax-deductions" value={selectedAdjustment.otherPostTaxDeductions} onChange={(value) => updateAdjustment('otherPostTaxDeductions', value)} />
                  </label>
                  <label htmlFor="payroll-withholding-tax-override">
                    ภาษีหัก ณ ที่จ่าย (กำหนดเอง)
                    <InfoTip
                      label="ภาษีหัก ณ ที่จ่าย (กำหนดเอง)"
                      text="กรอกจำนวนภาษีที่ต้องการหักจริงสำหรับพนักงานคนนี้ในงวดนี้ จะแทนที่ค่าที่ระบบคำนวณ (และค่ามาตรฐานที่ตั้งไว้ในประวัติพนักงาน) เว้นว่างเพื่อใช้ค่าที่คำนวณอัตโนมัติ การคำนวณภาษีทั้งปียังคงเดิม เปลี่ยนเฉพาะยอดหักจริงงวดนี้"
                    />
                    <MoneyInput id="payroll-withholding-tax-override" value={selectedAdjustment.withholdingTaxOverride} onChange={(value) => updateAdjustment('withholdingTaxOverride', value)} />
                    <small className="block text-muted">
                      เว้นว่าง = คำนวณอัตโนมัติ · ภาษีงวดนี้ที่ใช้จริง: {formatMoney(selectedLine.withholdingTax)}
                    </small>
                  </label>
                </FormGrid>
              </CollapsibleSection>

              <CollapsibleSection
                title="รายการหักก่อนภาษี"
                defaultOpen={false}
                headerRight={<InfoTip label="รายการหักก่อนภาษี" text="รายการเหล่านี้จะถูกหักออกจากรายได้ก่อนคำนวณภาษี ต่างจากรายการหักหลังภาษีด้านบน" />}
              >
                <FormGrid>
                  <label htmlFor="payroll-warning-letter-deduction">
                    หักตามใบเตือน
                    <MoneyInput id="payroll-warning-letter-deduction" value={selectedAdjustment.warningLetterDeduction} onChange={(value) => updateAdjustment('warningLetterDeduction', value)} />
                  </label>
                  <label htmlFor="payroll-customer-return-deduction">
                    หักลูกค้าคืนสินค้า
                    <MoneyInput id="payroll-customer-return-deduction" value={selectedAdjustment.customerReturnDeduction} onChange={(value) => updateAdjustment('customerReturnDeduction', value)} />
                  </label>
                  {/* P1 fix (Opus review, 2026-07-30): always false before this fix (never rendered),
                      so the flag was always the unearned/pre-tax path — the fix for the real June 2026
                      negative-net incident (handoff section 6) could never actually be selected. Shown
                      only once a return amount is entered, same principle as the fields above. */}
                  {parsePayrollNumber(selectedAdjustment.customerReturnDeduction) > 0 && (
                    <label htmlFor="payroll-customer-return-already-earned" className="inline-flex items-center gap-2">
                      <input
                        id="payroll-customer-return-already-earned"
                        type="checkbox"
                        checked={selectedAdjustment.customerReturnAlreadyEarned}
                        onChange={(event) => updateAdjustment('customerReturnAlreadyEarned', event.target.checked)}
                      />
                      คอมมิชชันนี้รับไปแล้ว (หักเป็นเงินคืนหลังภาษี ไม่ลดรายได้คิดภาษีงวดนี้)
                      <InfoTip
                        label="คอมมิชชันนี้รับไปแล้ว"
                        text="ไม่ติ๊ก = คอมมิชชันยังไม่รับ ระบบจะลดยอดคอมมิชชันงวดนี้ก่อนคำนวณภาษี ติ๊ก = คอมมิชชันรับและจ่ายไปแล้ว ระบบจะหักเป็นรายการหักหลังภาษีแทน ไม่ลดรายได้คิดภาษีงวดนี้"
                      />
                    </label>
                  )}
                  <label htmlFor="payroll-other-pretax-deduction">
                    หักอื่น ๆ ก่อนภาษี
                    <MoneyInput id="payroll-other-pretax-deduction" value={selectedAdjustment.otherPretaxDeduction} onChange={(value) => updateAdjustment('otherPretaxDeduction', value)} />
                  </label>
                </FormGrid>
              </CollapsibleSection>

              <div className="payroll-breakdown">
                <span><b>SSO</b>{formatMoney(selectedLine.socialSecurity)}</span>
                <span><b>ฐาน ปกส.</b>{formatMoney(selectedLine.ssoWageBase)}</span>
                <span><b>รายได้ทั้งปีประมาณการ</b>{formatMoney(selectedLine.projectedAnnualIncome)}</span>
                <span><b>ค่าลดหย่อนรวม</b>{formatMoney(selectedLine.taxAllowanceTotal)}</span>
                <span><b>รายได้ไม่คิดภาษี</b>{formatMoney(selectedLine.nonTaxableIncome)}</span>
                {Number(selectedLine.directorRemuneration || 0) > 0 && (
                  <span><b>ค่าตอบแทนกรรมการ</b>{formatMoney(selectedLine.directorRemuneration)}</span>
                )}
                {Number(selectedLine.leaveRefundDays || 0) > 0 && (
                  <span>
                    <b>คืนเครดิตวันลาไม่รับค่าจ้าง ({selectedLine.leaveRefundDays} วัน)</b>
                    {formatMoney(selectedLine.leaveDeductionRefund)}
                  </span>
                )}
              </div>
            </>
          ) : (
            <EmptyState icon="badgeDollar" title="เลือกพนักงาน" description="เลือกแถวเงินเดือนเพื่อดูรายละเอียดและปรับเงินพิเศษ" />
          )}
        </aside>
      </section>

      <ConfirmDialog
        open={confirmProcess}
        title="ประมวลผลเงินเดือน"
        message={(
          <div>
            <p className="confirm-dialog-message">
              ยืนยันประมวลผลเงินเดือนรอบ {formatThaiMonthYearFromMonthInputValue(month)} สำหรับพนักงาน {period?.lineCount || 0} คน?
              การดำเนินการนี้ไม่สามารถย้อนกลับได้
            </p>
            <p className="confirm-dialog-message">
              หลังยืนยัน การอนุมัติ OT ของเดือนนี้จะปิดทันที และไม่มีทางยกเลิกการประมวลผล
            </p>
            <p className="confirm-dialog-message">
              รายได้รวม {formatMoney(period?.totalGross)} · เงินหักรวม {formatMoney(period?.totalDeductions)} · ยอดโอนสุทธิ {formatMoney(period?.totalNet)}
            </p>
          </div>
        )}
        confirmLabel="ยืนยันประมวลผล"
        tone="danger"
        busy={saving}
        onConfirm={confirmProcessPayroll}
        onCancel={() => setConfirmProcess(false)}
      />
    </PageStack>
  );
}

/**
 * P0 fix (Opus review, 2026-07-30): the tax-treatment matrix screen. HR needs a place to classify
 * every non-SALARY component per employee (spec sections 1 and 10) — before this fix
 * `PayrollRepository#upsertComponentTaxTreatment` had no caller anywhere, backend HTTP surface
 * included, so nothing could satisfy `PayrollCalculator#calculateClassified`'s classification gate
 * beyond the V100 migration's one-time backfill. One `CollapsibleSection` per employee (owner
 * decision, section 9c: "never a shrunken fifteen-column table" — this applies just as much to a
 * 16-component matrix as to the pay-input grid it echoes), each with its own unclassified-count badge
 * so HR can find the outliers without opening every employee.
 *
 * <p>F2 fix (fifth Opus review, 2026-07-30): `item.byComponent` now carries the EFFECTIVE
 * classification (synthesized defaults merged in server-side by `PayrollService#treatmentsFor`),
 * not the raw stored map — a select for a component nobody has classified yet now shows the
 * treatment the engine actually applies, instead of blank. `item.explicitlyClassifiedComponents`
 * says which of those values HR (or a backfill) actually set versus which are synthesized; the
 * "ค่าเริ่มต้นของระบบ" hint below a select renders only for the latter, so HR can tell "the system is
 * defaulting this" from "someone chose this" — the distinction the branch's back-loading-risk
 * mitigation depends on.
 */
function TaxTreatmentMatrixSection({ payrollMonth, showToast }) {
  const taxYear = Number(String(payrollMonth || '').slice(0, 4)) || new Date().getFullYear();
  const [items, setItems] = useState([]);
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  // employeeId -> { [component]: 'REGULAR_REPROJECT' | 'EXTRA_KNOWN_FREQUENCY' |
  // 'EXTRA_CUMULATIVE_ACTUAL' | '' } — unsaved edits only; not yet reconciled into `items`.
  const [edits, setEdits] = useState({});

  async function load() {
    setLoading(true);
    try {
      const response = await api.payroll.getComponentTaxTreatments(taxYear);
      setItems(response?.items || []);
      setEdits({});
    } catch (error) {
      showToast('error', error.message || 'โหลดข้อมูลการจัดประเภทภาษีหัก ณ ที่จ่ายไม่สำเร็จ');
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [taxYear]);

  function valueFor(item, componentKey) {
    const edited = edits[item.employeeId]?.[componentKey];
    if (edited !== undefined) return edited;
    return item.byComponent?.[componentKey] || '';
  }

  // F2 fix (fifth Opus review, 2026-07-30): true once HR has an actual stored row for this cell (per
  // `item.explicitlyClassifiedComponents`, from the effective-classification snapshot) OR is setting
  // one right now in this unsaved session. False means `valueFor` above is showing a value the
  // backend synthesized, not one anybody chose.
  function isExplicit(item, componentKey) {
    if (edits[item.employeeId]?.[componentKey] !== undefined) return true;
    return item.explicitlyClassifiedComponents?.includes(componentKey) ?? false;
  }

  function setValue(employeeId, componentKey, value) {
    setEdits((current) => ({
      ...current,
      [employeeId]: { ...(current[employeeId] || {}), [componentKey]: value },
    }));
  }

  function unclassifiedCount(item) {
    return TAX_TREATMENT_COMPONENTS.filter((field) => !valueFor(item, field.key)).length;
  }

  const totalUnclassified = items.reduce((sum, item) => sum + unclassifiedCount(item), 0);
  const hasEdits = Object.keys(edits).length > 0;

  async function save() {
    const changes = [];
    Object.entries(edits).forEach(([employeeId, byComponent]) => {
      Object.entries(byComponent).forEach(([component, value]) => {
        changes.push({
          employeeId: Number(employeeId),
          component,
          taxTreatment: value === '' ? null : value,
        });
      });
    });
    if (changes.length === 0) return;
    setSaving(true);
    try {
      const response = await api.payroll.saveComponentTaxTreatments(taxYear, changes);
      setItems(response?.items || []);
      setEdits({});
      showToast('success', 'บันทึกการจัดประเภทภาษีหัก ณ ที่จ่ายแล้ว');
    } catch (error) {
      showToast('error', error.message || 'บันทึกการจัดประเภทภาษีหัก ณ ที่จ่ายไม่สำเร็จ');
    } finally {
      setSaving(false);
    }
  }

  return (
    <CollapsibleSection
      title="การจัดประเภทภาษีหัก ณ ที่จ่าย (ป.96/2543)"
      defaultOpen={false}
      headerRight={(
        <span className={cn('collapsible-total', totalUnclassified > 0 && 'text-danger')}>
          {totalUnclassified > 0 ? `ยังไม่จัดประเภท ${totalUnclassified} รายการ` : 'จัดประเภทครบแล้ว'}
        </span>
      )}
    >
      <p className="text-sm text-text-muted mb-3">
        เงินเดือนล็อกเป็น &quot;ประจำ&quot; เสมอ ส่วนประกอบอื่นทุกรายการต้องจัดประเภทก่อนจึงจะประมวลผลเงินเดือนได้
        เมื่อพนักงานมียอดไม่เท่ากับศูนย์ในส่วนนั้นของงวดใดงวดหนึ่ง — รายการที่ยังไม่จัดประเภทและมียอดจะทำให้ระบบปฏิเสธการประมวลผลทั้งรอบ
        รายการที่ขึ้นป้าย &quot;ค่าเริ่มต้นของระบบ&quot; ยังไม่มีใครกำหนด — ระบบใช้ค่าเริ่มต้นเพื่อให้ประมวลผลได้ก่อน
        ควรตรวจสอบและเลือกให้ถูกต้องเมื่อสะดวก
      </p>
      {loading ? (
        <p className="text-sm text-text-muted">กำลังโหลด…</p>
      ) : items.length === 0 ? (
        <EmptyState icon="badgeDollar" title="ไม่มีพนักงาน" description="ไม่พบพนักงานที่มีเงินเดือนหรือค่าตอบแทนกรรมการในรอบนี้" />
      ) : (
        <div className="flex flex-col gap-2">
          {items.map((item) => {
            const employeeUnclassified = unclassifiedCount(item);
            return (
              <CollapsibleSection
                key={item.employeeId}
                title={`${item.employeeName} (${item.employeeCode})`}
                defaultOpen={false}
                headerRight={(
                  <span className={cn('collapsible-total', employeeUnclassified > 0 && 'text-danger')}>
                    {employeeUnclassified > 0 ? `ยังไม่จัดประเภท ${employeeUnclassified}` : 'ครบ'}
                  </span>
                )}
              >
                <FormGrid>
                  {TAX_TREATMENT_COMPONENTS.map((field) => {
                    const inputId = `tax-treatment-${item.employeeId}-${field.key}`;
                    const value = valueFor(item, field.key);
                    // F2 fix (fifth Opus review, 2026-07-30): a non-blank value nobody explicitly set
                    // is the backend's synthesized default -- flag it so HR does not mistake "the
                    // system is defaulting this" for "someone already reviewed and chose this".
                    const isDefaulted = Boolean(value) && !isExplicit(item, field.key);
                    return (
                      <label key={field.key} htmlFor={inputId}>
                        {field.label}
                        <select
                          id={inputId}
                          value={value}
                          onChange={(event) => setValue(item.employeeId, field.key, event.target.value)}
                        >
                          {TAX_TREATMENT_OPTIONS.map((option) => (
                            <option key={option.value || 'unset'} value={option.value}>{option.label}</option>
                          ))}
                        </select>
                        {isDefaulted && (
                          <small className="block text-text-muted">ค่าเริ่มต้นของระบบ — ยังไม่มีใครกำหนด</small>
                        )}
                      </label>
                    );
                  })}
                </FormGrid>
              </CollapsibleSection>
            );
          })}
        </div>
      )}
      <div className="flex justify-end gap-2 mt-4">
        <Button type="button" variant="secondary" onClick={load} disabled={loading || saving}>
          <Icon name="refresh" />
          รีเฟรช
        </Button>
        <Button type="button" onClick={save} disabled={loading || saving || !hasEdits}>
          <Icon name="check" />
          บันทึกการจัดประเภท
        </Button>
      </div>
    </CollapsibleSection>
  );
}

function MiniMetric({ label, value }) {
  return (
    <div className="mini-metric">
      <small>{label}</small>
      <strong>{value}</strong>
    </div>
  );
}

function MoneyInput({ id, value, onChange }) {
  function handleChange(event) {
    const { value: nextValue } = event.target;
    if (nextValue !== '' && Number(nextValue) < 0) {
      onChange('0');
      return;
    }
    onChange(nextValue);
  }

  return (
    <span className="currency-input">
      <span className="currency-input-symbol" aria-hidden="true">฿</span>
      <input
        id={id}
        type="number"
        inputMode="decimal"
        min="0"
        step="0.01"
        placeholder="0.00"
        value={value ?? ''}
        onChange={handleChange}
      />
    </span>
  );
}
