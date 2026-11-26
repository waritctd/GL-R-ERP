import { useMemo } from 'react';
import { useQueries, useQuery } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { Button } from '../../components/common/Button.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { PageHeader } from '../../components/common/PageHeader.jsx';
import { Panel } from '../../components/common/Layout.jsx';
import { Skeleton } from '../../components/common/Skeleton.jsx';
import { addDaysIso, bangkokMonthStartIso, formatMoney } from '../../utils/format.js';
import { MyWork } from '../salesBoard/MyWork.jsx';
import { bangkokNowHm, carryOverRange, dayLineLabel, weekStartIso } from '../salesBoard/visitWeek.js';
import { newestQuotationByRequest } from '../quotations/quotationMeta.js';
import { defaultRange } from '../tickets/movementRange.js';
import { nextSalesAction, sortWorklist } from '../tickets/salesActions.js';
import { offRoutePartiesFor, stageIndexIn, useStageCatalog } from '../tickets/stageCatalog.js';
import { Figure, failed } from './dashboardParts.jsx';
import { InsightLanding } from './InsightSwitch.jsx';
import { buildTodayRows, buildWaitingRows } from './repToday.js';
import { ReportBlock, SavedToday, TodayVisits, TodayWorklist, WaitingOthers } from './repTodayParts.jsx';

// useQueries' `combine`: each query's rows. Module-level so react-query keeps the combined array
// stable while the data is unchanged, and the worklist memo below holds.
const dataOfQueries = (results) => results.map((result) => result.data);
const asList = (data) => (Array.isArray(data) ? data : data?.visits ?? []);

/**
 * Sales' landing: งานวันนี้ (spec 0003 slice 3, boards TD1 / TD2). MyWork hosts every flow the page's buttons open
 * (call log, sample sent, visit tick / plan / reschedule, resume, new deal); this page is the body: how complete the
 * rep's weekly report data is, รายการที่ต้องอัปเดต grouped by what blocks the report, today's visits, what is already
 * saved today and what waits on other teams. The worklist is nextSalesAction / sortWorklist, unchanged. Own-scoped end
 * to end: every query is scoped to the rep server-side.
 */
export function SalesOverview(props) {
  return (
    <InsightLanding
      title="งานวันนี้"
      user={props.user}
      showToast={props.showToast}
      insight={false}
      renderToday={(switcher) => (
        <MyWork user={props.user} showToast={props.showToast}>
          {(ctx) => <SalesToday {...props} switcher={switcher} ctx={ctx} />}
        </MyWork>
      )}
    />
  );
}

function SalesToday({ switcher, ctx }) {
  const navigate = useNavigate();
  const { work, todayIso } = ctx;
  const payrollMonth = bangkokMonthStartIso();
  const thisMonday = weekStartIso(todayIso);

  // The default 7-day range (today-6..today, Bangkok): rows carry the server's stageChangedInRange /
  // activityInRange flags. Every ORDER is kept, so an add-on's own next action shows.
  const range = useMemo(() => defaultRange(todayIso), [todayIso]);
  const ticketsQuery = useQuery({
    queryKey: queryKeys.ticketListByRange(range.from, range.to),
    queryFn: () => api.tickets.list({ movementFrom: range.from, movementTo: range.to }).then((response) => response.tickets ?? []),
  });
  const pricingRequestsQuery = useQuery({
    queryKey: queryKeys.pricingRequestQueue({}),
    queryFn: () => api.pricingRequests.queue({}).then((response) => response.items ?? []),
  });
  // Server-computed monthly commission estimate (CommissionService#monthlySummary), never re-derived client-side.
  const commissionSummaryQuery = useQuery({
    queryKey: ['commissions', 'monthly-summary', payrollMonth],
    queryFn: () => api.commissions.monthlySummary({ payrollMonth }).then((response) => response.summary),
  });
  const reportQuery = useQuery({
    queryKey: queryKeys.salesBoardWeeklyReport(thisMonday, null),
    queryFn: () => api.salesBoard.weeklyReport({ week: thisMonday, ownerId: undefined }),
  });
  // Visits still PLANNED from earlier weeks; this week's come with my-work.
  const { from: carryFrom, to: carryTo } = carryOverRange(thisMonday);
  const carriedQuery = useQuery({
    queryKey: queryKeys.salesVisitsOwn(carryFrom, carryTo),
    queryFn: () => api.salesVisits.list({ from: carryFrom, to: carryTo }).then(asList),
  });
  const tomorrow = addDaysIso(todayIso, 1);
  const tomorrowQuery = useQuery({
    queryKey: queryKeys.salesVisitsOwn(tomorrow, tomorrow),
    queryFn: () => api.salesVisits.list({ from: tomorrow, to: tomorrow }).then(asList),
  });

  const deals = useMemo(() => ticketsQuery.data ?? [], [ticketsQuery.data]);
  const pricingRequests = useMemo(() => pricingRequestsQuery.data ?? [], [pricingRequestsQuery.data]);
  const loading = ticketsQuery.isLoading || pricingRequestsQuery.isLoading;
  const activeDeals = useMemo(() => deals.filter((deal) => deal.lifecycle === 'ACTIVE'), [deals]);
  const { catalog: stageCatalog } = useStageCatalog();

  // Each quoted deal's quotation list (the deal page's own query, shared through the cache), so the worklist reads
  // approval waits and redos the way the deal page does.
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

  // Old deals still waiting on the import/stock decision read จัดซื้อสินค้า, not ออกใบขอซื้อ (IR).
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

  const report = reportQuery.data;
  const rep = report?.reps?.[0] ?? null;
  const orders = useMemo(() => report?.orders ?? [], [report]);
  const planned = useMemo(() => {
    const byId = new Map([...(carriedQuery.data ?? []), ...(work?.visitsThisWeek ?? [])].map((v) => [v.id, v]));
    return [...byId.values()].filter((v) => v.status === 'PLANNED');
  }, [carriedQuery.data, work]);
  const returning = useMemo(() => (work?.followUp ?? []).filter((d) => d.returning), [work]);

  const rows = useMemo(() => buildTodayRows({ worklist, orders, visits: planned, returning, todayIso, nowHm: bangkokNowHm() }),
    [worklist, orders, planned, returning, todayIso]);
  const waiting = useMemo(() => buildWaitingRows({
    worklist, pricingRequests, activeIds: new Set(activeDeals.map((d) => Number(d.id))), todayIso,
  }), [worklist, pricingRequests, activeDeals, todayIso]);

  const todaysVisits = (work?.visitsThisWeek ?? []).filter((v) => v.plannedDate === todayIso && v.status !== 'CANCELLED');
  const dueVisits = planned.filter((v) => rows.some((r) => r.key === `visit-${v.id}`));
  const undated = orders.filter((o) => !o.nextPayment && o.outstanding > 0);
  const summary = commissionSummaryQuery.data;
  const dash = (query, value) => (failed(query) ? '—' : value ?? <Skeleton width={96} height={22} className="mobile:ml-auto" />);
  const dealsFailed = failed(ticketsQuery) || failed(pricingRequestsQuery);

  function act(a) {
    if (a.type === 'go') navigate(a.to);
    else if (a.type === 'call') ctx.openCall(a.deal);
    else if (a.type === 'visit') ctx.tickVisit(a.visit);
    else if (a.type === 'resume') ctx.resume(a.deal);
  }

  const dateLine = `${dayLineLabel(todayIso)} ${Number(todayIso.slice(0, 4)) + 543}`;
  const summaryLine = `${dateLine} · นัดหมาย ${todaysVisits.length} นัด · ต้องอัปเดต ${rows.length} รายการ · รอฝ่ายอื่น ${waiting.length} รายการ`;

  return (
    <>
      <PageHeader
        title="งานวันนี้"
        subtitle={loading ? dateLine : summaryLine}
        actions={(
          <>
            {switcher}
            <Button type="button" variant="secondary" onClick={ctx.openSample}>ส่งตัวอย่าง</Button>
            <Button type="button" variant="secondary" className="mobile:hidden" onClick={ctx.openCallPicker}>บันทึกการโทร</Button>
            <Button type="button" variant="secondary" className="mobile:hidden" onClick={ctx.openCreate}>
              <Icon name="plus" size={16} />
              งานใหม่
            </Button>
            <Button type="button" data-testid="log-visit-header" className="mobile:hidden" onClick={ctx.openLog}>
              <Icon name="calendar" size={16} />
              บันทึกเข้าพบ
            </Button>
          </>
        )}
      />

      <div className="grid grid-cols-[minmax(0,1.7fr)_minmax(0,1fr)] items-start gap-[18px] nav-drawer:grid-cols-1">
        {/* Below the nav-drawer breakpoint the two columns dissolve and `order` restores the phone board's sequence. */}
        <div className="grid min-w-0 content-start gap-[18px] nav-drawer:contents">
          <Panel title="รายงานสัปดาห์ของคุณ" className="nav-drawer:order-1" actions={<span className="text-xs text-text-muted">จัดทำจากข้อมูลที่บันทึกในพอร์ทัล</span>}>
            {failed(reportQuery) ? <p className="m-0 text-sm font-bold text-danger">โหลดไม่สำเร็จ</p> : !rep ? <Skeleton height={140} /> : (
              <div className="grid gap-4">
                <ReportBlock rep={rep} />
                <dl className="m-0 grid grid-cols-3 gap-x-6 border-t border-border-subtle pt-3 mobile:grid-cols-1 mobile:divide-y mobile:divide-border mobile:[&>div]:py-2.5">
                  <Figure
                    label="ยังไม่นับในประมาณการ"
                    value={formatMoney(undated.reduce((t, o) => t + o.outstanding, 0))}
                    caption={`${undated.length} งานยังไม่ระบุวันรับเงิน`}
                  />
                  <Figure
                    label="นัดที่รอบันทึกผล"
                    value={`${dueVisits.length} นัด`}
                    caption={dueVisits.length ? dayLineLabel([...dueVisits].sort((a, b) => b.plannedDate.localeCompare(a.plannedDate))[0].plannedDate) : 'ไม่มีนัดค้าง'}
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
              </div>
            )}
          </Panel>

          <Panel
            title="รายการที่ต้องอัปเดต"
            className="nav-drawer:order-3"
            actions={!loading && !dealsFailed ? <span className="text-xs text-text-muted">{rows.length} รายการ · กดปุ่มท้ายแถวเพื่อดำเนินการได้ทันที</span> : null}
          >
            {dealsFailed ? <p className="m-0 text-sm font-bold text-danger">โหลดไม่สำเร็จ</p> : loading || !work ? (
              <div className="grid gap-2" role="status" aria-busy="true" aria-label="กำลังโหลดรายการที่ต้องอัปเดต">
                <Skeleton height={36} />
                <Skeleton height={36} />
                <Skeleton height={36} />
              </div>
            ) : <TodayWorklist rows={rows} busy={ctx.resuming} onAct={act} />}
          </Panel>
        </div>

        <div className="grid min-w-0 content-start gap-[18px] nav-drawer:contents">
          <div className="grid gap-[18px] nav-drawer:order-2">
            <TodayVisits
              visits={todaysVisits}
              tomorrowCount={(tomorrowQuery.data ?? []).filter((v) => v.status !== 'CANCELLED').length}
              onDone={ctx.tickVisit}
              onMove={ctx.reschedule}
            />
          </div>
          <div className="grid gap-[18px] nav-drawer:contents">
            <div className="nav-drawer:order-4"><SavedToday entries={report?.today} failedLoad={failed(reportQuery)} /></div>
            <div className="nav-drawer:order-5"><WaitingOthers rows={waiting} /></div>
          </div>
        </div>
      </div>

      {/* The header's actions move to a sticky bottom bar on a phone, with a spacer so it never covers the last row.
          Toasts sit above it (Toast.jsx's mobile bottom offset is 72px). */}
      <div aria-hidden="true" className="hidden h-16 mobile:block" />
      <div className="hidden mobile:fixed mobile:inset-x-4 mobile:bottom-[max(12px,env(safe-area-inset-bottom))] mobile:z-20 mobile:grid mobile:grid-cols-2 mobile:gap-2">
        <Button type="button" variant="secondary" className="min-h-[48px] whitespace-nowrap bg-surface shadow-lg" onClick={ctx.openCallPicker}>บันทึกการโทร</Button>
        <Button type="button" variant="secondary" className="min-h-[48px] whitespace-nowrap bg-surface shadow-lg" onClick={ctx.openCreate}>งานใหม่</Button>
        <Button type="button" data-testid="log-visit-sticky" className="col-span-2 min-h-[48px] whitespace-nowrap shadow-lg" onClick={ctx.openLog}>บันทึกเข้าพบ</Button>
      </div>
    </>
  );
}
