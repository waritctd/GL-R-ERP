import { useEffect, useMemo, useState } from 'react';
import { api } from '../../api/index.js';
import { EmptyState } from '../../components/common/EmptyState.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { PageHeader } from '../../components/common/PageHeader.jsx';
import { StatCard } from '../../components/common/StatCard.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { formatMoney } from '../../utils/format.js';

const thisMonth = new Date().toISOString().slice(0, 7);
const specialPayKeys = Array.from({ length: 8 }, (_, index) => `specialPay${index + 1}`);

function blankAdjustment(employeeId) {
  return {
    employeeId,
    specialPay1: 0,
    specialPay2: 0,
    specialPay3: 0,
    specialPay4: 0,
    specialPay5: 0,
    specialPay6: 0,
    specialPay7: 0,
    specialPay8: 0,
    unpaidLeaveDays: 0,
    studentLoanDeduction: 0,
    legalExecutionDeduction: 0,
    otherPostTaxDeductions: 0,
  };
}

function adjustmentFromLine(line) {
  const adjustment = blankAdjustment(line.employeeId);
  (line.specialPays || []).forEach((item, index) => {
    adjustment[`specialPay${index + 1}`] = Number(item.amount || 0);
  });
  adjustment.unpaidLeaveDays = Number(line.unpaidLeaveDays || 0);
  adjustment.studentLoanDeduction = Number(line.studentLoanDeduction || 0);
  adjustment.legalExecutionDeduction = Number(line.legalExecutionDeduction || 0);
  adjustment.otherPostTaxDeductions = Number(line.otherPostTaxDeductions || 0);
  return adjustment;
}

function hasPayrollInput(input) {
  return Object.entries(input).some(([key, value]) => key !== 'employeeId' && Number(value || 0) > 0);
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

export function PayrollPage({ user, showToast }) {
  const [month, setMonth] = useState(thisMonth);
  const [period, setPeriod] = useState(null);
  const [adjustments, setAdjustments] = useState({});
  const [selectedEmployeeId, setSelectedEmployeeId] = useState(null);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);

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
      applyPeriod(response.period);
    } catch (error) {
      showToast('error', error.message || 'โหลดเงินเดือนไม่สำเร็จ');
    } finally {
      setLoading(false);
    }
  }

  function applyPeriod(nextPeriod) {
    setPeriod(nextPeriod);
    const nextAdjustments = {};
    (nextPeriod?.lines || []).forEach((line) => {
      nextAdjustments[line.employeeId] = adjustmentFromLine(line);
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
      inputs: Object.values(adjustments).filter(hasPayrollInput),
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

  async function process() {
    if (!window.confirm(`ยืนยันประมวลผลเงินเดือนรอบ ${month}?`)) return;
    setSaving(true);
    try {
      const response = await api.payroll.process(payload());
      applyPeriod(response.period);
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
    setAdjustments((current) => ({
      ...current,
      [selectedLine.employeeId]: {
        ...(current[selectedLine.employeeId] || blankAdjustment(selectedLine.employeeId)),
        [field]: Number(value || 0),
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
                  {specialPayKeys.map((key, index) => (
                    <label key={key}>
                      เงินพิเศษ {index + 1}
                      <input
                        type="number"
                        min="0"
                        step="0.01"
                        value={selectedAdjustment[key]}
                        onChange={(event) => updateAdjustment(key, event.target.value)}
                      />
                    </label>
                  ))}
                </div>
              </div>

              <div className="payroll-adjustment-group">
                <h3>รายการหักรายบุคคล</h3>
                <div className="form-grid">
                  <label>
                    วันลาไม่รับค่าจ้าง
                    <input type="number" min="0" step="0.25" value={selectedAdjustment.unpaidLeaveDays} onChange={(event) => updateAdjustment('unpaidLeaveDays', event.target.value)} />
                  </label>
                  <label>
                    หัก กยศ.
                    <input type="number" min="0" step="0.01" value={selectedAdjustment.studentLoanDeduction} onChange={(event) => updateAdjustment('studentLoanDeduction', event.target.value)} />
                  </label>
                  <label>
                    หักอายัดกรมบังคับคดี
                    <input type="number" min="0" step="0.01" value={selectedAdjustment.legalExecutionDeduction} onChange={(event) => updateAdjustment('legalExecutionDeduction', event.target.value)} />
                  </label>
                  <label>
                    หักอื่น ๆ หลังภาษี
                    <input type="number" min="0" step="0.01" value={selectedAdjustment.otherPostTaxDeductions} onChange={(event) => updateAdjustment('otherPostTaxDeductions', event.target.value)} />
                  </label>
                </div>
              </div>

              <div className="payroll-breakdown">
                <span><b>SSO</b>{formatMoney(selectedLine.socialSecurity)}</span>
                <span><b>ฐาน ปกส.</b>{formatMoney(selectedLine.ssoWageBase)}</span>
                <span><b>รายได้ทั้งปีประมาณการ</b>{formatMoney(selectedLine.projectedAnnualIncome)}</span>
                <span><b>ค่าลดหย่อนรวม</b>{formatMoney(selectedLine.taxAllowanceTotal)}</span>
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
