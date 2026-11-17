import { useMemo } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { Button } from '../../components/common/Button.jsx';
import { StatCard } from '../../components/common/StatCard.jsx';
import { PageHeader } from '../../components/common/PageHeader.jsx';
import { PageStack, Panel, StatGrid } from '../../components/common/Layout.jsx';
import { bangkokMonthStartIso, formatThaiDate, payrollStatusLabel } from '../../utils/format.js';
import { ActionQueue } from './ActionQueue.jsx';
import { buildHrDashboardStats, DivisionHeadcountPanel } from './hrHeadcount.jsx';

/** HR Overview — the HR-role landing (`/` when `user.role === 'hr'`). HR is NOT an approver of either — HR only
 * *monitors* status here. RECONCILED APPROVAL MODEL (owner-confirmed, 2026-07-22): OT and leave both route
 * division-manager -> CEO, or straight to CEO when the division has no manager. */

const DASHBOARD_GRID = 'grid gap-[18px] items-start grid-cols-[1.15fr_0.85fr] nav-drawer:grid-cols-2 mobile:grid-cols-1';

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
    <div className="flex items-center justify-between gap-4 border-b border-surface-subtle py-3 last:border-b-0 last:pb-0 mobile-sm:flex-col mobile-sm:items-start mobile-sm:gap-2">
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

// Deliberately renders no "approve"/"reject" affordance — HR's role for OT and leave is monitoring only (see
// file-level note above). The only action offered is "ดูสถานะ" (view status), which opens the read-only list.
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

  // PayrollController requires payrollMonth (RC-04); Bangkok month, not UTC. Own key suffix:
  // PayrollPage caches a different shape under payrollCurrent(month).
  const payrollMonth = bangkokMonthStartIso().slice(0, 7);
  const payrollQuery = useQuery({
    queryKey: [...queryKeys.payrollCurrent(payrollMonth), 'overview'],
    queryFn: () => api.payroll.current({ payrollMonth }).then((response) => response?.period ?? null),
    enabled: !!api.payroll?.current,
  });
  const payrollPeriod = payrollQuery.data ?? null;

  // Same queryKey and queryFn shape as TaxAllowanceReviewPage's own register query — deliberately, so this
  // shares that one cache entry instead of adding a second fetch of the same rows.
  const currentTaxYear = new Date().getFullYear();
  const taxAllowanceRegisterQuery = useQuery({
    queryKey: queryKeys.taxAllowanceDeclarationsRegister({ year: currentTaxYear }),
    queryFn: () => api.payroll.getTaxAllowanceDeclarations({ year: currentTaxYear })
      .then((response) => response.items || [])
      .catch(() => []),
  });
  const pendingTaxAllowance = (taxAllowanceRegisterQuery.data ?? [])
    .filter((item) => item.status === 'PENDING').length;

  const stats = useMemo(() => buildHrDashboardStats(employees, dashboardSummary), [employees, dashboardSummary]);

  // HR's only actionable queue item is profile requests — OT/leave are monitoring-only (see file header) so they
  // never appear as "needs action".
  const queueItems = [
    { key: 'profileRequests', label: 'คำขอแก้ไขข้อมูลรออนุมัติ', value: stats.pendingProfileRequests, to: () => navigate('/requests') },
    { key: 'notifications', label: 'แจ้งเตือนยังไม่อ่าน', value: stats.unreadNotifications },
  ];

  const payrollDetail = payrollQuery.isError
    ? 'โหลดรอบเงินเดือนไม่สำเร็จ'
    : payrollPeriod
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
        <div className="grid grid-cols-3 gap-3 mobile-sm:grid-cols-1">
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
            {/* ล.ย.01 review is HR's own work, like the profile-change queue above it — approve/ reject/apply are hr-only —
             * so it belongs here rather than in the read-only monitor panel. It had no presence on this page at all
             * before, which meant HR had to remember to go looking for a queue nothing surfaced. */}
            <HrTaskRow
              label="แบบแจ้ง ล.ย.01 (ค่าลดหย่อนภาษี)"
              detail={`${pendingTaxAllowance} รายการรอตรวจ`}
              cta="ตรวจแบบแจ้ง"
              onClick={() => navigate('/tax-allowance-review?status=PENDING')}
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

      <DivisionHeadcountPanel divisionRows={stats.divisionRows} maxHeadcount={stats.maxHeadcount} />
    </PageStack>
  );
}
