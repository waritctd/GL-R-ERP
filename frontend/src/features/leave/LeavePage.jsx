import { useEffect, useMemo, useState } from 'react';
import { api } from '../../api/index.js';
import { hasPermission } from '../../app/permissions.js';
import { ConfirmDialog } from '../../components/common/ConfirmDialog.jsx';
import { EmptyState } from '../../components/common/EmptyState.jsx';
import { FormField, fieldErrorId } from '../../components/common/FormField.jsx';
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

function defaultForm(employeeId = '', leaveTypeCode = 'VACATION') {
  const date = todayIso();
  return {
    employeeId: employeeId || '',
    leaveTypeCode,
    startDate: date,
    endDate: date,
    reason: '',
    attachmentName: '',
    attachmentUrl: '',
  };
}

function statusInfo(status) {
  const map = {
    SUBMITTED: { label: 'รออนุมัติ', tone: 'warning' },
    APPROVED: { label: 'อนุมัติแล้ว', tone: 'success' },
    REJECTED: { label: 'ปฏิเสธแล้ว', tone: 'danger' },
    CANCELLED: { label: 'ยกเลิกแล้ว', tone: 'neutral' },
    AUTO_REJECTED: { label: 'โควตาไม่พอ', tone: 'danger' },
  };
  return map[status] ?? { label: status || '-', tone: 'neutral' };
}

function formatDate(value) {
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

function formatDateRange(start, end) {
  return start === end ? formatDate(start) : `${formatDate(start)} - ${formatDate(end)}`;
}

function formatDays(value) {
  const days = Number(value || 0);
  return `${new Intl.NumberFormat('th-TH', { maximumFractionDigits: 2 }).format(days)} วัน`;
}

export function LeavePage({ user, currentEmployee, showToast }) {
  const [filters, setFilters] = useState({
    from: monthStartIso(),
    to: todayIso(),
    employeeId: '',
    status: '',
  });
  const [form, setForm] = useState(() => defaultForm(currentEmployee?.id || user.employeeId || ''));
  const [requests, setRequests] = useState([]);
  const [employeeOptions, setEmployeeOptions] = useState([]);
  const [leaveTypes, setLeaveTypes] = useState([]);
  const [balances, setBalances] = useState([]);
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [confirmState, setConfirmState] = useState(null);

  const startDateInPast = Boolean(form.startDate && form.startDate < todayIso());

  const canReviewAll = hasPermission(user.role, 'canReviewLeave');
  const submitEmployeeOptions = useMemo(
    () => employeeOptions.filter((employee) => employee.self || employee.directReport || canReviewAll),
    [employeeOptions, canReviewAll],
  );
  const canSubmitForTeam = submitEmployeeOptions.some((employee) => employee.directReport) || canReviewAll;
  const hasMultipleEmployeeOptions = employeeOptions.length > 1;
  const hasMultipleSubmitOptions = submitEmployeeOptions.length > 1;

  const totals = useMemo(() => {
    const submitted = requests.filter((request) => request.status === 'SUBMITTED').length;
    const approved = requests.filter((request) => request.status === 'APPROVED');
    const approvedDays = approved.reduce((sum, request) => sum + Number(request.totalDays || 0), 0);
    const remainingDays = balances.reduce((sum, balance) => sum + Number(balance.remainingDays || 0), 0);
    return { submitted, approved: approved.length, approvedDays, remainingDays };
  }, [requests, balances]);

  const activeCalendarItems = useMemo(
    () => requests
      .filter((request) => ['SUBMITTED', 'APPROVED'].includes(request.status))
      .slice()
      .sort((first, second) => first.startDate.localeCompare(second.startDate))
      .slice(0, 8),
    [requests],
  );

  async function loadRequests(nextFilters = filters) {
    setLoading(true);
    try {
      const response = await api.leave.list({
        from: nextFilters.from,
        to: nextFilters.to,
        status: nextFilters.status,
        ...(nextFilters.employeeId ? { employeeId: nextFilters.employeeId } : {}),
      });
      setRequests(response.requests || []);
    } catch (error) {
      showToast('error', error.message || 'โหลดข้อมูลวันลาไม่สำเร็จ');
    } finally {
      setLoading(false);
    }
  }

  async function loadBalances(employeeId = form.employeeId) {
    try {
      const response = await api.leave.balances({
        ...(employeeId ? { employeeId } : {}),
        year: Number((form.startDate || todayIso()).slice(0, 4)),
      });
      setBalances(response.balances || []);
    } catch (error) {
      showToast('error', error.message || 'โหลดโควตาวันลาไม่สำเร็จ');
    }
  }

  async function loadReferenceData() {
    try {
      const [employeesResponse, typesResponse] = await Promise.all([
        api.leave.employees(),
        api.leave.types(),
      ]);
      const options = employeesResponse.employees || [];
      const types = typesResponse.leaveTypes || [];
      const submitOptions = options.filter((employee) => employee.self || employee.directReport || canReviewAll);
      setEmployeeOptions(options);
      setLeaveTypes(types);
      setForm((current) => ({
        ...current,
        employeeId: current.employeeId || submitOptions.find((employee) => employee.self)?.employeeId || submitOptions[0]?.employeeId || '',
        leaveTypeCode: current.leaveTypeCode || types[0]?.code || 'VACATION',
      }));
    } catch (error) {
      showToast('error', error.message || 'โหลดข้อมูลตั้งต้นวันลาไม่สำเร็จ');
    }
  }

  useEffect(() => {
    loadReferenceData();
    loadRequests();
  }, []);

  useEffect(() => {
    if (form.employeeId) loadBalances(form.employeeId);
  }, [form.employeeId, form.startDate]);

  function updateFilter(field, value) {
    setFilters((current) => ({ ...current, [field]: value }));
  }

  function updateForm(field, value) {
    setForm((current) => {
      if (field !== 'startDate') return { ...current, [field]: value };
      return {
        ...current,
        startDate: value,
        endDate: current.endDate < value ? value : current.endDate,
      };
    });
  }

  async function submitFilters(event) {
    event.preventDefault();
    await loadRequests(filters);
  }

  async function submitLeave(event) {
    event.preventDefault();
    if (startDateInPast) return;
    setSaving(true);
    try {
      const response = await api.leave.create({
        employeeId: form.employeeId ? Number(form.employeeId) : null,
        leaveTypeCode: form.leaveTypeCode,
        startDate: form.startDate,
        endDate: form.endDate,
        reason: form.reason.trim(),
        attachmentName: form.attachmentName.trim() || null,
        attachmentUrl: form.attachmentUrl.trim() || null,
      });
      const created = response.request;
      const nextEmployeeId = form.employeeId || currentEmployee?.id || user.employeeId || '';
      setForm(defaultForm(nextEmployeeId, form.leaveTypeCode));
      if (created?.status === 'AUTO_REJECTED') {
        showToast('error', created.systemNote || 'โควตาวันลาไม่เพียงพอ');
      } else {
        showToast('success', 'ส่งคำขอลาแล้ว');
      }
      await Promise.all([loadRequests(filters), loadBalances(nextEmployeeId)]);
    } catch (error) {
      showToast('error', error.message || 'ส่งคำขอลาไม่สำเร็จ');
    } finally {
      setSaving(false);
    }
  }

  async function approve(id) {
    setSaving(true);
    try {
      await api.leave.approve(id, {});
      showToast('success', 'อนุมัติวันลาแล้ว');
      await Promise.all([loadRequests(filters), loadBalances(form.employeeId)]);
    } catch (error) {
      showToast('error', error.message || 'อนุมัติวันลาไม่สำเร็จ');
    } finally {
      setSaving(false);
    }
  }

  function reject(id) {
    setConfirmState({ kind: 'reject', id });
  }

  async function confirmReject(reviewerNote) {
    if (!reviewerNote?.trim()) return;
    setSaving(true);
    try {
      await api.leave.reject(confirmState.id, { reviewerNote: reviewerNote.trim() });
      showToast('success', 'ปฏิเสธคำขอลาแล้ว');
      setConfirmState(null);
      await Promise.all([loadRequests(filters), loadBalances(form.employeeId)]);
    } catch (error) {
      showToast('error', error.message || 'ปฏิเสธวันลาไม่สำเร็จ');
    } finally {
      setSaving(false);
    }
  }

  function cancel(id) {
    const request = requests.find((item) => item.id === id);
    if (request && canManagerCancel(request)) {
      setConfirmState({ kind: 'cancel', id });
      return;
    }
    doCancel(id, '');
  }

  async function doCancel(id, reviewerNote) {
    setSaving(true);
    try {
      await api.leave.cancel(id, { reviewerNote: reviewerNote?.trim() || null });
      showToast('success', 'ยกเลิกคำขอลาแล้ว');
      setConfirmState(null);
      await Promise.all([loadRequests(filters), loadBalances(form.employeeId)]);
    } catch (error) {
      showToast('error', error.message || 'ยกเลิกวันลาไม่สำเร็จ');
    } finally {
      setSaving(false);
    }
  }

  function canReviewRequest(request) {
    return request.status === 'SUBMITTED'
      && (canReviewAll || (request.managerEmployeeId && Number(request.managerEmployeeId) === Number(user.employeeId)));
  }

  function canManagerCancel(request) {
    return ['SUBMITTED', 'APPROVED'].includes(request.status)
      && (canReviewAll || (request.managerEmployeeId && Number(request.managerEmployeeId) === Number(user.employeeId)));
  }

  return (
    <div className="page-stack">
      <PageHeader
        title="จัดการการลา"
        subtitle={canSubmitForTeam ? 'ยื่นคำขอแทนทีม ตรวจโควตา และอนุมัติวันลา' : 'ยื่นคำขอลาและดูโควตาของคุณ'}
        actions={(
          <button type="button" className="secondary-button" onClick={() => loadRequests(filters)} disabled={loading}>
            <Icon name="refresh" />
            รีเฟรช
          </button>
        )}
      />

      <section className="stat-grid">
        <StatCard label="คำขอทั้งหมด" value={requests.length} helper="ในช่วงที่เลือก" icon="clipboard" tone="indigo" />
        <StatCard label="รออนุมัติ" value={totals.submitted} helper="Submitted" icon="clock" tone="amber" />
        <StatCard label="อนุมัติแล้ว" value={totals.approved} helper={formatDays(totals.approvedDays)} icon="check" tone="teal" />
        <StatCard label="โควตาคงเหลือ" value={formatDays(totals.remainingDays)} helper="รวมประเภทที่เลือกได้" icon="calendar" tone="blue" />
      </section>

      <section className="panel">
        <div className="panel-header">
          <h2>โควตาวันลา</h2>
        </div>
        <div className="leave-balance-grid">
          {balances.length === 0 ? (
            <EmptyState icon="calendar" title="ยังไม่มีข้อมูลโควตา" />
          ) : balances.map((balance) => (
            <div className="leave-balance-card" key={balance.leaveTypeCode}>
              <span>{balance.leaveTypeNameTh || balance.leaveTypeCode}</span>
              <strong>{formatDays(balance.remainingDays)}</strong>
              <small>ใช้แล้ว {formatDays(balance.approvedDays)} · รออนุมัติ {formatDays(balance.pendingDays)} · สิทธิ์ {formatDays(balance.annualQuotaDays)}</small>
            </div>
          ))}
        </div>
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
            <option value="AUTO_REJECTED">โควตาไม่พอ</option>
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
          <h2>ยื่นคำขอลา</h2>
        </div>
        <form className="form-grid" onSubmit={submitLeave}>
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
            ประเภทการลา
            <select value={form.leaveTypeCode} onChange={(event) => updateForm('leaveTypeCode', event.target.value)} required>
              {leaveTypes.map((type) => (
                <option key={type.code} value={type.code}>{type.nameTh || type.nameEn}</option>
              ))}
            </select>
          </label>
          <FormField
            label="วันที่เริ่ม"
            htmlFor="leave-start-date"
            error={startDateInPast ? 'วันที่เริ่มลาต้องไม่ก่อนวันนี้' : undefined}
          >
            <input
              id="leave-start-date"
              type="date"
              value={form.startDate}
              onChange={(event) => updateForm('startDate', event.target.value)}
              className={startDateInPast ? 'is-invalid' : ''}
              aria-invalid={startDateInPast}
              aria-describedby={startDateInPast ? fieldErrorId('leave-start-date') : undefined}
              required
            />
          </FormField>
          <label>
            วันที่สิ้นสุด
            <input type="date" value={form.endDate} onChange={(event) => updateForm('endDate', event.target.value)} min={form.startDate} required />
          </label>
          <label>
            ชื่อเอกสาร
            <input value={form.attachmentName} onChange={(event) => updateForm('attachmentName', event.target.value)} placeholder="Medical certificate.pdf" />
          </label>
          <label>
            ลิงก์เอกสาร
            <input value={form.attachmentUrl} onChange={(event) => updateForm('attachmentUrl', event.target.value)} placeholder="https://..." />
          </label>
          <label className="span-2">
            เหตุผลการลา
            <textarea rows={3} value={form.reason} onChange={(event) => updateForm('reason', event.target.value)} required />
          </label>
          <div className="span-2 row-actions">
            <button type="submit" className="primary-button" disabled={saving || startDateInPast}>
              <Icon name="plus" />
              ส่งคำขอ
            </button>
          </div>
        </form>
      </section>

      <section className="panel">
        <div className="panel-header">
          <h2>ปฏิทินวันลา</h2>
        </div>
        <div className="leave-calendar-list">
          {activeCalendarItems.length === 0 ? (
            <EmptyState icon="calendar" title="ยังไม่มีรายการวันลาในช่วงนี้" />
          ) : activeCalendarItems.map((request) => {
            const status = statusInfo(request.status);
            return (
              <div className="leave-calendar-item" key={request.id}>
                <span>
                  <strong>{formatDateRange(request.startDate, request.endDate)}</strong>
                  <small>{request.employeeName || request.employeeCode} · {request.leaveTypeNameTh}</small>
                </span>
                <StatusBadge tone={status.tone}>{status.label}</StatusBadge>
              </div>
            );
          })}
        </div>
      </section>

      <section className="table-panel">
        <div className="leave-table table-head">
          <span>ช่วงลา / พนักงาน</span>
          <span>ประเภท / จำนวนวัน</span>
          <span>เหตุผล / เอกสาร</span>
          <span>สถานะ</span>
          <span>อนุมัติ / หมายเหตุ</span>
          <span />
        </div>
        {loading ? (
          <EmptyState icon="calendar" title="กำลังโหลดคำขอลา" />
        ) : requests.length === 0 ? (
          <EmptyState icon="calendar" title="ยังไม่มีคำขอลา" description="ลองเปลี่ยนช่วงวันที่หรือยื่นคำขอใหม่" />
        ) : requests.map((request) => {
          const status = statusInfo(request.status);
          const reviewable = canReviewRequest(request);
          const canCancel = request.status === 'SUBMITTED' && Number(request.employeeId) === Number(user.employeeId);
          const managerCancellable = canManagerCancel(request);
          return (
            <div className="leave-table table-row" key={request.id}>
              <span>
                <strong>{formatDateRange(request.startDate, request.endDate)}</strong>
                <small>{request.employeeName || request.employeeCode || request.employeeId}</small>
              </span>
              <span>
                <strong>{request.leaveTypeNameTh || request.leaveTypeCode}</strong>
                <small>{formatDays(request.totalDays)} · เหลือ {formatDays(request.quotaRemainingAfter)}</small>
              </span>
              <span>
                <strong>{request.reason}</strong>
                <small>{request.attachmentName || request.attachmentUrl || '-'}</small>
              </span>
              <StatusBadge tone={status.tone}>{status.label}</StatusBadge>
              <span>
                <strong>{request.reviewedByName || '-'}</strong>
                <small>{request.reviewerNote || request.systemNote || formatDateTime(request.requestedAt)}</small>
              </span>
              <span className="row-actions">
                {reviewable ? (
                  <>
                    <button type="button" className="icon-button" title="อนุมัติ" aria-label="อนุมัติ" disabled={saving} onClick={() => approve(request.id)}>
                      <Icon name="check" size={14} />
                    </button>
                    <button type="button" className="icon-button" title="ปฏิเสธ" aria-label="ปฏิเสธ" disabled={saving} onClick={() => reject(request.id)}>
                      <Icon name="close" size={14} />
                    </button>
                  </>
                ) : null}
                {(canCancel || managerCancellable) ? (
                  <button type="button" className="icon-button" title="ยกเลิก" aria-label="ยกเลิก" disabled={saving} onClick={() => cancel(request.id)}>
                    <Icon name="close" size={14} />
                  </button>
                ) : null}
              </span>
            </div>
          );
        })}
      </section>

      <ConfirmDialog
        open={confirmState?.kind === 'reject'}
        title="ปฏิเสธคำขอลา"
        message="ยืนยันการปฏิเสธคำขอลานี้?"
        confirmLabel="ปฏิเสธคำขอ"
        tone="danger"
        busy={saving}
        requireReason
        reasonLabel="เหตุผลการปฏิเสธ"
        onConfirm={confirmReject}
        onCancel={() => setConfirmState(null)}
      />
      <ConfirmDialog
        open={confirmState?.kind === 'cancel'}
        title="ยกเลิกคำขอลา"
        message="ยืนยันการยกเลิกคำขอลานี้?"
        confirmLabel="ยกเลิกคำขอ"
        tone="danger"
        busy={saving}
        requireReason
        optionalReason
        reasonLabel="หมายเหตุการยกเลิก (ถ้ามี)"
        onConfirm={(reason) => doCancel(confirmState.id, reason)}
        onCancel={() => setConfirmState(null)}
      />
    </div>
  );
}
