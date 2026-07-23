import { useEffect, useMemo } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { Button } from '../../components/common/Button.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { StatCard } from '../../components/common/StatCard.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { EmptyState } from '../../components/common/EmptyState.jsx';
import { PageHeader } from '../../components/common/PageHeader.jsx';
import { PageStack, Panel, StatGrid } from '../../components/common/Layout.jsx';
import { attendanceStatusLabel, formatThaiDate } from '../../utils/format.js';
import { cn } from '../../utils/cn.js';

/**
 * Same column ratio as EmployeeDashboard's `.dashboard-grid` (no shared
 * primitive for this exact ratio yet — see that file's own copy of this
 * constant for the reasoning).
 */
const DASHBOARD_GRID = 'grid gap-[18px] items-start grid-cols-[1.15fr_0.85fr] max-[1040px]:grid-cols-2 max-[720px]:grid-cols-1';

function bangkokDateParts(date = new Date()) {
  return Object.fromEntries(new Intl.DateTimeFormat('en-US', {
    timeZone: 'Asia/Bangkok',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).formatToParts(date).map((part) => [part.type, part.value]));
}

function todayIso() {
  const parts = bangkokDateParts();
  return `${parts.year}-${parts.month}-${parts.day}`;
}

function bangkokTimeOfDay(isoValue) {
  if (!isoValue) return '-';
  const date = new Date(isoValue);
  if (Number.isNaN(date.getTime())) return '-';
  return new Intl.DateTimeFormat('th-TH', {
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
    timeZone: 'Asia/Bangkok',
  }).format(date);
}

function formatWorkDate(value) {
  if (!value) return '-';
  const date = new Date(`${value}T00:00:00+07:00`);
  if (Number.isNaN(date.getTime())) return '-';
  return new Intl.DateTimeFormat('th-TH', { dateStyle: 'medium', timeZone: 'Asia/Bangkok' }).format(date);
}

function formatDateRange(start, end) {
  return start === end ? formatWorkDate(start) : `${formatWorkDate(start)} - ${formatWorkDate(end)}`;
}

function otDetail(request) {
  const window = `${bangkokTimeOfDay(request.plannedStartAt)}-${bangkokTimeOfDay(request.plannedEndAt)}`;
  return `OT ${formatWorkDate(request.workDate)} · ${window} น.`;
}

function leaveDetail(request) {
  const type = request.leaveTypeNameTh || request.leaveTypeCode || 'ลา';
  return `${type} · ${formatDateRange(request.startDate, request.endDate)}`;
}

/**
 * "พนักงาน ✓ → คุณ (ตอนนี้) → CEO" chain badge. Steps are passed explicitly
 * per item because OT/leave genuinely have different chains in this codebase
 * (see the OT/leave worklist comment below) — this component only renders
 * whatever it's handed, it never assumes a fixed 3-step shape.
 */
function ApprovalChain({ steps }) {
  return (
    <div className="flex flex-wrap items-center gap-1 mt-1">
      {steps.map((step, index) => (
        <span key={step.label} className="flex items-center gap-1">
          <span
            className={cn(
              'text-xs font-bold',
              step.current ? 'text-primary' : step.done ? 'text-success' : 'text-text-muted',
            )}
          >
            {step.label}
            {step.done ? ' ✓' : null}
            {step.current ? ' (ตอนนี้)' : null}
          </span>
          {index < steps.length - 1 ? <Icon name="chevronRight" size={12} className="text-text-faint" /> : null}
        </span>
      ))}
    </div>
  );
}

/**
 * Division-manager (non-sales) landing — role `employee` + the manager flag
 * (DivisionAccessPolicy.isManager: position contains "ผู้จัดการ", assistant
 * managers included). This replaces EmployeeDashboard's generic 'manager'
 * mode for this audience with a purpose-built people-ops worklist: approve
 * the division's OT/leave (step 1 of employee -> manager -> CEO), see the
 * team's attendance today, plus the manager's own self-service. No sales
 * surface — this role never had one.
 *
 * Approve/reject mutations are NOT duplicated here — every CTA deep-links to
 * the real approve surface (OvertimePanel via /employee-requests, LeavePage
 * via /leave), which already owns the confirm dialogs and the mutations.
 */
export function DivisionManagerOverview({ user, employee, dashboardSummary, showToast }) {
  const navigate = useNavigate();
  const today = todayIso();

  const overtimeQuery = useQuery({
    queryKey: queryKeys.overtimeRequests({ status: 'SUBMITTED' }),
    queryFn: () => api.overtime.list({ status: 'SUBMITTED' }).then((response) => response.requests || []),
  });
  const leaveQuery = useQuery({
    queryKey: queryKeys.leaveRequests({ status: 'SUBMITTED' }),
    queryFn: () => api.leave.list({ status: 'SUBMITTED' }).then((response) => response.requests || []),
  });
  const attendanceQuery = useQuery({
    queryKey: queryKeys.attendanceDaily(today, today),
    queryFn: () => api.attendance.daily({ from: today, to: today }).then((response) => response.days || []),
  });

  useEffect(() => {
    if (overtimeQuery.error) showToast?.('error', overtimeQuery.error.message || 'โหลดคำขอ OT ไม่สำเร็จ');
  }, [overtimeQuery.error, showToast]);
  useEffect(() => {
    if (leaveQuery.error) showToast?.('error', leaveQuery.error.message || 'โหลดคำขอลาไม่สำเร็จ');
  }, [leaveQuery.error, showToast]);
  useEffect(() => {
    if (attendanceQuery.error) showToast?.('error', attendanceQuery.error.message || 'โหลดข้อมูลเวลาทำงานไม่สำเร็จ');
  }, [attendanceQuery.error, showToast]);

  // `api.overtime.list`/`api.leave.list` are already scoped server-side to what
  // this caller may review (division-wide for OT, direct-report FK for leave —
  // see mockApi's canReviewOvertime/canReviewLeave); the one thing left to drop
  // client-side is the caller's OWN submitted request, which the API legitimately
  // returns (they may see it) but which they cannot approve (no self-approval).
  const otPending = useMemo(
    () => (overtimeQuery.data ?? []).filter((request) => Number(request.employeeId) !== Number(user?.employeeId)),
    [overtimeQuery.data, user?.employeeId],
  );
  const leavePending = useMemo(
    () => (leaveQuery.data ?? []).filter((request) => Number(request.employeeId) !== Number(user?.employeeId)),
    [leaveQuery.data, user?.employeeId],
  );

  const attendanceDays = useMemo(() => attendanceQuery.data ?? [], [attendanceQuery.data]);
  const presentToday = attendanceDays.filter((day) => day.check_in).length;
  const lateToday = attendanceDays.filter((day) => day.status === 'LATE').length;
  const notCheckedOut = attendanceDays.filter((day) => day.check_in && !day.check_out).length;
  const teamTotal = dashboardSummary?.headcount?.active ?? 0;

  // Worklist rows. OT genuinely is a 3-step chain in this codebase (SUBMITTED
  // -> MANAGER_APPROVED -> APPROVED, i.e. employee -> manager -> CEO — see
  // OvertimePanel.canCeoApprove). Leave, however, is single-step here
  // (SUBMITTED -> APPROVED, no CEO stage — see LeavePage.canReviewRequest /
  // mockApi canReviewLeave): the manager's approval is terminal. The chain
  // badge reflects that difference rather than forcing a uniform 3-step shape
  // the leave workflow doesn't actually have.
  const worklist = useMemo(() => {
    const otRows = otPending.map((request) => ({
      key: `ot-${request.id}`,
      employeeName: request.employeeName || request.employeeCode || `พนักงาน #${request.employeeId}`,
      detail: otDetail(request),
      chain: [
        { label: 'พนักงาน', done: true },
        { label: 'คุณ', current: true },
        { label: 'CEO' },
      ],
      onApprove: () => navigate('/employee-requests?tab=ot'),
    }));
    const leaveRows = leavePending.map((request) => ({
      key: `leave-${request.id}`,
      employeeName: request.employeeName || request.employeeCode || `พนักงาน #${request.employeeId}`,
      detail: leaveDetail(request),
      chain: [
        { label: 'พนักงาน', done: true },
        { label: 'คุณ', current: true },
      ],
      onApprove: () => navigate('/leave'),
    }));
    return [...otRows, ...leaveRows];
  }, [otPending, leavePending, navigate]);

  // A couple of team rows for the "การลงเวลาทีมวันนี้" rail: attention-worthy
  // statuses (late, missing check-out) surface first, same "don't bury what
  // needs a look" principle as the worklist above; capped to keep the card short.
  const teamAttendanceRows = useMemo(() => {
    const priority = { LATE: 0, MISSING_CHECK_OUT: 1, MISSING_CHECK_IN: 2, PRESENT: 3, NON_WORKDAY: 4, NO_RECORD: 5 };
    return attendanceDays
      .slice()
      .sort((a, b) => (priority[a.status] ?? 9) - (priority[b.status] ?? 9))
      .slice(0, 5);
  }, [attendanceDays]);

  const pulseCards = [
    {
      icon: 'clock',
      label: 'รออนุมัติ OT',
      value: otPending.length,
      helper: 'ทีม',
      tone: 'amber',
      onClick: () => navigate('/employee-requests?tab=ot'),
    },
    {
      icon: 'calendar',
      label: 'รออนุมัติลา',
      value: leavePending.length,
      helper: 'ทีม',
      tone: 'amber',
      onClick: () => navigate('/leave'),
    },
    {
      icon: 'clock',
      label: 'มาสายวันนี้',
      value: lateToday,
      helper: 'ทีม',
      tone: lateToday > 0 ? 'amber' : 'indigo',
      onClick: () => navigate('/attendance'),
    },
    {
      icon: 'users',
      label: 'ทีมทั้งหมด',
      value: teamTotal,
      helper: 'Active',
      tone: 'indigo',
      onClick: () => navigate('/attendance'),
    },
  ];

  const quickActions = [
    ['/attendance', 'ลงเวลา'],
    ['/leave', 'ขอลา'],
    ['/employee-requests?tab=ot', 'ขอ OT'],
  ];

  const divisionName = employee?.divisionTh
    || dashboardSummary?.headcount?.byDivision?.[0]?.divisionName
    || '';

  return (
    <PageStack>
      <PageHeader
        title={`ภาพรวมทีม · ${divisionName || '-'}`}
        subtitle={`${formatThaiDate(new Date().toISOString())} · คำขอที่รอคุณอนุมัติ · การลงเวลาของทีม`}
      />

      <StatGrid>
        {pulseCards.map((card) => (
          <StatCard key={card.label} {...card} />
        ))}
      </StatGrid>

      <div className={DASHBOARD_GRID}>
        <Panel title="คำขอที่รอคุณอนุมัติ">
          {worklist.length === 0 ? (
            <EmptyState icon="check" title="ไม่มีคำขอที่รอคุณอนุมัติตอนนี้" />
          ) : (
            <div className="flex flex-col">
              {worklist.map((item) => (
                <div
                  key={item.key}
                  className="flex items-center justify-between gap-3 py-3 border-b border-surface-subtle last:border-b-0 max-[720px]:flex-col max-[720px]:items-start max-[720px]:gap-2.5"
                >
                  <div className="min-w-0">
                    <p className="m-0 text-sm font-bold text-text truncate">{item.employeeName}</p>
                    <p className="m-0 text-xs text-text-muted truncate">{item.detail}</p>
                    <ApprovalChain steps={item.chain} />
                  </div>
                  <Button
                    type="button"
                    variant="success"
                    size="sm"
                    onClick={item.onApprove}
                    className="shrink-0 max-[720px]:w-full"
                  >
                    <Icon name="check" size={14} />
                    อนุมัติ
                  </Button>
                </div>
              ))}
            </div>
          )}
        </Panel>

        <div className="grid gap-[18px]">
          <Panel title="การลงเวลาทีมวันนี้">
            <div className="grid grid-cols-3 gap-2 text-center mb-4">
              <div>
                <div className="text-xl font-extrabold text-text">{presentToday}</div>
                <div className="text-xs text-text-muted">มาแล้ว</div>
              </div>
              <div>
                <div className={cn('text-xl font-extrabold', lateToday > 0 ? 'text-warning' : 'text-text')}>{lateToday}</div>
                <div className="text-xs text-text-muted">มาสาย</div>
              </div>
              <div>
                <div className="text-xl font-extrabold text-text">{notCheckedOut}</div>
                <div className="text-xs text-text-muted">ยังไม่ออก</div>
              </div>
            </div>
            {teamAttendanceRows.length === 0 ? (
              <EmptyState icon="calendar" title="ยังไม่มีข้อมูลเวลาทำงานวันนี้" />
            ) : (
              <div className="flex flex-col">
                {teamAttendanceRows.map((day) => {
                  const status = attendanceStatusLabel(day.status);
                  return (
                    <div
                      key={day.employee_id}
                      className="flex items-center justify-between gap-3 py-2 border-b border-surface-subtle last:border-b-0"
                    >
                      <span className="text-sm text-text truncate">{day.employee_name}</span>
                      <StatusBadge tone={status.tone}>{status.label}</StatusBadge>
                    </div>
                  );
                })}
              </div>
            )}
          </Panel>

          <Panel title="ของฉัน (self-service)">
            <div className="action-list">
              {quickActions.map(([path, label]) => (
                <button type="button" key={path} onClick={() => navigate(path)}>{label}</button>
              ))}
            </div>
          </Panel>
        </div>
      </div>
    </PageStack>
  );
}
