import { useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { Button } from '../../components/common/Button.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { Panel } from '../../components/common/Layout.jsx';
import { QuotaBar } from '../../components/common/QuotaBar.jsx';
import { SkeletonCard } from '../../components/common/Skeleton.jsx';
import { StatePanel } from '../../components/common/StatePanel.jsx';
import { cn } from '../../utils/cn.js';
import { formatDays } from './leaveFormatting.js';
import { EVERYDAY_LEAVE_TYPE_CODES } from './MyLeaveTab.jsx';

// Manager team-quota summary (2026-09) -- owner ask: "there is no summary of like how much each employee have
// left". Nothing here re-checks or narrows that scope; whatever the API returns is exactly what this component
// renders.

// Use fluid columns to avoid overlapping tablet and Tailwind breakpoint rules.
const CARD_GRID_CLASSNAME = 'grid grid-cols-[repeat(auto-fill,minmax(272px,1fr))] gap-3';

const TONE_CLASSES = {
  success: 'border-success-border bg-success-bg text-success-dark',
  warning: 'border-warning-border bg-warning-bg text-warning-dark',
  danger: 'border-danger-border bg-danger-bg text-danger',
};

// A manager scanning this is asking "can this person afford the leave they're about to request" -- so the
// chip's own color is the answer, not just the number.
function remainingTone(balance) {
  const remaining = Number(balance.remainingDays) || 0;
  const quota = Number(balance.annualQuotaDays) || 0;
  if (remaining <= 0) return 'danger';
  if (quota > 0 && remaining <= Math.max(1, quota * 0.25)) return 'warning';
  return 'success';
}

function RemainingChip({ balance }) {
  const tone = remainingTone(balance);
  return (
    <span
      className={cn(
        'inline-flex min-w-0 flex-col items-start gap-0.5 rounded-md border px-2.5 py-1.5',
        TONE_CLASSES[tone],
      )}
    >
      <span className="max-w-full truncate text-[11px] font-bold uppercase tracking-wide opacity-80">
        {balance.leaveTypeNameTh}
      </span>
      <span className="text-sm font-extrabold tabular-nums">{formatDays(balance.remainingDays)}</span>
    </span>
  );
}

function TeamMemberQuotaCard({ member }) {
  const [expanded, setExpanded] = useState(false);
  const everydayBalances = useMemo(
    () => member.balances.filter((balance) => EVERYDAY_LEAVE_TYPE_CODES.has(balance.leaveTypeCode)),
    [member.balances],
  );
  // Rare types tucked behind an expand toggle rather than shown as chips up front. A QuotaBar renders nothing
  // when cap == null, so this list still degrades safely if a future rare type ever ships with no quota at all.
  const otherBalances = useMemo(
    () => member.balances.filter((balance) => !EVERYDAY_LEAVE_TYPE_CODES.has(balance.leaveTypeCode)),
    [member.balances],
  );
  const detailId = `team-quota-detail-${member.employeeId}`;

  return (
    <div className="flex min-w-0 flex-col gap-3 rounded-md border border-border bg-surface p-4">
      <div className="min-w-0">
        <p className="m-0 truncate text-sm font-extrabold text-text">{member.employeeName}</p>
        <p className="m-0 truncate text-xs text-text-muted">
          {member.employeeCode}
          {member.departmentName ? ` · ${member.departmentName}` : ''}
        </p>
      </div>
      {everydayBalances.length > 0 ? (
        <div className="flex flex-wrap gap-2">
          {everydayBalances.map((balance) => (
            <RemainingChip key={balance.leaveTypeCode} balance={balance} />
          ))}
        </div>
      ) : null}
      {otherBalances.length > 0 ? (
        <div>
          <Button
            type="button"
            variant="text"
            className="min-h-0 gap-1 self-start px-0 py-0 text-xs font-bold text-text-muted"
            aria-expanded={expanded}
            aria-controls={detailId}
            onClick={() => setExpanded((current) => !current)}
          >
            <Icon name={expanded ? 'chevronUp' : 'chevronDown'} size={13} />
            {expanded ? 'ซ่อนวันลาประเภทอื่น' : `ดูวันลาประเภทอื่น (${otherBalances.length})`}
          </Button>
          {expanded ? (
            <div id={detailId} className="mt-2 grid gap-2">
              {otherBalances.map((balance) => (
                <QuotaBar
                  key={balance.leaveTypeCode}
                  label={balance.leaveTypeNameTh}
                  used={Number(balance.approvedDays || 0) + Number(balance.pendingDays || 0)}
                  cap={balance.annualQuotaDays}
                  formatValue={formatDays}
                />
              ))}
            </div>
          ) : null}
        </div>
      ) : null}
    </div>
  );
}

function TeamQuotaSummarySkeleton() {
  return (
    <div className={CARD_GRID_CLASSNAME} aria-busy="true" aria-label="กำลังโหลดโควตาวันลาของทีม">
      {Array.from({ length: 3 }, (_, index) => (
        <SkeletonCard key={index} lines={2} />
      ))}
    </div>
  );
}

/** "ลูกทีม" tab quota summary -- each direct report's remaining leave, by type, in one scannable card grid. */
export function TeamQuotaSummary({
  title,
  emptyDescription = 'เมื่อมีพนักงานที่รายงานตรงต่อคุณ โควตาวันลาคงเหลือของแต่ละคนจะแสดงที่นี่',
}) {
  const query = useQuery({
    queryKey: queryKeys.leaveTeamBalances(),
    queryFn: () => api.leave.teamBalances().then((response) => response.team || []),
  });
  const team = query.data ?? [];

  let body;
  if (query.isPending) {
    body = <TeamQuotaSummarySkeleton />;
  } else if (query.isError) {
    body = (
      <StatePanel
        state="error"
        description={query.error?.message || 'โหลดโควตาวันลาของทีมไม่สำเร็จ'}
        action={<Button type="button" variant="secondary" onClick={() => query.refetch()}>ลองอีกครั้ง</Button>}
      />
    );
  } else if (team.length === 0) {
    body = (
      <StatePanel state="empty" title="ยังไม่มีลูกทีม" description={emptyDescription} />
    );
  } else {
    body = (
      <div className={CARD_GRID_CLASSNAME}>
        {team.map((member) => (
          <TeamMemberQuotaCard key={member.employeeId} member={member} />
        ))}
      </div>
    );
  }

  return <Panel title={title}>{body}</Panel>;
}
