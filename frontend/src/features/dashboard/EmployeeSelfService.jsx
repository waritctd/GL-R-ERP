import { useMemo } from 'react';
import { useMutation, useQuery } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { Button } from '../../components/common/Button.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { StatCard } from '../../components/common/StatCard.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { PageHeader } from '../../components/common/PageHeader.jsx';
import { PageStack, Panel } from '../../components/common/Layout.jsx';
import {
  bangkokTodayIso,
  formatBangkokTime,
  formatShortDate,
  formatThaiDate,
  leaveStatusLabel,
  overtimeStatusLabel,
  requestStatus,
} from '../../utils/format.js';

// Three-tile grid: no shared primitive has this exact ratio (StatGrid is
// 4-col). Mirrors LeavePage's LEAVE_BALANCE_GRID pattern (3-col, 1-col
// ≤720px) — see that file for the precedent.
const TILE_GRID = 'grid grid-cols-3 gap-3 max-[1040px]:grid-cols-1 max-[720px]:grid-cols-1';

// Terminal negative states: a stepper implies "still progressing", which is
// wrong once a request has been stopped. Rejected/cancelled rows show only
// the status badge, never the chain.
const TERMINAL_NEGATIVE = new Set(['REJECTED', 'CANCELLED', 'AUTO_REJECTED', 'rejected']);

function downloadBlob(blob, filename) {
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = filename;
  anchor.click();
  URL.revokeObjectURL(url);
}

// ส่งแล้ว → หัวหน้าฝ่าย → CEO for OT (mirrors OvertimePanel's
// SUBMITTED -> MANAGER_APPROVED -> APPROVED chain); ส่งแล้ว → อนุมัติ for leave
// (single reviewer, no separate CEO step — LeavePage has no ceo-stage fields);
// ส่งแล้ว → HR อนุมัติ for profile-change requests (ProfileRequestsPage is
// hr-only, single step). Never invents an approver name the API didn't return.
function chainForRequest(kind, request) {
  if (kind === 'ot') {
    return [
      { label: 'ส่งแล้ว', done: true },
      { label: 'หัวหน้าฝ่าย', done: ['MANAGER_APPROVED', 'APPROVED'].includes(request.status), name: request.managerApprovedByName, at: request.managerApprovedAt },
      { label: 'CEO', done: request.status === 'APPROVED', name: request.ceoApprovedByName, at: request.ceoApprovedAt },
    ];
  }
  if (kind === 'leave') {
    return [
      { label: 'ส่งแล้ว', done: true },
      { label: 'อนุมัติ', done: request.status === 'APPROVED', name: request.reviewedByName, at: request.reviewedAt },
    ];
  }
  return [
    { label: 'ส่งแล้ว', done: true },
    { label: 'HR อนุมัติ', done: request.status === 'approved', at: request.reviewedAt },
  ];
}

function ApprovalChain({ steps }) {
  return (
    <span className="flex flex-wrap items-center gap-1 text-2xs text-text-muted">
      {steps.map((step, index) => (
        <span key={step.label} className="flex items-center gap-1">
          {index > 0 ? <Icon name="chevronRight" size={11} /> : null}
          <span className={step.done ? 'font-bold text-text' : ''}>
            {step.label}
            {step.done && step.name ? ` · ${step.name}` : ''}
          </span>
        </span>
      ))}
    </span>
  );
}

function MyRequestRow({ row }) {
  const showChain = !TERMINAL_NEGATIVE.has(row.status);
  return (
    <div className="request-feed-item compact">
      <span>
        <strong>{row.title}</strong>
        <small>{row.dateLabel}</small>
        {showChain ? <ApprovalChain steps={row.chain} /> : null}
      </span>
      <StatusBadge tone={row.statusInfo.tone}>{row.statusInfo.label}</StatusBadge>
    </div>
  );
}

export function EmployeeSelfService({ user, employee, profileRequests = [], dashboardSummary, showToast }) {
  const navigate = useNavigate();

  // Omitting from/to gives the current calendar month (mirrors
  // AttendancePage.monthBounds()'s self-view range and mockApi's own default
  // when params are omitted), so today's row and the month tile come from one
  // fetch instead of two.
  const attendanceQuery = useQuery({
    queryKey: queryKeys.attendanceDaily(),
    queryFn: () => api.attendance.daily({}).then((response) => response.days || []),
  });
  const attendanceDays = attendanceQuery.data ?? [];
  const todayIso = bangkokTodayIso();
  const today = attendanceDays.find((day) => day.work_date === todayIso) ?? null;
  const daysPresent = attendanceDays.filter((day) => day.check_in).length;
  const lateDays = attendanceDays.filter((day) => Number(day.late_minutes) > 0).length;

  // Own balances only — omitting employeeId defaults to the caller
  // (api.leave.balances mirrors this: `params.employeeId ? ... : user.employeeId`).
  const leaveBalancesQuery = useQuery({
    queryKey: queryKeys.leaveBalances(user?.employeeId, new Date().getFullYear()),
    queryFn: () => api.leave.balances({}).then((response) => response.balances || []),
  });
  const leaveBalances = leaveBalancesQuery.data ?? [];
  const totalRemainingLeave = leaveBalances.reduce((sum, balance) => sum + Number(balance.remainingDays || 0), 0);

  // No from/to filter — both endpoints scope to the caller automatically for a
  // plain employee (leave.list/overtime.list: `list.filter(item =>
  // item.employeeId === user.employeeId || canReview...)`), so this returns
  // every own request regardless of date, same shape OvertimePanel/LeavePage
  // already rely on for their own-row visibility.
  const ownLeaveQuery = useQuery({
    queryKey: queryKeys.leaveRequests({}),
    queryFn: () => api.leave.list({}).then((response) => response.requests || []),
  });
  const ownOvertimeQuery = useQuery({
    queryKey: queryKeys.overtimeRequests({}),
    queryFn: () => api.overtime.list({}).then((response) => response.requests || []),
  });

  const myRequests = useMemo(() => {
    const leaveRows = (ownLeaveQuery.data ?? []).map((request) => ({
      id: `leave-${request.id}`,
      // leaveTypeNameTh already carries the "ลา" prefix (ลาพักร้อน/ลากิจ/ลาป่วย)
      // — prepending it again read as a stutter ("ลาลาพักร้อน").
      title: request.leaveTypeNameTh || `ลา${request.leaveTypeCode}`,
      dateLabel: formatShortDate(request.startDate),
      requestedAt: request.requestedAt || request.startDate,
      status: request.status,
      statusInfo: leaveStatusLabel(request.status),
      chain: chainForRequest('leave', request),
    }));
    const otRows = (ownOvertimeQuery.data ?? []).map((request) => ({
      id: `ot-${request.id}`,
      title: `OT ${formatShortDate(request.workDate)}`,
      dateLabel: formatShortDate(request.workDate),
      requestedAt: request.requestedAt || request.workDate,
      status: request.status,
      statusInfo: overtimeStatusLabel(request.status),
      chain: chainForRequest('ot', request),
    }));
    const profileRows = profileRequests.map((request) => ({
      id: `profile-${request.id}`,
      title: `ขอแก้ไข${request.fieldLabel}`,
      dateLabel: formatShortDate(request.requestedAt),
      requestedAt: request.requestedAt,
      status: request.status,
      statusInfo: requestStatus(request.status),
      chain: chainForRequest('profile', request),
    }));
    return [...leaveRows, ...otRows, ...profileRows]
      .sort((a, b) => String(b.requestedAt || '').localeCompare(String(a.requestedAt || '')))
      .slice(0, 8);
  }, [ownLeaveQuery.data, ownOvertimeQuery.data, profileRequests]);

  const latestPayrollPeriodId = dashboardSummary?.latestPayrollPeriodId;
  const ownPayslipMutation = useMutation({
    mutationFn: (periodId) => api.payroll.downloadOwnPayslip(periodId),
    onSuccess: (blob) => {
      downloadBlob(blob, `glr-my-payslip-${latestPayrollPeriodId}.pdf`);
      showToast?.('success', 'ดาวน์โหลดสลิปเงินเดือนแล้ว');
    },
    onError: (error) => showToast?.('error', error.message || 'ดาวน์โหลดสลิปเงินเดือนไม่สำเร็จ'),
  });

  function downloadMyPayslip() {
    if (!latestPayrollPeriodId) return;
    ownPayslipMutation.mutate(latestPayrollPeriodId);
  }

  const hasCheckedIn = Boolean(today?.check_in);
  const firstName = employee?.nickName || employee?.nameTh || user?.name || '';

  return (
    <PageStack>
      <PageHeader
        title={`สวัสดี คุณ${firstName}`}
        subtitle={`${formatThaiDate(new Date())} · เวลาทำงานและคำขอของคุณ`}
      />

      {/* Clock card: no self-punch endpoint exists yet (attendance is derived
          from badge-scanner imports — see AttendancePage/routes.js, there is
          no POST clock-in/out route), so the primary action is honest about
          being a link to the attendance page rather than a real punch
          action, exactly as instructed when no such action exists. */}
      <button
        type="button"
        onClick={() => navigate('/attendance')}
        className="bg-surface border border-border rounded-md shadow-sm p-5 w-full text-left cursor-pointer flex items-center justify-between gap-4 transition-colors hover:border-primary/50 hover:bg-surface-hover focus-visible:outline-none focus-visible:shadow-[var(--shadow-focus-ring)] focus-visible:border-primary-hover max-[720px]:flex-col max-[720px]:items-start max-[720px]:gap-3"
      >
        <span className="flex items-center gap-3 min-w-0">
          <span className={`stat-icon !mb-0 stat-${hasCheckedIn ? 'teal' : 'amber'}`}>
            <Icon name="badgeCheck" size={21} />
          </span>
          <span className="min-w-0">
            <span className="block !text-sm !font-bold !text-text">
              {hasCheckedIn ? 'เข้างานแล้ว' : 'ยังไม่ลงเวลา'}
            </span>
            <span className="block !text-xs !text-text-muted">
              {hasCheckedIn ? `เข้างาน ${formatBangkokTime(today.check_in)} น.` : 'วันนี้ยังไม่มีการสแกนเข้างาน'}
            </span>
          </span>
        </span>
        <span className="flex items-center gap-2 max-[720px]:w-full max-[720px]:justify-between">
          <span className="!text-sm !font-bold !text-primary">
            {hasCheckedIn ? 'ลงเวลาออกงาน / ลงเวลาเข้างาน' : 'ลงเวลาเข้างาน'}
          </span>
          <Icon name="chevronRight" size={16} className="text-text-faint" />
        </span>
      </button>

      <div className={TILE_GRID}>
        <StatCard
          icon="calendar"
          label="เวลาทำงานเดือนนี้"
          value={`${daysPresent} วัน`}
          helper={lateDays > 0 ? `มาสาย ${lateDays} วัน` : 'ไม่มีวันมาสาย'}
          tone={lateDays > 0 ? 'amber' : 'teal'}
          onClick={() => navigate('/attendance')}
        />
        <StatCard
          icon="clipboard"
          label="วันลาคงเหลือ"
          value={`${totalRemainingLeave.toLocaleString('th-TH', { maximumFractionDigits: 2 })} วัน`}
          helper={leaveBalances.length > 0
            ? leaveBalances.map((balance) => `${balance.leaveTypeNameTh || balance.leaveTypeCode} ${balance.remainingDays}`).join(' · ')
            : 'ยังไม่มีข้อมูลโควตา'}
          tone="blue"
          onClick={() => navigate('/leave')}
        />
        <StatCard
          icon="badgeDollar"
          label="สลิปเงินเดือน"
          value={latestPayrollPeriodId ? `งวด ${latestPayrollPeriodId}` : '-'}
          // KNOWN GAP (mock only): mockApi's dashboard.summary() hardcodes
          // latestPayrollPeriodId: null, so this tile always renders the
          // "no data yet" state under VITE_USE_MOCKS=true — that reflects the
          // mock's real (empty) value, not an invented number.
          helper={latestPayrollPeriodId ? 'ดาวน์โหลดสลิปเงินเดือน' : 'ยังไม่มีข้อมูลงวดล่าสุด'}
          tone="indigo"
          onClick={latestPayrollPeriodId ? downloadMyPayslip : undefined}
        />
      </div>

      <Panel
        title="คำขอของฉัน"
        actions={(
          <span className="flex gap-2">
            <Button type="button" variant="secondary" onClick={() => navigate('/leave')}>
              <Icon name="plus" />
              ขอลา
            </Button>
            <Button type="button" variant="secondary" onClick={() => navigate('/employee-requests?tab=ot')}>
              <Icon name="plus" />
              ขอ OT
            </Button>
          </span>
        )}
      >
        <div className="request-feed">
          {myRequests.length === 0 ? (
            <div className="empty-state">ยังไม่มีคำขอล่าสุด</div>
          ) : myRequests.map((row) => <MyRequestRow key={row.id} row={row} />)}
        </div>
      </Panel>
    </PageStack>
  );
}
