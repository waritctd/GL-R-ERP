import { useNavigate } from 'react-router-dom';
import { Button } from '../../components/common/Button.jsx';
import { Panel } from '../../components/common/Layout.jsx';
import { divisions, findDivision } from '../../data/referenceData.js';

/** Headcount-by-division rows plus the dashboardSummary counters the HR landings share. */
export function buildHrDashboardStats(employees, dashboardSummary) {
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
}

export function DivisionHeadcountPanel({ divisionRows, maxHeadcount }) {
  const navigate = useNavigate();
  return (
    <Panel
      title="จำนวนพนักงานตามฝ่าย"
      actions={<Button type="button" variant="text" onClick={() => navigate('/employees')}>ดูรายชื่อ</Button>}
    >
      <div className="grid gap-2.5">
        {divisionRows.map(({ division, count }) => {
          const widthPct = Math.max(8, Math.round((count / maxHeadcount) * 100));
          return (
            <div className="grid grid-cols-[minmax(160px,1fr)_2fr_34px] gap-3 items-center" key={division.id}>
              <span>
                <strong>{division.th}</strong>
                <small>{division.en}</small>
              </span>
              <div className="h-[9px] bg-surface-subtle rounded-pill overflow-hidden">
                <i className="block h-full rounded-[inherit] bg-accent w-[var(--bar-width)]" style={{ '--bar-width': `${widthPct}%` }} />
              </div>
              <b>{count}</b>
            </div>
          );
        })}
      </div>
    </Panel>
  );
}
