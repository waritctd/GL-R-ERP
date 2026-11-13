import { useMemo } from 'react';
import { useQueries, useQuery } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { Button } from '../../components/common/Button.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { PageHeader } from '../../components/common/PageHeader.jsx';
import { PageStack, Panel } from '../../components/common/Layout.jsx';
import { Skeleton } from '../../components/common/Skeleton.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { bangkokMonthStartIso, bangkokTodayIso, formatMoney, formatThaiDate, greetingName } from '../../utils/format.js';
import { newestQuotationByRequest } from '../quotations/quotationMeta.js';
import { dealAmountLabel, defaultRange, isOrderedIn, sumAmount, weekPredicates } from '../tickets/movementRange.js';
import { followUpStatus, nextSalesAction, sortWorklist, SALES_ACTION } from '../tickets/salesActions.js';
import { offRoutePartiesFor, stageIndexIn, useStageCatalog } from '../tickets/stageCatalog.js';
import { Figure, failed } from './dashboardParts.jsx';

// useQueries' `combine`: each query's rows. Module-level so react-query keeps the combined array
// stable while the data is unchanged, and the worklist memo below holds.
const dataOfQueries = (results) => results.map((result) => result.data);

// The worklist's four groups, in reading order. `of` picks a group from the action key.
const GROUPS = [
  { key: 'follow', label: 'ติดตามลูกค้า', hint: 'ถึงกำหนดหรือเลยนัด', dot: 'bg-danger' },
  { key: 'next', label: 'ต้องทำต่อ', hint: 'ขั้นต่อไปเป็นของคุณ', dot: 'bg-warning' },
  { key: 'wait', label: 'รอผู้อื่น', hint: 'รอ ผจก.ขาย/CEO อนุมัติ', dot: 'bg-border-strong' },
  { key: 'quiet', label: 'ไม่มีความเคลื่อนไหว', hint: 'ยังไม่มีบันทึกล่าสุด', dot: 'bg-border-strong', muted: true },
];

// Which group a worklist row reads under. Every other action (a PCR to create, a quotation to
// issue, an order to confirm, …) is the rep's next step, however long the deal has been still.
function groupOf(action) {
  if (action.key === SALES_ACTION.FOLLOW_UP) return 'follow';
  if (action.key === SALES_ACTION.AWAIT_DIRECT_APPROVAL) return 'wait';
  if (action.key === SALES_ACTION.LOG_ACTIVITY) return 'quiet';
  return 'next';
}

function WorklistRow({ deal, action, followUp, muted, onClick }) {
  const amount = dealAmountLabel(deal);
  // DESIGN.md §15: tone says whose move it is. Every rep-actionable bucket (the slice-2 direct
  // SUBMIT/CONFIRM included) falls through to warning; AWAIT_DIRECT_APPROVAL is waiting on
  // ผจก.ขาย/CEO, so it reads neutral like the other not-mine-yet row.
  const badgeTone = action.followUp === 'overdue'
    ? 'danger'
    : action.key === SALES_ACTION.LOG_ACTIVITY || action.key === SALES_ACTION.AWAIT_DIRECT_APPROVAL
      ? 'neutral'
      : action.followUp === 'today'
        ? 'teal'
        : 'warning';
  return (
    <button
      type="button"
      data-testid={`sales-row-${deal.id}`}
      onClick={onClick}
      className={`flex min-h-11 w-full items-center justify-between gap-3 border-0 border-t border-border-subtle bg-transparent px-5 py-3 text-left text-text transition-colors hover:bg-surface-hover focus-visible:outline-none focus-visible:bg-surface-hover focus-visible:shadow-[inset_var(--shadow-focus-ring)] mobile:px-3.5 mobile:min-h-[44px] ${muted ? 'opacity-80' : ''}`}
    >
      <span className="flex min-w-0 flex-col gap-0.5">
        <strong className="min-w-0 truncate text-sm font-bold text-text">{deal.customerName || deal.title}</strong>
        <span className="min-w-0 truncate text-xs text-text-muted">
          {[deal.code, deal.projectName].filter(Boolean).join(' · ')}
        </span>
      </span>
      <span className="flex shrink-0 flex-wrap items-center justify-end gap-2">
        <span className="text-sm font-bold tabular-nums text-text">{amount}</span>
        <StatusBadge tone={badgeTone}>{action.label}</StatusBadge>
        {followUp === 'overdue' ? <StatusBadge tone="danger">เลยนัด {formatThaiDate(deal.nextFollowUpAt)}</StatusBadge> : null}
        {followUp === 'today' ? <StatusBadge tone="teal">นัดวันนี้</StatusBadge> : null}
        <Icon name="chevronRight" size={16} className="text-text-faint" />
      </span>
    </button>
  );
}

/**
 * Sales' personal-cockpit landing (role-scoped views, Sales branch): "what needs me today".
 * Top to bottom: a 7-day movement panel (four tiles linking to the filtered deal list), a money
 * band (ordered / expected value, this month's commission estimate), then one worklist grouped by whose move it is: ติดตามลูกค้า, ต้องทำต่อ, รอผู้อื่น,
 * ไม่มีความเคลื่อนไหว. Each row opens its deal and
 * carries the next action and, when due, the follow-up date. The full pipeline
 * (`รายการดีล`/TicketListPage) stays a separate nav item; this page never duplicates its
 * filters/table. Nothing is gated on both queries: each figure and the worklist load (or say
 * they failed) on their own. Own-scoped end to end: api.tickets.list/api.pricingRequests.queue
 * both already scope to `created_by` server-side for role === 'sales' (see
 * salesViewScope.js's header note on mock-vs-Java authority) — this component
 * adds no client-side ownership filtering of its own beyond `lifecycle === 'ACTIVE'`.
 */
export function SalesOverview({ user, employee }) {
  const navigate = useNavigate();
  const todayIso = bangkokTodayIso();
  const payrollMonth = bangkokMonthStartIso();

  // The default 7-day range (today-6..today, Bangkok): rows carry the server's stageChangedInRange /
  // activityInRange flags, which the movement tiles count.
  const range = useMemo(() => defaultRange(todayIso), [todayIso]);
  const ticketsQuery = useQuery({
    queryKey: queryKeys.ticketListByRange(range.from, range.to),
    queryFn: () => api.tickets.list({ movementFrom: range.from, movementTo: range.to }).then((response) => response.tickets ?? []),
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
  // Each deal's route (the served catalog.routes for its entryChannel), for the route choice.
  const { catalog: stageCatalog } = useStageCatalog();

  // Review V11: each quoted deal's quotation list (the deal page's own query, shared through the
  // cache), so the worklist reads approval waits and redos the way the deal page does. Only deals
  // with a request the list decides for; until a deal's list is read, it gets no route choice.
  const quotedDealIds = useMemo(() => [...new Set(pricingRequests
    .filter((pr) => pr.status === 'APPROVED_FOR_QUOTATION' || pr.status === 'QUOTATION_ISSUED')
    .map((pr) => Number(pr.ticketId)))], [pricingRequests]);
  const quotationRows = useQueries({
    queries: quotedDealIds.map((id) => ({
      queryKey: queryKeys.dealQuotationsByTicket(id),
      queryFn: () => api.dealQuotations.listForTicket(id).then((response) => response.items ?? []),
    })),
    combine: dataOfQueries,
  });

  // Old deals still waiting on the import/stock decision read จัดซื้อสินค้า, not ออกใบขอซื้อ (IR): ask the
  // server (the deal page's own query) for each ACTIVE, undecided-axis deal at/after ORDER_RECEIVED.
  const orderReceivedIdx = stageIndexIn(stageCatalog, 'ORDER_RECEIVED');
  const sourceDealIds = useMemo(() => orderReceivedIdx < 0 ? [] : activeDeals
    .filter((deal) => deal.fulfillmentStatus == null && stageIndexIn(stageCatalog, deal.salesStage) >= orderReceivedIdx)
    .map((deal) => Number(deal.id)), [activeDeals, stageCatalog, orderReceivedIdx]);
  const sourceRows = useQueries({
    queries: sourceDealIds.map((id) => ({
      queryKey: queryKeys.procurementSource(id),
      queryFn: () => api.tickets.getProcurementSource(id),
    })),
    combine: dataOfQueries,
  });

  const worklist = useMemo(() => {
    const sourceByDeal = new Map(sourceDealIds.map((id, index) => [id, sourceRows[index]]));
    const rowsByDeal = new Map(quotedDealIds.map((id, index) => [id, quotationRows[index]]));
    const items = activeDeals
      .map((deal) => {
        const rows = rowsByDeal.get(Number(deal.id));
        return {
          deal,
          action: nextSalesAction(deal, pricingRequests, {
            offRouteParties: rows ? offRoutePartiesFor(stageCatalog, deal.entryChannel) : null,
            quotationByRequest: rows ? newestQuotationByRequest(rows) : undefined,
            procurementSource: sourceByDeal.get(Number(deal.id)),
          }),
        };
      })
      .filter((item) => item.action != null);
    return sortWorklist(items);
  }, [activeDeals, pricingRequests, stageCatalog, quotedDealIds, quotationRows, sourceDealIds, sourceRows]);

  // Same definitions as the list page (movementRange), so a tile's number = the rows its link opens.
  const weekStats = useMemo(() => Object.fromEntries(
    Object.entries(weekPredicates).map(([key, matches]) => [key, deals.filter((deal) => matches(deal, range, todayIso)).length]),
  ), [deals, range, todayIso]);
  const { ordered, expected } = useMemo(() => {
    const isOrdered = isOrderedIn(stageCatalog);
    return {
      ordered: activeDeals.filter(isOrdered),
      expected: orderReceivedIdx >= 0 ? activeDeals.filter((deal) => !isOrdered(deal)) : [],
    };
  }, [activeDeals, stageCatalog, orderReceivedIdx]);

  const grouped = useMemo(() => {
    const byKey = Object.fromEntries(GROUPS.map((g) => [g.key, []]));
    worklist.forEach((item) => byKey[groupOf(item.action)].push(item));
    return byKey;
  }, [worklist]);

  const greetName = employee?.nickName || employee?.nameTh || user?.name || '';

  const summary = commissionSummaryQuery.data;
  const dash = (query, value) => (failed(query) ? '—' : value ?? <Skeleton width={96} height={22} className="mobile:ml-auto" />);
  const dealsFailed = failed(ticketsQuery) || failed(pricingRequestsQuery);

  return (
    <PageStack>
      <PageHeader
        title="ดีลของฉัน"
        subtitle={`สวัสดี, ${greetingName(greetName)} · ${formatThaiDate(todayIso)}`}
        actions={<Button type="button" variant="secondary" onClick={() => navigate('/tickets')}>ดูรายการดีลทั้งหมด</Button>}
      />

      <Panel
        title="7 วันล่าสุด"
        actions={<span className="text-xs text-text-muted">{formatThaiDate(range.from)} – {formatThaiDate(range.to)}</span>}
      >
        <div className="m-0 grid grid-cols-4 gap-x-6 nav-drawer:grid-cols-2 nav-drawer:gap-y-4 mobile:grid-cols-1 mobile:divide-y mobile:divide-border mobile:[&>div]:py-2.5">
          {[
            { key: 'moved', label: 'ดีลที่ขยับขั้น', caption: 'เปลี่ยนขั้นตอนใน 7 วัน' },
            { key: 'activity', label: 'ดีลที่มีกิจกรรม', caption: 'มีการติดตามใน 7 วัน' },
            { key: 'due', label: 'นัดติดตามถึงกำหนด', caption: weekStats.overdue > 0 ? <span className="font-bold text-danger">{weekStats.overdue} ดีลเลยนัดแล้ว</span> : 'ในช่วงนี้หรือเลยนัด' },
            { key: 'silent', label: 'ไม่เคลื่อนไหวในช่วงนี้', caption: 'ไม่ขยับขั้นและไม่มีการติดตามใน 7 วัน' },
          ].map((f) => (
            <button
              key={f.key}
              type="button"
              data-testid={`week-${f.key}`}
              onClick={() => navigate(`/tickets?week=${f.key}`)}
              className="block min-w-0 cursor-pointer rounded-md border-0 bg-transparent p-3 text-left hover:bg-surface-hover focus-visible:outline-none focus-visible:shadow-[inset_var(--shadow-focus-ring)] mobile:min-h-[44px]"
            >
              <dl className="m-0 grid gap-0.5 mobile:grid-cols-[1fr_auto] mobile:items-baseline mobile:gap-x-3">
                <Figure
                  label={f.label}
                  value={dash(ticketsQuery, ticketsQuery.data && `${weekStats[f.key]} ดีล`)}
                  caption={f.caption}
                  danger={f.key === 'silent' && weekStats.silent > 0}
                />
              </dl>
            </button>
          ))}
        </div>
      </Panel>

      <section className="rounded-md border border-border bg-surface p-5 mobile:p-3.5" aria-label="ตัวเลขเดือนนี้">
        <dl className="m-0 grid grid-cols-3 gap-x-6 nav-drawer:grid-cols-2 nav-drawer:gap-y-4 mobile:grid-cols-1 mobile:divide-y mobile:divide-border mobile:[&>div]:py-2.5">
          <Figure
            lead
            label="ยอดที่สั่งซื้อแล้ว"
            value={dash(ticketsQuery, ticketsQuery.data && formatMoney(sumAmount(ordered)))}
            caption={`${ordered.length} ดีล · ยังไม่ปิดงาน`}
          />
          <Figure
            label="ยอดคาดหวัง"
            value={dash(ticketsQuery, ticketsQuery.data && formatMoney(sumAmount(expected)))}
            caption={`${expected.length} ดีล · ยังไม่ได้รับใบสั่งซื้อ`}
          />
          <Figure
            label="ค่าคอมประมาณการ (เดือนนี้)"
            value={dash(commissionSummaryQuery, summary && formatMoney(summary.totalCommission ?? 0))}
            caption={(
              <>
                ฐานคิดค่าคอม {dash(commissionSummaryQuery, summary && formatMoney(summary.commissionableBase ?? 0))}{' · '}
                <Button type="button" variant="text" size="sm" onClick={() => navigate('/commissions')} className="mobile:min-h-[44px]">ดูรายละเอียดค่าคอม</Button>
              </>
            )}
          />
        </dl>
      </section>

      <Panel
        flush
        title="สิ่งที่ต้องทำ"
        actions={!loading && !dealsFailed ? (
          <span className="rounded-full bg-text px-2.5 py-px text-xs font-extrabold text-surface">
            <span data-testid="worklist-total" aria-hidden="true">{worklist.length}</span>
            <span className="sr-only">รวม {worklist.length} ดีล</span>
          </span>
        ) : null}
      >
        {dealsFailed ? (
          <p className="m-0 px-5 py-4 text-sm font-bold text-danger mobile:px-3.5">โหลดไม่สำเร็จ</p>
        ) : loading ? (
          <div className="grid gap-2 px-5 py-4 mobile:px-3.5" role="status" aria-busy="true" aria-label="กำลังโหลดงานที่ต้องทำ">
            <Skeleton height={36} />
            <Skeleton height={36} />
            <Skeleton height={36} />
          </div>
        ) : worklist.length === 0 ? (
          <div className="flex items-center gap-3 px-5 py-4 text-text-muted mobile:px-3.5">
            <Icon name="check" size={18} />
            <span className="text-sm font-bold">ไม่มีสิ่งที่ต้องดำเนินการตอนนี้</span>
          </div>
        ) : (
          GROUPS.filter((g) => grouped[g.key].length > 0).map((g) => {
            const items = grouped[g.key];
            return (
              <div key={g.key} data-testid={`sales-group-${g.key}`}>
                <div className="flex flex-wrap items-center gap-2 px-5 py-2.5 mobile:px-3.5">
                  <span aria-hidden="true" className={`size-2 rounded-full ${g.dot}`} />
                  <span className={`text-sm font-extrabold ${g.muted ? 'text-text-muted' : 'text-text'}`}>{g.label}</span>
                  <span className="text-xs tabular-nums text-text-muted">{items.length} ดีล</span>
                  <span className="text-xs text-text-faint">· {g.hint}</span>
                </div>
                {items.map(({ deal, action }) => (
                  <WorklistRow
                    key={deal.id}
                    deal={deal}
                    action={action}
                    followUp={followUpStatus(deal, todayIso)}
                    muted={g.muted}
                    onClick={() => navigate(`/tickets/${deal.id}`)}
                  />
                ))}
              </div>
            );
          })
        )}
      </Panel>
    </PageStack>
  );
}
