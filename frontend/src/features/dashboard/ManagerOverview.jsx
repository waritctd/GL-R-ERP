import { useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Link, useNavigate } from 'react-router-dom';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { Button } from '../../components/common/Button.jsx';
import { DataTable } from '../../components/common/DataTable.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { PageHeader } from '../../components/common/PageHeader.jsx';
import { PageStack, Panel } from '../../components/common/Layout.jsx';
import { Skeleton } from '../../components/common/Skeleton.jsx';
import { addDaysIso, bangkokTodayIso, formatMoney, greetingName } from '../../utils/format.js';
import { defaultRange } from '../tickets/movementRange.js';
import { PipelineImportDialog } from '../salesBoard/PipelineImportDialog.jsx';
import { dayLineLabel, timeLabel, weekStartIso } from '../salesBoard/visitWeek.js';
import { Figure, failed, moneySum } from './dashboardParts.jsx';
import { InsightLanding } from './InsightSwitch.jsx';
import { daysSince, nextMondayLabel } from './repToday.js';
import { ReportBlock, Row } from './repTodayParts.jsx';
import { bangkokDay, dayMonth, weekRange } from './weeklyReportParts.jsx';

/**
 * Sales Manager landing: งานวันนี้ (spec 0003 slice 4, boards TD3 / TD4). The team's weekly-report completeness, what
 * waits on the manager (quotations to approve, commissions to review, what to help follow up), the team's visits today,
 * what waits on CEO / import, and one row per rep opening that rep's report. Visits are read-only; every action row has
 * a button that opens the existing screen. Money comes from the weekly report, never re-derived here.
 */

// Won once it reaches the terminal pipeline stage; lost once its lifecycle/legacy status marks it lost or cancelled.
const isDealWon = (t) => t.salesStage === 'CLOSED_PAID';
const isDealLost = (t) => t.lifecycle === 'CLOSED_LOST' || t.lifecycle === 'CANCELLED' || t.status === 'cancelled';

const MAX_COMMISSIONS = 5;
const MANAGER_TITLE = 'งานวันนี้';
const asList = (data) => (Array.isArray(data) ? data : data?.visits ?? []);

// Who a pricing request waits on; account / warehouse have no data to show (spec D11).
const WAITING_GROUPS = [
  { key: 'ceo', who: 'CEO · พิจารณาราคา', statuses: ['READY_FOR_CEO_REVIEW', 'CEO_REVIEWING'] },
  { key: 'import', who: 'ฝ่ายนำเข้า · ตอบราคา', statuses: ['SUBMITTED', 'IMPORT_REVIEWING', 'AWAITING_FACTORY_RESPONSE'] },
];

const Failed = () => <p className="m-0 text-sm font-bold text-danger">โหลดไม่สำเร็จ</p>;
const Empty = ({ children }) => <p className="m-0 flex items-center gap-2 text-sm font-bold text-text-muted"><Icon name="check" size={18} />{children}</p>;

function CountPill({ count, unit = 'รายการ' }) {
  return (
    <span className="rounded-full bg-text px-2.5 py-px text-xs font-extrabold text-surface">
      <span aria-hidden="true">{count}</span>
      <span className="sr-only">{count} {unit}</span>
    </span>
  );
}

function Group({ label, hint, children }) {
  return (
    <section aria-label={label}>
      <h3 className="m-0 flex flex-wrap items-baseline gap-x-2 border-b border-border-subtle pb-1.5 text-sm font-extrabold">
        {label}{hint ? <span className="text-xs font-normal tabular-nums text-text-muted">{hint}</span> : null}
      </h3>
      {children}
    </section>
  );
}

const sumReps = (reps, key) => reps.reduce((t, r) => t + (r[key] ?? 0), 0);

/** When a quotation went to approval: "ส่งขออนุมัติเมื่อวาน", "ส่งขออนุมัติ 24 พ.ย.". */
function sentLabel(iso, todayIso) {
  if (!iso) return null;
  const days = daysSince(iso, todayIso);
  return days === 0 ? 'ส่งขออนุมัติวันนี้' : days === 1 ? 'ส่งขออนุมัติเมื่อวาน' : `ส่งขออนุมัติ ${dayMonth(bangkokDay(iso))}`;
}

/** Nothing logged by this rep this week (every count of the report row is zero). */
const hasNoEntries = (r) => r.entriesToday === 0
  && ['newDeals', 'stageMoves', 'quotationsIssued', 'ordersConfirmed', 'lost', 'visitsDone'].every((k) => !r[k]);

function waitingRows(pricingRequests, todayIso) {
  return WAITING_GROUPS.map((g) => {
    const list = pricingRequests.filter((pr) => g.statuses.includes(pr.status));
    if (!list.length) return null;
    const first = list[0];
    return {
      key: g.key, who: g.who,
      days: Math.max(...list.map((pr) => daysSince(pr.updatedAt ?? pr.submittedAt, todayIso) ?? 0)),
      detail: [first.projectName || first.customerName, list.length > 1 ? `และอีก ${list.length - 1} งาน` : null].filter(Boolean).join(' '),
    };
  }).filter(Boolean);
}

function Meter({ done, total }) {
  return (
    <span className="block h-1.5 w-full overflow-hidden rounded-full bg-surface-subtle" role="img" aria-label={`${done} จาก ${total}`}>
      <i className="block h-full bg-accent" style={{ width: total ? `${Math.round((done / total) * 100)}%` : '0%' }} />
    </span>
  );
}

const table = { showPagination: false, pageSize: 200 };
const dash = (n, unit = '') => (n ? `${n}${unit}` : '–');
const doneOf = (r) => r.ordersWithDate + r.visitsDueWithResult;
const totalOf = (r) => r.ordersOpen + r.visitsDue;

/** รายบุคคล: one row per rep, the name opens that rep's report. */
function RepTable({ reps, pendingByRep, hrefFor }) {
  const columns = [
    { key: 'name', header: 'พนักงานขาย', render: (r) => <Link to={hrefFor(r.ownerId)} className="font-bold text-link no-underline hover:underline">{r.name}</Link> },
    { key: 'complete', header: 'ข้อมูลรายงานครบถ้วน', render: (r) => (<span className="grid gap-1"><span className="tabular-nums">{doneOf(r)} จาก {totalOf(r)}</span><Meter done={doneOf(r)} total={totalOf(r)} /></span>) },
    { key: 'today', header: 'บันทึกวันนี้', align: 'right', render: (r) => dash(r.entriesToday, ' รายการ') },
    { key: 'moves', header: 'เลื่อนขั้นสัปดาห์นี้', align: 'right', render: (r) => dash(r.stageMoves) },
    { key: 'quotes', header: 'ใบเสนอราคา', align: 'right', render: (r) => dash(r.quotationsIssued) },
    { key: 'visits', header: 'เข้าพบ', align: 'right', render: (r) => dash(r.visitsDone, ' นัด') },
    { key: 'pending', header: 'รอคุณอนุมัติ', align: 'right', render: (r) => dash(pendingByRep.get(r.ownerId)) },
  ];
  return (
    <DataTable
      {...table}
      columns={columns}
      rows={reps}
      getRowKey={(r) => r.ownerId}
      gridClassName="grid-cols-[minmax(0,1.5fr)_minmax(0,1.4fr)_repeat(5,minmax(4.5rem,0.8fr))]"
      mobileCard={(r) => (
        <Link to={hrefFor(r.ownerId)} className="grid min-h-11 gap-1.5 px-3.5 py-3 text-text no-underline">
          <span className="flex items-baseline justify-between gap-3"><b className="text-sm">{r.name}</b><small className="text-xs tabular-nums text-text-muted">{doneOf(r)} จาก {totalOf(r)}</small></span>
          <Meter done={doneOf(r)} total={totalOf(r)} />
          <small className="text-xs text-text-muted">
            บันทึกวันนี้ {dash(r.entriesToday, ' รายการ')} · เลื่อนขั้น {dash(r.stageMoves)} · ใบเสนอราคา {dash(r.quotationsIssued)} · เข้าพบ {dash(r.visitsDone, ' นัด')} · รอคุณอนุมัติ {dash(pendingByRep.get(r.ownerId))}
          </small>
        </Link>
      )}
      emptyState={{ title: 'ยังไม่มีพนักงานขายในทีม' }}
    />
  );
}

/** Landing page: today's team work, the weekly report (?view=report) or the sales-analytics snapshot (?view=insight). */
export function ManagerOverview(props) {
  return (
    <InsightLanding title={MANAGER_TITLE} user={props.user} showToast={props.showToast} renderToday={(switcher, panelProps) => <ManagerToday {...props} switcher={switcher} panelProps={panelProps} />} />
  );
}

function ManagerToday({ user, employee, switcher, panelProps }) {
  const navigate = useNavigate();
  const [importing, setImporting] = useState(false);
  const todayIso = bangkokTodayIso();
  const thisMonday = weekStartIso(todayIso);

  const reportQuery = useQuery({
    queryKey: queryKeys.salesBoardWeeklyReport(thisMonday, null),
    queryFn: () => api.salesBoard.weeklyReport({ week: thisMonday, ownerId: undefined }),
  });
  const quotationsQuery = useQuery({
    queryKey: queryKeys.dealQuotationsList({ status: 'PENDING_APPROVAL' }),
    queryFn: () => api.dealQuotations.list({ status: 'PENDING_APPROVAL' }).then((r) => r.items ?? []),
  });
  const commissionsQuery = useQuery({
    queryKey: queryKeys.commissionsPendingApproval(),
    queryFn: () => api.commissions.pendingApproval().then((r) => r.commissions ?? []),
  });
  const teamRange = useMemo(() => defaultRange(), []);
  const ticketsQuery = useQuery({
    queryKey: queryKeys.ticketListByRange(teamRange.from, teamRange.to, 'project'),
    queryFn: () => api.tickets.list({ movementFrom: teamRange.from, movementTo: teamRange.to, fold: 'project' }).then((r) => r.tickets ?? []),
  });
  const visitsQuery = useQuery({
    queryKey: queryKeys.salesVisitsTeam(todayIso, todayIso),
    queryFn: () => api.salesVisits.team({ from: todayIso, to: todayIso }).then(asList),
  });
  const pricingQuery = useQuery({
    queryKey: queryKeys.pricingRequestQueue({ activeOnly: true }),
    queryFn: () => api.pricingRequests.queue({ activeOnly: true }).then((r) => r.items ?? []),
  });

  const report = reportQuery.data;
  const reps = useMemo(() => report?.reps ?? [], [report]);
  const orders = useMemo(() => report?.orders ?? [], [report]);
  const quotations = quotationsQuery.data ?? null;
  const pendingCommissions = commissionsQuery.data ?? null;
  const visits = useMemo(() => (visitsQuery.data ?? []).filter((v) => v.status !== 'CANCELLED')
    .sort((a, b) => (timeLabel(a.plannedTime) || '99').localeCompare(timeLabel(b.plannedTime) || '99')), [visitsQuery.data]);
  const waiting = waitingRows(pricingQuery.data ?? [], todayIso);

  const teamRep = useMemo(() => Object.fromEntries(
    ['ordersOpen', 'ordersWithDate', 'visitsDue', 'visitsDueWithResult', 'stageMoves', 'quotationsIssued', 'visitsDone', 'ordersConfirmed']
      .map((k) => [k, sumReps(reps, k)]),
  ), [reps]);
  const pendingByRep = useMemo(() => {
    const byRep = new Map();
    (quotations ?? []).forEach((q) => byRep.set(Number(q.salesRepId), (byRep.get(Number(q.salesRepId)) ?? 0) + 1));
    return byRep;
  }, [quotations]);

  const week = report?.ledger.weeks.find((w) => w.isCurrent);
  const weekOpen = week ? week.deliveredAwaiting + week.deliveriesScheduled + week.depositsExpected : 0;
  const undated = orders.filter((o) => !o.nextPayment && o.outstanding > 0);
  const slipped = orders.filter((o) => o.slipped && o.nextPayment);
  const quietReps = reps.filter(hasNoEntries);
  const commissionBase = pendingCommissions ? moneySum(pendingCommissions, (e) => e.commission.commissionableBase) : 0;
  const quotationCount = quotations?.length ?? 0;
  const followCount = slipped.length + quietReps.length;
  const actionCount = quotationCount + (pendingCommissions?.length ?? 0) + followCount;

  const hrefFor = (id) => `?view=report&rep=${id}`;
  const act = (a) => navigate(a.to);
  const dateLine = `${dayLineLabel(todayIso)} ${Number(todayIso.slice(0, 4)) + 543}`;
  const greeting = `สวัสดี, ${greetingName(employee?.nickName || employee?.nameTh || user?.name)}`;
  const quotationRows = (quotations ?? []).map((q) => ({
    key: `qt-${q.id}`, name: q.projectName || q.customerName || q.number, code: q.number,
    reason: [q.salesRepName, sentLabel(q.submittedAt, todayIso)].filter(Boolean).join(' · '),
    amount: formatMoney(q.grandTotal), button: 'ตรวจและอนุมัติ', to: `/quotations/${q.id}`, act: { to: `/quotations/${q.id}` },
  }));
  const followRows = [
    ...slipped.map((o) => ({
      key: `slip-${o.ticketId}`, name: o.projectName || o.customerName || o.title || o.code, code: o.code,
      reason: [o.ownerName, `กำหนดรับเงินเลื่อนจากสัปดาห์ ${weekRange(o.nextPayment.week).replace(/ \d{4}$/, '')}`].filter(Boolean).join(' · '),
      amount: formatMoney(o.nextPayment.amount), button: 'เปิดงาน', to: `/tickets/${o.ticketId}`, act: { to: `/tickets/${o.ticketId}` },
    })),
    ...quietReps.map((r) => ({
      key: `quiet-${r.ownerId}`, name: r.name, code: null,
      reason: `ยังไม่มีการบันทึกในสัปดาห์นี้ · ${r.lastEntryAt ? `อัปเดตล่าสุด ${dayMonth(bangkokDay(r.lastEntryAt))}` : 'ยังไม่เคยบันทึก'}`,
      amount: null, button: 'เปิดรายงาน', to: null, act: { to: hrefFor(r.ownerId) },
    })),
  ];
  const visitsDone = visits.filter((v) => v.status === 'DONE').length;

  return (
    <PageStack>
      <PageHeader
        title={MANAGER_TITLE}
        subtitle={`${greeting} · ${dateLine} · รอคุณดำเนินการ ${actionCount} รายการ · นัดหมายของทีม ${visits.length} นัด · รอฝ่ายอื่น ${waiting.length} รายการ`}
        actions={(
          <>
            {switcher}
            {quotationCount > 0 ? (
              <Button type="button" onClick={() => navigate('/quotations?status=PENDING_APPROVAL')} className="mobile:min-h-11">อนุมัติใบเสนอราคา {quotationCount} รายการ</Button>
            ) : null}
            <Button type="button" variant="secondary" onClick={() => setImporting(true)} className="mobile:min-h-11"><Icon name="upload" size={14} />นำเข้าจากชีท</Button>
            <Button type="button" variant="secondary" onClick={() => navigate('/tickets')} className="mobile:min-h-11">งานทั้งหมด</Button>
          </>
        )}
      />
      {importing ? <PipelineImportDialog onClose={() => setImporting(false)} /> : null}
      <PageStack {...panelProps}>
        <Panel title="รายงานสัปดาห์ของทีม">
          {failed(reportQuery) ? <Failed /> : !report ? <Skeleton height={140} /> : (
            <div className="grid gap-4">
              <ReportBlock team rep={teamRep} nextMonday={nextMondayLabel(todayIso)} />
              <dl className="m-0 grid grid-cols-4 gap-x-6 border-t border-border-subtle pt-3 nav-drawer:grid-cols-2 mobile:grid-cols-1 mobile:divide-y mobile:divide-border mobile:[&>div]:py-2.5">
                <Figure label="รับชำระแล้วสัปดาห์นี้" value={formatMoney(week?.received ?? 0)} caption={`จากประมาณการสัปดาห์ ${formatMoney((week?.received ?? 0) + weekOpen)}`} />
                <Figure label="คาดว่าจะรับอีกสัปดาห์นี้" value={formatMoney(weekOpen)} caption={`ถึง${dayLineLabel(addDaysIso(thisMonday, 4))}`} />
                <Figure label="ยังไม่นับในประมาณการ" value={formatMoney(undated.reduce((t, o) => t + o.outstanding, 0))} caption={`${undated.length} งานยังไม่ระบุวันรับเงิน`} />
                <Figure label="ค่าคอมรอตรวจ" value={failed(commissionsQuery) ? '—' : formatMoney(commissionBase)} caption={`${pendingCommissions?.length ?? 0} รายการ`} />
              </dl>
            </div>
          )}
        </Panel>

        <Panel title="รายการที่รอคุณดำเนินการ" actions={<span className="text-xs text-text-muted">{actionCount} รายการ · กดปุ่มท้ายแถวเพื่อดำเนินการได้ทันที</span>}>
          <div className="grid gap-4">
            <Group label="ใบเสนอราคารออนุมัติ" hint={quotations ? `${quotationCount} รายการ · ${formatMoney(moneySum(quotations, (q) => q.grandTotal))}` : null}>
              {failed(quotationsQuery) ? <Failed /> : quotations === null ? <Skeleton height={36} /> : quotationRows.length === 0 ? <Empty>ไม่มีใบเสนอราคารออนุมัติ</Empty> : (
                <ul className="m-0 grid list-none divide-y divide-border-subtle p-0">{quotationRows.map((row) => <Row key={row.key} row={row} onAct={act} />)}</ul>
              )}
            </Group>

            <Group label="ค่าคอมรอตรวจ" hint={pendingCommissions ? <CountPill count={pendingCommissions.length} /> : null}>
              {failed(commissionsQuery) ? <Failed /> : pendingCommissions === null ? <Skeleton height={36} /> : pendingCommissions.length === 0 ? <Empty>ไม่มีค่าคอมรออนุมัติตอนนี้</Empty> : (
                <ul className="m-0 grid list-none divide-y divide-border-subtle p-0">
                  {pendingCommissions.slice(0, MAX_COMMISSIONS).map(({ commission: record, ticketCode }) => {
                    const ref = [record.invoiceDetails?.invoiceNumber, ticketCode].filter(Boolean).join(' · ');
                    return (
                      <li key={record.id} data-testid={`commission-row-${record.id}`} className="flex items-center gap-3 py-3 mobile:flex-wrap">
                        <span className="grid min-w-0 flex-1 gap-0.5 mobile:basis-full">
                          <strong className="min-w-0 truncate text-sm font-bold text-text">{record.salesRepName || `#${record.salesRepId}`}</strong>
                          <code className="truncate text-2xs text-text-muted">{ref || '-'}</code>
                        </span>
                        <span className="shrink-0 text-right">
                          <strong className="block text-sm tabular-nums">{formatMoney(record.commissionableBase)}</strong>
                          <span className="block text-2xs text-text-muted">ฐานคิดค่าคอม</span>
                        </span>
                        <Button type="button" variant="secondary" size="sm" className="shrink-0 mobile:min-h-11 mobile:flex-1" onClick={() => navigate('/commissions?view=pending')}>ตรวจค่าคอม</Button>
                      </li>
                    );
                  })}
                  <li className="py-3 text-sm text-text-muted">
                    {pendingCommissions.length > MAX_COMMISSIONS ? `อีก ${pendingCommissions.length - MAX_COMMISSIONS} รายการ · ` : ''}
                    รวมฐาน <strong className="tabular-nums text-text">{formatMoney(commissionBase)}</strong>
                  </li>
                </ul>
              )}
            </Group>

            {report && followRows.length ? (
              <Group label="ควรช่วยติดตาม" hint={`${followCount} รายการ`}>
                <ul className="m-0 grid list-none divide-y divide-border-subtle p-0">{followRows.map((row) => <Row key={row.key} row={row} onAct={act} />)}</ul>
              </Group>
            ) : null}
          </div>
        </Panel>

        <Panel title="นัดหมายของทีมวันนี้" actions={<span className="text-xs text-text-muted">เข้าพบแล้ว {visitsDone} จาก {visits.length}</span>}>
          {failed(visitsQuery) ? <Failed /> : visits.length === 0 ? <p className="m-0 text-sm text-text-muted">วันนี้ไม่มีนัดหมายของทีม</p> : (
            <ul className="m-0 grid list-none divide-y divide-border-subtle p-0">
              {visits.map((v) => (
                <li key={v.id} data-testid={`team-visit-${v.id}`} className={`flex items-baseline gap-2.5 py-2.5 ${v.status === 'DONE' ? 'opacity-70' : ''}`}>
                  <b className="w-12 shrink-0 text-sm tabular-nums">{timeLabel(v.plannedTime) || '–'}</b>
                  <span className="grid min-w-0 flex-1 gap-0.5">
                    <span className="text-sm font-bold">{v.ownerName}</span>
                    <small className="text-xs text-text-muted">{[v.contactName, v.ticketTitle, v.status === 'DONE' ? 'เข้าพบแล้ว' : null].filter(Boolean).join(' · ')}</small>
                  </span>
                </li>
              ))}
            </ul>
          )}
        </Panel>

        <Panel title="งานของทีมที่รอฝ่ายอื่น" actions={<span className="text-xs text-text-muted">{waiting.length} รายการ</span>}>
          {failed(pricingQuery) ? <Failed /> : waiting.length === 0 ? <p className="m-0 text-sm text-text-muted">ไม่มีรายการที่รอฝ่ายอื่น</p> : (
            <ul className="m-0 grid list-none divide-y divide-border-subtle p-0">
              {waiting.map((w) => (
                <li key={w.key} className="grid gap-0.5 py-2.5">
                  <span className="flex items-baseline justify-between gap-3 text-sm">
                    <b>{w.who}</b>
                    <small className="shrink-0 text-xs text-text-muted">{w.days > 0 ? `รอ ${w.days} วัน` : 'รอตั้งแต่วันนี้'}</small>
                  </span>
                  <small className="truncate text-xs text-text-muted">{w.detail}</small>
                </li>
              ))}
            </ul>
          )}
        </Panel>

        <Panel flush title="รายบุคคล" actions={<span className="text-xs text-text-muted">เลือกชื่อเพื่อเปิดรายงานรายบุคคล</span>}>
          {failed(reportQuery) ? <div className="p-5"><Failed /></div> : !report ? <div className="p-5"><Skeleton height={120} /></div> : (
            <>
              <RepTable reps={reps} pendingByRep={pendingByRep} hrefFor={hrefFor} />
              <p className="m-0 border-t border-border-subtle px-5 py-3 text-xs text-text-muted mobile:px-3.5">
                รวมทีม {reps.length} คน · {doneOf(teamRep)} จาก {totalOf(teamRep)} · เลื่อนขั้น {teamRep.stageMoves} · ใบเสนอราคา {teamRep.quotationsIssued} · เข้าพบ {teamRep.visitsDone} นัด · รอคุณอนุมัติ {quotationCount}
              </p>
            </>
          )}
        </Panel>

        <Panel title="อัตราปิดงาน">
          {failed(ticketsQuery) ? (
            <Failed />
          ) : !ticketsQuery.data ? (
            <Skeleton height={28} />
          ) : (() => {
            const won = ticketsQuery.data.filter(isDealWon).length;
            const lost = ticketsQuery.data.filter(isDealLost).length;
            const rate = won + lost > 0 ? (won / (won + lost)) * 100 : null;
            return (
              <p className="m-0 flex flex-wrap items-baseline gap-x-3 text-sm text-text-muted">
                <strong className="text-2xl font-extrabold tabular-nums text-text">{rate == null ? '—' : `${rate.toFixed(0)}%`}</strong>
                {rate == null ? null : <span>ปิดสำเร็จ {won} · เสีย {lost}</span>}
              </p>
            );
          })()}
        </Panel>
      </PageStack>
    </PageStack>
  );
}
