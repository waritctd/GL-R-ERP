import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useSearchParams } from 'react-router-dom';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { Button, buttonVariants } from '../../components/common/Button.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { Panel } from '../../components/common/Layout.jsx';
import { Skeleton } from '../../components/common/Skeleton.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { addDaysIso, bangkokTodayIso, formatMoney, formatThaiDate, formatThaiMonthYearFromMonthInputValue } from '../../utils/format.js';
import { timeLabel, visitIsDue, weekStartIso } from '../salesBoard/visitWeek.js';
import { isOrderedIn } from '../tickets/movementRange.js';
import { stageIndexIn, useStageCatalog } from '../tickets/stageCatalog.js';
import { WeeklyLedger } from './WeeklyLedger.jsx';
import {
  ExpectedTable, MovementTable, OrdersTable, RepTable, SectionTitle, SlipTable, VisitsByDay, WeekCounts, Worklist, bangkokDay, weekRange,
} from './weeklyReportParts.jsx';

const TEAM_ROLES = ['sales_manager', 'ceo'];
const EXPECTED_SHOWN = 5;

/** The Mondays of the month holding `monday`'s Thursday: the weeks the stepper walks (a week belongs to one month). */
function monthWeeks(monday) {
  const month = addDaysIso(monday, 3).slice(0, 7);
  const weeks = [];
  for (let m = weekStartIso(`${month}-01`), i = 0; i < 6; m = addDaysIso(m, 7), i += 1) {
    if (addDaysIso(m, 3).slice(0, 7) === month) weeks.push(m);
  }
  return weeks;
}

const asList = (data) => (Array.isArray(data) ? data : data?.visits ?? []);

function WeekStepper({ weeks, weekStart, currentWeek, onChange }) {
  const i = weeks.indexOf(weekStart);
  const step = (delta) => onChange(weeks[i + delta]);
  return (
    <div className="flex flex-wrap items-center gap-2.5">
      <div role="group" aria-label="เลือกสัปดาห์" className="inline-flex items-center gap-1 rounded-md border border-border bg-surface">
        <Button variant="icon" aria-label="สัปดาห์ก่อนหน้า" disabled={i <= 0} onClick={() => step(-1)} className="border-0"><Icon name="chevronLeft" size={16} /></Button>
        <b className="min-w-[8.5rem] text-center text-sm tabular-nums" aria-live="polite">{weekRange(weekStart)}</b>
        <Button variant="icon" aria-label="สัปดาห์ถัดไป" disabled={i < 0 || i >= weeks.length - 1} onClick={() => step(1)} className="border-0"><Icon name="chevronRight" size={16} /></Button>
      </div>
      {weekStart === currentWeek ? <StatusBadge tone="teal">สัปดาห์นี้</StatusBadge> : null}
    </div>
  );
}

function LoadingBody() {
  return (
    <div className="grid gap-4" role="status" aria-busy="true" aria-label="กำลังโหลดรายงาน">
      <Skeleton height={220} />
      <Skeleton height={120} />
      <Skeleton height={160} />
    </div>
  );
}

/**
 * รายงานสัปดาห์ (spec 0003, boards WR1-WR4): the weekly report the reps used to write by hand. A rep sees their own;
 * sales_manager / ceo see the team, and `?rep=<id>` opens one rep's report. The week lives in `?week=`. All figures
 * come from GET /api/sales/weekly-report; the expected-value table is the board's own pre-order rows and the visit
 * table is the existing visits API.
 */
export function WeeklyReport({ user, showToast }) {
  const queryClient = useQueryClient();
  const [params, setParams] = useSearchParams();
  const isTeamRole = TEAM_ROLES.includes(user?.role);
  const repId = isTeamRole ? Number(params.get('rep')) || null : null;
  const todayIso = bangkokTodayIso();
  const currentWeek = weekStartIso(todayIso);
  const weeks = monthWeeks(currentWeek);
  const requested = params.get('week');
  const weekStart = weeks.includes(requested) ? requested : currentWeek;
  const weekEnd = addDaysIso(weekStart, 6);
  const { catalog } = useStageCatalog();

  const reportQuery = useQuery({
    queryKey: queryKeys.salesBoardWeeklyReport(weekStart, repId),
    queryFn: () => api.salesBoard.weeklyReport({ week: weekStart, ownerId: repId ?? undefined }),
  });
  const visitsQuery = useQuery({
    queryKey: isTeamRole ? queryKeys.salesVisitsTeam(weekStart, weekEnd) : queryKeys.salesVisitsOwn(weekStart, weekEnd),
    queryFn: () => (isTeamRole ? api.salesVisits.team({ from: weekStart, to: weekEnd }) : api.salesVisits.list({ from: weekStart, to: weekEnd })).then(asList),
  });
  const report = reportQuery.data;
  const showExpected = report?.scope === 'OWN';
  const boardQuery = useQuery({
    queryKey: isTeamRole ? queryKeys.salesBoardTeamBoard() : queryKeys.salesBoardMyWork(),
    queryFn: () => (isTeamRole ? api.salesBoard.teamBoard() : api.salesBoard.myWork()),
    enabled: showExpected,
  });
  const markDone = useMutation({
    mutationFn: (id) => api.salesVisits.done(id),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['tickets'] }),
    onError: (error) => showToast?.('error', error?.message || 'บันทึกการเข้าพบไม่สำเร็จ'),
  });

  const setWeek = (next) => setParams((prev) => {
    const copy = new URLSearchParams(prev);
    if (next === currentWeek) copy.delete('week'); else copy.set('week', next);
    return copy;
  });
  const hrefFor = (rep) => {
    const copy = new URLSearchParams(params);
    if (rep) copy.set('rep', rep); else copy.delete('rep');
    return `?${copy.toString()}`;
  };

  if (reportQuery.isError && !report) {
    return (
      <Panel>
        <p className="m-0 text-sm font-bold text-danger">โหลดไม่สำเร็จ</p>
        <Button variant="secondary" className="mt-3" onClick={() => reportQuery.refetch()}>ลองอีกครั้ง</Button>
      </Panel>
    );
  }
  if (!report) return <LoadingBody />;

  const team = report.scope === 'TEAM';
  const canMarkDone = !team && !repId; // only the report's own subject ticks a visit
  const visits = (visitsQuery.data ?? []).filter((v) => repId == null || v.ownerId === repId);
  const orders = report.orders;
  const pendingVisits = visits.filter((v) => visitIsDue(v, todayIso));

  // รายการที่ควรติดตาม: only what has a button that opens the fix.
  const undated = orders.filter((o) => !o.nextPayment && o.outstanding > 0);
  const slipped = orders.filter((o) => o.slipped && o.nextPayment);
  const items = [];
  if (undated.length) {
    items.push({
      key: 'undated', count: undated.length, title: 'งานที่ยังไม่ระบุวันคาดว่าจะรับเงิน',
      detail: `สั่งซื้อแล้ว ${undated.length} งาน · ${formatMoney(undated.reduce((t, o) => t + o.outstanding, 0))} ยังไม่รวมในประมาณการ`,
      ...(team ? { to: '/tickets', action: 'ดูรายการ' }
        : undated.length === 1 ? { to: `/tickets/${undated[0].ticketId}?tab=money`, action: 'ระบุวันที่' }
          : { onClick: () => document.getElementById('weekly-orders')?.scrollIntoView({ behavior: 'smooth' }), action: 'ดูรายการ' }),
    });
  }
  if (team && slipped.length) {
    items.push({
      key: 'slipped', count: slipped.length, title: 'ยอดรับเงินที่เลื่อนกำหนด',
      detail: `${slipped.length} รายการ · ${formatMoney(slipped.reduce((t, o) => t + o.nextPayment.amount, 0))}`,
      onClick: () => document.getElementById('weekly-slips')?.scrollIntoView({ behavior: 'smooth' }), action: 'ดูรายการ',
    });
  }
  if (team && weekStart === currentWeek) {
    report.reps.filter((r) => r.entriesToday === 0 && (!r.lastEntryAt || bangkokDay(r.lastEntryAt) < weekStart)).forEach((r) => items.push({
      key: `quiet-${r.ownerId}`, count: 1, title: 'พนักงานขายที่ยังไม่มีการบันทึกในสัปดาห์นี้',
      detail: `${r.name} · อัปเดตล่าสุด ${r.lastEntryAt ? formatThaiDate(bangkokDay(r.lastEntryAt)) : 'ยังไม่มี'}`,
      to: hrefFor(r.ownerId), action: 'เปิดรายงาน',
    }));
  }
  if (team) {
    // A manager does not tick a rep's visit: one line per rep, opening that rep's report.
    const byRep = new Map();
    pendingVisits.forEach((v) => byRep.set(v.ownerId, [...(byRep.get(v.ownerId) ?? []), v]));
    byRep.forEach((list, ownerId) => items.push({
      key: `visits-${ownerId}`, count: list.length, title: `นัดเข้าพบที่รอบันทึกผล · ${list[0].ownerName} ${list.length} นัด`,
      detail: 'ผู้แทนขายยังไม่บันทึกผลการเข้าพบ', to: hrefFor(ownerId), action: 'ดูรายงาน',
    }));
  } else if (canMarkDone) {
    pendingVisits.forEach((v) => items.push({
      key: `visit-${v.id}`, count: 1, title: 'นัดเข้าพบที่รอบันทึกผล',
      detail: [formatThaiDate(v.plannedDate), timeLabel(v.plannedTime), v.contactName, v.ticketTitle].filter(Boolean).join(' · '),
      onClick: () => markDone.mutate(v.id), busy: markDone.isPending, action: 'เข้าพบแล้ว',
    }));
  }

  // ยอดคาดหวัง: pre-order board rows (already folded per project), biggest first.
  const catalogReady = stageIndexIn(catalog, 'ORDER_RECEIVED') >= 0;
  const isOrdered = isOrderedIn(catalog);
  const expectedAll = (boardQuery.data?.board ?? [])
    .filter((d) => (repId == null || d.ownerId === repId) && !isOrdered(d))
    .sort((a, b) => (Number(b.value) || 0) - (Number(a.value) || 0));
  const expectedTotal = expectedAll.reduce((t, d) => t + (Number(d.value) || 0), 0);
  const expectedRest = expectedAll.slice(EXPECTED_SHOWN);

  const title = team ? 'รายงานประจำสัปดาห์ของทีมขาย' : repId ? `รายงานประจำสัปดาห์ของ ${report.reps[0]?.name ?? ''}` : 'รายงานประจำสัปดาห์';
  const confirmed = orders.filter((o) => o.orderConfirmedAt && bangkokDay(o.orderConfirmedAt) >= weekStart && bangkokDay(o.orderConfirmedAt) <= weekEnd);

  return (
    <div className="grid min-w-0 gap-[18px]">
      <header className="grid gap-2.5">
        {repId ? (
          <Link to={hrefFor(null)} className="inline-flex min-h-11 items-center gap-1 text-sm font-bold text-link no-underline">
            <Icon name="chevronLeft" size={16} />กลับไปรายงานทีม
          </Link>
        ) : null}
        <h2 className="m-0 text-xl font-extrabold">{title}</h2>
        <WeekStepper weeks={weeks} weekStart={weekStart} currentWeek={currentWeek} onChange={setWeek} />
        <p className="m-0 text-xs text-text-muted">
          ระบบจัดทำรายงานนี้จากข้อมูลงาน ใบเสนอราคา นัดเข้าพบ และยอดรับชำระที่ฝ่ายบัญชีบันทึกในพอร์ทัล จึงไม่ต้องจัดทำไฟล์หรือส่งอีเมล
        </p>
      </header>

      <div className="grid grid-cols-[minmax(0,1.7fr)_minmax(0,1fr)] items-start gap-[18px] nav-drawer:grid-cols-1">
        <Panel title={`${team ? 'ยอดรับเงินของทีม' : 'ยอดรับเงิน'} ${formatThaiMonthYearFromMonthInputValue(report.ledger.month)}`} actions={<span className="text-xs text-text-muted">ยอดเงินทั้งหมดรวม VAT</span>}>
          <WeeklyLedger ledger={report.ledger} moneyFrom={report.moneyFrom} formatDate={formatThaiDate} />
        </Panel>
        <div className="grid gap-[18px]">
          <Panel title="รายการที่ควรติดตาม" actions={<span className="text-xs text-text-muted">{items.length} รายการ</span>}>
            {markDone.isError ? <p className="mb-2 mt-0 text-sm font-bold text-danger" role="alert">บันทึกการเข้าพบไม่สำเร็จ</p> : null}
            <Worklist items={items} />
          </Panel>
          <Panel title={`${team ? 'สัปดาห์นี้ทั้งทีม' : 'สัปดาห์นี้'} ${weekRange(weekStart).replace(/ \d{4}$/, '')}`}>
            <WeekCounts reps={report.reps} orders={orders} weekStart={weekStart} weekEnd={weekEnd} />
          </Panel>
        </div>
      </div>

      {team ? (
        <Panel title="รายบุคคล" actions={<span className="text-xs text-text-muted">เลือกชื่อเพื่อเปิดรายงานรายบุคคล</span>}>
          <RepTable reps={report.reps} orders={orders} hrefFor={hrefFor} todayIso={todayIso} />
        </Panel>
      ) : null}

      <Panel id="weekly-orders" title={team ? 'งานที่สั่งซื้อแล้ว' : 'งานของฉัน'}>
        <SectionTitle title="ยอดที่สั่งซื้อแล้ว" aside={`${orders.length} งาน · ${formatMoney(orders.reduce((t, o) => t + o.value, 0))}`} />
        <OrdersTable orders={orders} catalog={catalog} showOwner={team} />
        {showExpected ? (
          <div className="mt-5">
            <SectionTitle title="ยอดคาดหวัง" aside={`${expectedAll.length} งาน · ${formatMoney(expectedTotal)} · เรียงตามมูลค่า`} />
            {boardQuery.isError ? (
              <p className="m-0 text-sm font-bold text-danger">โหลดไม่สำเร็จ</p>
            ) : boardQuery.isLoading || !catalogReady ? (
              <Skeleton height={120} />
            ) : (
              <>
                <ExpectedTable rows={expectedAll.slice(0, EXPECTED_SHOWN)} catalog={catalog} />
                <div className="mt-2 flex flex-wrap items-center justify-between gap-2 text-xs text-text-muted">
                  <span>แสดง {Math.min(EXPECTED_SHOWN, expectedAll.length)} จาก {expectedAll.length} งาน{expectedRest.length ? ` · อีก ${expectedRest.length} งานรวม ${formatMoney(expectedRest.reduce((t, d) => t + (Number(d.value) || 0), 0))}` : ''}</span>
                  <Link to="/tickets" className={buttonVariants({ variant: 'secondary', size: 'sm' })}>ดูงานคาดหวังทั้งหมด</Link>
                </div>
              </>
            )}
          </div>
        ) : null}
      </Panel>

      {team ? (
        <div className="grid grid-cols-2 items-start gap-[18px] nav-drawer:grid-cols-1">
          <Panel title="ความเคลื่อนไหวของงานสัปดาห์นี้" actions={<span className="text-xs text-text-muted">ยืนยันคำสั่งซื้อ {confirmed.length} งาน · ปิดการขายไม่สำเร็จ {report.reps.reduce((t, r) => t + r.lost, 0)} งาน</span>}>
            <MovementTable orders={confirmed} />
          </Panel>
          <Panel id="weekly-slips" title="ยอดรับเงินที่เลื่อนกำหนด" actions={<span className="text-xs text-text-muted">{slipped.length} รายการ · {formatMoney(slipped.reduce((t, o) => t + o.nextPayment.amount, 0))}</span>}>
            <SlipTable orders={slipped} />
            <p className="mb-0 mt-2 text-xs text-text-muted">การเลื่อนกำหนดไม่ถือเป็นข้อผิดพลาด ส่วนนี้แสดงเพื่อให้ทราบว่ายอดใดมีการเปลี่ยนกำหนดรับเงิน</p>
          </Panel>
        </div>
      ) : null}

      <Panel
        title="การเข้าพบลูกค้า"
        actions={<span className="text-xs text-text-muted">เข้าพบแล้ว {visits.filter((v) => v.status === 'DONE').length} จาก {visits.filter((v) => v.status !== 'CANCELLED').length} นัด</span>}
      >
        {visitsQuery.isError ? (
          <p className="m-0 text-sm font-bold text-danger">โหลดไม่สำเร็จ</p>
        ) : visitsQuery.isLoading ? (
          <Skeleton height={100} />
        ) : (
          <VisitsByDay visits={visits} weekStart={weekStart} todayIso={todayIso} onDone={canMarkDone ? (id) => markDone.mutate(id) : null} busyId={markDone.isPending ? markDone.variables : null} showOwner={team} />
        )}
      </Panel>
    </div>
  );
}
