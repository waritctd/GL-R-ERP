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
import { bangkokMonthStartIso, formatMoney, formatShortDate, formatThaiMonthYearFromMonthInputValue, payrollStatusLabel as statusInfo } from '../../utils/format.js';
import { ThaiDateInput } from '../../components/common/ThaiDateInput.jsx';

// Reproduces `.panel` for the detail sidebar, which must stay a real <aside> element — Layout.jsx's Panel
// always renders a <section> and has no `as` prop, so the exact utility string is duplicated here rather than
// wrapping a <section> inside an <aside> or changing the shared primitive.
const PANEL_CLASS = 'bg-surface border border-border rounded-md p-5';

// The payroll export files HR can generate for a processed period. `value` is the backend export
// slug (/api/payroll/{id}/export/{value}); `filePrefix` names the downloaded blob; `extension`
// MIRRORS PayrollExportKind's file extension and is not derived from it (kbank/pnd1 are CP874 .txt,
// sso and payroll-detail are xlsx). Nothing enforces that this list matches the enum: the server
// sends the bytes, but the filename is built here, so a backend extension change not mirrored here
// saves the right bytes under the wrong suffix. Change both together.
const EXPORT_KINDS = [
  { value: 'kbank', label: 'KBank Payroll', filePrefix: 'PCT', extension: 'txt' },
  { value: 'pnd1', label: 'ภ.ง.ด.1', filePrefix: 'Pnd1', extension: 'txt' },
  { value: 'sso', label: 'ประกันสังคม (สปส.1-10) (Excel)', filePrefix: 'SPS1-10', extension: 'xlsx' },
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
      // Tailwind utilities at the call site, not styles.css rules (Tailwind-first).
      //
      // `min-w-0` on the inner span only lets the flex item shrink; it does not clip the text inside, so
      // a long name would paint past its edge and under the `.payroll-selected-cue` badge (a later
      // sibling). The legacy `.data-row > td > span/strong/small` block+ellipsis rule lives in
      // `@layer legacy` and Tailwind utilities always win over it, so it cannot be relied on here.
      // `<strong>`/`<small>` are inline and only stack because of that rule, so each needs its own
      // `block truncate` to clip at its own width instead of overflowing into the badge.
      const warning = line.payType === 'D' && !(Number(line.daysWorked) > 0)
        ? '⚠ ยังไม่ได้ระบุจำนวนวันทำงาน'
        : null;
      return (
        <>
          <span className="flex items-center justify-between gap-2.5 min-w-0 mobile:hidden">
            <span className="min-w-0 overflow-hidden">
              <strong className="block truncate">{line.employeeName}</strong>
              <small className="block truncate">{line.employeeCode} · {line.departmentName || '-'}</small>
              {/* List-level warning so a daily-rate employee with no/zero days-worked is visible without opening
                  the detail panel (hasPayrollInput never submits a row with nothing entered, so HR could Process
                  a month without noticing). Only a nudge; PayrollService#requireEveryDailyRateEmployeeHasDaysWorked
                  is the real enforcement. */}
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

const specialPayFields = [
  // Numbering aligned to the accountant's workbook (2026.xlsx); see PayrollService#specialPayDtos.
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

// ค่าอาหาร and เบี้ยเลี้ยง (ตจว/ตปท): real taxable columns in the accountant's ledger (2026.xlsx cols
// K and P). Not พิเศษ slots: the workbook doesn't number them as พิเศษ either.
const namedAllowanceFields = [
  { key: 'mealAllowance', label: 'ค่าอาหาร' },
  // เบี้ยเลี้ยง is entered as two amounts because มาตรา 42 exempts it only PARTIALLY — the portion within the
  // government rate is exempt, the excess is taxable. HR makes the split; the system can't, without knowing
  // destination and employee grade.
  { key: 'perDiemExempt', label: 'เบี้ยเลี้ยง — ส่วนที่ยกเว้นภาษี (ม.42)' },
  { key: 'perDiemTaxable', label: 'เบี้ยเลี้ยง — ส่วนเกิน (เสียภาษี)' },
];

// The two มาตรา 42 limbs a เบี้ยเลี้ยง payment can be made under (backend PerDiemBasis). Values must
// match that enum's names exactly; they are sent verbatim as `perDiemBasis`. Required only when a
// per-diem amount is entered: chk_payroll_line_per_diem_basis_present rejects the Process insert.
const PER_DIEM_BASIS_OPTIONS = [
  { value: 'FLAT_RATE_S42_2', label: 'เหมาจ่ายตามอัตราราชการ (ม.42(2)) — ไม่ต้องมีใบเสร็จ' },
  { value: 'REIMBURSED_S42_1', label: 'จ่ายจริงตามหน้าที่ (ม.42(1)) — ต้องมีใบเสร็จ' },
];
// เงินโบนัส / อื่นๆ: the dedicated one-off fields the backend carries (bonusPay is the archetypal
// EXTRA_KNOWN_FREQUENCY component).
const oneOffPayFields = [
  { key: 'bonusPay', label: 'เงินโบนัส' },
  { key: 'otherOneOffPay', label: 'เงินก้อนอื่นๆ (ครั้งเดียว)' },
];
// Which statutory cap applies to a legal-execution garnishment (backend PayrollGarnishmentType).
// Unset falls back to the SALARY cap in PayrollCalculator, which is wrong for a bonus, OT/diligence
// or severance payout (BONUS 50% / OVERTIME 30% / SEVERANCE ฿300,000). Values must match that
// enum's names exactly.
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
  // The three pre-tax deductions the accountant's sheet subtracts before tax (columns Z/AA/AB).
  // Editable per run, unlike director remuneration below, which is fixed on the employee record.
  'warningLetterDeduction',
  'customerReturnDeduction',
  'otherPretaxDeduction',
];
// Days worked this period. Only meaningful for a pay_type = 'D' employee
// (hr.employee.current_salary is that employee's PER-DAY rate): PayrollComponent.SALARY is
// rate * daysWorked. One-off like bonusPay/otherOneOffPay, re-entered fresh every run since a day
// count is specific to the month worked.
const dailyRateInputKeys = ['daysWorked'];
const payrollInputKeys = [
  ...specialPayKeys, ...namedAllowanceKeys, ...oneOffPayKeys, ...incomeInputKeys, ...deductionInputKeys,
  ...dailyRateInputKeys,
];

// The withholding-tax classification matrix screen. Every non-SALARY, non-NON_TAXABLE_INCOME
// PayrollComponent HR can classify; labels mirror specialPayFields/namedAllowanceFields/
// oneOffPayFields above and PayrollComponent's own javadoc. SALARY is absent: it is locked to
// REGULAR_REPROJECT and never needs a stored row.
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
// Values must match backend PayrollTaxTreatment's enum names exactly; '' means "not yet classified" (submits
// as null, resetting the stored row per PayrollRepository#upsertComponentTaxTreatment).
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

// Carry-forward: the recurring fields pre-filled from the employee's most recent prior processed
// payroll_line (GET /api/payroll/suggested-inputs) when HR starts a brand-new run. Which of the nine
// specialPay slots carries is per employee, per component (hr.payroll_component_carry_forward), not
// hardcoded here; a slot with the flag off gets 0 back. This is a client-side pre-fill only:
// whatever sits in the field when HR hits Preview/Process (carried, edited, or cleared to 0) is
// submitted as-is via `payload()`.
function indexSuggestionsByEmployee(rows) {
  const map = {};
  (rows || []).forEach((row) => {
    if (row && row.employeeId != null) map[row.employeeId] = row;
  });
  return map;
}

// A real carried figure from last month beats the hardcoded UAT demo default (500) below — the carried value
// is an actual prior figure, the hardcoded one is only a guess for when nothing else is known. Returns a
// numeric string, or null when there is nothing to carry for this key.
function suggestedFallback(suggestion, key) {
  if (!suggestion) return null;
  const amount = Number(suggestion[key] || 0);
  return amount > 0 ? String(amount) : null;
}

// A saved-but-not-yet-processed draft value HR typed earlier beats a carried-forward guess.
//
// Deliberately NOT the same collapsing rule as suggestedFallback above. A suggestion only fills a
// blank/zero field, so "suggested 0" and "no suggestion" are equivalent. A draft is saved as a
// COMPLETE snapshot of the form (saveDraft() submits every field), so once any draft row exists
// every field in it, including one HR deliberately zeroed, is what they saw on screen and must win
// over the suggestion. Collapsing that zero to null would let a stale carry-forward suggestion
// resurrect over a value HR cleared (e.g. a carried เบี้ยขยันประจำ that should not repeat).
//
// So return '' (not null) for "draft row exists, field is 0": non-nullish, so the `??` chain at
// every call site does not fall through, yet it still renders blank like every other field (see
// draftValue). Return null only when there is no draft row at all for this employee. Presence is an
// employee-level fact: a draft row is written wholesale, never with some fields missing.
function draftFallback(draft, key) {
  if (!draft) return null;
  const amount = Number(draft[key] || 0);
  return amount > 0 ? String(amount) : '';
}

// The withholding-tax override is NULLABLE/meaningful, so it cannot ride the generic
// draftValue/parsePayrollNumber machinery (which collapses ''<->0). A stored/carried 0 is a real
// override (withhold nothing) and must round-trip as "0"; only null/'' means "no override".
// Priority: the line's own persisted per-run value > a saved draft > last month's carried per-run
// value > blank. The standing employee override is not surfaced here; it re-applies server-side.
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
    // ค่าอาหาร/เบี้ยเลี้ยง: mealAllowance/perDiemExempt/perDiemTaxable are plain numeric fields like
    // the special-pay slots above; perDiemBasis is a nullable enum ('' = not yet chosen), like
    // withholdingTaxOverride below, so it stays out of the numeric payrollInputKeys.
    mealAllowance: '',
    perDiemExempt: '',
    perDiemTaxable: '',
    perDiemBasis: '',
    // One-off like bonusPay/otherOneOffPay above: never carried forward, re-entered fresh every run.
    daysWorked: '',
    // เงินโบนัส/เงินก้อนอื่นๆ: plain numeric fields like the special-pay slots above.
    bonusPay: '',
    otherOneOffPay: '',
    // garnishmentType is a nullable enum ('' = not chosen, backend defaults to SALARY), like
    // perDiemBasis above. customerReturnAlreadyEarned is a plain boolean (default false = "not yet
    // earned", the pre-tax netting path); see PayrollCalculator#calculateClassified.
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
    // Priority: the line's own real value (already persisted) > a saved draft > a carried figure
    // from last month > the hardcoded UAT demo default. A draft/suggestion only fills a blank/zero
    // field on a fresh run.
    const fallback = draftFallback(draft, key) ?? suggestedFallback(suggestion, key) ?? defaultSpecialPayValue(key, applyDefaults);
    adjustment[key] = draftValue(item.amount, fallback);
  });
  adjustment.nonTaxableIncome = draftValue(
    line.nonTaxableIncome,
    draftFallback(draft, 'nonTaxableIncome') ?? suggestedFallback(suggestion, 'nonTaxableIncome') ?? '');
  // unpaidLeaveDays is event-driven (this month's approved-beyond-quota leave, from GET
  // /api/payroll/suggested-inputs), unlike the carried recurring amounts above, but pre-fills the
  // same way: a real line value wins, otherwise a saved draft, otherwise the suggestion.
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
  // Hydrate from customerReturnRequested, NOT customerReturnDeduction: the latter is the POST-TAX
  // bookkeeping figure and is 0 whenever customerReturnAlreadyEarned is false (the unearned amount
  // is netted pre-tax out of commission), so reading it back would wipe the form field and the
  // checkbox below (which gates on > 0) on every reload and stop a reprocess re-applying the netting.
  // customerReturnRequested always echoes what was typed.
  adjustment.customerReturnDeduction = draftValue(line.customerReturnRequested, draftFallback(draft, 'customerReturnDeduction') ?? '');
  adjustment.otherPretaxDeduction = draftValue(line.otherPretaxDeduction, draftFallback(draft, 'otherPretaxDeduction') ?? '');
  // ค่าอาหาร carries forward like the other recurring fields; the two เบี้ยเลี้ยง amounts and the
  // basis do not (the carry-forward flags only cover mealAllowance for this group): a per-diem is
  // trip-driven, so re-entering it fresh each run is correct. A saved draft still fills in all three.
  adjustment.mealAllowance = draftValue(
    line.mealAllowance,
    draftFallback(draft, 'mealAllowance') ?? suggestedFallback(suggestion, 'mealAllowance') ?? '');
  adjustment.perDiemExempt = draftValue(line.perDiemExempt, draftFallback(draft, 'perDiemExempt') ?? '');
  adjustment.perDiemTaxable = draftValue(line.perDiemTaxable, draftFallback(draft, 'perDiemTaxable') ?? '');
  adjustment.perDiemBasis = line.perDiemBasis || draft?.perDiemBasis || '';
  // bonusPay/otherOneOffPay are one-off, so they do not carry forward (same as perDiemExempt/
  // perDiemTaxable above); a saved draft still fills in.
  adjustment.bonusPay = draftValue(line.bonusPay, draftFallback(draft, 'bonusPay') ?? '');
  adjustment.otherOneOffPay = draftValue(line.otherOneOffPay, draftFallback(draft, 'otherOneOffPay') ?? '');
  // Survives a reload: persisted line's daysWorked first, else a saved draft. No carry-forward (a
  // day count is specific to the month worked).
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

// perDiemBasis is a nullable enum, not an amount (see withholdingOverrideValue above). '' (never
// chosen) submits as null; a chosen value submits as its PerDiemBasis name verbatim.
function perDiemBasisValue(input) {
  const raw = input.perDiemBasis;
  return raw === '' || raw == null ? null : raw;
}

// garnishmentType is a nullable enum (see perDiemBasisValue above). '' (never chosen) submits as
// null, which the backend defaults to SALARY (see PayrollEmployeeInputRequest).
function garnishmentTypeValue(input) {
  const raw = input.garnishmentType;
  return raw === '' || raw == null ? null : raw;
}

function normalizedAdjustment(input) {
  return {
    employeeId: input.employeeId,
    ...Object.fromEntries(payrollInputKeys.map((key) => [key, parsePayrollNumber(input[key])])),
    // Nullable: '' -> null ("compute/standing"), a number (incl. 0) -> that override. Kept separate from the
    // numeric keys above precisely so a cleared field stays null instead of collapsing to 0.
    withholdingTaxOverride: withholdingOverrideValue(input),
    // Nullable enum, like withholdingTaxOverride above.
    perDiemBasis: perDiemBasisValue(input),
    // Nullable enum (garnishmentType) and a plain boolean (customerReturnAlreadyEarned); neither
    // belongs in the numeric payrollInputKeys spread above.
    garnishmentType: garnishmentTypeValue(input),
    customerReturnAlreadyEarned: Boolean(input.customerReturnAlreadyEarned),
  };
}

function hasPayrollInput(input) {
  // An override-only adjustment must still be submitted -- especially an override of 0 (withhold nothing), which
  // the numeric-key check below would treat as "no input" and drop.
  if (withholdingOverrideValue(input) != null) return true;
  return payrollInputKeys.some((key) => parsePayrollNumber(input[key]) > 0);
}

// The two raw `<input type="number">` fields on this page (unpaidLeaveDays, daysWorked) have no
// equivalent of MoneyInput's onChange clamp, so a typed "-5" would reach updateAdjustment verbatim
// and @PositiveOrZero on PayrollEmployeeInputRequest would 400 the ENTIRE draft/preview/process PUT
// (every other employee's edits rejected with it). Mirrors MoneyInput's clamp.
function clampNonNegative(value) {
  if (value !== '' && Number(value) < 0) return '0';
  return value;
}

// MoneyInput and clampNonNegative above already stop a negative from being typed, but saveDraft()
// scans the built payload once more before sending: a backstop against a future field that forgets
// the clamp. Returns the first offending {employeeId, key} pair, or null.
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
  // PayrollController mirrors this split: every GET, plus the non-persisting POST /preview and
  // /preview/export/{kind}, is hasAnyRole('HR','CEO'); every write (process, PUT input-draft,
  // tax-allowances, ytd-seed, component-tax-treatments, POST distribute) is hasRole('HR') only. This
  // is an ALLOWLIST read off ROLE_PERMISSIONS.canManagePayroll (routes.js) via `hasPermission`,
  // deliberately NOT a `role !== 'ceo'` denylist, which would fail OPEN to any role later added to
  // canViewPayroll. `hasPermission(undefined, ...)` is false, so every caller (including tests) must
  // pass a real user.
  const canManage = hasPermission(user?.role, 'canManagePayroll');
  const [month, setMonth] = useState(() => bangkokMonthStartIso().slice(0, 7));
  const [period, setPeriod] = useState(null);
  const [adjustments, setAdjustments] = useState({});
  const [selectedEmployeeId, setSelectedEmployeeId] = useState(null);
  const [detailOpen, setDetailOpen] = useState(false);
  // The detail panel has two presentations: >=1366px is a persistent side panel next to the table;
  // below that it is a fixed slide-over overlay. The query is the JS-side mirror of the CSS
  // `payroll-wide:` custom variant (`min-width: 1366px`, see index.css's `@custom-variant`) so the
  // two cannot disagree. The 310px panel floor lets the table's 714px minimum and the 16px gap fit
  // side-by-side at 1366px; 1280px stays overlay because the content width cannot pay for the panel.
  const isDesktopPanel = useMediaQuery('(min-width: 1366px)');
  const isOverlayPanel = !isDesktopPanel;
  const detailPanelRef = useRef(null);
  const [saving, setSaving] = useState(false);
  const [confirmProcess, setConfirmProcess] = useState(false);
  // The raw suggestions response, kept so the pending cancel-after-close correction credit (if any)
  // can be shown as a hint next to unpaidLeaveDays. Never re-applied to `adjustments` after the
  // initial pre-fill in applyPeriod: HR's edits always win.
  const [suggestionsByEmployee, setSuggestionsByEmployee] = useState({});
  // Month-level (not per-employee) advisory count of leave requests still SUBMITTED that overlap the
  // current payroll month (PayrollCarryForwardDtos#SuggestedInputsResponse). Fetched with the same
  // suggestedInputs() call and fresh-PREVIEW-only gate, defaulting to 0 when that call is skipped or
  // fails, so the warning only renders on a real non-zero count.
  const [pendingSubmittedLeaveCount, setPendingSubmittedLeaveCount] = useState(0);
  const [payDate, setPayDate] = useState(() => `${month}-26`);
  const [draftDirty, setDraftDirty] = useState(false);
  const [draftSaving, setDraftSaving] = useState(false);
  // 'conflict' is a terminal status alongside 'saved'/'dirty'/'saving'/'error': reached only on a
  // 409 from PUT /input-draft, cleared only by the explicit "โหลดข้อมูลใหม่" action (never by the
  // retry machinery, which would just 409 again against the same stale token). See
  // draftConflictRef for why the synchronous gate lives in a ref.
  const [draftSaveStatus, setDraftSaveStatus] = useState('saved');
  // The Thai message from the 409 response, shown in the persistent conflict banner below (unlike the transient
  // toast, which auto-dismisses after 3.2s -- see useToast.js -- and is therefore not sufficient on its own for
  // "another user saved this month's draft," which HR must not miss).
  const [draftConflictMessage, setDraftConflictMessage] = useState(null);
  const adjustmentsRef = useRef({});
  const draftDirtyRef = useRef(false);
  const draftVersionRef = useRef(0);
  // The month-level ETag saveDraft() threads through as If-Match on the NEXT save. A ref updated on a
  // strict allowlist, not whenever a fresh value arrives: a background poll must never refresh it
  // while the form is dirty, or it would silently adopt another user's concurrent save as "the
  // version I started from" and defeat the conflict check. Updated only: (a) month change, reset to
  // null; (b) "data arrived" effect, only when the form is clean; (c) saveDraft()'s own successful
  // response; (d) after a successful process() (which clears the server's draft rows). Never mutated
  // on a 409.
  const draftEtagRef = useRef(null);
  // Synchronous companion to draftSaveStatus === 'conflict': saveDraft()'s `finally` decides whether
  // to auto-retry, and reading React state there would see the previous render's value, letting the
  // retry chain hammer the backend with the same doomed-to-409 token on every keystroke. This ref is
  // set the instant the 409 is caught.
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
  // Guard the irreversible "Process Payroll" action against an empty period. The backend #process
  // does not need a period id: it recomputes from findActiveEmployees() and saves regardless. With no
  // period loaded, `adjustments` is `{}` so `payload().inputs` is `[]`, and every HR-entered value
  // (special pays, non-taxable income, student loan, legal execution, unpaid-leave days, withholding
  // override) would commit as zero. `status = 'PROCESSED'` is one-way and OvertimeService
  // #requirePayrollMonthOpen then refuses OT approval for that month.
  //
  // MUST guard on `!(period?.lineCount > 0)`, NOT `!period?.id`: PayrollService#preview builds its
  // DTO with `id: null` and `currentOrPreview` falls back to preview for any never-processed month,
  // so `period.id` is null on the normal first run. That is why the export/send groups below can key
  // off `!period?.id` (they need an already-saved period) and this one cannot. PayrollPage.test.jsx's
  // "enables Process Payroll for a fresh (never-processed) period ... id === null" pins this.
  const emptyPeriod = !period || !(period.lineCount > 0);
  // Draft saving is allowed for any loaded period, any status. Every field stays editable (gated
  // only on !canManage below), so a narrower gate would let HR type into a form that silently kept
  // nothing. It is safe because draftValue()'s `> 0` precedence means a committed non-zero figure on
  // the line always beats a draft restore: a draft only fills a field that is blank/zero on the real
  // line. Known gap, intentionally left: HR clearing a non-zero COMMITTED field down to 0 still sees
  // the committed value reappear on reload, because draftValue cannot tell "never touched" from
  // "deliberately zeroed" once a persisted value exists.
  //
  // CEO can never save a draft (PUT /input-draft is hasRole('HR') only), so that is folded into this
  // condition to keep the autosave badge and blur autosave off for a read-only session.
  const canSaveDraft = canManage && Boolean(period);
  // "Latest" mirror of canSaveDraft for code that runs OUTSIDE this render's closure: the
  // month-change effect's cleanup and the beforeunload listener. Both are set up once per effect
  // instance and would otherwise read a stale canSaveDraft/period. Updated in a post-render effect
  // (React lint forbids mutating a ref during render), which still runs before either consumer fires.
  const canSaveDraftRef = useRef(canSaveDraft);
  useEffect(() => {
    canSaveDraftRef.current = canSaveDraft;
  });
  // A monotonically incremented VISIT token, not a "latest month VALUE" mirror. It is bumped in the
  // month-change effect's own body (never a separate no-deps passive effect: a promise settling right
  // after `setMonth` commits could still see the stale value). A value mirror alone is not enough
  // either: July -> June -> July makes `month === monthAtStart` true again, so a save from the first
  // (abandoned) July visit would pass as if it belonged to the second. Each visit gets its own token,
  // so saveDraft()'s resolution can tell "this exact visit" from "a same-named but different visit".
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

  // Focus trap + Escape-to-close + focus restoration, shared with Modal.jsx via useDialogFocus.
  // `active` is false in the >=1366px persistent side panel, which must not steal Tab or respond to
  // Escape since the rest of the page beside it is not hidden.
  useDialogFocus({ active: isOverlayPanel && detailOpen, containerRef: detailPanelRef, onClose: closeDetailPanel });

  // react-query so this page shares the cache (approvals elsewhere invalidate the `['payroll']`
  // prefix) and gets a 60s poll + window-focus refetch. One combined queryFn (period + suggestions +
  // draft) gives a single cache entry to invalidate, and each of the suggestion/draft fetches keeps
  // its own try/catch so a failure never blocks the period from loading.
  const payrollQuery = useQuery({
    queryKey: queryKeys.payrollCurrent(month),
    queryFn: async () => {
      const response = await api.payroll.current({ payrollMonth: month });
      const nextPeriod = response.period;
      // A SUGGESTION only makes sense for a fresh run (PREVIEW with no processed/void period): an
      // already-touched period reflects real submitted values and adjustmentFromLine's precedence
      // means the line's value wins anyway, so fetching one is pure waste. A draft, unlike a
      // suggestion, is NOT safe to skip once a period is touched (fields stay editable); see
      // canSaveDraft.
      let suggestionsByEmployee = {};
      // pendingSubmittedLeaveCount rides the same suggestedInputs() response/gate/try-catch as
      // suggestionsByEmployee: another field on that read-only advisory payload, same resilience and
      // fresh-PREVIEW-only scope.
      let pendingSubmittedLeaveCount = 0;
      if (nextPeriod?.status === 'PREVIEW' && !nextPeriod?.id) {
        try {
          // Optional chaining keeps this resilient if a caller's api.payroll mock predates this method (e.g. an older
          // test double) — suggestions are a convenience pre-fill only and must never block payroll from loading.
          const suggestionResponse = await api.payroll.suggestedInputs?.({ payrollMonth: month });
          suggestionsByEmployee = indexSuggestionsByEmployee(suggestionResponse?.suggestions);
          pendingSubmittedLeaveCount = suggestionResponse?.pendingSubmittedLeaveCount || 0;
        } catch {
          suggestionsByEmployee = {};
          pendingSubmittedLeaveCount = 0;
        }
      }
      // Fetched on every load, not only a fresh PREVIEW run (see canSaveDraft). A draft is a
      // convenience restore only and must never block payroll from loading.
      //
      // draftEtag is the month-level token threaded back as If-Match on the next save (see
      // draftEtagRef for when it is applied). `null` (the fetch failed) is distinct from `undefined`
      // (a cache seed with no draft info, e.g. preview()'s); the "data arrived" effect treats them
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
      return { period: nextPeriod, suggestionsByEmployee, pendingSubmittedLeaveCount, draftByEmployee, draftEtag };
    },
    refetchInterval: 60_000,
    refetchOnWindowFocus: true,
  });
  // `isLoading` (true only for the FIRST fetch of a month) drives every loading skeleton; unlike
  // `isFetching` it stays false during the silent 60s poll/focus refetch, so a background refresh
  // never flashes the table/stat strip.
  const loading = payrollQuery.isLoading;

  // showToast sits behind a ref so an unstable identity cannot re-fire this effect. useToast.js now
  // wraps showToast in useCallback([]), so the ref is no longer load-bearing in production, but it
  // is kept because the regression test ("does not call showToast more than once ... driven by an
  // App-shaped (unstable) showToast") uses a harness whose counting wrapper is a fresh closure every
  // render; putting that wrapper in this effect's deps would loop (`Maximum update depth exceeded`)
  // while `payrollQuery.error` stays non-null. Keyed on the error's MESSAGE (a primitive), not the
  // Error object: the 60s poll hands back a new Error instance per failed attempt, which would
  // otherwise re-toast the same message every poll.
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

  // Applies the query's data to local state whenever it changes (initial load and every background
  // refetch alike).
  //
  // Governing constraint: a background refetch must NEVER clobber what HR is typing.
  // period/suggestionsByEmployee (server-derived, read-only) always update; adjustments (the FORM
  // values) are re-applied only when the form is clean (`!draftDirtyRef.current`). While dirty, only
  // `period` updates, and typed values stay until the next successful autosave, an explicit
  // บันทึกร่าง, or a month change reconciles them.
  useEffect(() => {
    if (!payrollQuery.data) return;
    // preview()/process() call applyPeriod(response.period) directly (applyUatDefaults=false) AND
    // seed that same response into the query cache via setQueryData so the next poll diffs against
    // fresh data. That cache write also changes payrollQuery.data and re-runs this effect for the
    // same result. This must be correct whether or not react-query re-fires (structural sharing
    // sometimes skips it, by accident of cache equality), so `applyUatDefaults` travels WITH the
    // data (true for an ordinary fetch) and preview()/process() seed `applyUatDefaults: false`; a
    // redundant re-run then derives `adjustments` from exactly the same inputs.
    const {
      period: nextPeriod,
      suggestionsByEmployee: nextSuggestions,
      pendingSubmittedLeaveCount: nextPendingSubmittedLeaveCount = 0,
      draftByEmployee,
      draftEtag,
      applyUatDefaults = true,
    } = payrollQuery.data;
    setSuggestionsByEmployee(nextSuggestions);
    setPendingSubmittedLeaveCount(nextPendingSubmittedLeaveCount);
    if (draftDirtyRef.current) {
      // draftEtagRef is deliberately left alone here (see its comment): adopting a fresher token
      // mid-edit would let the next save match a version that already contains someone else's change,
      // the lost update this feature prevents.
      setPeriod(nextPeriod);
      return;
    }
    applyPeriod(nextPeriod, { applyUatDefaults, suggestionsByEmployee: nextSuggestions, draftByEmployee });
    // `undefined` (not `null`) means this data came from a manual cache seed that never carried draft info
    // (preview()'s setQueryData) -- leave the last known-good token alone.
    if (draftEtag !== undefined) {
      draftEtagRef.current = draftEtag;
    }
    // A clean path that adopts a fresh server token must also clear draftConflictRef. Otherwise: a
    // 409/428 sets draftConflictRef=true on a clean form; the next poll takes this branch, adopts a
    // good token and clears the banner, but the ref stays true and autosaveDraft's
    // `|| draftConflictRef.current` guard returns early forever with no banner or toast. A fresh,
    // adopted token means the conflict is resolved.
    draftConflictRef.current = false;
    setDraftSaveStatus('saved');
  }, [payrollQuery.data]);

  // draftPayload is declared here rather than down with payload -- function declarations are hoisted either way,
  // but this repo's lint config flags a hook body reading a value declared later in the same component as an
  // ordering hazard, so the textual order is made to match the dependency.
  function draftPayload() {
    return {
      payrollMonth: `${month}-01`,
      inputs: Object.values(adjustmentsRef.current).map(normalizedAdjustment),
    };
  }

  // Flush what HR typed for the OUTGOING month before switching (or unmounting); without it, the
  // month change resets draftDirtyRef with no attempt to save the previous month's edits.
  //
  // `draftPayload()` here is correct and needs no `flushDraftFor(month)` helper: `month` is a
  // primitive captured by this render's closure, and `draftPayload` and this effect (cleanup
  // included) are created together, so they close over the same `month`. React runs the previous
  // effect instance's cleanup before the new one, so the cleanup still sees the outgoing month. This
  // differs from canSaveDraftRef/draftDirtyRef just below, which must be refs because they change
  // WITHOUT this effect re-running (its deps are `[month]` alone).
  useEffect(() => {
    // Bumped here, in this effect's own body -- see monthVisitTokenRef's own comment above for why this exact spot
    // (not a separate passive effect, not during render).
    monthVisitTokenRef.current += 1;
    setSelectedEmployeeId(null);
    setDetailOpen(false);
    setPayDate(`${month}-26`); // default salary pay date is the 26th of the selected month A new month starts with a clean slate; any dirty
    // tracking belongs to the OUTGOING month (flushed by this same effect's cleanup, below) and must not leak into
    // the incoming one.
    draftDirtyRef.current = false;
    setDraftDirty(false);
    setDraftSaving(false);
    setDraftSaveStatus('saved');
    setDraftConflictMessage(null);
    draftVersionRef.current = 0;
    // A new month visit starts with no known token (the incoming month's query resolution supplies
    // one); a conflict from the OUTGOING month is unrelated.
    draftEtagRef.current = null;
    draftConflictRef.current = false;
    return () => {
      if (draftDirtyRef.current && canSaveDraftRef.current) {
        // Fire-and-forget: month switching must not block on this, and there is nowhere left to
        // toast. Errors are swallowed; the beforeunload guard below is the backstop for closing the
        // tab with unsaved work, and a failed flush just means the next blur autosave picks it up.
        //
        // If-Match is required by the backend; draftEtagRef.current (the outgoing month's last token)
        // keeps this a normal conditional save instead of a guaranteed 428. If it is stale, this 409s
        // and is swallowed like any other flush failure.
        void api.payroll.saveInputDraft(draftPayload(), { ifMatch: draftEtagRef.current }).catch(() => {});
      }
    };
    // Keep this targeted lint exception for the documented hook usage.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [month]);

  // Warn before a real tab close/reload with unsaved draft edits. No async save is attempted
  // (browsers do not reliably wait for one); this is the backstop for what the month-change/unmount
  // flush above cannot cover.
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

  // Preview is the only read-only recompute command: commission, overtime, leave refund, director
  // remuneration, tax/SSO are recomputed from current upstream data and shown WITHOUT committing.
  // load()/current can return a saved processed snapshot; this is the safe "show me the latest run".
  async function preview() {
    setSaving(true);
    try {
      const response = await api.payroll.preview(payload());
      applyPeriod(response.period);
      // Seed the result into the query cache so the next background poll diffs against this value
      // instead of the stale pre-preview one. suggestionsByEmployee/draftByEmployee are {} to match
      // applyPeriod's defaults. `applyUatDefaults: false` mirrors the direct applyPeriod(response.period)
      // call so a redundant effect re-run is idempotent (see the "data arrived" effect).
      // `draftEtag: draftEtagRef.current` is the honest current value: preview() never touches
      // server-side drafts.
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

  // Persist exactly what HR typed so a reload does not lose it. Unfiltered by hasPayrollInput, unlike
  // payload() above: a draft must also capture an employee HR started but did not finish (e.g. only a
  // garnishment type picked, no amount yet).
  async function saveDraft({ quiet = false } = {}) {
    if (!canSaveDraft) return;
    // Captured so the resolution below can detect HR having moved on from THIS EXACT visit while the
    // PUT was in flight; a token, not `month`, because a value comparison is fooled by a round trip
    // back to the same month (see monthVisitTokenRef).
    const visitTokenAtStart = monthVisitTokenRef.current;
    const draftPayloadValue = draftPayload();
    // MoneyInput and clampNonNegative already stop a negative from being typed, but refuse to SEND
    // one anyway: a single negative 400s the ENTIRE draft PUT with no visible error, taking every
    // other employee's edits with it. Naming the employee/field and refusing is safer than clamping,
    // which would change a number HR typed without them noticing.
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
    // The token this exact attempt is conditioned on, captured now rather than after the await. A
    // later overlapping saveDraft() (auto-retry in `finally`, or the button racing a blur) reads
    // draftEtagRef.current fresh at its own call time.
    const ifMatchAtStart = draftEtagRef.current;
    setDraftSaving(true);
    setDraftSaveStatus('saving');
    try {
      const response = await api.payroll.saveInputDraft(draftPayloadValue, { ifMatch: ifMatchAtStart });
      // If HR has moved on from this exact visit (a month change, or a round trip July -> June ->
      // July, which bumps the token twice), this resolution belongs to a visit no longer on screen.
      // The badge/toast must not paint a save state for the wrong visit (e.g. a late success flipping
      // a freshly reloaded July's clean badge to "รอบันทึกอัตโนมัติ"). The month-change flush already
      // covers the outgoing visit's final state, so this is a no-op. It also skips the etag update:
      // draftEtagRef now belongs to the month HR is looking at, and this response is for the one they
      // left.
      if (monthVisitTokenRef.current !== visitTokenAtStart) return;
      // The server just wrote successfully -- its returned ETag is unconditionally the new truth for this month,
      // regardless of whether draftVersionRef drifted further during the await (that drift is a SEPARATE "is there
      // more to save" concern, handled by the branch below).
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
      // A 409 means another user (or tab) saved this month's draft first. HR's typed values must NOT
      // be discarded and the form must stay dirty, so unlike every other failure branch this one does
      // not touch draftDirtyRef/setDraftDirty and does NOT refresh draftEtagRef (the stale token stays
      // stale so a blind retry keeps refusing). 'conflict' is a terminal state distinct from 'error',
      // with its own persistent banner: the badge/toast machinery is built for a transient failure
      // with an implicit retry, not "someone else's data is on the server and yours would clobber it".
      //
      // 428 (missing If-Match, e.g. the initial GET failed so draftEtagRef never got a token) gets the
      // same treatment. Otherwise draftConflictRef stays untouched and the `finally` retry chain and
      // every blur's autosaveDraft keep re-firing the identical headerless PUT, a request storm that
      // cannot succeed without a reload.
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
      // A failed autosave always toasts (quiet autosave or not); a 400/403/409 shown only as a small
      // header badge is easy to miss.
      showToast('error', error.message || 'บันทึกร่างไม่สำเร็จ');
    } finally {
      setDraftSaving(false);
      if (
        monthVisitTokenRef.current === visitTokenAtStart
        && draftDirtyRef.current && draftVersionRef.current !== versionAtStart && canSaveDraft
        // Never auto-chain a retry against a token already known to be stale; every further
        // keystroke would fire another doomed PUT (and 409 toast) until HR reloads.
        && !draftConflictRef.current
      ) {
        void saveDraft({ quiet: true });
      }
    }
  }

  function autosaveDraft() {
    // draftConflictRef.current: see saveDraft's own comment -- do not let the silent blur-autosave spam repeated
    // 409s once a conflict is showing.
    if (!canSaveDraft || !draftDirtyRef.current || draftSaving || draftConflictRef.current) return;
    saveDraft({ quiet: true });
  }

  // The conflict banner's explicit resolution: HR chose to look at the other user's version. This is
  // the ONE place HR's unsaved typing may be discarded, because HR clicked it after being told why
  // their save was refused. Clearing draftDirtyRef BEFORE refetching lets the ordinary "data arrived"
  // effect apply the fresh server state instead of duplicating applyPeriod's logic here.
  async function reloadAfterDraftConflict() {
    draftDirtyRef.current = false;
    setDraftDirty(false);
    draftConflictRef.current = false;
    setDraftSaveStatus('saved');
    setDraftConflictMessage(null);
    await payrollQuery.refetch();
  }

  // One button, not two: for HR the preview command doubles as the draft-save trigger (PUT
  // /input-draft, THEN POST /preview). CEO cannot write a draft (hasRole('HR') only), so the CEO
  // branch (see the button JSX below) calls `preview` directly and keeps the original คำนวณตัวอย่าง
  // label so it never promises a save CEO cannot perform.
  //
  // saveDraft() never throws (it catches and toasts its own errors), so a failed draft PUT still
  // surfaces its error and the preview still runs.
  //
  // Exception: skip preview() when the draft save hit a conflict. preview() calls
  // applyPeriod(response.period) directly, bypassing the dirty-guard, and its payload() filters
  // inputs by hasPayrollInput (unlike draftPayload()), so it would paint a period built from a
  // payload that already dropped an unfinished row, wiping it from the form. The banner already
  // tells HR what to do.
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
      // process() clears the server's draft rows, so the held token is stale too. Re-fetch it rather
      // than assuming the empty-set token's literal value (PayrollDraftETag's to define). Best-effort:
      // on failure draftEtagRef falls back to null and the next save gets a clean 428 asking HR to
      // reload, rather than risking a wrong token matching silently. Fetched BEFORE the cache seed
      // below so the seed carries the right value in one shot (the seed's shape must match the
      // queryFn's for structural-sharing parity).
      let freshDraftEtag = null;
      try {
        const freshDraft = await api.payroll.getInputDraft({ payrollMonth: month });
        freshDraftEtag = freshDraft?.etag ?? null;
      } catch {
        freshDraftEtag = null;
      }
      draftEtagRef.current = freshDraftEtag;
      // Same cache seed as preview(), so the next poll does not flash the pre-process figures.
      // `applyUatDefaults: false` mirrors the direct applyPeriod call (see preview()) rather than
      // relying on `status` already being 'PROCESSED'.
      queryClient.setQueryData(queryKeys.payrollCurrent(month), {
        period: response.period,
        suggestionsByEmployee: {},
        draftByEmployee: {},
        draftEtag: freshDraftEtag,
        applyUatDefaults: false,
      });
      // The backend clears hr.payroll_input_draft for this month once processed (PayrollService#process).
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
    // KBank/PND1/SSO need a processed, paid period. payroll-detail must also work for a month that
    // was never processed (HR wants to review the month BEFORE committing it), so it only requires
    // SOME period to be loaded (lineCount > 0), not a persisted id.
    if (isDetailKind ? !(period?.lineCount > 0) : !period?.id) return;
    setSaving(true);
    try {
      // kbank/pnd1 return raw CP874 bytes; sso and payroll-detail return real xlsx workbooks. Either way the result
      // is fetched as a binary blob so the bytes survive the download intact.
      const blob = period?.id
        ? await api.payroll.exportFile(period.id, kind.value, payDate || undefined)
        // No persisted period (a fresh, unprocessed month) -- POST the same payrollMonth/inputs payload the
        // Preview/Process buttons already send, so the workbook's figures are guaranteed to equal whatever the
        // on-screen preview shows for the same inputs.
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
    // A typed change is not saved anywhere durable until the blur autosave succeeds (or Process).
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
    // POST /{periodId}/distribute is hasRole('HR') only -- omitted entirely for a read-only CEO session rather
    // than shown disabled (nothing on this page lets CEO reach a state where it would ever become available, so a
    // disabled entry would just be dead weight in the menu).
    ...(canManage ? [{
      key: 'email-payslips',
      label: 'ส่งอีเมลสลิปเงินเดือน',
      icon: 'mail',
      onSelect: distributePayslips,
      disabled: Boolean(disabledBecauseBusy || !hasSavedPeriod),
      disabledReason: disabledBecauseBusy || (!hasSavedPeriod ? needsSavedPeriod : null),
    }] : []),
  ];
  // 'conflict' takes priority over every other status: it is the one state where "keep waiting or
  // retrying" is wrong (the token is stale until HR reloads via the banner below).
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
          <div className="flex flex-wrap gap-2.5 items-center mobile:w-full mobile:items-stretch mobile:gap-2">
            {/* Read-only oversight indicator: the backend grants CEO a read of everything on this page
                (every GET plus the non-persisting preview endpoints) while every write stays HR-only;
                this badge is the cue that the missing controls below are intentional. */}
            {!canManage && (
              <StatusBadge tone="info">มุมมองสำหรับผู้บริหาร — อ่านอย่างเดียว</StatusBadge>
            )}
            <label className="inline-flex items-center gap-2 m-0 text-[length:var(--text-sm)] font-extrabold">
              รอบเดือน
              <span className="w-[150px]">
                <input
                  type="month"
                  value={month}
                  onChange={(event) => setMonth(event.target.value)}
                  className="mobile:min-h-[44px]"
                />
              </span>
              {/* The native <input type="month"> always renders Gregorian and cannot show Buddhist era; this is a read-only
               * display of the same value, not a second control. */}
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

      {/* Advisory only, never a gate; rendered before every other banner so HR sees it before
          Process. Leave still SUBMITTED does not feed findUnpaidLeaveDaysByEmployeeForMonth, so it
          contributes nothing to this run's unpaid-day deduction until approved; this is where that
          gap is surfaced before payroll runs. */}
      {pendingSubmittedLeaveCount > 0 && (
        <div
          role="status"
          className="flex flex-wrap items-center gap-3 rounded-md border border-warning-border bg-warning-bg-soft px-4 py-3 text-sm font-bold text-warning"
        >
          <Icon name="triangleAlert" size={16} />
          <span>
            มีคำขอลา {pendingSubmittedLeaveCount} รายการที่ยังรอการอนุมัติในรอบเดือนนี้
            หากไม่อนุมัติก่อนประมวลผลเงินเดือน ระบบจะยังไม่หักวันลาดังกล่าวออกจากเงินเดือนงวดนี้
          </span>
        </div>
      )}

      {/* Persistent conflict message: unlike the header badge (easy to miss) and the global toast
          (auto-dismisses after 3.2s), this stays until HR acts, because HR's typed values are still
          sitting unsaved in the form and must not be quietly abandoned. */}
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

      {/* A bare "-" in all four tiles read as broken UI, not "no data yet". `loading` drives
          CompactStatRow's skeleton for the fetch-in-flight window; the true empty case (no period
          once loading has settled, e.g. the initial load failed) gets an empty state explaining what
          would populate the strip. */}
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

      {/* Routine commands are Preview + one labelled document menu. Process stays in its own region so
          the irreversible action is visually apart. */}
      <FilterBar className="flex-col items-stretch gap-[14px]">
        <div className="flex flex-wrap items-center gap-3">
          {/* Review: safe, read-only recompute. For HR this doubles as the draft-save trigger
              (saveDraftAndPreview PUTs the draft, THEN previews). CEO cannot write a draft, so this
              branches on canManage rather than canSaveDraft: the label must never promise a save CEO
              cannot perform, even where canSaveDraft is false for HR too (e.g. period not loaded). */}
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

            {/* Phase A: the export format selector became four document menu actions, but the effective date is still an
             * explicit field because PayrollService uses it for KBank, PND1, SSO, and payroll-detail filenames. Hiding it
             * behind only the KBank action would make the UI contradict the backend contract. */}
            <label className="m-0 inline-flex items-center gap-2 text-sm font-extrabold">
              วันที่เอกสาร
              <ThaiDateInput
                value={payDate}
                onChange={(event) => setPayDate(event.target.value)}
                disabled={saving || !(period?.lineCount > 0)}
                wrapperClassName="w-[9.5rem] shrink-0"
                className="mobile:min-h-[44px]"
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

          {/* Run: the irreversible action, in its own region after routine preview/export/send work.
              Phone stays editable; the mobile-only typed phrase lives in the confirm dialog instead
              of disabling this button. POST /process is hasRole('HR') only, so a CEO session never
              sees the button (not disabled: nothing would ever enable it), only the period status
              in a neutral readout.

              Within the HR branch the border turns danger-red only once the action is armed (a real
              period with lines to process). An unmet precondition (emptyPeriod, see
              processBlockedReason) is a disabled control waiting on data, not a failure, so it reads
              neutral; red must stay reserved for genuine failures or HR learns to ignore it. */}
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
                {/* Informational, not an error: explains why the button is unavailable, so it uses
                    muted body colour. Utilities at the call site, not a styles.css rule (styles.css
                    is in @layer legacy and loses to any text utility). The `info` icon carries the
                    "guidance, not an alarm" signal non-visually too (WCAG 1.4.1). */}
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

      {/* The withholding-tax classification matrix. HR needs it to satisfy
          PayrollCalculator#calculateClassified's classification gate for any component beyond the
          backfill defaults. Collapsed by default so it does not compete with the main workflow;
          one employee per sub-section ("never a shrunken fifteen-column table" on mobile). */}
      <TaxTreatmentMatrixSection payrollMonth={month} showToast={showToast} canManage={canManage} />

      {/* Layout at the call site (Tailwind-first). Two states: below the `payroll-wide:` custom
          variant (the panel's overlay floor, see isDesktopPanel) the table is the only in-flow
          column; at >=1366px a second `minmax(310px, 0.3fr)` column holds the sticky side panel.
          `minmax(0, ...)` on both tracks, never a bare `1fr`: a bare fr track floors at min-content
          and pushed the panel off-canvas. The 310px floor lets 1366px keep the persistent panel
          while paying for the table's 714px floor and the 16px gap; 1280px stays overlay. */}
      <section className="grid grid-cols-[minmax(0,1fr)] payroll-wide:grid-cols-[minmax(0,1fr)_minmax(310px,0.3fr)] gap-4 items-start">
        {/* While the detail panel is an open overlay dialog (<1366px), the page behind the backdrop
            must be unreachable, not just dimmed, or Tab walks a keyboard/screen-reader user into
            content hidden only by z-index. `inert` removes the region from the accessibility tree
            and tab order. `'' : undefined`, not a boolean: React 18 only renders the bare `inert`
            attribute for a truthy STRING; `inert={true}` is dropped. */}
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
            // A full payroll is 200+ rows; without the cap the table grows to the page instead of
            // scrolling inside its own region.
            panelClassName="max-h-[min(68vh,760px)] overflow-auto"
            pageSize={Math.max(periodLines.length, 25)}
            searchable
            searchPlaceholder="ค้นหาพนักงาน"
            // `cursor-pointer` at the call site; `.payroll-row.active`'s background/shadow stay in
            // styles.css (theme tokens).
            rowClassName={(line) => cn(
              // `.payroll-row.active` uses box-shadow for the selection inset, so focus has to re-apply the shared ring
              // token from the Tailwind utilities layer where it wins.
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

        {/* Backdrop is a plain `role="presentation"` <div> (as in Modal.jsx), not a
            <button aria-hidden="true">: an interactive element hidden from the accessibility tree is
            the anti-pattern Modal's backdrop avoids. A click still closes the panel. */}
        <div
          className={cn('payroll-detail-backdrop', detailOpen && 'is-open')}
          role="presentation"
          onClick={closeDetailPanel}
        />

        <aside
          ref={detailPanelRef}
          // tabIndex={-1}: lets the panel itself take focus programmatically (useDialogFocus falls back to it when the
          // panel has no focusable children) without ever joining the Tab order.
          tabIndex={-1}
          // `@container` (built into Tailwind v4) makes the panel a container-query context so the two
          // money-field grids below respond to the PANEL's width, not the viewport's: it is a sticky
          // side column at >=1366px and a fixed-width overlay below, so its width is not a function of
          // viewport width alone. See the `@sm:grid-cols-2` classes below.
          className={cn(
            PANEL_CLASS,
            'payroll-detail-panel',
            '@container',
            'mobile:inset-0 mobile:max-h-[100dvh] mobile:w-full mobile:rounded-none mobile:border-0 mobile:p-4 mobile:pt-0 mobile:[&_input:not([type=checkbox])]:min-h-[44px] mobile:[&_select]:min-h-[44px]',
            detailOpen && 'is-open',
          )}
          onBlurCapture={handleAdjustmentBlur}
          // Dialog semantics apply ONLY in overlay mode (<1366px). At >=1366px this is a persistent
          // side panel that never traps focus or blocks the page, so role/aria-modal/aria-labelledby
          // must be absent there.
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
                  {/* Rendered only in the <1366px overlay. At >=1366px the panel's visibility is not
                      gated by `detailOpen` (only the overlay's CSS transform states are), so a dismiss
                      control there would set `detailOpen=false` and visibly do nothing.

                      A conditional RENDER, not a `display` toggle: a styles.css rule
                      (`.payroll-detail-close { display: none }`) is dead because `buttonVariants`'
                      `inline-flex` sits in `layer(utilities)` and index.css declares
                      `@layer theme, legacy, utilities`, so a Tailwind utility always beats a
                      styles.css rule. Not rendering it also keeps it out of the tab order. */}
                  {isOverlayPanel && (
                  <Button
                    type="button"
                    variant="icon"
                    size="sm"
                    // `variant="icon" size="sm"` is a 36x36 compound-variant box (Button.jsx), too small
                    // for the primary dismiss control on a full-height overlay a touch user must reach.
                    // Fixed at this call site (`w-11 min-h-[44px]`; `cn`'s twMerge wins over the
                    // compound `w-9 min-h-[36px]`) rather than in the shared icon+sm variant, which
                    // DataTable's pagination arrows and TicketListPage's filter-panel close also use.
                    className="payroll-detail-close w-11 min-h-[44px]"
                    onClick={closeDetailPanel}
                    title="ปิดรายละเอียด"
                  >
                    <Icon name="close" size={16} />
                  </Button>
                  )}
                </div>
              </Panel.Header>

              {/* `grid-cols-1 @sm:grid-cols-2`: 1 column while the panel container is narrower than
                  Tailwind's `@sm` (24rem/384px) content-box floor, 2 once there is room. A container
                  query keyed off `@container` on the `<aside>` above, not a viewport breakpoint. */}
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

              {/* Shown only for a pay_type = 'D' employee: hr.employee.current_salary is that
                  employee's PER-DAY rate, so "ฐานเงินเดือน" above is really rate * daysWorked and HR
                  types the day count each run. Gated on selectedLine.payType, echoed onto
                  PayrollLineDto for this purpose. */}
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
                        // Upper bound matches the backend's @Max(31)/chk_payroll_line_days_worked_range.
                        // Decimals stay legitimate (e.g. 21.5), so step is unchanged.
                        max="31"
                        step="0.5"
                        placeholder="0"
                        value={selectedAdjustment.daysWorked}
                        // Raw <input type="number"> has no MoneyInput-style negative clamp; a typed
                        // "-5" would 400 the whole draft/preview/process PUT.
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
                    {/* Client-side warning mirroring PayrollService#requireEveryDailyRateEmployeeHasDaysWorked.
                        Only an early nudge: Process is still refused server-side regardless. */}
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
                {/* Same `grid-cols-1 @sm:grid-cols-2` as payroll-detail-grid above. One column gives each
                    label/input the panel's full content width, keeping every money input >=140px and
                    every Thai label <=2 lines at the 310px panel floor, without growing the panel as
                    more fields land here. */}
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
                  {/* Shown only once a per-diem amount is entered (nothing to classify until money
                      moves, as with tax-treatment classification elsewhere). Required once either amount
                      is non-zero, or chk_payroll_line_per_diem_basis_present rejects Process with a 500
                      (also caught earlier server-side, PayrollService#calculateLine). */}
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

              {/* bonusPay/otherOneOffPay: end-to-end on the backend; bonusPay is the archetypal
                  EXTRA_KNOWN_FREQUENCY component. */}
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
                    {/* Same clamp as daysWorked above: raw <input type="number"> has no MoneyInput clamp. */}
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
                  {/* Without a selection every garnishment uses the SALARY cap (backend default), making
                      the BONUS 50% / OVERTIME 30% / SEVERANCE ฿300,000 rules dead code. Shown only once an
                      amount is requested, as with the per-diem basis selector above. */}
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
                  {/* Selects the earned vs unearned (pre-tax netting) path; the unearned default caused a
                      negative-net payslip. Shown only once a return amount is entered, as above. */}
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

              {/* ล.ย.01 tax-allowance drill-down: composes the year's declaration register client-side
                  (no employeeId filter exists on GET /declarations); see TaxAllowanceDrilldown.jsx.

                  Titled to say what it is: ค่าลดหย่อนรวม above is computed from
                  hr.employee_tax_allowance (what PayrollCalculator applied this run), while
                  TaxAllowanceDrilldown reads ONLY hr.tax_allowance_declaration, a separate table the two
                  can disagree with (e.g. a grandfathered employee shows "no declaration" under a
                  non-zero total). The HR register it links out to ("ดูในหน้าตรวจสอบของ HR") holds the
                  full reconciliation. */}
              <CollapsibleSection
                title="ประวัติการยื่นแบบ ล.ย.01"
                subtitle="อาจไม่ตรงกับค่าลดหย่อนรวมด้านบน ซึ่งคำนวณจากข้อมูลที่ใช้จริงในระบบเงินเดือน"
                defaultOpen={false}
              >
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
            <p className="confirm-dialog-message text-text-secondary leading-normal">
              ยืนยันประมวลผลเงินเดือนรอบ {formatThaiMonthYearFromMonthInputValue(month)} สำหรับพนักงาน {period?.lineCount || 0} คน?
              การดำเนินการนี้ไม่สามารถย้อนกลับได้
            </p>
            <p className="confirm-dialog-message text-text-secondary leading-normal">
              หลังยืนยัน การอนุมัติ OT ของเดือนนี้จะปิดทันที และไม่มีทางยกเลิกการประมวลผล
            </p>
            <p className="confirm-dialog-message text-text-secondary leading-normal">
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
 * The tax-treatment matrix screen. HR classifies every non-SALARY component per employee (spec
 * sections 1 and 10), which is what satisfies `PayrollCalculator#calculateClassified`'s
 * classification gate. One `CollapsibleSection` per employee ("never a shrunken fifteen-column
 * table"), each with its own unclassified-count badge so HR can find the outliers.
 *
 * `item.byComponent` carries the EFFECTIVE classification (defaults synthesized server-side by
 * `PayrollService#treatmentsFor`), so a select for an unclassified component shows the treatment the
 * engine applies instead of blank. `item.explicitlyClassifiedComponents` says which values HR (or a
 * backfill) actually set; the "ค่าเริ่มต้นของระบบ" hint renders only for the others, so HR can tell
 * "the system is defaulting this" from "someone chose this".
 */
function TaxTreatmentMatrixSection({ payrollMonth, showToast, canManage }) {
  const taxYear = Number(String(payrollMonth || '').slice(0, 4)) || new Date().getFullYear();
  const [items, setItems] = useState([]);
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  // employeeId -> { [component]: 'REGULAR_REPROJECT' | 'EXTRA_KNOWN_FREQUENCY' | 'EXTRA_CUMULATIVE_ACTUAL' | ''
  // } — unsaved edits only; not yet reconciled into `items`.
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

  // True once HR has a stored row for this cell (per `item.explicitlyClassifiedComponents`) OR is
  // setting one now in this unsaved session. False means `valueFor` above shows a backend-synthesized
  // value, not one anybody chose.
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
                    // A non-blank value nobody explicitly set is the backend's synthesized default; flag it
                    // so HR does not mistake it for a reviewed choice.
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
        {/* PUT /component-tax-treatments is hasRole('HR') only -- omitted for a read-only CEO session (the selects
         * above are disabled too, so there is never anything to save). */}
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
    <div className="grid gap-1 min-w-0 p-3 border border-border rounded-md bg-surface-muted">
      <small className="text-text-muted text-[length:var(--text-2xs)] font-extrabold">{label}</small>
      <strong className="min-w-0 overflow-hidden text-text text-[length:var(--text-lg)] text-ellipsis whitespace-nowrap">{value}</strong>
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
    <span className="relative block">
      <span className="currency-input-symbol absolute left-3 top-1/2 z-[1] text-text-muted font-black leading-none pointer-events-none -translate-y-1/2" aria-hidden="true">฿</span>
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
        className="pl-[34px]"
      />
    </span>
  );
}
