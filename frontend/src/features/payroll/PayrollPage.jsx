import React, { useEffect, useMemo, useState } from 'react';
import { api } from '../../api/index.js';
import { Button } from '../../components/common/Button.jsx';
import { CollapsibleSection } from '../../components/common/CollapsibleSection.jsx';
import { ConfirmDialog } from '../../components/common/ConfirmDialog.jsx';
import { DataTable } from '../../components/common/DataTable.jsx';
import { DesktopOnlyNotice } from '../../components/common/DesktopOnlyNotice.jsx';
import { EmptyState } from '../../components/common/EmptyState.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { InfoTip } from '../../components/common/InfoTip.jsx';
import { FilterBar, FormGrid, PageStack, Panel, StatGrid } from '../../components/common/Layout.jsx';
import { PageHeader } from '../../components/common/PageHeader.jsx';
import { StatCard } from '../../components/common/StatCard.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { useIsMobile } from '../../hooks/useIsMobile.js';
import { cn } from '../../utils/cn.js';
import { formatMoney, payrollStatusLabel as statusInfo } from '../../utils/format.js';

// Reproduces `.panel` for the detail sidebar, which must stay a real <aside>
// element (semantic landmark) — Layout.jsx's Panel always renders a <section>
// and has no `as` prop, so the exact utility string is duplicated here rather
// than wrapping a <section> inside an <aside> or changing the shared primitive.
const PANEL_CLASS = 'bg-surface border border-border rounded-md shadow-sm p-5';

// The three statutory files HR can generate for a processed period. `value` is the backend export
// slug (/api/payroll/{id}/export/{value}); `filePrefix` names the downloaded blob.
const EXPORT_KINDS = [
  { value: 'kbank', label: 'KBank Payroll', filePrefix: 'PCT' },
  { value: 'pnd1', label: 'ภ.ง.ด.1', filePrefix: 'Pnd1' },
  { value: 'sso', label: 'ประกันสังคม (สปส.1-10)', filePrefix: 'SPS1-10' },
];

const payrollColumns = [
  {
    key: 'employee',
    header: 'พนักงาน',
    sortable: true,
    sortAccessor: (line) => line.employeeName,
    searchAccessor: (line) => line.employeeName,
    render: (line) => (
      <span>
        <strong>{line.employeeName}</strong>
        <small>{line.employeeCode} · {line.departmentName || '-'}</small>
      </span>
    ),
  },
  {
    key: 'grossEarnings',
    header: 'รายได้',
    sortable: true,
    sortAccessor: (line) => Number(line.grossEarnings || 0),
    render: (line) => <code>{formatMoney(line.grossEarnings)}</code>,
  },
  {
    key: 'specialPayTotal',
    header: 'เงินพิเศษ',
    sortable: true,
    sortAccessor: (line) => Number(line.specialPayTotal || 0),
    render: (line) => <code>{formatMoney(line.specialPayTotal)}</code>,
  },
  {
    key: 'otCommission',
    header: 'OT / Commission',
    sortable: true,
    sortAccessor: (line) => Number(line.overtimePay || 0) + Number(line.commissionPay || 0),
    render: (line) => <code>{formatMoney(Number(line.overtimePay || 0) + Number(line.commissionPay || 0))}</code>,
  },
  {
    key: 'totalDeductions',
    header: 'เงินหัก',
    sortable: true,
    sortAccessor: (line) => Number(line.totalDeductions || 0),
    render: (line) => <code>{formatMoney(line.totalDeductions)}</code>,
  },
  {
    key: 'netPay',
    header: 'สุทธิ',
    sortable: true,
    sortAccessor: (line) => Number(line.netPay || 0),
    render: (line) => <code>{formatMoney(line.netPay)}</code>,
  },
];

const thisMonth = new Date().toISOString().slice(0, 7);
const specialPayFields = [
  { key: 'specialPay1', label: 'พิเศษ 1 (ค่าครองชีพ)', defaultValue: '500' },
  { key: 'specialPay2', label: 'พิเศษ 2 (เบี้ยเลี้ยงประจำ)' },
  { key: 'specialPay3', label: 'พิเศษ 3 (ค่าตำแหน่ง)' },
  { key: 'specialPay4', label: 'พิเศษ 4 (เบี้ยขยันประจำ)' },
  { key: 'specialPay5', label: 'พิเศษ 5 (ค่า GPRS)', defaultValue: '500' },
  { key: 'specialPay6', label: 'พิเศษ 6 (คอมมิชชั่น)' },
  { key: 'specialPay7', label: 'พิเศษ 7 (ทำได้ตาม KPI)' },
  { key: 'specialPay8', label: 'พิเศษ 8 (เงินรางวัล/เงินช่วยเหลืออื่นๆ)' },
];
const specialPayKeys = specialPayFields.map((field) => field.key);
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
const payrollInputKeys = [...specialPayKeys, ...incomeInputKeys, ...deductionInputKeys];

function defaultSpecialPayValue(key, applyDefaults) {
  if (!applyDefaults) return '';
  return specialPayFields.find((field) => field.key === key)?.defaultValue ?? '';
}

function draftValue(value, fallback = '') {
  const amount = Number(value || 0);
  return amount > 0 ? String(amount) : fallback;
}

// Special-pay carry-forward (2026-07-23): the recurring fields pre-filled from the employee's most
// recent prior processed payroll_line (via GET /api/payroll/suggested-inputs) when HR starts a
// brand-new run. Deliberately excludes specialPay6 (commission — already fed by the commission
// engine), specialPay7/8 (KPI/bonus — one-off) and every event-driven field. This is a client-side
// pre-fill convenience only: whatever value sits in the field when HR hits Preview/Process — carried,
// edited, or explicitly cleared to 0 — is submitted as-is via `payload()` below, same as today.
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
  };
}

function adjustmentFromLine(line, { applyDefaults = false, suggestion = null } = {}) {
  const adjustment = blankAdjustment(line.employeeId, { applyDefaults });
  (line.specialPays || []).forEach((item, index) => {
    const key = `specialPay${index + 1}`;
    // Priority: the line's own real value (already-submitted/persisted) > a carried figure from
    // last month > the hardcoded UAT demo default. A suggestion only ever fills in for a genuinely
    // blank/zero field on a fresh run.
    const fallback = suggestedFallback(suggestion, key) ?? defaultSpecialPayValue(key, applyDefaults);
    adjustment[key] = draftValue(item.amount, fallback);
  });
  adjustment.nonTaxableIncome = draftValue(line.nonTaxableIncome, suggestedFallback(suggestion, 'nonTaxableIncome') ?? '');
  // Leave -> payroll unpaid-day deduction (2026-07-23): unpaidLeaveDays is event-driven (this
  // month's approved-beyond-quota leave, from GET /api/payroll/suggested-inputs), unlike the other
  // carried fields above which are prior-month recurring amounts -- but it pre-fills the same way:
  // a real line value (already-submitted/persisted) wins, otherwise fall back to the suggestion.
  // HR can still edit/clear it before Preview/Process, same as every other field here.
  adjustment.unpaidLeaveDays = draftValue(line.unpaidLeaveDays, suggestedFallback(suggestion, 'unpaidLeaveDays') ?? '');
  adjustment.studentLoanDeduction = draftValue(line.studentLoanDeduction, suggestedFallback(suggestion, 'studentLoanDeduction') ?? '');
  adjustment.legalExecutionDeduction = draftValue(line.legalExecutionDeduction, suggestedFallback(suggestion, 'legalExecutionDeduction') ?? '');
  adjustment.otherPostTaxDeductions = draftValue(line.otherPostTaxDeductions);
  adjustment.warningLetterDeduction = draftValue(line.warningLetterDeduction);
  adjustment.customerReturnDeduction = draftValue(line.customerReturnDeduction);
  adjustment.otherPretaxDeduction = draftValue(line.otherPretaxDeduction);
  return adjustment;
}

function normalizedAdjustment(input) {
  return {
    employeeId: input.employeeId,
    ...Object.fromEntries(payrollInputKeys.map((key) => [key, parsePayrollNumber(input[key])])),
  };
}

function hasPayrollInput(input) {
  return payrollInputKeys.some((key) => parsePayrollNumber(input[key]) > 0);
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

  async function load() {
    setLoading(true);
    try {
      const response = await api.payroll.current({ payrollMonth: month });
      const nextPeriod = response.period;
      // Special-pay carry-forward: only fetch suggestions when starting a fresh run for this month
      // (PREVIEW with no processed period yet) — a PROCESSED period already reflects real, submitted
      // values and must never be overwritten by a stale suggestion.
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
      setSuggestionsByEmployee(suggestionsByEmployee);
      applyPeriod(nextPeriod, { applyUatDefaults: true, suggestionsByEmployee });
    } catch (error) {
      showToast('error', error.message || 'โหลดเงินเดือนไม่สำเร็จ');
    } finally {
      setLoading(false);
    }
  }

  function applyPeriod(nextPeriod, { applyUatDefaults = false, suggestionsByEmployee = {} } = {}) {
    setPeriod(nextPeriod);
    const nextAdjustments = {};
    const applyDefaults = applyUatDefaults && nextPeriod?.status === 'PREVIEW';
    (nextPeriod?.lines || []).forEach((line) => {
      nextAdjustments[line.employeeId] = adjustmentFromLine(line, {
        applyDefaults,
        suggestion: suggestionsByEmployee[line.employeeId],
      });
    });
    setAdjustments(nextAdjustments);
    setSelectedEmployeeId((current) => current || nextPeriod?.lines?.[0]?.employeeId || null);
  }

  useEffect(() => {
    setSelectedEmployeeId(null);
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

  function process() {
    setConfirmProcess(true);
  }

  async function confirmProcessPayroll() {
    setSaving(true);
    try {
      const response = await api.payroll.process(payload());
      applyPeriod(response.period);
      showToast('success', 'ประมวลผลเงินเดือนเรียบร้อย');
      setConfirmProcess(false);
    } catch (error) {
      showToast('error', error.message || 'ประมวลผลเงินเดือนไม่สำเร็จ');
    } finally {
      setSaving(false);
    }
  }

  async function generateExportFile() {
    if (!period?.id) return;
    const kind = EXPORT_KINDS.find((item) => item.value === exportKind) || EXPORT_KINDS[0];
    setSaving(true);
    try {
      // The backend returns raw CP874 bytes; exportFile fetches them as a binary blob so the Thai
      // encoding survives the download intact. payDate is the salary pay/transfer date.
      const blob = await api.payroll.exportFile(period.id, kind.value, payDate || undefined);
      downloadBlob(blob, `${kind.filePrefix}-${month}.txt`);
      showToast('success', `ดาวน์โหลดไฟล์ ${kind.label} แล้ว`);
    } catch (error) {
      showToast('error', error.message || 'ดาวน์โหลดไฟล์ไม่สำเร็จ');
    } finally {
      setSaving(false);
    }
  }

  const columns = [
    ...payrollColumns,
    {
      key: 'actions',
      header: 'เอกสาร',
      render: (line) => (
        <span className="row-actions">
          <Button type="button" variant="text" onClick={() => setSelectedEmployeeId(line.employeeId)}>
            รายละเอียด
          </Button>
          <Button type="button" variant="secondary" onClick={() => downloadPayslip(line)} disabled={!period?.id || !line.id || saving}>
            <Icon name="fileText" />
            Download payslip
          </Button>
        </span>
      ),
    },
  ];

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
            </label>
            <Button type="button" variant="secondary" onClick={load} disabled={loading || saving}>
              <Icon name="refresh" />
              รีเฟรช
            </Button>
          </div>
        )}
      />

      <StatGrid>
        <StatCard icon="badgeDollar" label="รายได้รวม" value={formatMoney(period?.totalGross)} helper="Gross earnings" tone="indigo" />
        <StatCard icon="clipboard" label="เงินหักรวม" value={formatMoney(period?.totalDeductions)} helper="Deductions" tone="amber" />
        <StatCard icon="check" label="ยอดโอนสุทธิ" value={formatMoney(period?.totalNet)} helper="Net transfer" tone="teal" />
        <StatCard icon="shield" label="ภาษี/ปกส." value={formatMoney(Number(period?.totalWithholdingTax || 0) + Number(period?.totalSocialSecurity || 0))} helper="Tax + SSO" tone="blue" />
      </StatGrid>

      <FilterBar className="payroll-actions">
        <div>
          <strong>{period?.lineCount || 0} employees</strong>
          <span>สถานะรอบเงินเดือน</span>
        </div>
        <StatusBadge tone={status.tone}>{status.label}</StatusBadge>
        <Button type="button" variant="secondary" onClick={preview} disabled={loading || saving}>
          <Icon name="search" />
          Preview
        </Button>
        <Button type="button" onClick={process} disabled={loading || saving}>
          <Icon name="check" />
          Process Payroll
        </Button>
        <div className="flex flex-wrap items-center gap-2">
          <select
            value={exportKind}
            onChange={(event) => setExportKind(event.target.value)}
            disabled={!period?.id || saving}
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
              disabled={!period?.id || saving}
            />
          </label>
          <Button type="button" variant="secondary" onClick={generateExportFile} disabled={!period?.id || saving}>
            <Icon name="fileText" />
            ดาวน์โหลดไฟล์
          </Button>
        </div>
        <Button type="button" variant="secondary" onClick={distributePayslips} disabled={!period?.id || saving}>
          <Icon name="mail" />
          Email payslips
        </Button>
      </FilterBar>

      <section className="payroll-workspace">
        <DataTable
          columns={columns}
          rows={period?.lines || []}
          getRowKey={(line) => line.employeeId}
          gridClassName="payroll-table"
          pageSize={25}
          searchable
          searchPlaceholder="ค้นหาพนักงาน"
          rowClassName={(line) => `payroll-row${Number(line.employeeId) === Number(selectedLine?.employeeId) ? ' active' : ''}`}
          loading={loading}
          emptyState={{
            icon: 'badgeDollar',
            title: 'ยังไม่มีข้อมูลเงินเดือน',
            description: 'เลือกรอบเดือนหรือกดรีเฟรชเพื่อคำนวณตัวอย่าง',
          }}
        />

        <aside className={cn(PANEL_CLASS, 'payroll-detail-panel')}>
          {selectedLine && selectedAdjustment ? (
            <>
              <Panel.Header>
                <h2 className="m-0 text-lg">{selectedLine.employeeName}</h2>
                <StatusBadge tone="info">{selectedLine.employeeCode}</StatusBadge>
              </Panel.Header>

              <div className="payroll-detail-grid">
                <MiniMetric label="ฐานเงินเดือน" value={formatMoney(selectedLine.baseSalary)} />
                <MiniMetric label="รายได้คิดภาษี" value={formatMoney(selectedLine.grossTaxableIncome)} />
                <MiniMetric label="ภาษีงวดนี้" value={formatMoney(selectedLine.withholdingTax)} />
                <MiniMetric label="เงินโอนสุทธิ" value={formatMoney(selectedLine.netPay)} />
              </div>

              <CollapsibleSection
                title="เงินพิเศษบริษัท"
                defaultOpen
                headerRight={<span className="collapsible-total">{formatMoney(specialPayKeys.reduce((sum, key) => sum + parsePayrollNumber(selectedAdjustment[key]), 0))}</span>}
              >
                <div className="payroll-special-grid">
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
                  <label htmlFor="payroll-other-post-tax-deductions">
                    หักอื่น ๆ หลังภาษี
                    <MoneyInput id="payroll-other-post-tax-deductions" value={selectedAdjustment.otherPostTaxDeductions} onChange={(value) => updateAdjustment('otherPostTaxDeductions', value)} />
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
              ยืนยันประมวลผลเงินเดือนรอบ {month}? การดำเนินการนี้ไม่สามารถย้อนกลับได้
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
