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
import { formatMoney } from '../../utils/format.js';

// Reproduces `.panel` for the detail sidebar, which must stay a real <aside>
// element (semantic landmark) — Layout.jsx's Panel always renders a <section>
// and has no `as` prop, so the exact utility string is duplicated here rather
// than wrapping a <section> inside an <aside> or changing the shared primitive.
const PANEL_CLASS = 'bg-surface border border-border rounded-md shadow-sm p-5';

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
  };
}

function adjustmentFromLine(line, { applyDefaults = false } = {}) {
  const adjustment = blankAdjustment(line.employeeId, { applyDefaults });
  (line.specialPays || []).forEach((item, index) => {
    const key = `specialPay${index + 1}`;
    adjustment[key] = draftValue(item.amount, defaultSpecialPayValue(key, applyDefaults));
  });
  adjustment.nonTaxableIncome = draftValue(line.nonTaxableIncome);
  adjustment.unpaidLeaveDays = draftValue(line.unpaidLeaveDays);
  adjustment.studentLoanDeduction = draftValue(line.studentLoanDeduction);
  adjustment.legalExecutionDeduction = draftValue(line.legalExecutionDeduction);
  adjustment.otherPostTaxDeductions = draftValue(line.otherPostTaxDeductions);
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

function statusInfo(status) {
  const map = {
    PREVIEW: { label: 'ตัวอย่าง', tone: 'info' },
    OPEN: { label: 'เปิดรอบ', tone: 'warning' },
    PROCESSED: { label: 'ประมวลผลแล้ว', tone: 'success' },
    CLOSED: { label: 'ปิดรอบ', tone: 'neutral' },
    VOID: { label: 'ยกเลิก', tone: 'danger' },
  };
  return map[status] ?? { label: status || '-', tone: 'neutral' };
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

  const selectedLine = useMemo(
    () => (period?.lines || []).find((line) => Number(line.employeeId) === Number(selectedEmployeeId)) || period?.lines?.[0] || null,
    [period, selectedEmployeeId],
  );
  const selectedAdjustment = selectedLine
    ? adjustments[selectedLine.employeeId] || adjustmentFromLine(selectedLine)
    : null;
  const status = statusInfo(period?.status);

  async function load() {
    setLoading(true);
    try {
      const response = await api.payroll.current({ payrollMonth: month });
      applyPeriod(response.period, { applyUatDefaults: true });
    } catch (error) {
      showToast('error', error.message || 'โหลดเงินเดือนไม่สำเร็จ');
    } finally {
      setLoading(false);
    }
  }

  function applyPeriod(nextPeriod, { applyUatDefaults = false } = {}) {
    setPeriod(nextPeriod);
    const nextAdjustments = {};
    const applyDefaults = applyUatDefaults && nextPeriod?.status === 'PREVIEW';
    (nextPeriod?.lines || []).forEach((line) => {
      nextAdjustments[line.employeeId] = adjustmentFromLine(line, { applyDefaults });
    });
    setAdjustments(nextAdjustments);
    setSelectedEmployeeId((current) => current || nextPeriod?.lines?.[0]?.employeeId || null);
  }

  useEffect(() => {
    setSelectedEmployeeId(null);
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

  async function exportBankFile() {
    if (!period?.id) return;
    setSaving(true);
    try {
      const text = await api.payroll.bankExport(period.id);
      const blob = new Blob([text], { type: 'text/plain;charset=utf-8' });
      downloadBlob(blob, `glr-payroll-${month}.txt`);
      showToast('success', 'ดาวน์โหลดไฟล์โอนเงินแล้ว');
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
        <Button type="button" variant="secondary" onClick={exportBankFile} disabled={!period?.id || saving}>
          <Icon name="fileText" />
          Bank file
        </Button>
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
                    <input id="payroll-unpaid-leave-days" type="number" min="0" step="0.25" placeholder="0" value={selectedAdjustment.unpaidLeaveDays} onChange={(event) => updateAdjustment('unpaidLeaveDays', event.target.value)} />
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

              <div className="payroll-breakdown">
                <span><b>SSO</b>{formatMoney(selectedLine.socialSecurity)}</span>
                <span><b>ฐาน ปกส.</b>{formatMoney(selectedLine.ssoWageBase)}</span>
                <span><b>รายได้ทั้งปีประมาณการ</b>{formatMoney(selectedLine.projectedAnnualIncome)}</span>
                <span><b>ค่าลดหย่อนรวม</b>{formatMoney(selectedLine.taxAllowanceTotal)}</span>
                <span><b>รายได้ไม่คิดภาษี</b>{formatMoney(selectedLine.nonTaxableIncome)}</span>
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
