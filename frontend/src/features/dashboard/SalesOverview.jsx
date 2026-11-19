import { useMemo } from 'react';
import { useQueries, useQuery } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { Button } from '../../components/common/Button.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { Panel } from '../../components/common/Layout.jsx';
import { MyWork } from '../salesBoard/MyWork.jsx';
import { Skeleton } from '../../components/common/Skeleton.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { bangkokMonthStartIso, bangkokTodayIso, formatMoney, formatThaiDate, greetingName } from '../../utils/format.js';
import { newestQuotationByRequest } from '../quotations/quotationMeta.js';
import { dealAmountLabel, defaultRange, isOrderedIn, sumAmount } from '../tickets/movementRange.js';
import { followUpStatus, nextSalesAction, sortWorklist, SALES_ACTION } from '../tickets/salesActions.js';
import { offRoutePartiesFor, stageIndexIn, useStageCatalog } from '../tickets/stageCatalog.js';
import { Figure, failed } from './dashboardParts.jsx';

// useQueries' `combine`: each query's rows. Module-level so react-query keeps the combined array
// stable while the data is unchanged, and the worklist memo below holds.
const dataOfQueries = (results) => results.map((result) => result.data);

// The worklist's four groups, in reading order. `of` picks a group from the action key.
const GROUPS = [
  { key: 'follow', label: 'ถึงกำหนดตาม', hint: 'ถึงกำหนดหรือเลยนัด', dot: 'bg-danger' },
  { key: 'next', label: 'ต้องทำต่อ', hint: 'ขั้นต่อไปเป็นของคุณ', dot: 'bg-warning' },
  { key: 'wait', label: 'รอผู้อื่น', hint: 'รอ ผจก.ขาย/CEO อนุมัติ', dot: 'bg-border-strong' },
];

// Which group a worklist row reads under. Every other action (a PCR to create, a quotation to
// issue, an order to confirm, …) is the rep's next step, however long the deal has been still.
function groupOf(action) {
  if (action.key === SALES_ACTION.FOLLOW_UP) return 'follow';
  if (action.key === SALES_ACTION.AWAIT_DIRECT_APPROVAL) return 'wait';
  // A quiet deal with nothing else to do is ควรตามต่อ's job (the 14-day QuietRule), not a worklist row.
  if (action.key === SALES_ACTION.LOG_ACTIVITY) return null;
  return 'next';
}

function WorklistRow({ deal, action, followUp, muted, onClick }) {
  const amount = dealAmountLabel(deal);
  // DESIGN.md §15: tone says whose move it is. Every rep-actionable bucket (the slice-2 direct SUBMIT/CONFIRM
  // included) falls through to warning; AWAIT_DIRECT_APPROVAL is waiting on ผจก.ขาย/CEO, so it reads neutral
  // like the other not-mine-yet row.
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
 * Sales' landing: งานของฉัน (spec 2026-11-15 §7.3, slice 1). MyWork owns the page header, ควรตามต่อ and the board;
 * this component puts back, above them, what the rep's landing already did (review H3): the compact money band
 * (ordered / expected value and this month's server-computed commission estimate — slice 3 replaces the band) and
 * the next-action worklist — ถึงกำหนดตาม (follow-up due today or overdue, quiet or not), ต้องทำต่อ, รอผู้อื่น.
 * A deal whose only action is "log an activity" is not listed here: ควรตามต่อ (the 14-day QuietRule) covers it.
 * Own-scoped end to end: api.tickets.list / api.pricingRequests.queue / api.salesBoard.myWork scope to the rep
 * server-side; this component adds no client-side ownership filtering beyond `lifecycle === 'ACTIVE'`.
 */
export function SalesOverview({ user, employee, showToast }) {
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
  // The headline figures count one row per project (server-side fold); the to-do worklist below keeps every ORDER, so an
  // add-on's own next action shows.
  const projectTicketsQuery = useQuery({
    queryKey: queryKeys.ticketListByRange(range.from, range.to, 'project'),
    queryFn: () => api.tickets.list({ movementFrom: range.from, movementTo: range.to, fold: 'project' }).then((response) => response.tickets ?? []),
  });
  const pricingRequestsQuery = useQuery({
    queryKey: queryKeys.pricingRequestQueue({}),
    queryFn: () => api.pricingRequests.queue({}).then((response) => response.items ?? []),
  });
  // Server-computed monthly commission estimate (CommissionService#monthlySummary) — never re-derived
  // client-side, so this can never drift from a DB tier-config change the way the former client-side
  // re-implementation could (see CLAUDE.md's V81 note).
  const commissionSummaryQuery = useQuery({
    queryKey: ['commissions', 'monthly-summary', payrollMonth],
    queryFn: () => api.commissions.monthlySummary({ payrollMonth }).then((response) => response.summary),
  });

  // useMemo (not a bare `?? []`) so the fallback empty array is referentially stable across renders while a
  // query is still loading — otherwise every render would hand the useMemo hooks below a "new" array and defeat
  // their memoization (same convention as TicketListPage's `allDeals`).
  const deals = useMemo(() => ticketsQuery.data ?? [], [ticketsQuery.data]);
  const projectDeals = useMemo(() => projectTicketsQuery.data ?? [], [projectTicketsQuery.data]);
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
  const catalogReady = orderReceivedIdx >= 0; // before it loads nothing can be classed ordered / expected
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
      .filter((item) => item.action != null && groupOf(item.action) != null);
    return sortWorklist(items);
  }, [activeDeals, pricingRequests, stageCatalog, quotedDealIds, quotationRows, sourceDealIds, sourceRows]);

  // COUNTS (งาน) come from the folded project rows, so they match the list they link to; MONEY is classified per ORDER by
  // that order's own stage (a folded row carries the lead order's stage but the sum of every open order's value).
  const { ordered, expected, orderedMoney, expectedMoney } = useMemo(() => {
    const isOrdered = isOrderedIn(stageCatalog);
    const activeProjects = projectDeals.filter((deal) => deal.lifecycle === 'ACTIVE');
    return {
      ordered: activeProjects.filter(isOrdered),
      expected: orderReceivedIdx >= 0 ? activeProjects.filter((deal) => !isOrdered(deal)) : [],
      orderedMoney: sumAmount(activeDeals.filter(isOrdered)),
      expectedMoney: orderReceivedIdx >= 0 ? sumAmount(activeDeals.filter((deal) => !isOrdered(deal))) : 0,
    };
  }, [projectDeals, activeDeals, stageCatalog, orderReceivedIdx]);

  const grouped = useMemo(() => {
    const byKey = Object.fromEntries(GROUPS.map((g) => [g.key, []]));
    worklist.forEach((item) => {
      const group = groupOf(item.action);
      if (group) byKey[group].push(item);
    });
    return byKey;
  }, [worklist]);

  const greetName = employee?.nickName || employee?.nameTh || user?.name || '';

  const summary = commissionSummaryQuery.data;
  const dash = (query, value) => (failed(query) ? '—' : value ?? <Skeleton width={96} height={22} className="mobile:ml-auto" />);
  const dealsFailed = failed(ticketsQuery) || failed(pricingRequestsQuery);

  return (
    <MyWork
      user={user}
      showToast={showToast}
      subtitle={`สวัสดี, ${greetingName(greetName)} · ${formatThaiDate(todayIso)}`}
      before={(
        <>
          <section className="rounded-md border border-border bg-surface p-5 mobile:p-3.5" aria-label="ตัวเลขเดือนนี้">
            <dl className="m-0 grid grid-cols-3 gap-x-6 nav-drawer:grid-cols-2 nav-drawer:gap-y-4 mobile:grid-cols-1 mobile:divide-y mobile:divide-border mobile:[&>div]:py-2.5">
              <Figure
                lead
                label="ยอดที่สั่งซื้อแล้ว"
                value={dash(ticketsQuery, ticketsQuery.data && (catalogReady ? formatMoney(orderedMoney) : '—'))}
                caption={`${ordered.length} งาน · ยังไม่ปิดงาน`}
              />
              <Figure
                label="ยอดคาดหวัง"
                value={dash(ticketsQuery, ticketsQuery.data && (catalogReady ? formatMoney(expectedMoney) : '—'))}
                caption={`${expected.length} งาน · ยังไม่ได้รับใบสั่งซื้อ`}
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
                <span className="sr-only">รวม {worklist.length} งาน</span>
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
                      <span className="text-xs tabular-nums text-text-muted">{items.length} งาน</span>
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
        </>
      )}
    />
  );
}
