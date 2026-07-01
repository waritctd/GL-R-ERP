import { useEffect, useMemo, useState } from 'react';
import { api } from '../../api/index.js';
import { EmptyState } from '../../components/common/EmptyState.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { PageHeader } from '../../components/common/PageHeader.jsx';
import { StatCard } from '../../components/common/StatCard.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';

function bangkokDateParts(date = new Date()) {
  return Object.fromEntries(new Intl.DateTimeFormat('en-US', {
    timeZone: 'Asia/Bangkok',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).formatToParts(date).map((part) => [part.type, part.value]));
}

const todayIso = () => {
  const parts = bangkokDateParts();
  return `${parts.year}-${parts.month}-${parts.day}`;
};

const monthStartIso = () => {
  const parts = bangkokDateParts();
  return `${parts.year}-${parts.month}-01`;
};

function defaultForm(employeeId = '') {
  const date = todayIso();
  return {
    employeeId: employeeId || '',
    workDate: date,
    plannedStartAt: `${date}T18:00`,
    plannedEndAt: `${date}T20:00`,
    dayType: 'WORKDAY',
    reason: '',
  };
}

function statusInfo(status) {
  const map = {
    SUBMITTED: { label: 'รออนุมัติ', tone: 'warning' },
    APPROVED: { label: 'อนุมัติแล้ว', tone: 'success' },
    REJECTED: { label: 'ปฏิเสธแล้ว', tone: 'danger' },
    CANCELLED: { label: 'ยกเลิกแล้ว', tone: 'neutral' },
  };
  return map[status] ?? { label: status || '-', tone: 'neutral' };
}

function apiDateTime(value) {
  return value.length === 16 ? `${value}:00+07:00` : `${value}+07:00`;
}

function formatWorkDate(value) {
  if (!value) return '-';
  const date = new Date(`${value}T00:00:00+07:00`);
  if (Number.isNaN(date.getTime())) return '-';
  return new Intl.DateTimeFormat('th-TH', {
    dateStyle: 'medium',
    timeZone: 'Asia/Bangkok',
  }).format(date);
}

function formatDateTime(value) {
  if (!value) return '-';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return '-';
  return new Intl.DateTimeFormat('th-TH', {
    dateStyle: 'medium',
    timeStyle: 'short',
    hour12: false,
    timeZone: 'Asia/Bangkok',
  }).format(date);
}

function formatMinutes(value) {
  const minutes = Number(value || 0);
  const hours = Math.floor(minutes / 60);
  const rest = minutes % 60;
  if (hours <= 0) return `${rest} นาที`;
  if (rest === 0) return `${hours} ชม.`;
  return `${hours} ชม. ${rest} นาที`;
}

export function OvertimePage({ user, currentEmployee, showToast }) {
  const [filters, setFilters] = useState({
    from: monthStartIso(),
    to: todayIso(),
    employeeId: '',
    status: '',
  });
  const [form, setForm] = useState(() => defaultForm(currentEmployee?.id || user.employeeId || ''));
  const [requests, setRequests] = useState([]);
  const [employeeOptions, setEmployeeOptions] = useState([]);
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);

  const submitEmployeeOptions = useMemo(
    () => employeeOptions.filter((employee) => employee.self || employee.directReport || user.role === 'admin'),
    [employeeOptions, user.role],
  );
  const canSubmitForTeam = submitEmployeeOptions.some((employee) => employee.directReport);
  const hasMultipleEmployeeOptions = employeeOptions.length > 1;
  const hasMultipleSubmitOptions = submitEmployeeOptions.length > 1;

  const totals = useMemo(() => {
    const submitted = requests.filter((request) => request.status === 'SUBMITTED').length;
    const approved = requests.filter((request) => request.status === 'APPROVED');
    const payableMinutes = approved.reduce((sum, request) => sum + Number(request.payableMinutes || 0), 0);
    return { submitted, approved: approved.length, payableMinutes };
  }, [requests]);

  async function loadRequests(nextFilters = filters) {
    setLoading(true);
    try {
      const response = await api.overtime.list({
        from: nextFilters.from,
        to: nextFilters.to,
        status: nextFilters.status,
        ...(nextFilters.employeeId ? { employeeId: nextFilters.employeeId } : {}),
      });
      setRequests(response.requests || []);
    } catch (error) {
      showToast('error', error.message || 'โหลดข้อมูล OT ไม่สำเร็จ');
    } finally {
      setLoading(false);
    }
  }

  async function loadEmployeeOptions() {
    try {
      const response = await api.overtime.employees();
      const options = response.employees || [];
      const submitOptions = options.filter((employee) => employee.self || employee.directReport || user.role === 'admin');
      setEmployeeOptions(options);
      setForm((current) => ({
        ...current,
        employeeId: current.employeeId || submitOptions.find((employee) => employee.self)?.employeeId || submitOptions[0]?.employeeId || '',
      }));
    } catch (error) {
      showToast('error', error.message || 'โหลดรายชื่อพนักงานสำหรับ OT ไม่สำเร็จ');
    }
  }

  useEffect(() => {
    loadEmployeeOptions();
    loadRequests();
  }, []);

  function updateFilter(field, value) {
    setFilters((current) => ({ ...current, [field]: value }));
  }

  function updateForm(field, value) {
    setForm((current) => {
      if (field !== 'workDate') return { ...current, [field]: value };
      const startTime = current.plannedStartAt.slice(11) || '18:00';
      const endTime = current.plannedEndAt.slice(11) || '20:00';
      return {
        ...current,
        workDate: value,
        plannedStartAt: `${value}T${startTime}`,
        plannedEndAt: `${value}T${endTime}`,
      };
    });
  }

  async function submitFilters(event) {
    event.preventDefault();
    await loadRequests(filters);
  }

  async function submitOvertime(event) {
    event.preventDefault();
    setSaving(true);
    try {
      await api.overtime.create({
        employeeId: form.employeeId ? Number(form.employeeId) : null,
        workDate: form.workDate,
        plannedStartAt: apiDateTime(form.plannedStartAt),
        plannedEndAt: apiDateTime(form.plannedEndAt),
        dayType: form.dayType,
        reason: form.reason.trim(),
      });
      setForm(defaultForm(currentEmployee?.id || user.employeeId || ''));
      showToast('success', 'ส่งคำขอ OT แล้ว');
      await loadRequests(filters);
    } catch (error) {
      showToast('error', error.message || 'ส่งคำขอ OT ไม่สำเร็จ');
    } finally {
      setSaving(false);
    }
  }

  async function approve(id) {
    setSaving(true);
    try {
      await api.overtime.approve(id, {});
      showToast('success', 'อนุมัติ OT แล้ว');
      await loadRequests(filters);
    } catch (error) {
      showToast('error', error.message || 'อนุมัติ OT ไม่สำเร็จ');
    } finally {
      setSaving(false);
    }
  }

  async function reject(id) {
    const reviewerNote = window.prompt('เหตุผลการปฏิเสธ');
    if (!reviewerNote?.trim()) return;
    setSaving(true);
    try {
      await api.overtime.reject(id, { reviewerNote: reviewerNote.trim() });
      showToast('success', 'ปฏิเสธคำขอ OT แล้ว');
      await loadRequests(filters);
    } catch (error) {
      showToast('error', error.message || 'ปฏิเสธ OT ไม่สำเร็จ');
    } finally {
      setSaving(false);
    }
  }

  // A ฝ่าย manager's list is already scoped server-side to their division, so any request
  // they see (other than their own) is one they may review; direct reports-to managers too.
  function managesRequest(request) {
    const directManager = request.managerEmployeeId
      && Number(request.managerEmployeeId) === Number(user.employeeId);
    const divisionManager = user.manager
      && Number(request.employeeId) !== Number(user.employeeId);
    return Boolean(directManager || divisionManager);
  }

  function canReviewRequest(request) {
    return request.status === 'SUBMITTED' && managesRequest(request);
  }

  function canManagerCancel(request) {
    return ['SUBMITTED', 'APPROVED'].includes(request.status) && managesRequest(request);
  }

  async function cancel(id) {
    const request = requests.find((item) => item.id === id);
    const reviewerNote = request && canManagerCancel(request) ? window.prompt('หมายเหตุการยกเลิก (ถ้ามี)') : '';
    if (reviewerNote === null) return;
    setSaving(true);
    try {
      await api.overtime.cancel(id, { reviewerNote: reviewerNote?.trim() || null });
      showToast('success', 'ยกเลิกคำขอ OT แล้ว');
      await loadRequests(filters);
    } catch (error) {
      showToast('error', error.message || 'ยกเลิก OT ไม่สำเร็จ');
    } finally {
      setSaving(false);
    }
  }

  return (
    <div className="page-stack">
      <PageHeader
        title="จัดการล่วงเวลา"
        subtitle={canSubmitForTeam ? 'ยื่นคำขอแทนทีมและอนุมัติจากเวลาสแกนจริง' : 'ยื่นคำขอ OT และดูประวัติของคุณ'}
        actions={(
          <button type="button" className="secondary-button" onClick={() => loadRequests(filters)} disabled={loading}>
            <Icon name="refresh" />
            รีเฟรช
          </button>
        )}
      />

      <section className="stat-grid">
        <StatCard label="คำขอทั้งหมด" value={requests.length} helper="ในช่วงที่เลือก" icon="clock" tone="indigo" />
        <StatCard label="รออนุมัติ" value={totals.submitted} helper="Submitted" icon="clipboard" tone="amber" />
        <StatCard label="อนุมัติแล้ว" value={totals.approved} helper="Approved" icon="check" tone="teal" />
        <StatCard label="ชั่วโมงจ่ายได้" value={formatMinutes(totals.payableMinutes)} helper="Approved payable" icon="calendar" tone="blue" />
      </section>

      <form className="filter-bar" onSubmit={submitFilters}>
        <label>
          จากวันที่
          <input type="date" value={filters.from} onChange={(event) => updateFilter('from', event.target.value)} />
        </label>
        <label>
          ถึงวันที่
          <input type="date" value={filters.to} onChange={(event) => updateFilter('to', event.target.value)} />
        </label>
        <label>
          สถานะ
          <select value={filters.status} onChange={(event) => updateFilter('status', event.target.value)}>
            <option value="">ทุกสถานะ</option>
            <option value="SUBMITTED">รออนุมัติ</option>
            <option value="APPROVED">อนุมัติแล้ว</option>
            <option value="REJECTED">ปฏิเสธแล้ว</option>
            <option value="CANCELLED">ยกเลิกแล้ว</option>
          </select>
        </label>
        {hasMultipleEmployeeOptions ? (
          <label>
            พนักงาน
            <select value={filters.employeeId} onChange={(event) => updateFilter('employeeId', event.target.value)}>
              <option value="">ทุกคน</option>
              {employeeOptions.map((employee) => (
                <option key={employee.employeeId} value={employee.employeeId}>{employee.employeeName} · {employee.employeeCode}</option>
              ))}
            </select>
          </label>
        ) : null}
        <button type="submit" className="primary-button" disabled={loading}>
          <Icon name="search" />
          ค้นหา
        </button>
      </form>

      <section className="panel">
        <div className="panel-header">
          <h2>ยื่นคำขอ OT</h2>
        </div>
        <form className="form-grid" onSubmit={submitOvertime}>
          {hasMultipleSubmitOptions ? (
            <label>
              พนักงาน
              <select value={form.employeeId} onChange={(event) => updateForm('employeeId', event.target.value)} required>
                <option value="">เลือกพนักงาน</option>
                {submitEmployeeOptions.map((employee) => (
                  <option key={employee.employeeId} value={employee.employeeId}>
                    {employee.employeeName} · {employee.employeeCode}{employee.directReport ? ' · ลูกทีม' : ''}
                  </option>
                ))}
              </select>
            </label>
          ) : (
            <label>
              พนักงาน
              <input value={currentEmployee?.nameTh || user.name || '-'} disabled />
            </label>
          )}
          <label>
            วันที่ทำ OT
            <input type="date" value={form.workDate} onChange={(event) => updateForm('workDate', event.target.value)} required />
          </label>
          <label>
            ประเภท OT
            <select value={form.dayType} onChange={(event) => updateForm('dayType', event.target.value)}>
              <option value="WORKDAY">วันทำงานปกติ · 1.5x</option>
              <option value="HOLIDAY">วันหยุด/วันหยุดนักขัตฤกษ์ · 3x</option>
            </select>
          </label>
          <label>
            เริ่ม
            <input type="datetime-local" value={form.plannedStartAt} onChange={(event) => updateForm('plannedStartAt', event.target.value)} required />
          </label>
          <label>
            สิ้นสุด
            <input type="datetime-local" value={form.plannedEndAt} onChange={(event) => updateForm('plannedEndAt', event.target.value)} required />
          </label>
          <label className="span-2">
            เหตุผลความจำเป็น
            <textarea rows={3} value={form.reason} onChange={(event) => updateForm('reason', event.target.value)} required />
          </label>
          <div className="span-2 row-actions">
            <button type="submit" className="primary-button" disabled={saving}>
              <Icon name="plus" />
              ส่งคำขอ
            </button>
          </div>
        </form>
      </section>

      <section className="table-panel">
        <div className="overtime-table table-head">
          <span>วันที่ / พนักงาน</span>
          <span>แผน OT</span>
          <span>เหตุผล</span>
          <span>เวลาจริง / จ่ายได้</span>
          <span>สถานะ</span>
          <span />
        </div>
        {loading ? (
          <EmptyState icon="clock" title="กำลังโหลดคำขอ OT" />
        ) : requests.length === 0 ? (
          <EmptyState icon="clock" title="ยังไม่มีคำขอ OT" description="ลองเปลี่ยนช่วงวันที่หรือยื่นคำขอใหม่" />
        ) : requests.map((request) => {
          const status = statusInfo(request.status);
          const reviewable = canReviewRequest(request);
          const canCancel = request.status === 'SUBMITTED' && Number(request.employeeId) === Number(user.employeeId);
          const managerCancellable = canManagerCancel(request);
          return (
            <div className="overtime-table table-row" key={request.id}>
              <span>
                <strong>{formatWorkDate(request.workDate)}</strong>
                <small>{request.employeeName || request.employeeCode || request.employeeId}</small>
              </span>
              <span>
                <strong>{formatDateTime(request.plannedStartAt)}</strong>
                <small>
                  {formatDateTime(request.plannedEndAt)} · {formatMinutes(request.plannedMinutes)} · {request.dayType === 'HOLIDAY' ? '3x' : '1.5x'}
                </small>
              </span>
              <span>
                <strong>{request.reason}</strong>
                <small>{request.reviewerNote || request.calculationNote || '-'}</small>
              </span>
              <span>
                <strong>{formatMinutes(request.actualMinutes)}</strong>
                <small>จ่ายได้ {formatMinutes(request.payableMinutes)}</small>
              </span>
              <StatusBadge tone={status.tone}>{status.label}</StatusBadge>
              <span className="row-actions">
                {reviewable ? (
                  <>
                    <button type="button" className="icon-button" title="อนุมัติ" disabled={saving} onClick={() => approve(request.id)}>
                      <Icon name="check" size={14} />
                    </button>
                    <button type="button" className="icon-button" title="ปฏิเสธ" disabled={saving} onClick={() => reject(request.id)}>
                      <Icon name="close" size={14} />
                    </button>
                  </>
                ) : null}
                {(canCancel || managerCancellable) ? (
                  <button type="button" className="icon-button" title="ยกเลิก" disabled={saving} onClick={() => cancel(request.id)}>
                    <Icon name="close" size={14} />
                  </button>
                ) : null}
              </span>
            </div>
          );
        })}
      </section>
    </div>
  );
}
