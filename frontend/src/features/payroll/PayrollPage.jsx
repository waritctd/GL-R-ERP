import React, { useEffect, useMemo, useRef, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { hasPermission } from '../../app/permissions.js';
import { Button } from '../../components/common/Button.jsx';
import { CollapsibleSection } from '../../components/common/CollapsibleSection.jsx';
import { CompactStatRow } from '../../components/common/CompactStatRow.jsx';
import { ConfirmDialog } from '../../components/common/ConfirmDialog.jsx';
import { DataTable } from '../../components/common/DataTable.jsx';
import { EmptyState } from '../../components/common/EmptyState.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { InfoTip } from '../../components/common/InfoTip.jsx';
import { FilterBar, FormGrid, PageStack, Panel } from '../../components/common/Layout.jsx';
import { OverflowMenu } from '../../components/common/OverflowMenu.jsx';
import { PageHeader } from '../../components/common/PageHeader.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { TaxAllowanceDrilldown } from '../taxAllowance/TaxAllowanceDrilldown.jsx';
import { useDialogFocus } from '../../hooks/useDialogFocus.js';
import { useIsMobile, useMediaQuery } from '../../hooks/useIsMobile.js';
import { cn } from '../../utils/cn.js';
import { formatMoney, formatShortDate, formatThaiMonthYearFromMonthInputValue, payrollStatusLabel as statusInfo } from '../../utils/format.js';

// Reproduces `.panel` for the detail sidebar, which must stay a real <aside>
// element (semantic landmark) — Layout.jsx's Panel always renders a <section>
// and has no `as` prop, so the exact utility string is duplicated here rather
// than wrapping a <section> inside an <aside> or changing the shared primitive.
const PANEL_CLASS = 'bg-surface border border-border rounded-md p-5';

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
const MOBILE_PROCESS_CONFIRM_PHRASE = 'ประมวลผล';
const mobileOptionalMoneySummaries = [
  { key: 'specialPayTotal', label: 'เงินพิเศษ', amount: (line) => Number(line.specialPayTotal || 0) },
  { key: 'otCommission', label: 'ล่วงเวลา/คอมมิชชัน', amount: (line) => Number(line.overtimePay || 0) + Number(line.commissionPay || 0) },
  { key: 'welfarePay', label: 'สวัสดิการ', amount: (line) => Number(line.welfarePay || 0) },
];

function MoneyCode({ value }) {
  return <code className="payroll-money">{formatMoney(value)}</code>;
}

function PayrollMobileZeroDisclosure({ line }) {
  const zeroItems = mobileOptionalMoneySummaries.filter((item) => item.amount(line) === 0);
  if (zeroItems.length === 0) return null;
  return (
    <details className="col-span-2 mt-2 rounded-sm border border-border-subtle bg-surface-muted px-2">
      <summary className="flex min-h-[44px] cursor-pointer items-center text-xs font-extrabold text-text-muted">
        รายการศูนย์ {zeroItems.length} รายการ
      </summary>
      <dl className="m-0 grid gap-1 border-t border-border-subtle py-2 text-xs">
        {zeroItems.map((item) => (
          <div key={item.key} className="flex items-center justify-between gap-3">
            <dt className="text-text-muted">{item.label}</dt>
            <dd className="m-0 font-mono font-extrabold text-text">{formatMoney(0)}</dd>
          </div>
        ))}
      </dl>
    </details>
  );
}

function createEmployeeColumn(selectedEmployeeId) {
  return {
    key: 'employee',
    header: 'พนักงาน',
    className: 'mobile:[&::before]:hidden',
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
      const warning = line.payType === 'D' && !(Number(line.daysWorked) > 0)
        ? '⚠ ยังไม่ได้ระบุจำนวนวันทำงาน'
        : null;
      return (
        <>
          <span className="flex items-center justify-between gap-2.5 min-w-0 mobile:hidden">
            <span className="min-w-0 overflow-hidden">
              <strong className="block truncate">{line.employeeName}</strong>
              <small className="block truncate">{line.employeeCode} · {line.departmentName || '-'}</small>
              {/* Finding 1 fix (Opus review, 2026-07-30): list-level warning so a daily-rate employee
                  with no/zero days-worked is visible WITHOUT having to open their detail panel first --
                  the gap the ฿0-payslip bug actually lived in (hasPayrollInput below never submits a row
                  with nothing entered, so HR could Process a whole month without ever noticing). Purely
                  a nudge; PayrollService#requireEveryDailyRateEmployeeHasDaysWorked is the real
                  enforcement. */}
              {warning && (
                <small className="block truncate text-warning">{warning}</small>
              )}
            </span>
            {selected ? (
              <span className="inline-flex flex-none items-center gap-1 whitespace-nowrap rounded-sm border border-info-border bg-info-bg px-1.5 py-0.5 text-2xs font-black text-info">
                <Icon name="check" size={13} />
                เลือกอยู่
              </span>
            ) : null}
          </span>
          <span className="hidden min-w-0 mobile:grid mobile:grid-cols-[minmax(0,1fr)_auto] mobile:items-start mobile:gap-x-3 mobile:gap-y-1">
            <span className="min-w-0">
              <strong className="block truncate text-sm">{line.employeeName}</strong>
              <small className="block truncate text-xs">{line.employeeCode} · {line.departmentName || '-'}</small>
              {warning && (
                <small className="block truncate text-warning">{warning}</small>
              )}
            </span>
            <span className="text-right">
              <small className="block text-2xs font-black text-text-muted">สุทธิ</small>
              <MoneyCode value={line.netPay} />
            </span>
            {selected ? (
              <span className="col-span-2 inline-flex w-fit items-center gap-1 whitespace-nowrap rounded-sm border border-info-border bg-info-bg px-1.5 py-0.5 text-2xs font-black text-info">
                <Icon name="check" size={13} />
                เลือกอยู่
              </span>
            ) : null}
            <PayrollMobileZeroDisclosure line={line} />
          </span>
        </>
      );
    },
  };
}

function payrollMoneyCellClassName(column, line, options = {}) {
  const mobileHiddenWhenZero = line && column.hideWhenZero && Number(column.totalAccessor?.(line) || 0) === 0;
  const mobileHidden = mobileHiddenWhenZero || (line && options.hideOnMobile);
  return cn(
    'payroll-money-cell mobile:grid mobile:grid-cols-[minmax(0,1fr)_auto] mobile:items-center mobile:gap-3 mobile:[&::before]:m-0 mobile:[&::before]:normal-case',
    mobileHidden && 'mobile:hidden',
  );
}

const payrollMoneyColumns = [
  {
    key: 'grossEarnings',
    header: 'รายได้',
    heroLabel: 'รายได้รวม',
    heroAccessor: (period) => period?.totalGross,
    sortable: true,
    align: 'right',
    className: (line) => payrollMoneyCellClassName(payrollMoneyColumns[0], line),
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
    className: (line) => payrollMoneyCellClassName(payrollMoneyColumns[1], line),
    isMoney: true,
    hideWhenZero: true,
    totalAccessor: (line) => Number(line.specialPayTotal || 0),
    sortAccessor: (line) => Number(line.specialPayTotal || 0),
    render: (line) => <MoneyCode value={line.specialPayTotal} />,
  },
  {
    key: 'otCommission',
    header: 'ล่วงเวลา/คอมมิชชัน',
    sortable: true,
    align: 'right',
    className: (line) => payrollMoneyCellClassName(payrollMoneyColumns[2], line),
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
    className: (line) => payrollMoneyCellClassName(payrollMoneyColumns[3], line),
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
    className: (line) => payrollMoneyCellClassName(payrollMoneyColumns[4], line, { hideOnMobile: true }),
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
  // V128. Listed here specifically so HR can change the ป.96 treatment the migration seeds
  // (EXTRA_KNOWN_FREQUENCY, by analogy with เงินโบนัส) without needing another migration. Not in
  // namedAllowanceFields above: welfare is auto-fed from approved claims, never typed.
  { key: 'WELFARE_PAY', label: 'สวัสดิการ (จากคำขอที่อนุมัติแล้ว อัตโนมัติ)' },
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

// A5 fix (issue #422): the two raw `<input type="number">` fields on this page (unpaidLeaveDays,
// daysWorked) have no equivalent of MoneyInput's own onChange clamp, so a typed "-5" reached
// updateAdjustment verbatim -- @PositiveOrZero on PayrollEmployeeInputRequest then 400s the
// ENTIRE draft/preview/process PUT with no client-side signal at all (every OTHER employee's
// legitimate edits silently rejected along with it). Mirrors MoneyInput's own clamp exactly.
function clampNonNegative(value) {
  if (value !== '' && Number(value) < 0) return '0';
  return value;
}

// A5 belt-and-braces (issue #422): MoneyInput and clampNonNegative above already stop a negative
// from being TYPED into any field on this page, but saveDraft() below scans the built payload one
// more time before sending it -- a defensive backstop against a future field that forgets the
// clamp, not a redundant check. Returns the first offending {employeeId, key} pair, or null.
function findNegativeAdjustmentField(inputs) {
  for (const input of inputs) {
    for (const key of payrollInputKeys) {
      if (Number(input[key]) < 0) return { employeeId: input.employeeId, key };
    }
  }
  return null;
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
            <code className="payroll-money text-xs">{formatSatang(item.sumSatang)}</code>
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

export function PayrollPage({ user, showToast }) {
  const isMobile = useIsMobile();
  // Split (issue #390): PayrollController mirrors -- every GET, plus the non-persisting POST
  // /preview and /preview/export/{kind}, is @PreAuthorize("hasAnyRole('HR','CEO')"); every write
  // (process, PUT input-draft, PUT tax-allowances, PUT ytd-seed, PUT component-tax-treatments,
  // POST distribute) is hasRole('HR') only. This is an ALLOWLIST read straight off
  // ROLE_PERMISSIONS.canManagePayroll (routes.js) via the same `hasPermission` helper every other
  // route guard uses -- deliberately NOT a `role !== 'ceo'` denylist, which would fail OPEN (grant
  // every write) to any role added to canViewPayroll later without anyone touching this file.
  // `hasPermission(undefined, ...)` returns false, so an absent `user` reads as no management
  // access, not full access -- every caller (including tests) must pass a real user explicitly.
  const canManage = hasPermission(user?.role, 'canManagePayroll');
  const [month, setMonth] = useState(thisMonth);
  const [period, setPeriod] = useState(null);
  const [adjustments, setAdjustments] = useState({});
  const [selectedEmployeeId, setSelectedEmployeeId] = useState(null);
  const [detailOpen, setDetailOpen] = useState(false);
  // B1 fix (Opus review, 2026-07-31): the detail panel has exactly two presentations, not three.
  // >=1366px is a persistent side panel next to the table; below that it is the fixed slide-over
  // overlay tablet already used. The query is the JS-side mirror of the CSS `payroll-wide:` custom
  // variant (`min-width: 1366px`, see index.css's `@custom-variant`) so the two can never disagree
  // about which mode is active.
  //
  // Phase A (2026-07-31): the command-surface pass shrinks the side panel floor to 310px, which lets
  // the table's measured 714px minimum and the 16px gap fit side-by-side at 1366px without hidden
  // money columns. 1280px still stays overlay; the content width there cannot pay for the panel.
  const isDesktopPanel = useMediaQuery('(min-width: 1366px)');
  const isOverlayPanel = !isDesktopPanel;
  const detailPanelRef = useRef(null);
  const [saving, setSaving] = useState(false);
  const [confirmProcess, setConfirmProcess] = useState(false);
  // Leave -> payroll unpaid-day deduction (2026-07-23): the raw suggestions response, kept only so
  // the pending cancel-after-close correction credit (if any) can be shown as a hint next to the
  // unpaidLeaveDays field. Never re-applied to `adjustments` after the initial pre-fill in
  // applyPeriod -- HR's edits always win.
  const [suggestionsByEmployee, setSuggestionsByEmployee] = useState({});
  const [payDate, setPayDate] = useState(`${thisMonth}-26`);
  const [draftDirty, setDraftDirty] = useState(false);
  const [draftSaving, setDraftSaving] = useState(false);
  // Optimistic concurrency (issue #422 follow-up): 'conflict' is a new terminal status alongside
  // the existing 'saved'/'dirty'/'saving'/'error' -- reached only on a 409 from PUT /input-draft,
  // and cleared only by the explicit "โหลดข้อมูลใหม่" action below (never by the retry machinery,
  // which would just 409 again against the same stale token). See draftConflictRef's own comment
  // for why the synchronous gate lives in a ref, not this state value.
  const [draftSaveStatus, setDraftSaveStatus] = useState('saved');
  // The Thai message from the 409 response, shown in the persistent conflict banner below (unlike
  // the transient toast, which auto-dismisses after 3.2s -- see useToast.js -- and is therefore
  // not sufficient on its own for "another user saved this month's draft," which HR must not miss).
  const [draftConflictMessage, setDraftConflictMessage] = useState(null);
  const adjustmentsRef = useRef({});
  const draftDirtyRef = useRef(false);
  const draftVersionRef = useRef(0);
  // Optimistic concurrency (issue #422 follow-up): the month-level ETag saveDraft() must thread
  // through as If-Match on the NEXT save. Deliberately a ref updated on a strict allowlist, not
  // whenever a fresh value is available -- see the "data arrived" effect below for why a
  // background poll must never refresh this while the form is dirty: doing so would silently
  // adopt another user's concurrent save as "the version I started from," defeating the entire
  // conflict check the next time HR saves. Updated only: (a) the month-change effect, reset to
  // null; (b) the "data arrived" effect, ONLY when the form is clean; (c) on saveDraft()'s own
  // successful response, unconditionally (that response IS the new server truth); (d) explicitly
  // after a successful process() (which clears the server's draft rows out from under whatever
  // token was current). Never mutated on a 409 -- see draftConflictRef.
  const draftEtagRef = useRef(null);
  // Synchronous companion to draftSaveStatus === 'conflict': saveDraft()'s own `finally` block
  // decides whether to auto-retry a few lines below, and reading React state there would see the
  // PREVIOUS render's value (setDraftSaveStatus('conflict') hasn't flushed yet at that point in
  // the same synchronous call), which would let the auto-retry chain hammer the backend with the
  // same doomed-to-409 token on every subsequent keystroke. This ref is set the instant the 409 is
  // caught, in time for that same function invocation's `finally` to see it.
  const draftConflictRef = useRef(false);
  const queryClient = useQueryClient();

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
  // Payroll input draft (2026-07-30; WIDENED 2026-08-02, issue #422 A1): this used to require
  // `period?.status === 'PREVIEW' && !period?.id` -- a brand-new, never-touched run only -- on
  // the theory that a draft would be "useless" once a period was PROCESSED or
  // VOID-with-real-lines, because the line's own persisted value always wins in
  // adjustmentFromLine regardless. That reasoning holds for RESTORE, but not for
  // EDIT-IN-PROGRESS: every field on those periods stays fully editable (gated only on
  // !canManage below), so the old gate just let HR type into a form that silently kept nothing
  // -- no badge, no toast, no save (issue #422 §A1: "I saved a draft and it was gone", reported
  // from a PROCESSED month and from a VOID month with pre-loaded real inputs alike).
  //
  // Widening this to "any loaded period, any status" is safe by construction, not just
  // convenient: draftValue()'s own `> 0` precedence (see its comment above) means a committed
  // non-zero figure already on the line ALWAYS beats whatever a draft restore would supply -- a
  // draft can only ever fill in a field that is genuinely blank/zero on the real line. One
  // residual gap is pre-existing and intentionally NOT fixed here (it would change restore
  // precedence for already-committed payroll): HR clearing a non-zero COMMITTED field down to 0
  // still sees the committed value reappear on reload, because draftValue can't distinguish
  // "never touched" from "deliberately zeroed" once a real persisted value exists.
  //
  // CEO can never save a draft (PUT /input-draft is hasRole('HR') only) -- folded into the same
  // condition as the period-existence check so the autosave status badge and the blur autosave
  // below both stay off for a read-only session, not just visually hidden.
  const canSaveDraft = canManage && Boolean(period);
  // "Latest" mirror of canSaveDraft for code that runs OUTSIDE this render's own closure -- the
  // month-change effect's cleanup (A4 below) and the beforeunload listener. Both are set up once
  // per effect instance and, without this, would keep reading whatever canSaveDraft/period were
  // AT THE TIME that effect instance was created (e.g. right when `month` changed, before `load`
  // even resolved), not the current render's values -- a stale closure, not a "wrong month" bug,
  // but just as silent. Updated in a post-render effect (not during render itself -- React's own
  // lint rules forbid mutating a ref while rendering), which still runs well before either
  // consumer below can possibly fire.
  const canSaveDraftRef = useRef(canSaveDraft);
  useEffect(() => {
    canSaveDraftRef.current = canSaveDraft;
  });
  // P2 fix (issue #422, adversarial review, round 2): a monotonically incremented VISIT token,
  // not a "latest month VALUE" mirror -- bumped directly in the month-change effect's own body
  // below (never in a separate no-deps passive effect: a promise settling in the microtask right
  // after `setMonth` commits, but before a SEPARATE effect gets its turn in the passive-effect
  // flush, would still see the stale value and slip through). A value mirror is not enough on its
  // own either way: July -> June -> July makes `month === monthAtStart` true again by
  // coincidence, so a save from the FIRST (abandoned) July visit would pass a value-based guard
  // as if it belonged to the second. Each visit -- including a round trip back to a month HR
  // already left -- gets its own token value, so `saveDraft()`'s resolution can tell "this exact
  // visit" from "a same-named but different visit" instead of merely "this month name".
  const monthVisitTokenRef = useRef(0);
  const processBlockedReason = emptyPeriod
    ? 'ยังไม่มีพนักงานในรอบเงินเดือนนี้ — กดคำนวณตัวอย่างหรือเลือกรอบเดือนที่มีข้อมูลก่อนประมวลผล'
    : null;
  const requiresMobileProcessPhrase = isMobile && !emptyPeriod;

  function selectPayrollLine(line) {
    setSelectedEmployeeId(line.employeeId);
    setDetailOpen(true);
  }

  function closeDetailPanel() {
    setDetailOpen(false);
  }

  // B2 fix (Opus review, 2026-07-31): focus trap + Escape-to-close + focus-restoration, shared with
  // Modal.jsx via useDialogFocus (see that hook's own comment). `active` is false whenever the panel
  // is the >=1366px persistent side panel rather than the <1366px overlay -- a side panel must not
  // steal Tab or respond to Escape, since the rest of the page beside it is not actually hidden.
  useDialogFocus({ active: isOverlayPanel && detailOpen, containerRef: detailPanelRef, onClose: closeDetailPanel });

  // Issue #422 B1: PayrollPage onto react-query, so it participates in the shared cache (an
  // approval elsewhere can now invalidate it -- see B5's `['payroll']` prefix calls in
  // CommissionPage/OvertimePanel/LeavePage) and gets a 60s poll + window-focus refetch instead of
  // being a dead end that only ever refreshed on month change or an explicit action. A combined
  // queryFn (period + suggestions + draft in one) rather than three separate queries: it keeps
  // the exact sequencing/resilience the old imperative load() had (a failing suggestion or draft
  // fetch must never block the period from loading -- each keeps its own try/catch) and gives one
  // single query-cache entry to invalidate from elsewhere.
  const payrollQuery = useQuery({
    queryKey: queryKeys.payrollCurrent(month),
    queryFn: async () => {
      const response = await api.payroll.current({ payrollMonth: month });
      const nextPeriod = response.period;
      // Special-pay carry-forward (2026-07-23): a SUGGESTION only makes sense for a genuinely
      // fresh run (PREVIEW with no processed/void period yet) -- an already-touched period
      // (PROCESSED, or VOID like July's pre-loaded real inputs) already reflects real, submitted
      // values in the line itself, and adjustmentFromLine's own precedence means the line's value
      // always wins regardless, so fetching a suggestion for it would be pure waste, not a
      // correctness issue. (issue #422 A1 widened the DRAFT fetch below past this same gate --
      // see canSaveDraft's own comment for why a draft, unlike a suggestion, is NOT safe to skip
      // once a period is touched: the fields stay editable regardless of status.)
      let suggestionsByEmployee = {};
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
      }
      // Payroll input draft (2026-07-30; UNCONDITIONAL as of issue #422 A1): fetched on every
      // load, not only a fresh PREVIEW run -- see canSaveDraft's own comment for the full
      // reasoning. Same resilience as suggestedInputs above -- a draft is a convenience restore
      // only and must never block payroll from loading.
      //
      // Optimistic concurrency (issue #422 follow-up): draftEtag is the month-level token this
      // page must thread back as If-Match on the next save -- see draftEtagRef's own comment for
      // exactly when it is (and is not) applied from a query resolution. `null` here (the fetch
      // itself failed) is a real, defined value distinct from `undefined` (a cache seed that never
      // carried draft info at all, e.g. preview()'s) -- the "data arrived" effect treats them
      // differently.
      let draftByEmployee = {};
      let draftEtag = null;
      try {
        const draftResponse = await api.payroll.getInputDraft?.({ payrollMonth: month });
        draftByEmployee = indexSuggestionsByEmployee(draftResponse?.drafts);
        draftEtag = draftResponse?.etag ?? null;
      } catch {
        draftByEmployee = {};
        draftEtag = null;
      }
      return { period: nextPeriod, suggestionsByEmployee, draftByEmployee, draftEtag };
    },
    refetchInterval: 60_000,
    refetchOnWindowFocus: true,
  });
  // `isLoading` (true only for the FIRST fetch of a given month, i.e. no cached data yet) drives
  // every loading skeleton on this page -- unlike `isFetching`, it stays false during the silent
  // 60s poll/focus refetch, so a background refresh never flashes the table/stat strip.
  const loading = payrollQuery.isLoading;

  // Adversarial-review fix (P0): `showToast` comes from useToast() (App.jsx) and is a PLAIN
  // function re-created on every App render -- calling it does setToast(...), which re-renders
  // App, which hands this component a new `showToast` identity, which (if `showToast` sat in this
  // effect's deps, as it used to) re-fires the effect for as long as `payrollQuery.error` stays
  // non-null: an infinite `Maximum update depth exceeded` loop that every test here missed only
  // because every test passes a stable `vi.fn()` instead of an App-shaped one. Fixed two ways at
  // once: a ref holds the LATEST showToast without being a dependency (mutated in its own
  // no-deps effect, not during render -- see canSaveDraftRef's identical pattern above), and the
  // effect keys on the error's MESSAGE (a primitive), not the Error object or the callback, so it
  // only re-fires when the failure actually changes.
  const showToastRef = useRef(showToast);
  useEffect(() => {
    showToastRef.current = showToast;
  });
  const payrollQueryErrorMessage = payrollQuery.error?.message;
  useEffect(() => {
    if (payrollQueryErrorMessage) showToastRef.current('error', payrollQueryErrorMessage || 'โหลดเงินเดือนไม่สำเร็จ');
  }, [payrollQueryErrorMessage]);

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
    adjustmentsRef.current = nextAdjustments;
    setAdjustments(nextAdjustments);
    setSelectedEmployeeId((current) => current || nextPeriod?.lines?.[0]?.employeeId || null);
  }

  // Applies the query's data to local state whenever it changes -- the INITIAL load for a month
  // AND every subsequent background refetch (60s poll / window focus) alike.
  //
  // Governing constraint (issue #422 B1, read this twice): a background refetch must NEVER
  // clobber what HR is currently typing. period/suggestionsByEmployee (server-derived, read-only)
  // always update; adjustments (the FORM values) are re-applied only when the form is clean
  // (`!draftDirtyRef.current`) -- while dirty, only `period` is updated (so e.g. the stat strip
  // and other-employees' figures stay live), and the typed values are left exactly as HR left
  // them. The next successful autosave, an explicit บันทึกร่าง, or a month change is what
  // eventually reconciles them with the server -- never a silent background overwrite
  // mid-keystroke.
  useEffect(() => {
    if (!payrollQuery.data) return;
    // Regression fix (issue #422 follow-up, caught by PayrollPage.test.jsx "allows clearing
    // zero/default amounts and sends them as zeroes" while wiring the ETag work): preview()/
    // process() both call applyPeriod(response.period) directly (applyUatDefaults=false -- see
    // their own definitions) AND seed that identical response into the query cache via
    // queryClient.setQueryData, purely so the next 60s background poll diffs against fresh data
    // instead of the stale pre-action one. That cache write ALSO makes payrollQuery.data change,
    // which re-runs this very effect a second time for the SAME result. Previously this went
    // unnoticed only because react-query's structural-sharing bail-out (replaceEqualDeep)
    // sometimes treated the reseeded shape as unchanged and skipped notifying subscribers at all
    // -- an accident of cache-content equality, not a real guarantee: it silently stopped holding
    // the moment either seed's shape or values changed for any unrelated reason (this task's
    // `draftEtag` addition being one such reason). The effect must instead be correct regardless
    // of whether react-query happens to re-fire: `applyUatDefaults` now travels WITH the data
    // (defaulted to `true` below for an ordinary fetch -- the pre-existing first-load/poll
    // convenience) and preview()/process() explicitly seed `applyUatDefaults: false`, so a
    // redundant re-run derives `adjustments` with EXACTLY the same inputs the direct call already
    // used and produces an identical result -- true idempotence, not merely a race that happened
    // not to lose.
    const {
      period: nextPeriod,
      suggestionsByEmployee: nextSuggestions,
      draftByEmployee,
      draftEtag,
      applyUatDefaults = true,
    } = payrollQuery.data;
    setSuggestionsByEmployee(nextSuggestions);
    if (draftDirtyRef.current) {
      // Optimistic concurrency (issue #422 follow-up): draftEtagRef is DELIBERATELY left alone
      // here -- see its own comment above. Adopting a fresher token while HR is mid-edit would let
      // the next save silently match a version that already contains someone ELSE's change,
      // which is precisely the lost-update this whole feature exists to prevent.
      setPeriod(nextPeriod);
      return;
    }
    applyPeriod(nextPeriod, { applyUatDefaults, suggestionsByEmployee: nextSuggestions, draftByEmployee });
    // `undefined` (not `null`) means this data came from a manual cache seed that never carried
    // draft info (preview()'s setQueryData) -- leave the last known-good token alone. `null` means
    // a real query resolution whose draft fetch itself failed; adopting it is the safe default
    // (the next save 428s with a clear "reload" message instead of risking a wrong/stale token).
    if (draftEtag !== undefined) {
      draftEtagRef.current = draftEtag;
    }
    // Regression fix (Opus review, NEW-1): this clean path had adopted a fresh server token (just
    // above) without ever clearing draftConflictRef -- the ONLY other place that ref goes back to
    // false is a save success, reloadAfterDraftConflict, a month change, or a process() success.
    // Reachable sequence: a 409/428 sets draftConflictRef=true on a CLEAN form (that branch never
    // touches draftDirtyRef, so the form stays clean); the next ordinary poll/focus refetch then
    // takes THIS branch, adopts a good token, and clears the banner (draftSaveStatus -> 'saved')
    // -- but draftConflictRef stayed stuck true, so autosaveDraft's `|| draftConflictRef.current`
    // guard returns early FOREVER after that, with no banner and no toast to explain why nothing
    // is saving. Exactly the silent-autosave-failure class issue #422/PR #426 exists to eliminate.
    // A fresh, adopted token means whatever conflict existed genuinely IS resolved here.
    draftConflictRef.current = false;
    setDraftSaveStatus('saved');
  }, [payrollQuery.data]);

  // draftPayload() is declared here (above the month-change effect below, which references it)
  // rather than down with payload() -- function declarations are hoisted either way, but this
  // repo's lint config flags a hook body reading a value declared later in the same component as
  // an ordering hazard, so the textual order is made to match the dependency.
  function draftPayload() {
    return {
      payrollMonth: `${month}-01`,
      inputs: Object.values(adjustmentsRef.current).map(normalizedAdjustment),
    };
  }

  // A4 fix (issue #422): flush whatever HR had typed for the OUTGOING month before switching (or
  // unmounting) -- this effect used to just call load() for the new month, which unconditionally
  // reset draftDirtyRef.current with no attempt to save the previous month's in-progress edits
  // first.
  //
  // Adversarial-review correction: an earlier version of this fix introduced a separate
  // `flushDraftFor(monthValue)` helper here, on the theory that `draftPayload()` above "reads the
  // current month from closure" and would read the INCOMING month by the time this cleanup runs.
  // That theory was wrong and the reviewer proved it by mutation-testing: swapping
  // `draftPayload()` back in here left every test green. `month` is a plain primitive captured by
  // this render's closure -- both `draftPayload` and this effect (including the cleanup it
  // returns) are (re)created together on every render that this component function runs, so they
  // close over the SAME `month` binding. When `month` changes, React tears down the PREVIOUS
  // effect instance (the one created during the render where `month` was still the outgoing
  // value) before setting up the new one, so the cleanup below runs with `draftPayload`'s closure
  // still pointing at the outgoing month -- exactly like `month` itself does. This is unlike
  // canSaveDraftRef/draftDirtyRef just below, which genuinely DO need to be refs: those track
  // values that can change (via other state updates) WITHOUT this effect re-running, since its
  // dependency array is `[month]` alone -- a plain closure over `canSaveDraft`/`draftDirty` here
  // would go stale the moment either changed without a month change, which is exactly the
  // scenario this effect exists to handle correctly.
  useEffect(() => {
    // Bumped here, in this effect's own body -- see monthVisitTokenRef's own comment above for
    // why this exact spot (not a separate passive effect, not during render).
    monthVisitTokenRef.current += 1;
    setSelectedEmployeeId(null);
    setDetailOpen(false);
    setPayDate(`${month}-26`); // default salary pay date is the 26th of the selected month
    // A new month starts with a clean slate; any dirty tracking belongs to the OUTGOING month
    // (flushed by this same effect's cleanup, below) and must not leak into the incoming one.
    draftDirtyRef.current = false;
    setDraftDirty(false);
    setDraftSaving(false);
    setDraftSaveStatus('saved');
    setDraftConflictMessage(null);
    draftVersionRef.current = 0;
    // Optimistic concurrency (issue #422 follow-up): a new month visit starts with no known token
    // (the incoming month's own query resolution below supplies one); any conflict from the
    // OUTGOING month is unrelated to the incoming one.
    draftEtagRef.current = null;
    draftConflictRef.current = false;
    return () => {
      if (draftDirtyRef.current && canSaveDraftRef.current) {
        // Fire-and-forget: switching months (or navigating away) must not block on this, and
        // there is nowhere left to show a toast by the time it resolves/rejects. Errors are
        // swallowed deliberately -- the beforeunload guard below is the backstop for the case
        // that actually matters (closing the tab with unsaved work); a failed background flush on
        // ordinary month navigation just means the next autosave-on-blur picks it up again.
        //
        // Optimistic concurrency (issue #422 follow-up): If-Match is now REQUIRED by the backend
        // -- threading draftEtagRef.current here (the outgoing month's last known token, still
        // intact at this point; the reset above belongs to the render that already committed)
        // keeps this flush a normal conditional save instead of a guaranteed 428. If it has
        // already gone stale (another session saved first), this 409s and is swallowed exactly
        // like any other flush failure -- there is nothing left on screen to tell.
        void api.payroll.saveInputDraft(draftPayload(), { ifMatch: draftEtagRef.current }).catch(() => {});
      }
    };
    // draftPayload is deliberately NOT a dependency: it is a new function every render (not
    // memoized), so listing it would make this effect re-run -- and flush a save attempt -- on
    // every keystroke rather than only on an actual month change. It closes over the correct
    // (outgoing) month by construction; see draftPayload's own comment above for why that is
    // safe here, unlike the refs this same effect reads for canSaveDraft/draftDirty.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [month]);

  // A4 fix (issue #422): warn before an actual tab close/reload with unsaved draft edits --
  // `grep -rn "beforeunload" frontend/src` was zero hits before this. No async save is attempted
  // here (browsers do not reliably wait for one); this is purely the "are you sure" backstop for
  // the case the month-change/unmount flush above cannot cover (the whole page going away, not a
  // React-driven cleanup).
  useEffect(() => {
    function handleBeforeUnload(event) {
      if (draftDirtyRef.current && canSaveDraftRef.current) {
        event.preventDefault();
        event.returnValue = '';
      }
    }
    window.addEventListener('beforeunload', handleBeforeUnload);
    return () => window.removeEventListener('beforeunload', handleBeforeUnload);
  }, []);

  function payload() {
    return {
      payrollMonth: `${month}-01`,
      inputs: Object.values(adjustments).map(normalizedAdjustment).filter(hasPayrollInput),
    };
  }

  // Phase A command-surface cleanup: Preview is the only read-only recompute command. It keeps the
  // old Refresh semantics too: commission, overtime, leave refund, director remuneration, tax/SSO
  // are recomputed from current upstream data and shown WITHOUT committing. load()/current can still
  // return a saved processed snapshot; this command is the safe "show me the latest run" path.
  async function preview() {
    setSaving(true);
    try {
      const response = await api.payroll.preview(payload());
      applyPeriod(response.period);
      // B1 fix (issue #422): seed the result straight into the query cache so the NEXT background
      // poll (60s) diffs against this fresh value instead of the stale pre-preview one -- without
      // this, the UI would flash back to the old figures for up to a minute before the poll caught
      // up. suggestionsByEmployee/draftByEmployee are reset to {} to match applyPeriod's own
      // defaults above (preview does not re-consult either). `applyUatDefaults: false` matches
      // the DIRECT applyPeriod(response.period) call two lines up (which uses its own default of
      // `false`) -- see the "data arrived" effect's own comment for why this seed must mirror the
      // direct call's exact inputs, not just its output, for a redundant re-run to be genuinely
      // idempotent rather than a race that happened not to lose. `draftEtag: draftEtagRef.current`
      // is likewise the honest current value -- preview() never touches server-side drafts, so the
      // token genuinely has not changed.
      queryClient.setQueryData(queryKeys.payrollCurrent(month), {
        period: response.period,
        suggestionsByEmployee: {},
        draftByEmployee: {},
        draftEtag: draftEtagRef.current,
        applyUatDefaults: false,
      });
      showToast('success', 'คำนวณตัวอย่างเงินเดือนแล้ว');
    } catch (error) {
      showToast('error', error.message || 'คำนวณเงินเดือนไม่สำเร็จ');
    } finally {
      setSaving(false);
    }
  }

  // Payroll input draft (2026-07-30; Phase A autosave UI): persist exactly what HR has typed so a
  // browser reload does not lose it. Unfiltered by hasPayrollInput -- unlike payload() above, a
  // draft must also capture an employee HR has started but not finished (e.g. only a garnishment type
  // picked with no amount yet), not just what already qualifies for submission.
  async function saveDraft({ quiet = false } = {}) {
    if (!canSaveDraft) return;
    // P2 fix (issue #422, adversarial review, round 2): captured so the resolution below can
    // detect HR having since moved on from THIS EXACT visit to a month while this PUT was in
    // flight -- see monthVisitTokenRef's own comment for why this is a token, not `month` itself
    // (a value comparison alone is fooled by a round trip back to the same month).
    const visitTokenAtStart = monthVisitTokenRef.current;
    const draftPayloadValue = draftPayload();
    // A5 belt-and-braces (issue #422): MoneyInput and clampNonNegative already stop a negative
    // from being TYPED into any field on this page, but refuse to SEND one anyway -- a single
    // negative used to 400 the ENTIRE draft PUT with no visible error (issue #422 §A2/A5), taking
    // every OTHER employee's legitimate edits down with it. Naming exactly which employee/field is
    // wrong and refusing client-side is safer than silently clamping here: clamping would change a
    // number HR actually typed without them noticing.
    const negativeField = findNegativeAdjustmentField(draftPayloadValue.inputs);
    if (negativeField) {
      setDraftSaveStatus('error');
      showToast(
        'error',
        `บันทึกร่างไม่สำเร็จ — พบค่าติดลบในข้อมูลพนักงานรหัส ${negativeField.employeeId} กรุณาแก้ไขก่อนบันทึกอีกครั้ง`,
      );
      return;
    }
    const versionAtStart = draftVersionRef.current;
    // Optimistic concurrency (issue #422 follow-up): the token this exact attempt is conditioned
    // on, captured now rather than read again after the await -- see draftEtagRef's own comment
    // for why nothing else may refresh it while dirty, but a SUBSEQUENT saveDraft() call
    // overlapping this one (e.g. the auto-retry chain in `finally` below, or the explicit button
    // racing a blur) reads draftEtagRef.current fresh at ITS OWN call time, same as draftPayload().
    const ifMatchAtStart = draftEtagRef.current;
    setDraftSaving(true);
    setDraftSaveStatus('saving');
    try {
      const response = await api.payroll.saveInputDraft(draftPayloadValue, { ifMatch: ifMatchAtStart });
      // P2 fix (issue #422, adversarial review; round 2 corrected the guard from a month-VALUE
      // comparison to a visit-TOKEN comparison): if HR has since moved on from this exact visit
      // (a genuine month change, OR a round trip back to the same month -- July -> June -> July
      // bumps the token twice, so the second July visit's token differs from the first's even
      // though the month VALUE is identical again), this resolution belongs to a visit that is no
      // longer on screen. The badge/toast must not paint a save-state signal for the WRONG visit
      // (e.g. the first July visit's late success/failure flipping a freshly reloaded second July
      // visit's clean badge to "รอบันทึกอัตโนมัติ"/"บันทึกอัตโนมัติไม่สำเร็จ" for work that visit
      // never touched). The A4 month-change flush already accounts for the outgoing visit's own
      // final state when HR left it; this stale resolution has nothing further to contribute to
      // what's on screen now, so it is a no-op here. Deliberately also skips the etag update below
      // -- draftEtagRef now belongs to whatever month HR is actually looking at, and this response
      // is for the one they left.
      if (monthVisitTokenRef.current !== visitTokenAtStart) return;
      // The server just wrote successfully -- its returned ETag is unconditionally the new truth
      // for this month, regardless of whether draftVersionRef drifted further during the await
      // (that drift is a SEPARATE "is there more to save" concern, handled by the branch below).
      // Opus review finding F11: optional-chained -- apiRequest (client.js) returns `null` outright
      // for a 204 response, and `response.etag` on `null` would throw a TypeError INSIDE this try
      // block, getting caught by the catch below and reporting a write that actually SUCCEEDED as
      // a failure (with the stale token left in place). This endpoint always returns 200 with a
      // body today, but nothing about the shared `apiRequest` helper guarantees that stays true.
      draftEtagRef.current = response?.etag;
      draftConflictRef.current = false;
      setDraftConflictMessage(null);
      if (draftVersionRef.current === versionAtStart) {
        draftDirtyRef.current = false;
        setDraftDirty(false);
        setDraftSaveStatus('saved');
      } else {
        setDraftSaveStatus('dirty');
      }
      if (!quiet) showToast('success', 'บันทึกร่างข้อมูลเงินเดือนแล้ว');
    } catch (error) {
      // Same cross-visit guard as the success path above.
      if (monthVisitTokenRef.current !== visitTokenAtStart) return;
      // Optimistic concurrency (issue #422 follow-up): a 409 means another user (or another tab)
      // saved this month's draft first. Per the owner's explicit requirement, HR's typed values
      // must NOT be discarded and the form must stay dirty -- unlike every other failure branch
      // here, this one deliberately does not touch draftDirtyRef/setDraftDirty, and it does NOT
      // refresh draftEtagRef (the stale token stays stale on purpose, so a blind retry keeps
      // refusing instead of guessing). draftSaveStatus === 'conflict' is a new terminal state,
      // distinct from 'error', with its own persistent banner (below, near the badge) -- the
      // existing badge/toast machinery was built for a transient failure with an implicit retry,
      // not "someone else's data is now sitting on the server and yours would clobber it."
      //
      // Opus review finding F8: 428 (missing If-Match -- can happen if draftEtagRef never got a
      // real token, e.g. the initial GET itself failed) gets the SAME treatment as 409, not just
      // the generic error branch below. Without this, a 428 leaves draftConflictRef untouched, so
      // the `finally` retry chain AND every subsequent blur's autosaveDraft keep re-firing the
      // identical headerless PUT -- a request storm of doomed retries that can never succeed
      // without HR reloading, since draftEtagRef.current stays null either way.
      if (error?.status === 409 || error?.status === 428) {
        draftConflictRef.current = true;
        setDraftSaveStatus('conflict');
        const fallbackMessage = error?.status === 428
          ? 'ไม่พบข้อมูลอ้างอิงสำหรับบันทึกร่าง กรุณาโหลดหน้าใหม่แล้วลองอีกครั้ง'
          : 'มีผู้ใช้อื่นบันทึกร่างเงินเดือนของงวดนี้ไปแล้ว กรุณาโหลดข้อมูลใหม่ก่อนบันทึกอีกครั้ง';
        setDraftConflictMessage(error.message || fallbackMessage);
        showToast('error', error.message || fallbackMessage);
        return;
      }
      setDraftSaveStatus('error');
      // A3 fix (issue #422): a failed autosave used to be swallowed entirely (`if (!quiet)` also
      // guarded the ERROR toast, not just the success one) -- a 400/403/409 surfaced only as a
      // small badge in the page header, easy to miss and, before A1, not even rendered at all on
      // a PROCESSED/VOID period. Always visible now, quiet autosave or not.
      showToast('error', error.message || 'บันทึกร่างไม่สำเร็จ');
    } finally {
      setDraftSaving(false);
      if (
        monthVisitTokenRef.current === visitTokenAtStart
        && draftDirtyRef.current && draftVersionRef.current !== versionAtStart && canSaveDraft
        // Optimistic concurrency (issue #422 follow-up): never auto-chain a retry against a token
        // already known to be stale -- without this, every further keystroke would fire another
        // doomed PUT (and another 409 toast) until HR explicitly reloads.
        && !draftConflictRef.current
      ) {
        void saveDraft({ quiet: true });
      }
    }
  }

  function autosaveDraft() {
    // draftConflictRef.current: see saveDraft's own comment -- do not let the silent blur-autosave
    // spam repeated 409s once a conflict is showing. The explicit บันทึกร่าง button is NOT gated
    // the same way; HR may still choose to retry it (it will 409 again, harmlessly) or use the
    // conflict banner's "โหลดข้อมูลใหม่" action below.
    if (!canSaveDraft || !draftDirtyRef.current || draftSaving || draftConflictRef.current) return;
    saveDraft({ quiet: true });
  }

  // Optimistic concurrency (issue #422 follow-up): the conflict banner's explicit resolution --
  // HR chose to look at the other user's version rather than keep fighting a stale token. This is
  // the ONE place a background/automatic action is allowed to discard HR's own unsaved typing,
  // because it is not automatic: HR clicked it, after being told exactly why their save was
  // refused. Clearing draftDirtyRef BEFORE refetching lets the ordinary "data arrived" effect
  // apply the fresh server state the normal way, instead of duplicating applyPeriod's logic here.
  async function reloadAfterDraftConflict() {
    draftDirtyRef.current = false;
    setDraftDirty(false);
    draftConflictRef.current = false;
    setDraftSaveStatus('saved');
    setDraftConflictMessage(null);
    await payrollQuery.refetch();
  }

  // Issue #422 owner decision (one button, not two): the existing preview command doubles as the
  // draft-save trigger for HR instead of a second button -- PUT /input-draft, THEN POST /preview.
  // CEO cannot write a draft (PUT /input-draft is hasRole('HR') only), so the CEO branch (see the
  // button JSX below) calls `preview` directly instead of this, keeping the original คำนวณตัวอย่าง
  // label so it never promises a save CEO cannot perform.
  //
  // saveDraft() above never throws -- it catches its own errors and always toasts them (A3) -- so
  // awaiting it here and unconditionally continuing to `preview()` afterward satisfies the owner's
  // third condition verbatim: "if the draft PUT fails, still surface the error and still run the
  // preview (read-only, useful on its own) -- but the toast must say the draft did not save."
  // saveDraft's own error toast already says exactly that; nothing extra is needed here.
  //
  // Opus review finding F4: that "unconditionally" was too unconditional. preview() calls
  // applyPeriod(response.period) DIRECTLY, bypassing the dirty-guard the "data arrived" effect
  // otherwise respects, and its own payload() filters inputs by hasPayrollInput -- unlike
  // draftPayload(), which submits every touched employee unfiltered specifically so a
  // started-but-unfinished row (e.g. a garnishment type picked with no amount yet) is not lost.
  // Running preview() after a 409/428 therefore painted a period built from a payload that had
  // ALREADY silently dropped that unfinished row, wiping it from the form even though the promise
  // in this function's own comment ("HR's typed values must NOT be discarded") was upheld inside
  // saveDraft() itself. Skip preview() outright when the draft save hit a conflict -- the banner
  // already tells HR what happened and what to do; there is nothing useful preview() can safely
  // show without repeating the same clobber.
  async function saveDraftAndPreview() {
    setSaving(true);
    await saveDraft({ quiet: false });
    if (draftConflictRef.current) {
      setSaving(false);
      return;
    }
    await preview(); // preview() owns `saving` for the rest of this action, incl. the final reset.
  }

  function handleAdjustmentBlur(event) {
    if (typeof event.target?.matches !== 'function') return;
    if (!event.target.matches('input, select, textarea')) return;
    autosaveDraft();
  }

  function process() {
    setConfirmProcess(true);
  }

  async function confirmProcessPayroll() {
    setSaving(true);
    try {
      const response = await api.payroll.process(payload());
      applyPeriod(response.period);
      // Optimistic concurrency (issue #422 follow-up): process() clears the server's draft rows
      // out from under whatever token draftEtagRef was holding, so the token itself is now stale
      // too, not just the local adjustments. Re-fetch it explicitly rather than assuming the
      // well-defined empty-set token's literal value here -- that value is PayrollDraftETag's to
      // define, not this page's to duplicate. Best-effort: if this fails, draftEtagRef falls back
      // to null, and the next save attempt gets a clean 428 asking HR to reload, rather than a
      // wrong/stale token risking a silent bad match. Fetched BEFORE the cache seed below so the
      // seed can carry the correct value in one shot -- see preview()'s own comment for why the
      // seed's shape must match the queryFn's natural shape at all (structural-sharing parity),
      // not just why the token itself matters.
      let freshDraftEtag = null;
      try {
        const freshDraft = await api.payroll.getInputDraft({ payrollMonth: month });
        freshDraftEtag = freshDraft?.etag ?? null;
      } catch {
        freshDraftEtag = null;
      }
      draftEtagRef.current = freshDraftEtag;
      // B1 fix (issue #422): same cache-seed as preview() above, same reasoning -- keeps the next
      // background poll from flashing back to the pre-process figures for up to a minute.
      // `applyUatDefaults: false` matches applyPeriod(response.period)'s own default two lines up
      // -- see preview()'s identical seed for why this must mirror the direct call's inputs, not
      // just rely on `status` already being 'PROCESSED' (true today, but this keeps the seed
      // correct on its own terms rather than by coincidence of a status this code doesn't check).
      queryClient.setQueryData(queryKeys.payrollCurrent(month), {
        period: response.period,
        suggestionsByEmployee: {},
        draftByEmployee: {},
        draftEtag: freshDraftEtag,
        applyUatDefaults: false,
      });
      // The backend clears hr.payroll_input_draft for this month once processed (see
      // PayrollService#process) -- there is nothing left to save a draft over now.
      draftDirtyRef.current = false;
      setDraftDirty(false);
      setDraftSaveStatus('saved');
      draftConflictRef.current = false;
      setDraftConflictMessage(null);
      showToast('success', 'ประมวลผลเงินเดือนเรียบร้อย');
      setConfirmProcess(false);
    } catch (error) {
      showToast('error', error.message || 'ประมวลผลเงินเดือนไม่สำเร็จ');
    } finally {
      setSaving(false);
    }
  }

  async function generateExportFile(kindValue) {
    const kind = EXPORT_KINDS.find((item) => item.value === kindValue) || EXPORT_KINDS[0];
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
    draftVersionRef.current += 1;
    const nextAdjustments = {
      ...adjustmentsRef.current,
      [selectedLine.employeeId]: {
        ...(adjustmentsRef.current[selectedLine.employeeId] || blankAdjustment(selectedLine.employeeId)),
        [field]: value,
      },
    };
    adjustmentsRef.current = nextAdjustments;
    setAdjustments(nextAdjustments);
    // Payroll input draft (2026-07-30): any typed change is not yet saved anywhere durable until
    // the blur autosave succeeds (or Process, which persists it for real).
    draftDirtyRef.current = true;
    setDraftDirty(true);
    setDraftSaveStatus('dirty');
  }

  const hasLoadedLines = period?.lineCount > 0;
  const hasSavedPeriod = Boolean(period?.id);
  const hasProcessedPeriod = hasSavedPeriod && period?.status === 'PROCESSED';
  const disabledBecauseBusy = saving ? 'กำลังทำรายการอื่นอยู่' : null;
  const needsSavedPeriod = 'ต้องมีรอบเงินเดือนที่บันทึกแล้วก่อน';
  const needsProcessedPeriod = 'ต้องประมวลผลเงินเดือนก่อนสร้างเอกสารนี้';
  const needsLines = 'กดคำนวณตัวอย่างเพื่อสร้างรายการเงินเดือนก่อน';
  const payrollDocumentItems = [
    ...EXPORT_KINDS.map((kind) => {
      const isDetailKind = kind.value === 'payroll-detail';
      const disabledReason = disabledBecauseBusy
        || (isDetailKind
          ? (!hasLoadedLines ? needsLines : null)
          : (!hasSavedPeriod ? needsSavedPeriod : null));
      return {
        key: `export-${kind.value}`,
        label: `ดาวน์โหลด ${kind.label}`,
        icon: 'fileText',
        onSelect: () => generateExportFile(kind.value),
        disabled: Boolean(disabledReason),
        disabledReason,
      };
    }),
    {
      key: 'payslips-zip',
      label: 'ดาวน์โหลดสลิปเงินเดือนทั้งหมด',
      icon: 'fileText',
      onSelect: downloadPayslipsZip,
      disabled: Boolean(disabledBecauseBusy || !hasProcessedPeriod),
      disabledReason: disabledBecauseBusy || (!hasSavedPeriod ? needsSavedPeriod : period?.status !== 'PROCESSED' ? needsProcessedPeriod : null),
    },
    // POST /{periodId}/distribute is hasRole('HR') only -- omitted entirely for a read-only CEO
    // session rather than shown disabled (nothing on this page lets CEO reach a state where it
    // would ever become available, so a disabled entry would just be dead weight in the menu).
    ...(canManage ? [{
      key: 'email-payslips',
      label: 'ส่งอีเมลสลิปเงินเดือน',
      icon: 'mail',
      onSelect: distributePayslips,
      disabled: Boolean(disabledBecauseBusy || !hasSavedPeriod),
      disabledReason: disabledBecauseBusy || (!hasSavedPeriod ? needsSavedPeriod : null),
    }] : []),
  ];
  // Optimistic concurrency (issue #422 follow-up): 'conflict' takes priority over every other
  // status -- it is the one state where "keep waiting/retrying" is actively wrong (the token is
  // known-stale until HR explicitly reloads via the banner below).
  const draftStatusLabel = draftSaveStatus === 'conflict'
    ? 'มีการบันทึกที่ขัดแย้งกัน'
    : draftSaveStatus === 'error'
      ? 'บันทึกอัตโนมัติไม่สำเร็จ'
      : draftSaving
        ? 'กำลังบันทึก…'
        : draftDirty
          ? 'รอบันทึกอัตโนมัติ'
          : 'บันทึกแล้ว';
  const taxAndSsoTotal = Number(period?.totalWithholdingTax || 0) + Number(period?.totalSocialSecurity || 0);
  const payrollSummaryItems = [
    { key: 'status', label: 'สถานะรอบ', value: status.label, helper: period?.status === 'PREVIEW' ? 'ยังไม่ปิดรอบ' : 'สถานะเงินเดือน' },
    { key: 'gross', label: 'รายได้รวม', value: formatMoney(period?.totalGross), helper: 'Gross earnings' },
    { key: 'deductions', label: 'เงินหักรวม', value: formatMoney(period?.totalDeductions), helper: `ภาษี/ปกส. ${formatMoney(taxAndSsoTotal)}` },
    { key: 'net', label: 'ยอดโอนสุทธิ', value: formatMoney(period?.totalNet), helper: 'Net transfer' },
  ];

  return (
    <PageStack className="mobile:gap-3">
      <PageHeader
        title="ประมวลผลเงินเดือน"
        subtitle="Payroll Processing"
        actions={(
          <div className="toolbar-actions mobile:w-full mobile:items-stretch mobile:gap-2">
            {/* Read-only oversight indicator (issue #390): the backend deliberately grants CEO a
                read of everything on this page (PayrollController: every GET plus the
                non-persisting preview endpoints are hasAnyRole('HR','CEO')) while every write
                stays HR-only -- this badge is the one visible cue that the missing controls below
                are intentional, not a bug. */}
            {!canManage && (
              <StatusBadge tone="info">มุมมองสำหรับผู้บริหาร — อ่านอย่างเดียว</StatusBadge>
            )}
            <label>
              รอบเดือน
              <input
                type="month"
                value={month}
                onChange={(event) => setMonth(event.target.value)}
                className="mobile:min-h-[44px]"
              />
              {/* The native <input type="month"> always renders Gregorian and cannot show Buddhist
                  era; this is a read-only display of the same value, not a second control. */}
              <small className="font-normal text-text-muted">({formatThaiMonthYearFromMonthInputValue(month)})</small>
            </label>
            {canSaveDraft && (
              <span
                role="status"
                className={cn(
                  'inline-flex items-center rounded-sm border px-2 py-1 text-xs font-extrabold',
                  draftSaveStatus === 'conflict' || draftSaveStatus === 'error'
                    ? 'border-danger-border bg-danger-bg text-danger'
                    : draftDirty || draftSaving
                      ? 'border-warning-border bg-warning-bg-soft text-warning'
                      : 'border-success-bg bg-success-bg text-success',
                )}
              >
                {draftStatusLabel}
              </span>
            )}
          </div>
        )}
      />

      {/* Optimistic concurrency (issue #422 follow-up): the persistent conflict message the plan
          requires -- unlike the header badge above (a small, easy-to-miss status word) and the
          global toast (auto-dismisses after 3.2s, see useToast.js), this banner stays on screen
          until HR explicitly acts, because "another user saved this month's draft" is exactly the
          kind of thing a transient notification is wrong for: HR's own typed values are still
          sitting in the form, unsaved, and must not be quietly abandoned. */}
      {canSaveDraft && draftSaveStatus === 'conflict' && (
        <div
          role="alert"
          className="flex flex-wrap items-center justify-between gap-3 rounded-md border border-danger-border bg-danger-bg px-4 py-3 text-sm font-bold text-danger"
        >
          <span>
            {draftConflictMessage
              || 'มีผู้ใช้อื่นบันทึกร่างเงินเดือนของงวดนี้ไปแล้ว ข้อมูลที่คุณพิมพ์ไว้ยังไม่ได้บันทึกและยังอยู่ในฟอร์ม กรุณาโหลดข้อมูลใหม่ก่อนบันทึกอีกครั้ง'}
          </span>
          <Button type="button" variant="secondary" onClick={reloadAfterDraftConflict}>
            <Icon name="refresh" />
            โหลดข้อมูลใหม่
          </Button>
        </div>
      )}

      {/* Issue #394 fix: a bare "-" in all four tiles read as broken UI, not "no
          data yet". `loading` now drives CompactStatRow's own skeleton state
          (already built for exactly this, just never wired up here) for the
          normal fetch-in-flight window; the true empty case — no period at all
          once loading has settled (e.g. the initial load failed) — gets a real
          empty state explaining what would populate the strip, instead of four
          silent dashes. */}
      {!loading && !period ? (
        <EmptyState
          icon="badgeDollar"
          title="ยังไม่มีข้อมูลสรุปเงินเดือน"
          description="เลือกรอบเดือนที่มีข้อมูลพนักงาน หรือกดคำนวณตัวอย่างเพื่อโหลดข้อมูล"
        />
      ) : (
        <CompactStatRow
          items={payrollSummaryItems}
          loading={loading}
        />
      )}

      {/* Phase A command surface: routine commands are Preview + one labelled document menu. Process
          stays in its own Tailwind-styled region so the irreversible action remains visually apart
          without keeping dead `.payroll-actions-*` legacy rules in styles.css. */}
      <FilterBar className="flex-col items-stretch gap-[14px]">
        <div className="flex flex-wrap items-center gap-3">
          {/* Review — safe, read-only recompute. Issue #422 owner decision (one button, not two):
              for HR this doubles as the draft-save trigger (บันทึกร่าง; saveDraftAndPreview PUTs
              the draft, THEN previews) instead of a second button. CEO cannot write a draft
              (PUT /input-draft is hasRole('HR') only), so this branches on canManage rather than
              canSaveDraft -- the label must never promise a save CEO cannot perform, even on a
              render where canSaveDraft happens to be false for HR too (e.g. period not loaded
              yet). */}
          <div className="flex flex-wrap items-center gap-2">
            <Button
              type="button"
              onClick={canManage ? saveDraftAndPreview : preview}
              disabled={loading || saving}
              title={canManage
                ? 'บันทึกร่างข้อมูลเงินเดือนที่พิมพ์ไว้ แล้วคำนวณตัวอย่างจากข้อมูลล่าสุด'
                : 'คำนวณตัวอย่างจากข้อมูลล่าสุด โดยยังไม่ประมวลผลเงินเดือน'}
            >
              <Icon name={canManage ? 'save' : 'search'} />
              {canManage ? 'บันทึกร่าง' : 'คำนวณตัวอย่าง'}
            </Button>

            {/* Phase A: the export format selector became four document menu actions, but the
                effective date is still an explicit field because PayrollService uses it for KBank,
                PND1, SSO, and payroll-detail filenames. Hiding it behind only the KBank action would
                make the UI contradict the backend contract. */}
            <label className="m-0 inline-flex items-center gap-2 text-sm font-extrabold">
              วันที่เอกสาร
              <input
                type="date"
                value={payDate}
                onChange={(event) => setPayDate(event.target.value)}
                disabled={saving || !(period?.lineCount > 0)}
                className="w-[9.5rem] mobile:min-h-[44px]"
              />
              <small className="font-normal text-text-muted">({formatShortDate(payDate)})</small>
            </label>

            <OverflowMenu
              label="เอกสาร"
              triggerLabel="เอกสาร"
              triggerIcon="fileText"
              items={payrollDocumentItems}
            />
          </div>

          {/* Run — the irreversible action. Kept in its own region with the period state and pushed
              after routine preview/export/send work. Phone stays editable; the extra mobile-only
              typed phrase lives in the confirm dialog instead of disabling this button outright.
              POST /process is hasRole('HR') only — a CEO session never sees this button at all
              (not disabled: no state would ever enable it for that role), only the same period
              status in a neutral, non-actionable readout.

              Issue #394: within the HR branch, the region's border only turns danger-red once the
              action is actually armed (a real period with lines to process, ready to fire an
              irreversible commit) — an unmet precondition (emptyPeriod, see processBlockedReason)
              is a disabled control waiting on data, not a failure, so it reads neutral instead.
              Red on a screen that also surfaces genuine payroll failures must stay reserved for
              genuine failures, or HR learns to ignore it. */}
          {canManage ? (
            <section
              className={cn(
                'payroll-process-region ml-auto flex flex-wrap items-center gap-x-3.5 gap-y-2.5 rounded-md border bg-surface-muted px-3 py-2.5 nav-drawer:ml-0 nav-drawer:w-full nav-drawer:justify-between mobile:items-stretch',
                emptyPeriod ? 'border-border' : 'border-danger-border',
              )}
              aria-labelledby="payroll-process-title"
            >
              <div className="grid min-w-0 gap-1">
                <span id="payroll-process-title" className="text-sm font-black text-text">ปิดรอบเงินเดือน</span>
                <small className="flex flex-wrap items-center gap-1.5 text-xs font-extrabold text-text-muted">
                  <StatusBadge tone={status.tone}>{status.label}</StatusBadge>
                  <span>{period?.lineCount || 0} คน</span>
                </small>
              </div>
              <div className="grid min-w-0 gap-1 mobile:w-full">
                <Button
                  type="button"
                  variant="danger"
                  size="sm"
                  className="mobile:w-full"
                  onClick={process}
                  disabled={loading || saving || emptyPeriod}
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
                    future caller adds here. An `info` icon carries the same "this is
                    guidance, not an alarm" signal non-visually too (WCAG 1.4.1 — the
                    disabled state must not rely on the border colour alone). */}
                {processBlockedReason && (
                  <span
                    id="payroll-process-block-reason"
                    role="note"
                    className="flex max-w-[34ch] items-start gap-1 text-xs font-semibold text-text-muted"
                  >
                    <Icon name="info" size={13} className="mt-0.5 shrink-0" />
                    {processBlockedReason}
                  </span>
                )}
              </div>
            </section>
          ) : (
            <section
              className="ml-auto flex items-center gap-2 rounded-md border border-border bg-surface-muted px-3 py-2.5 nav-drawer:ml-0 nav-drawer:w-full mobile:items-stretch"
              aria-label="สถานะรอบเงินเดือน"
            >
              <StatusBadge tone={status.tone}>{status.label}</StatusBadge>
              <span className="text-xs font-extrabold text-text-muted">{period?.lineCount || 0} คน</span>
            </section>
          )}
        </div>
      </FilterBar>

      {/* P0 fix (Opus review, 2026-07-30): the withholding-tax classification matrix screen. Without
          this, HR had no way to satisfy PayrollCalculator#calculateClassified's classification gate
          for any component beyond the V100 backfill defaults — a screen HR can actually use, per spec
          sections 1 and 10. Collapsed by default so it does not compete with the main payroll
          workflow above; one employee per sub-section, per the owner's "never a shrunken
          fifteen-column table" mobile decision (section 9c). */}
      <TaxTreatmentMatrixSection payrollMonth={month} showToast={showToast} canManage={canManage} />

      {/* B1/B4 fix (Opus review, 2026-07-31): layout moved to Tailwind utilities at the call site
          (CLAUDE.md Tailwind-first) instead of a `.payroll-workspace` styles.css rule -- two states,
          not three: below the `payroll-wide:` custom variant (the panel's overlay floor, see the
          isDesktopPanel comment above) the table is the only in-flow column; at >=1366px a second
          `minmax(310px, 0.3fr)` column holds the sticky side panel. `minmax(0, ...)` on both tracks
          deliberately, never a bare `1fr`/`0.35fr` -- a bare fr track floors at min-content and was
          the root cause of the panel-pushed-off-canvas regression this fix replaces.

          Phase A (2026-07-31): shrinking the panel floor to 310px lets 1366px keep the persistent
          side panel while still paying for the table's 714px floor and the 16px gap. 1280px remains
          overlay, where the content width still cannot afford both. */}
      <section className="grid grid-cols-[minmax(0,1fr)] payroll-wide:grid-cols-[minmax(0,1fr)_minmax(310px,0.3fr)] gap-4 items-start">
        {/* B2 fix (Opus review, 2026-07-31): while the detail panel is a genuine overlay dialog
            (<1366px, open), the rest of the page behind the dim backdrop must be unreachable, not
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
            rowClassName={(line) => cn(
              // `.payroll-row.active` uses box-shadow for the selection inset, so focus has to
              // re-apply the shared ring token from the Tailwind utilities layer where it wins.
              'payroll-row cursor-pointer focus-visible:outline-none focus-visible:shadow-[var(--shadow-focus-ring)] mobile:gap-y-2 mobile:p-3.5',
              Number(line.employeeId) === Number(selectedLine?.employeeId) && 'active',
            )}
            loading={loading}
            stickyHeader
            showPagination={false}
            caption="รายการเงินเดือนพนักงานในรอบที่เลือก"
            onRowSelect={selectPayrollLine}
            isRowSelected={(line) => Number(line.employeeId) === Number(selectedLine?.employeeId)}
            focusRowOnSelectClick={isOverlayPanel}
            footerRow={({ columns: renderedColumns }) => (
              <PayrollTotalsFooter columns={renderedColumns} reconciliation={payrollReconciliation} />
            )}
            emptyState={{
              icon: 'badgeDollar',
              title: 'ยังไม่มีข้อมูลเงินเดือน',
              description: 'เลือกรอบเดือนหรือกดคำนวณตัวอย่าง',
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
          // the panel is a sticky side column at >=1366px and a fixed-width overlay below that, so
          // its width is not a function of viewport width alone (a `sm:`/`md:` viewport breakpoint
          // would be lying about what's actually being measured). See the `@sm:grid-cols-2` classes
          // on payroll-detail-grid/payroll-special-grid below.
          className={cn(
            PANEL_CLASS,
            'payroll-detail-panel',
            '@container',
            'mobile:inset-0 mobile:max-h-[100dvh] mobile:w-full mobile:rounded-none mobile:border-0 mobile:p-4 mobile:pt-0 mobile:[&_input:not([type=checkbox])]:min-h-[44px] mobile:[&_select]:min-h-[44px]',
            detailOpen && 'is-open',
          )}
          onBlurCapture={handleAdjustmentBlur}
          // B2 fix (Opus review, 2026-07-31): dialog semantics apply ONLY in overlay mode (<1366px) --
          // at >=1366px this is a persistent side panel beside the table, not a dialog stealing focus,
          // so role/aria-modal/aria-labelledby must not be present there (an always-on role="dialog"
          // on a panel that never traps focus or blocks the rest of the page would be actively wrong).
          role={isOverlayPanel && detailOpen ? 'dialog' : undefined}
          aria-modal={isOverlayPanel && detailOpen ? 'true' : undefined}
          aria-labelledby={isOverlayPanel && detailOpen ? 'payroll-detail-title' : undefined}
        >
          {selectedLine && selectedAdjustment ? (
            <>
              <Panel.Header className="payroll-detail-panel-header mobile:sticky mobile:top-0 mobile:z-10 mobile:-mx-4 mobile:mb-4 mobile:border-b mobile:border-border mobile:bg-surface mobile:px-4 mobile:py-3">
                <div>
                  <h2 id="payroll-detail-title" className="m-0 text-lg">{selectedLine.employeeName}</h2>
                  <small>{selectedLine.employeeCode} · {selectedLine.departmentName || '-'}</small>
                </div>
                <div className="payroll-detail-header-actions">
                  <StatusBadge tone="info">{selectedLine.employeeCode}</StatusBadge>
                  {/* Rendered only in the <1366px overlay presentation. At >=1366px the panel is a
                      persistent side column whose visibility is NOT gated by `detailOpen` at all
                      (only the overlay's CSS transform states are), so a dismiss control there is
                      inert -- it would set `detailOpen=false` and nothing would visibly happen.

                      This is a conditional RENDER rather than a `display` toggle on purpose. The
                      previous attempt lived in styles.css (`.payroll-detail-close { display: none }`
                      plus an `inline-flex` override under `max-width: 1365px`) and was completely
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
                        // A5 fix (issue #422): this raw <input type="number"> has no equivalent of
                        // MoneyInput's own negative clamp -- a typed "-5" reached updateAdjustment
                        // verbatim and 400'd the whole draft/preview/process PUT with no visible
                        // error.
                        onChange={(event) => updateAdjustment('daysWorked', clampNonNegative(event.target.value))}
                        disabled={!canManage}
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
                    the panel's current 310px floor (see the `<section>` grid-cols comment above)
                    without having to keep growing the panel indefinitely as more fields land here. */}
                <div className="payroll-special-grid grid grid-cols-1 gap-2.5 @sm:grid-cols-2">
                  {specialPayFields.map((field) => {
                    const inputId = `payroll-${field.key}`;
                    return (
                      <label key={field.key} htmlFor={inputId}>
                        {field.label}
                        <MoneyInput id={inputId} value={selectedAdjustment[field.key]} onChange={(value) => updateAdjustment(field.key, value)} disabled={!canManage} />
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
                    <MoneyInput id="payroll-meal-allowance" value={selectedAdjustment.mealAllowance} onChange={(value) => updateAdjustment('mealAllowance', value)} disabled={!canManage} />
                  </label>
                  <label htmlFor="payroll-per-diem-exempt">
                    เบี้ยเลี้ยง — ส่วนที่ยกเว้นภาษี (ม.42)
                    <InfoTip
                      label="เบี้ยเลี้ยง — ส่วนที่ยกเว้นภาษี (ม.42)"
                      text="ส่วนของเบี้ยเลี้ยงที่อยู่ภายในอัตราที่ได้รับการยกเว้นภาษีตามมาตรา 42 กรอกแยกจากส่วนเกิน"
                    />
                    <MoneyInput id="payroll-per-diem-exempt" value={selectedAdjustment.perDiemExempt} onChange={(value) => updateAdjustment('perDiemExempt', value)} disabled={!canManage} />
                  </label>
                  <label htmlFor="payroll-per-diem-taxable">
                    เบี้ยเลี้ยง — ส่วนเกิน (เสียภาษี)
                    <InfoTip
                      label="เบี้ยเลี้ยง — ส่วนเกิน (เสียภาษี)"
                      text="ส่วนของเบี้ยเลี้ยงที่เกินอัตรายกเว้นตามมาตรา 42 ถือเป็นเงินได้ต้องเสียภาษีตามปกติ"
                    />
                    <MoneyInput id="payroll-per-diem-taxable" value={selectedAdjustment.perDiemTaxable} onChange={(value) => updateAdjustment('perDiemTaxable', value)} disabled={!canManage} />
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
                        disabled={!canManage}
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
                        <MoneyInput id={inputId} value={selectedAdjustment[field.key]} onChange={(value) => updateAdjustment(field.key, value)} disabled={!canManage} />
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
                    <MoneyInput id="payroll-non-taxable-income" value={selectedAdjustment.nonTaxableIncome} onChange={(value) => updateAdjustment('nonTaxableIncome', value)} disabled={!canManage} />
                  </label>
                </FormGrid>
              </CollapsibleSection>

              <CollapsibleSection title="รายการหักรายบุคคล" defaultOpen={false}>
                <FormGrid>
                  <label htmlFor="payroll-unpaid-leave-days">
                    วันลาไม่รับค่าจ้าง
                    <InfoTip label="วันลาไม่รับค่าจ้าง" text="ตัวเลขนี้ถูกกรอกล่วงหน้าจากวันลาที่อนุมัติเกินโควตาในเดือนนี้ (ระบบวันลา) สามารถแก้ไขได้ก่อนคำนวณ/ประมวลผล" />
                    {/* A5 fix (issue #422): same clamp as daysWorked above -- this raw
                        <input type="number"> has no equivalent of MoneyInput's own negative clamp. */}
                    <input id="payroll-unpaid-leave-days" type="number" min="0" step="0.25" placeholder="0" value={selectedAdjustment.unpaidLeaveDays} onChange={(event) => updateAdjustment('unpaidLeaveDays', clampNonNegative(event.target.value))} disabled={!canManage} />
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
                    <MoneyInput id="payroll-student-loan-deduction" value={selectedAdjustment.studentLoanDeduction} onChange={(value) => updateAdjustment('studentLoanDeduction', value)} disabled={!canManage} />
                  </label>
                  <label htmlFor="payroll-legal-execution-deduction">
                    หักอายัดกรมบังคับคดี
                    <InfoTip label="หักอายัดกรมบังคับคดี" text="รายการหักตามคำสั่งอายัดเงินเดือนจากกรมบังคับคดี หักหลังคำนวณภาษีแล้ว" />
                    <MoneyInput id="payroll-legal-execution-deduction" value={selectedAdjustment.legalExecutionDeduction} onChange={(value) => updateAdjustment('legalExecutionDeduction', value)} disabled={!canManage} />
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
                        disabled={!canManage}
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
                    <MoneyInput id="payroll-other-post-tax-deductions" value={selectedAdjustment.otherPostTaxDeductions} onChange={(value) => updateAdjustment('otherPostTaxDeductions', value)} disabled={!canManage} />
                  </label>
                  <label htmlFor="payroll-withholding-tax-override">
                    ภาษีหัก ณ ที่จ่าย (กำหนดเอง)
                    <InfoTip
                      label="ภาษีหัก ณ ที่จ่าย (กำหนดเอง)"
                      text="กรอกจำนวนภาษีที่ต้องการหักจริงสำหรับพนักงานคนนี้ในงวดนี้ จะแทนที่ค่าที่ระบบคำนวณ (และค่ามาตรฐานที่ตั้งไว้ในประวัติพนักงาน) เว้นว่างเพื่อใช้ค่าที่คำนวณอัตโนมัติ การคำนวณภาษีทั้งปียังคงเดิม เปลี่ยนเฉพาะยอดหักจริงงวดนี้"
                    />
                    <MoneyInput id="payroll-withholding-tax-override" value={selectedAdjustment.withholdingTaxOverride} onChange={(value) => updateAdjustment('withholdingTaxOverride', value)} disabled={!canManage} />
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
                    <MoneyInput id="payroll-warning-letter-deduction" value={selectedAdjustment.warningLetterDeduction} onChange={(value) => updateAdjustment('warningLetterDeduction', value)} disabled={!canManage} />
                  </label>
                  <label htmlFor="payroll-customer-return-deduction">
                    หักลูกค้าคืนสินค้า
                    <MoneyInput id="payroll-customer-return-deduction" value={selectedAdjustment.customerReturnDeduction} onChange={(value) => updateAdjustment('customerReturnDeduction', value)} disabled={!canManage} />
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
                        disabled={!canManage}
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
                    <MoneyInput id="payroll-other-pretax-deduction" value={selectedAdjustment.otherPretaxDeduction} onChange={(value) => updateAdjustment('otherPretaxDeduction', value)} disabled={!canManage} />
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

              {/* ล.ย.01 tax-allowance drill-down (issue #387 screen 3) — surgical addition below
                  the existing ค่าลดหย่อนรวม line above. Composes the year's declaration register
                  client-side (no employeeId filter exists on GET /declarations) — see
                  TaxAllowanceDrilldown.jsx. */}
              <CollapsibleSection title="รายละเอียดค่าลดหย่อน (ล.ย.01)" defaultOpen={false}>
                <TaxAllowanceDrilldown
                  employeeId={selectedLine.employeeId}
                  employeeCode={selectedLine.employeeCode}
                  taxYear={Number(month.slice(0, 4))}
                />
              </CollapsibleSection>
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
        requireReason={requiresMobileProcessPhrase}
        reasonLabel="ยืนยันบนมือถือ"
        reasonPlaceholder={`พิมพ์คำว่า ${MOBILE_PROCESS_CONFIRM_PHRASE}`}
        validateReason={(value) => value === MOBILE_PROCESS_CONFIRM_PHRASE}
        reasonInvalidMessage={`พิมพ์คำว่า ${MOBILE_PROCESS_CONFIRM_PHRASE} เพื่อยืนยันบนมือถือ`}
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
function TaxTreatmentMatrixSection({ payrollMonth, showToast, canManage }) {
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
                <FormGrid className="mobile:[&_select]:min-h-[44px]">
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
                          disabled={!canManage}
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
        {/* PUT /component-tax-treatments is hasRole('HR') only -- omitted for a read-only CEO
            session (the selects above are disabled too, so there is never anything to save). */}
        {canManage && (
          <Button type="button" onClick={save} disabled={loading || saving || !hasEdits}>
            <Icon name="check" />
            บันทึกการจัดประเภท
          </Button>
        )}
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

function MoneyInput({ id, value, onChange, disabled = false }) {
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
        disabled={disabled}
      />
    </span>
  );
}
