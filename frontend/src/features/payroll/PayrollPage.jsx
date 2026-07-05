import React, { useEffect, useMemo, useRef, useState } from 'react';
import { api } from '../../api/index.js';
import { EmptyState } from '../../components/common/EmptyState.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { PageHeader } from '../../components/common/PageHeader.jsx';
import { StatCard } from '../../components/common/StatCard.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { formatMoney } from '../../utils/format.js';

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

export function PayrollPage({ showToast }) {
  const [month, setMonth] = useState(thisMonth);
  const [period, setPeriod] = useState(null);
  const [adjustments, setAdjustments] = useState({});
  const [selectedEmployeeId, setSelectedEmployeeId] = useState(null);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [hasFreshUpdate, setHasFreshUpdate] = useState(false);

  const dirtyRef = useRef(false);
  const savingRef = useRef(false);
  const loadingRef = useRef(true);
  const monthRef = useRef(month);
  const periodRef = useRef(null);
  const selectedEmployeeIdRef = useRef(null);

  useEffect(() => { savingRef.current = saving; }, [saving]);
  useEffect(() => { loadingRef.current = loading; }, [loading]);
  useEffect(() => { monthRef.current = month; }, [month]);
  useEffect(() => { periodRef.current = period; }, [period]);
  useEffect(() => { selectedEmployeeIdRef.current = selectedEmployeeId; }, [selectedEmployeeId]);

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
      dirtyRef.current = false;
      setHasFreshUpdate(false);
    } catch (error) {
      showToast('error', error.message || 'โหลดเงินเดือนไม่สำเร็จ');
    } finally {
      setLoading(false);
    }
  }

  function applyPeriod(nextPeriod, { applyUatDefaults = false, keepSelection = false } = {}) {
    setPeriod(nextPeriod);
    const nextAdjustments = {};
    const applyDefaults = applyUatDefaults && nextPeriod?.status === 'PREVIEW';
    (nextPeriod?.lines || []).forEach((line) => {
      nextAdjustments[line.employeeId] = adjustmentFromLine(line, { applyDefaults });
    });
    setAdjustments(nextAdjustments);
    if (keepSelection) {
      setSelectedEmployeeId((current) => current ?? nextPeriod?.lines?.[0]?.employeeId ?? null);
    } else {
      setSelectedEmployeeId((current) => current || nextPeriod?.lines?.[0]?.employeeId || null);
    }
  }

  useEffect(() => {
    setSelectedEmployeeId(null);
    load();
  }, [month]);

  // Silently poll for freshly-approved OT/leave/commission so the page doesn't
  // stay stale until the user clicks "รีเฟรช". Skips while loading or once the
  // period is PROCESSED (immutable). Never clobbers in-progress HR edits.
  useEffect(() => {
    async function poll() {
      const currentMonth = monthRef.current;
      const currentPeriod = periodRef.current;
      if (loadingRef.current) return;
      if (currentPeriod?.status === 'PROCESSED') return;

      try {
        const response = await api.payroll.current({ payrollMonth: currentMonth });
        const nextPeriod = response.period;

        if (savingRef.current || dirtyRef.current) {
          // Don't overwrite adjustments/inputs while the user is mid-edit or saving.
          // Just surface a hint if the fetched period actually differs.
          const changed = JSON.stringify(nextPeriod) !== JSON.stringify(currentPeriod);
          if (changed) setHasFreshUpdate(true);
          return;
        }

        applyPeriod(nextPeriod, { applyUatDefaults: true, keepSelection: true });
        setHasFreshUpdate(false);
      } catch (error) {
        // Swallow transient poll errors — never disrupt the user with a toast.
        console.error('Payroll background refresh failed', error);
      }
    }

    const interval = setInterval(poll, 30000);
    window.addEventListener('focus', poll);
    return () => {
      clearInterval(interval);
      window.removeEventListener('focus', poll);
    };
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
      dirtyRef.current = false;
      setHasFreshUpdate(false);
      showToast('success', 'คำนวณตัวอย่างเงินเดือนแล้ว');
    } catch (error) {
      showToast('error', error.message || 'คำนวณเงินเดือนไม่สำเร็จ');
    } finally {
      setSaving(false);
    }
  }

  async function process() {
    if (!window.confirm(`ยืนยันประมวลผลเงินเดือนรอบ ${month}?`)) return;
    setSaving(true);
    try {
      const response = await api.payroll.process(payload());
      applyPeriod(response.period);
      dirtyRef.current = false;
      setHasFreshUpdate(false);
      showToast('success', 'ประมวลผลเงินเดือนเรียบร้อย');
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
      const url = URL.createObjectURL(blob);
      const anchor = document.createElement('a');
      anchor.href = url;
      anchor.download = `glr-payroll-${month}.txt`;
      anchor.click();
      URL.revokeObjectURL(url);
      showToast('success', 'ดาวน์โหลดไฟล์โอนเงินแล้ว');
    } catch (error) {
      showToast('error', error.message || 'ดาวน์โหลดไฟล์ไม่สำเร็จ');
    } finally {
      setSaving(false);
    }
  }

  function updateAdjustment(field, value) {
    if (!selectedLine) return;
    dirtyRef.current = true;
    setAdjustments((current) => ({
      ...current,
      [selectedLine.employeeId]: {
        ...(current[selectedLine.employeeId] || blankAdjustment(selectedLine.employeeId)),
        [field]: value,
      },
    }));
  }

  return (
    <div className="page-stack">
      <PageHeader
        title="ประมวลผลเงินเดือน"
        subtitle="Payroll Processing"
        actions={(
          <div className="toolbar-actions">
            <label>
              รอบเดือน
              <input type="month" value={month} onChange={(event) => setMonth(event.target.value)} />
            </label>
            <button type="button" className="secondary-button" onClick={load} disabled={loading || saving}>
              <Icon name="refresh" />
              รีเฟรช
            </button>
            {hasFreshUpdate && (
              <button
                type="button"
                className="link-button"
                onClick={load}
                style={{ fontSize: 12, color: '#1d4ed8', background: 'none', border: 'none', cursor: 'pointer', padding: 0 }}
              >
                มีข้อมูลอนุมัติใหม่ กดรีเฟรชเพื่ออัปเดต
              </button>
            )}
          </div>
        )}
      />

      <section className="stat-grid">
        <StatCard icon="badgeDollar" label="รายได้รวม" value={formatMoney(period?.totalGross)} helper="Gross earnings" tone="indigo" />
        <StatCard icon="clipboard" label="เงินหักรวม" value={formatMoney(period?.totalDeductions)} helper="Deductions" tone="amber" />
        <StatCard icon="check" label="ยอดโอนสุทธิ" value={formatMoney(period?.totalNet)} helper="Net transfer" tone="teal" />
        <StatCard icon="shield" label="ภาษี/ปกส." value={formatMoney(Number(period?.totalWithholdingTax || 0) + Number(period?.totalSocialSecurity || 0))} helper="Tax + SSO" tone="blue" />
      </section>

      <section className="filter-bar payroll-actions">
        <div>
          <strong>{period?.lineCount || 0} employees</strong>
          <span>สถานะรอบเงินเดือน</span>
        </div>
        <StatusBadge tone={status.tone}>{status.label}</StatusBadge>
        <button type="button" className="secondary-button" onClick={preview} disabled={loading || saving}>
          <Icon name="search" />
          Preview
        </button>
        <button type="button" className="primary-button" onClick={process} disabled={loading || saving}>
          <Icon name="check" />
          Process Payroll
        </button>
        <button type="button" className="secondary-button" onClick={exportBankFile} disabled={!period?.id || saving}>
          <Icon name="fileText" />
          Bank file
        </button>
      </section>

      <section className="payroll-workspace">
        <div className="table-panel">
          <div className="payroll-table table-head">
            <span>พนักงาน</span>
            <span>รายได้</span>
            <span>เงินพิเศษ</span>
            <span>OT / Commission</span>
            <span>เงินหัก</span>
            <span>สุทธิ</span>
          </div>
          {loading ? (
            <div className="table-row" style={{ justifyContent: 'center', color: '#94a3b8' }}>กำลังโหลด...</div>
          ) : !period?.lines?.length ? (
            <EmptyState icon="badgeDollar" title="ยังไม่มีข้อมูลเงินเดือน" description="เลือกรอบเดือนหรือกดรีเฟรชเพื่อคำนวณตัวอย่าง" />
          ) : period.lines.map((line) => (
            <button
              key={line.employeeId}
              type="button"
              className={`payroll-table table-row payroll-row ${Number(line.employeeId) === Number(selectedLine?.employeeId) ? 'active' : ''}`}
              onClick={() => setSelectedEmployeeId(line.employeeId)}
            >
              <span>
                <strong>{line.employeeName}</strong>
                <small>{line.employeeCode} · {line.departmentName || '-'}</small>
              </span>
              <code>{formatMoney(line.grossEarnings)}</code>
              <code>{formatMoney(line.specialPayTotal)}</code>
              <code>{formatMoney(Number(line.overtimePay || 0) + Number(line.commissionPay || 0))}</code>
              <code>{formatMoney(line.totalDeductions)}</code>
              <code>{formatMoney(line.netPay)}</code>
            </button>
          ))}
        </div>

        <aside className="panel payroll-detail-panel">
          {selectedLine && selectedAdjustment ? (
            <>
              <div className="panel-header">
                <h2>{selectedLine.employeeName}</h2>
                <StatusBadge tone="info">{selectedLine.employeeCode}</StatusBadge>
              </div>

              <div className="payroll-detail-grid">
                <MiniMetric label="ฐานเงินเดือน" value={formatMoney(selectedLine.baseSalary)} />
                <MiniMetric label="รายได้คิดภาษี" value={formatMoney(selectedLine.grossTaxableIncome)} />
                <MiniMetric label="ภาษีงวดนี้" value={formatMoney(selectedLine.withholdingTax)} />
                <MiniMetric label="เงินโอนสุทธิ" value={formatMoney(selectedLine.netPay)} />
              </div>

              <div className="payroll-adjustment-group">
                <h3>เงินพิเศษบริษัท</h3>
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
              </div>

              <div className="payroll-adjustment-group">
                <h3>รายได้ไม่คิดภาษี</h3>
                <div className="form-grid">
                  <label htmlFor="payroll-non-taxable-income">
                    รายได้อื่นๆ (ไม่คิดภาษี)
                    <MoneyInput id="payroll-non-taxable-income" value={selectedAdjustment.nonTaxableIncome} onChange={(value) => updateAdjustment('nonTaxableIncome', value)} />
                  </label>
                </div>
              </div>

              <div className="payroll-adjustment-group">
                <h3>รายการหักรายบุคคล</h3>
                <div className="form-grid">
                  <label htmlFor="payroll-unpaid-leave-days">
                    วันลาไม่รับค่าจ้าง
                    <input id="payroll-unpaid-leave-days" type="number" min="0" step="0.25" placeholder="0" value={selectedAdjustment.unpaidLeaveDays} onChange={(event) => updateAdjustment('unpaidLeaveDays', event.target.value)} />
                    <small>เติมจากระบบลงเวลา/การลา แก้ไขได้</small>
                  </label>
                  <label htmlFor="payroll-student-loan-deduction">
                    หัก กยศ.
                    <MoneyInput id="payroll-student-loan-deduction" value={selectedAdjustment.studentLoanDeduction} onChange={(value) => updateAdjustment('studentLoanDeduction', value)} />
                  </label>
                  <label htmlFor="payroll-legal-execution-deduction">
                    หักอายัดกรมบังคับคดี
                    <MoneyInput id="payroll-legal-execution-deduction" value={selectedAdjustment.legalExecutionDeduction} onChange={(value) => updateAdjustment('legalExecutionDeduction', value)} />
                  </label>
                  <label htmlFor="payroll-other-post-tax-deductions">
                    หักอื่น ๆ หลังภาษี
                    <MoneyInput id="payroll-other-post-tax-deductions" value={selectedAdjustment.otherPostTaxDeductions} onChange={(value) => updateAdjustment('otherPostTaxDeductions', value)} />
                  </label>
                </div>
              </div>

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
    </div>
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
        onChange={(event) => onChange(event.target.value)}
      />
    </span>
  );
}
