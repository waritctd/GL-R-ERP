import { useMemo } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { Button } from '../../components/common/Button.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { STAT_ICON_TILE_CLASSES, STAT_TONE_CLASSES } from '../../components/common/StatCard.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { PageHeader } from '../../components/common/PageHeader.jsx';
import { PageStack, Panel } from '../../components/common/Layout.jsx';
import { TaxAllowanceActionRow } from '../taxAllowance/TaxAllowanceActionRow.jsx';
import { cn } from '../../utils/cn.js';
import {
  bangkokTodayIso,
  formatBangkokTime,
  formatShortDate,
  formatThaiDate,
  greetingName,
  leaveStatusLabel,
  overtimeStatusLabel,
  requestStatus,
} from '../../utils/format.js';

// Three-tile grid: no shared primitive has this exact ratio (StatGrid is
// Terminal negative states: a stepper implies "still progressing", which is
// wrong once a request has been stopped. Rejected/cancelled rows show only
// the status badge, never the chain.
// EXPIRED joins these for ล.ย.01: an expired declaration has stopped progressing, so a stepper
// implying "still on its way" would be wrong in exactly the way this set exists to prevent.
const TERMINAL_NEGATIVE = new Set(['REJECTED', 'CANCELLED', 'AUTO_REJECTED', 'rejected', 'EXPIRED']);

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
  // ล.ย.01: HR approval and payroll application are two distinct steps, and the gap between them
  // matters — an approved-but-unapplied declaration is not yet reducing anyone's tax. The status
  // badge already refuses to call that state "done"; the chain shows the same thing structurally.
  if (kind === 'taxAllowance') {
    return [
      { label: 'ส่งแล้ว', done: true },
      { label: 'HR ตรวจสอบ', done: request.status === 'APPROVED', at: request.reviewedAt },
      { label: 'ใช้กับเงินเดือน', done: !!request.appliedAt, at: request.appliedAt },
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
    <div className="request-feed-item flex items-center justify-between gap-3 min-w-0 py-2.5 border-b border-surface-subtle">
      <span>
        <strong>{row.title}</strong>
        <small>{row.dateLabel}</small>
        {showChain ? <ApprovalChain steps={row.chain} /> : null}
      </span>
      <StatusBadge tone={row.statusInfo.tone}>{row.statusInfo.label}</StatusBadge>
    </div>
  );
}

export function EmployeeSelfService({ user, employee, profileRequests = [], taxAllowanceSummary }) {
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
      // A1: pass pendingApproverRole through -- a status-only call mislabels every SUBMITTED
      // request 'รอผู้จัดการ' on this dashboard too, including the ones OvertimeService routes
      // straight to the CEO.
      statusInfo: overtimeStatusLabel(request.status, request.pendingApproverRole),
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
    // ล.ย.01 is a request with an approval chain exactly like the three above, and it was the one
    // the employee had no way to track. `statusInfo` comes from the shared taxAllowanceStatus
    // helper, so its labels stay identical to the ones on /tax-allowance and /profile.
    const declaration = taxAllowanceSummary?.declaration;
    const taxAllowanceRows = declaration ? [{
      id: `tax-allowance-${declaration.declarationId}`,
      title: 'แบบแจ้ง ล.ย.01 (ค่าลดหย่อนภาษี)',
      dateLabel: formatShortDate(declaration.submittedAt),
      requestedAt: declaration.submittedAt,
      status: declaration.status,
      statusInfo: taxAllowanceSummary.statusInfo,
      chain: chainForRequest('taxAllowance', declaration),
    }] : [];
    return [...leaveRows, ...otRows, ...profileRows, ...taxAllowanceRows]
      .sort((a, b) => String(b.requestedAt || '').localeCompare(String(a.requestedAt || '')))
      .slice(0, 8);
  }, [ownLeaveQuery.data, ownOvertimeQuery.data, profileRequests, taxAllowanceSummary]);


  const hasCheckedIn = Boolean(today?.check_in);
  const firstName = employee?.nickName || employee?.nameTh || user?.name || '';

  return (
    <PageStack>
      <PageHeader
        title={`สวัสดี ${greetingName(firstName)}`}
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
        className="bg-surface border border-border rounded-md p-5 w-full text-left cursor-pointer flex items-center justify-between gap-4 transition-colors hover:border-primary/50 hover:bg-surface-hover focus-visible:outline-none focus-visible:shadow-[var(--shadow-focus-ring)] focus-visible:border-primary-hover mobile:flex-col mobile:items-start mobile:gap-3"
      >
        <span className="flex items-center gap-3 min-w-0">
          <span className={cn(STAT_ICON_TILE_CLASSES, '!mb-0', STAT_TONE_CLASSES[hasCheckedIn ? 'teal' : 'amber'])}>
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
        <span className="flex items-center gap-2 mobile:w-full mobile:justify-between">
          <span className="!text-sm !font-bold !text-primary">
            {hasCheckedIn ? 'ลงเวลาออกงาน / ลงเวลาเข้างาน' : 'ลงเวลาเข้างาน'}
          </span>
          <Icon name="chevronRight" size={16} className="text-text-faint" />
        </span>
      </button>

      {/* Only rendered when there is something to file — see TaxAllowanceActionRow. Sits with the
          attendance card above the tiles because it is an action, not a metric. */}
      <TaxAllowanceActionRow summary={taxAllowanceSummary} />

      {/* The three stat tiles that used to sit here (เวลาทำงานเดือนนี้ / วันลาคงเหลือ /
          สลิปเงินเดือน) were removed on 2026-08-10, owner call: they did not earn the space.
          Each was a number this page could not act on — the sidebar already reaches
          /attendance, /leave and the payslip, and the คำขอของฉัน panel below is what the
          employee actually came to do.

          The leave tile was worse than merely redundant. It summed every leave type's
          remaining days into one headline, and MILITARY's annualQuotaDays is 366 — a
          deliberate sentinel, not a policy number (see mockApi.js's db.leaveTypes comment, and
          MyLeaveTab.jsx, which excludes it from its own quota display for exactly this reason).
          So the card read "564 วัน" (7+30+3+98+366+60): a total nobody has, presented as the
          most prominent figure on the employee's home screen. */}

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
        <div className="request-feed grid gap-2.5">
          {myRequests.length === 0 ? (
            <div className="grid min-h-[220px] place-items-center content-center gap-2 text-center text-text-muted">ยังไม่มีคำขอล่าสุด</div>
          ) : myRequests.map((row) => <MyRequestRow key={row.id} row={row} />)}
        </div>
      </Panel>
    </PageStack>
  );
}
