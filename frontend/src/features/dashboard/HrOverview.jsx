import { useMemo } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { Button } from '../../components/common/Button.jsx';
import { StatCard } from '../../components/common/StatCard.jsx';
import { PageHeader } from '../../components/common/PageHeader.jsx';
import { PageStack, Panel, StatGrid } from '../../components/common/Layout.jsx';
import { formatThaiDate, payrollStatusLabel } from '../../utils/format.js';
import { divisions, findDivision } from '../../data/referenceData.js';
import { ActionQueue } from './ActionQueue.jsx';

/**
 * HR Overview — the HR-role landing (`/` when `user.role === 'hr'`).
 *
 * RECONCILED APPROVAL MODEL (owner-confirmed, 2026-07-22): OT and leave both
 * route division-manager -> CEO, or straight to CEO when the division has no
 * manager. HR is NOT an approver of either — HR only *monitors* status here.
 * There is deliberately no approve action anywhere on this page for OT/leave;
 * see "ภาพรวมลา / OT (ดูอย่างเดียว)" below. HR's one real review task is
 * profile-change requests (canReviewProfileRequests={hr}), framed as
 * "ตรวจ/ดำเนินการ" rather than "approve".
 *
 * Reuses HrDashboard's Panel/StatGrid/StatCard/ActionQueue structure and its
 * `dashboard.summary` data wiring (see HrDashboard.jsx), but the queue/cards
 * below only ever surface actions HR genuinely owns.
 */

const DASHBOARD_GRID = 'grid gap-[18px] items-start grid-cols-[1.15fr_0.85fr] max-[1040px]:grid-cols-2 max-[720px]:grid-cols-1';

function AttendanceStat({ label, value, tone }) {
  return (
    <div className="flex flex-col items-center justify-center gap-1 rounded-md bg-surface-muted px-4 py-3 text-center">
      <span className={`text-2xl font-extrabold text-${tone}`}>{value}</span>
      <span className="text-xs font-bold text-text-muted">{label}</span>
    </div>
  );
}

function HrTaskRow({ label, detail, cta, onClick }) {
  return (
    <div className="flex items-center justify-between gap-4 border-b border-surface-subtle py-3 last:border-b-0 last:pb-0 max-[560px]:flex-col max-[560px]:items-start max-[560px]:gap-2">
      <span className="min-w-0">
        <span className="block text-sm font-bold text-text">{label}</span>
        <span className="block text-xs text-text-muted">{detail}</span>
      </span>
      <Button type="button" variant="secondary" size="sm" onClick={onClick} className="shrink-0">
        {cta}
      </Button>
    </div>
  );
}

// Deliberately renders no "approve"/"reject" affordance — HR's role for OT
// and leave is monitoring only (see file-level note above). The only action
// offered is "ดูสถานะ" (view status), which opens the read-only list.
function MonitorRow({ label, value, onClick }) {
  return (
    <button
      type="button"
      onClick={onClick}
      className="flex w-full items-center justify-between gap-4 rounded-md border-0 bg-surface-muted px-4 py-3 text-left transition-colors hover:bg-surface-hover focus-visible:outline-none focus-visible:shadow-[var(--shadow-focus-ring)]"
    >
      <span className="text-sm font-bold text-text">{label}</span>
      <span className="flex items-center gap-2">
        <span className="text-lg font-extrabold text-text">{value}</span>
        <span className="text-xs font-bold text-primary">ดูสถานะ</span>
      </span>
    </button>
  );
}

export function HrOverview({ employees, dashboardSummary }) {
  const navigate = useNavigate();

  const payrollQuery = useQuery({
    queryKey: queryKeys.payrollCurrent(),
    queryFn: () => api.payroll.current().then((response) => response?.period ?? null).catch(() => null),
    enabled: !!api.payroll?.current,
  });
  const payrollPeriod = payrollQuery.data ?? null;

  const stats = useMemo(() => {
    const headcountByDivision = new Map(divisions.map((division) => [division.id, { division, count: 0 }]));
    const extraDivisions = new Map();
    let activeCount = 0;

    employees.forEach((item) => {
      if (item.active) activeCount += 1;
      const knownDivision = findDivision(item.divisionId, item.divisionTh);
      if (knownDivision && headcountByDivision.has(knownDivision.id)) {
        headcountByDivision.get(knownDivision.id).count += 1;
      } else {
        const key = item.divisionId || item.divisionTh || 'unknown';
        const current = extraDivisions.get(key) || {
          division: { id: key, th: item.divisionTh || 'ไม่ระบุฝ่าย', en: item.divisionId || 'Unassigned' },
          count: 0,
        };
        current.count += 1;
        extraDivisions.set(key, current);
      }
    });

    const fallbackDivisionRows = [...headcountByDivision.values(), ...extraDivisions.values()];
    const summaryRows = dashboardSummary?.headcount?.byDivision?.map((item) => ({
      division: {
        id: item.divisionId ?? item.divisionCode ?? item.divisionName,
        th: item.divisionName || 'ไม่ระบุฝ่าย',
        en: item.divisionCode || '',
      },
      count: item.total ?? item.active ?? 0,
    })) ?? [];
    const divisionRows = summaryRows.length > 0 ? summaryRows : fallbackDivisionRows;
    const pending = dashboardSummary?.pendingApprovals ?? {};
    const attendance = dashboardSummary?.attendance ?? {};
    const notifications = dashboardSummary?.notifications ?? {};

    return {
      totalCount: dashboardSummary?.headcount?.total ?? employees.length,
      activeCount: dashboardSummary?.headcount?.active ?? activeCount,
      pendingProfileRequests: pending.profileRequests ?? 0,
      pendingOvertime: pending.overtime ?? 0,
      pendingLeave: pending.leave ?? 0,
      todayPresent: attendance.todayPresent ?? 0,
      lateToday: attendance.lateToday ?? 0,
      missingCheckout: attendance.missingCheckout ?? 0,
      unreadNotifications: notifications.unread ?? 0,
      divisionRows,
      maxHeadcount: Math.max(...divisionRows.map((item) => item.count), 1),
    };
  }, [employees, dashboardSummary]);

  // HR's only actionable queue item is profile requests — OT/leave are
  // monitoring-only (see file header) so they never appear as "needs action".
  const queueItems = [
    { key: 'profileRequests', label: 'คำขอแก้ไขข้อมูลรออนุมัติ', value: stats.pendingProfileRequests, to: () => navigate('/requests') },
    { key: 'notifications', label: 'แจ้งเตือนยังไม่อ่าน', value: stats.unreadNotifications },
  ];

  const payrollDetail = payrollPeriod
    ? `${formatThaiDate(payrollPeriod.payrollMonth)} · ${payrollStatusLabel(payrollPeriod.status).label}`
    : 'ยังไม่เริ่มรอบเงินเดือนเดือนนี้';

  return (
    <PageStack>
      <PageHeader
        title="สวัสดี ฝ่ายบุคคล"
        subtitle="งานบุคคลวันนี้ · การลงเวลา · รอบเงินเดือน"
      />

      <ActionQueue items={queueItems} />

      <StatGrid>
        <StatCard
          icon="userCog"
          label="คำขอแก้ไขข้อมูล · รอตรวจ"
          value={stats.pendingProfileRequests}
          helper="Profile requests"
          tone="amber"
          onClick={() => navigate('/requests')}
        />
        <StatCard
          icon="users"
          label="พนักงานทั้งหมด"
          value={stats.totalCount}
          helper={`Active ${stats.activeCount}`}
          tone="indigo"
          onClick={() => navigate('/employees')}
        />
        <StatCard
          icon="badgeCheck"
          label="มาแล้ววันนี้"
          value={stats.todayPresent}
          helper="Attendance"
          tone="teal"
          onClick={() => navigate('/attendance')}
        />
        <StatCard
          icon="clock"
          label="มาสายวันนี้"
          value={stats.lateToday}
          helper="Late today"
          tone={stats.lateToday > 0 ? 'amber' : 'indigo'}
          onClick={() => navigate('/attendance')}
        />
      </StatGrid>

      <Panel
        title="การลงเวลาวันนี้"
        actions={<Button type="button" variant="text" onClick={() => navigate('/attendance')}>ดูรายละเอียด</Button>}
      >
        <div className="grid grid-cols-3 gap-3 max-[560px]:grid-cols-1">
          <AttendanceStat label="มาแล้ว" value={stats.todayPresent} tone="accent" />
          <AttendanceStat label="มาสาย" value={stats.lateToday} tone="warning" />
          <AttendanceStat label="ยังไม่ออก" value={stats.missingCheckout} tone="info" />
        </div>
      </Panel>

      <div className={DASHBOARD_GRID}>
        <Panel title="งานของฝ่ายบุคคล">
          <div className="flex flex-col">
            <HrTaskRow
              label="คำขอแก้ไขข้อมูลพนักงาน"
              detail={`${stats.pendingProfileRequests} รายการรอตรวจ`}
              cta="ตรวจคำขอ"
              onClick={() => navigate('/requests')}
            />
            <HrTaskRow
              label="รอบเงินเดือน"
              detail={payrollDetail}
              cta="ไปที่หน้าเงินเดือน"
              onClick={() => navigate('/payroll')}
            />
          </div>
        </Panel>

        <Panel title="ภาพรวมลา / OT (ดูอย่างเดียว)">
          <p className="mt-0 mb-3 text-xs font-bold text-text-muted">
            อนุมัติโดยหัวหน้าฝ่าย/CEO — ฝ่ายบุคคลติดตามสถานะเท่านั้น
          </p>
          <div className="flex flex-col gap-2">
            <MonitorRow label="คำขอลารออนุมัติ" value={stats.pendingLeave} onClick={() => navigate('/leave')} />
            <MonitorRow label="OT รออนุมัติ" value={stats.pendingOvertime} onClick={() => navigate('/overtime')} />
          </div>
        </Panel>
      </div>

      <Panel
        title="จำนวนพนักงานตามฝ่าย"
        actions={<Button type="button" variant="text" onClick={() => navigate('/employees')}>ดูรายชื่อ</Button>}
      >
        <div className="bar-list">
          {stats.divisionRows.map(({ division, count }) => {
            const widthPct = Math.max(8, Math.round((count / stats.maxHeadcount) * 100));
            return (
              <div className="bar-row" key={division.id}>
                <span>
                  <strong>{division.th}</strong>
                  <small>{division.en}</small>
                </span>
                <div className="bar-track">
                  <i className="w-[var(--bar-width)]" style={{ '--bar-width': `${widthPct}%` }} />
                </div>
                <b>{count}</b>
              </div>
            );
          })}
        </div>
      </Panel>
    </PageStack>
  );
}
