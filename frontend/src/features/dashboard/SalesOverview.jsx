import { useMemo } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { Button } from '../../components/common/Button.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { PageHeader } from '../../components/common/PageHeader.jsx';
import { PageStack, Panel, StatGrid } from '../../components/common/Layout.jsx';
import { StatCard } from '../../components/common/StatCard.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { bangkokMonthStartIso, bangkokTodayIso, formatMoney, formatThaiDate, greetingName } from '../../utils/format.js';
import { followUpStatus, nextSalesAction, sortWorklist, SALES_ACTION } from '../tickets/salesActions.js';

const FOLLOW_UP_LIST_LIMIT = 6;

function WorklistRow({ deal, action, onClick }) {
  const badgeTone = action.followUp === 'overdue'
    ? 'danger'
    : action.key === SALES_ACTION.LOG_ACTIVITY
      ? 'neutral'
      : action.followUp === 'today'
        ? 'teal'
        : 'warning';
  return (
    <button
      type="button"
      onClick={onClick}
      className="flex min-h-11 w-full items-center justify-between gap-3 border-b border-border-subtle py-3 text-left last:border-b-0 transition-colors hover:bg-surface-hover focus-visible:outline-none focus-visible:bg-surface-hover focus-visible:shadow-[inset_var(--shadow-focus-ring)]"
    >
      <span className="flex min-w-0 flex-col gap-0.5">
        <strong className="min-w-0 truncate text-sm font-bold text-text">{deal.customerName || deal.title}</strong>
        <span className="min-w-0 truncate text-xs text-text-muted">
          {[deal.code, deal.projectName].filter(Boolean).join(' · ')}
        </span>
      </span>
      <span className="flex shrink-0 items-center gap-2">
        <StatusBadge tone={badgeTone}>{action.label}</StatusBadge>
        <Icon name="chevronRight" size={16} className="text-text-faint" />
      </span>
    </button>
  );
}

/**
 * Sales' personal-cockpit landing (role-scoped views, Sales branch): "what
 * needs me today" — pulse counts, a next-action worklist, this month's
 * commission estimate, and follow-ups coming due. The full pipeline
 * (`รายการดีล`/TicketListPage) stays a separate nav item; this page never
 * duplicates its filters/table, only surfaces what needs the rep's attention
 * right now. Own-scoped end to end: api.tickets.list/api.pricingRequests.queue
 * both already scope to `created_by` server-side for role === 'sales' (see
 * salesViewScope.js's header note on mock-vs-Java authority) — this component
 * adds no client-side ownership filtering of its own beyond `lifecycle === 'ACTIVE'`.
 */
export function SalesOverview({ user, employee }) {
  const navigate = useNavigate();
  const todayIso = bangkokTodayIso();
  const payrollMonth = bangkokMonthStartIso();

  const ticketsQuery = useQuery({
    queryKey: queryKeys.ticketList(''),
    queryFn: () => api.tickets.list({}).then((response) => response.tickets ?? []),
  });
  const pricingRequestsQuery = useQuery({
    queryKey: queryKeys.pricingRequestQueue({}),
    queryFn: () => api.pricingRequests.queue({}).then((response) => response.items ?? []),
  });
  // Server-computed monthly commission estimate (CommissionService#monthlySummary) — never
  // re-derived client-side, so this can never drift from a DB tier-config change the way the
  // former client-side re-implementation could (see CLAUDE.md's V81 note). Also lighter than the
  // former api.commissions.list() call it replaces: the panel needs only two totals, not every
  // record for the month.
  const commissionSummaryQuery = useQuery({
    queryKey: ['commissions', 'monthly-summary', payrollMonth],
    queryFn: () => api.commissions.monthlySummary({ payrollMonth }).then((response) => response.summary),
  });

  // useMemo (not a bare `?? []`) so the fallback empty array is referentially
  // stable across renders while a query is still loading — otherwise every
  // render would hand the useMemo hooks below a "new" array and defeat their
  // memoization (same convention as TicketListPage's `allDeals`).
  const deals = useMemo(() => ticketsQuery.data ?? [], [ticketsQuery.data]);
  const pricingRequests = useMemo(() => pricingRequestsQuery.data ?? [], [pricingRequestsQuery.data]);
  const loading = ticketsQuery.isLoading || pricingRequestsQuery.isLoading;

  const activeDeals = useMemo(() => deals.filter((deal) => deal.lifecycle === 'ACTIVE'), [deals]);

  const worklist = useMemo(() => {
    const items = activeDeals
      .map((deal) => ({ deal, action: nextSalesAction(deal, pricingRequests) }))
      .filter((item) => item.action != null);
    return sortWorklist(items);
  }, [activeDeals, pricingRequests]);

  const pulse = useMemo(() => {
    let overdueFollowUp = 0;
    let followUpToday = 0;
    for (const deal of activeDeals) {
      const status = followUpStatus(deal, todayIso);
      if (status === 'overdue') overdueFollowUp += 1;
      else if (status === 'today') followUpToday += 1;
    }
    const pendingQuotation = pricingRequests.filter((pr) => pr.status === 'APPROVED_FOR_QUOTATION').length;
    const pipelineValue = activeDeals.reduce((sum, deal) => sum + (Number(deal.amountPayable) || 0), 0);
    return { overdueFollowUp, followUpToday, pendingQuotation, pipelineValue };
  }, [activeDeals, pricingRequests, todayIso]);

  const followUpsDue = useMemo(() => activeDeals
    .filter((deal) => followUpStatus(deal, todayIso) != null)
    .sort((a, b) => new Date(a.nextFollowUpAt) - new Date(b.nextFollowUpAt))
    .slice(0, FOLLOW_UP_LIST_LIMIT), [activeDeals, todayIso]);

  const commission = useMemo(() => ({
    base: commissionSummaryQuery.data?.commissionableBase ?? 0,
    estimate: commissionSummaryQuery.data?.totalCommission ?? 0,
  }), [commissionSummaryQuery.data]);

  const greetName = employee?.nickName || employee?.nameTh || user?.name || '';

  return (
    <PageStack>
      <PageHeader
        title={`สวัสดี ${greetingName(greetName)}`}
        subtitle={`ดีลของฉัน · ${formatThaiDate(todayIso)} — งานและลูกค้าที่ต้องติดตามวันนี้`}
        actions={(
          <Button type="button" variant="text" onClick={() => navigate('/tickets')}>
            ดูรายการดีลทั้งหมด
            <Icon name="chevronRight" size={14} />
          </Button>
        )}
      />

      <StatGrid>
        <StatCard
          icon="triangleAlert"
          label="เกินกำหนดติดตาม"
          value={pulse.overdueFollowUp}
          helper="Overdue follow-up"
          tone="rose"
          onClick={() => navigate('/tickets')}
        />
        <StatCard
          icon="clock"
          label="ติดตามวันนี้"
          value={pulse.followUpToday}
          helper="Due today"
          tone="teal"
          onClick={() => navigate('/tickets')}
        />
        <StatCard
          icon="fileText"
          label="รอออกใบเสนอราคา"
          value={pulse.pendingQuotation}
          helper="Ready to quote"
          tone="amber"
          onClick={() => navigate('/tickets')}
        />
        <StatCard
          icon="badgeDollar"
          label="มูลค่า pipeline"
          value={formatMoney(pulse.pipelineValue)}
          helper="Open deals"
          tone="indigo"
        />
      </StatGrid>

      <div className="grid grid-cols-[1.15fr_0.85fr] items-start gap-[18px] nav-drawer:grid-cols-1">
        <Panel title="สิ่งที่ต้องทำ">
          {loading ? (
            <p className="m-0 text-sm text-text-muted">กำลังโหลดงานที่ต้องทำ…</p>
          ) : worklist.length === 0 ? (
            <div className="grid min-h-[220px] place-items-center content-center gap-2 text-center text-text-muted">ไม่มีสิ่งที่ต้องดำเนินการตอนนี้</div>
          ) : (
            <div className="flex flex-col">
              {worklist.map(({ deal, action }) => (
                <WorklistRow key={deal.id} deal={deal} action={action} onClick={() => navigate(`/tickets/${deal.id}`)} />
              ))}
            </div>
          )}
        </Panel>

        <div className="grid gap-[18px]">
          <Panel
            title="ค่าคอมเดือนนี้"
            actions={(
              <Button type="button" variant="text" onClick={() => navigate('/commissions')}>ดูรายละเอียด</Button>
            )}
          >
            <dl className="m-0 grid gap-2">
              <div className="flex items-baseline justify-between gap-3">
                <dt className="!text-xs !text-text-muted">ค่าคอมประมาณการ</dt>
                <dd className="m-0 text-xl font-extrabold text-text">{formatMoney(commission.estimate)}</dd>
              </div>
              <div className="flex items-baseline justify-between gap-3 border-t border-border-subtle pt-2">
                <dt className="!text-xs !text-text-muted">ฐานคิดค่าคอม</dt>
                <dd className="m-0 text-sm font-bold text-text-secondary">{formatMoney(commission.base)}</dd>
              </div>
            </dl>
          </Panel>

          <Panel title="ติดตามที่ครบกำหนด">
            {followUpsDue.length === 0 ? (
              <p className="m-0 text-sm text-text-muted">ไม่มีนัดติดตามที่ครบกำหนด</p>
            ) : (
              <div className="flex flex-col">
                {followUpsDue.map((deal) => (
                  <button
                    key={deal.id}
                    type="button"
                    onClick={() => navigate(`/tickets/${deal.id}`)}
                    className="flex min-h-11 items-center justify-between gap-2 border-b border-border-subtle py-2 text-left last:border-b-0 transition-colors hover:bg-surface-hover focus-visible:outline-none focus-visible:bg-surface-hover focus-visible:shadow-[inset_var(--shadow-focus-ring)]"
                  >
                    <span className="min-w-0 truncate text-sm text-text">{deal.customerName || deal.title}</span>
                    <StatusBadge tone={followUpStatus(deal, todayIso) === 'overdue' ? 'danger' : 'teal'}>
                      {formatThaiDate(deal.nextFollowUpAt)}
                    </StatusBadge>
                  </button>
                ))}
              </div>
            )}
          </Panel>
        </div>
      </div>
    </PageStack>
  );
}
