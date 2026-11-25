import { Link } from 'react-router-dom';
import { Button, buttonVariants } from '../../components/common/Button.jsx';
import { DataTable } from '../../components/common/DataTable.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { findStage } from '../tickets/stageCatalog.js';
import { addDaysIso, dealStageLabel, formatMoney, formatThaiDate } from '../../utils/format.js';
import { isoWeekdayIndex } from '../salesBoard/visitWeek.js';
import { quarterLabel } from '../salesBoard/boardFormat.js';

const DAY_NAMES = ['จันทร์', 'อังคาร', 'พุธ', 'พฤหัสบดี', 'ศุกร์', 'เสาร์', 'อาทิตย์'];
const PAYMENT_KIND = { DEPOSIT: 'มัดจำ', ON_DELIVERY: 'รับชำระวันส่งมอบ', BALANCE: 'ส่วนที่เหลือ' };

/** "26 พ.ย." for an ISO date. */
export const dayMonth = (iso) => formatThaiDate(iso).split(' ').slice(0, 2).join(' ');

/** "23–27 พ.ย. 2569" for the working days of the week starting `monday`. */
export function weekRange(monday) {
  const friday = addDaysIso(monday, 4);
  const [d1, m1] = dayMonth(monday).split(' ');
  const [d2, m2, y2] = formatThaiDate(friday).split(' ');
  return m1 === m2 ? `${d1}–${d2} ${m2} ${y2}` : `${d1} ${m1} – ${d2} ${m2} ${y2}`;
}

const stageText = (catalog, code) => `${findStage(catalog, code)?.no ?? ''} · ${dealStageLabel(code).label}`.replace(/^ · /, '');
const nameOf = (o) => o.projectName || o.title || o.customerName || o.code;

function JobLink({ id, children }) {
  return <Link to={`/tickets/${id}`} className="font-bold text-link no-underline hover:underline">{children}</Link>;
}

function Sub({ children }) {
  return <small className="block truncate text-xs font-normal text-text-muted">{children}</small>;
}

function Card({ id, title, sub, right, rightSub, lines = [] }) {
  return (
    <Link to={`/tickets/${id}`} className="flex min-h-11 items-start justify-between gap-3 px-3.5 py-3 text-text no-underline">
      <span className="grid min-w-0 gap-0.5">
        <b className="text-sm">{title}</b>
        {sub ? <small className="text-xs text-text-muted">{sub}</small> : null}
        {lines.filter(Boolean).map((line) => <small key={line} className="text-xs text-text-muted">{line}</small>)}
      </span>
      <span className="grid shrink-0 justify-items-end gap-0.5 text-sm font-bold tabular-nums">{right}{rightSub}</span>
    </Link>
  );
}

const table = { showPagination: false, pageSize: 200 };

export function SectionTitle({ title, aside }) {
  return (
    <div className="mb-2 mt-1 flex flex-wrap items-baseline gap-x-3 gap-y-0.5">
      <h3 className="m-0 text-base font-extrabold">{title}</h3>
      {aside ? <span className="text-xs text-text-muted">{aside}</span> : null}
    </div>
  );
}

/** One line per thing the rep or manager should chase; `to` navigates, `onClick` acts in place. */
export function Worklist({ items }) {
  if (!items.length) {
    return (
      <p className="m-0 flex items-center gap-2 text-sm font-bold text-text-muted">
        <Icon name="check" size={18} />ไม่มีรายการที่ต้องติดตามในสัปดาห์นี้
      </p>
    );
  }
  return (
    <ul className="m-0 grid list-none divide-y divide-border-subtle p-0">
      {items.map((item) => (
        <li key={item.key} className="flex items-center gap-3 py-3">
          <span className="grid size-7 shrink-0 place-items-center rounded-full bg-surface-subtle text-xs font-extrabold tabular-nums">{item.count}</span>
          <span className="min-w-0 flex-1">
            <b className="block text-sm">{item.title}</b>
            <small className="text-xs text-text-muted">{item.detail}</small>
          </span>
          {item.to ? (
            <Link to={item.to} className={buttonVariants({ variant: 'secondary' })}>{item.action}</Link>
          ) : (
            <Button variant="secondary" onClick={item.onClick} disabled={item.busy}>{item.action}</Button>
          )}
        </li>
      ))}
    </ul>
  );
}

/** The week's counts, summed over the reps in scope. */
export function WeekCounts({ reps, orders, weekStart, weekEnd }) {
  const sum = (key) => reps.reduce((total, rep) => total + rep[key], 0);
  const confirmed = orders.filter((o) => o.orderConfirmedAt && o.orderConfirmedAt.slice(0, 10) >= weekStart && o.orderConfirmedAt.slice(0, 10) <= weekEnd);
  const waiting = sum('visitsDue') - sum('visitsDueWithResult');
  const rows = [
    ['งานใหม่', `${sum('newDeals')} งาน`],
    ['งานที่เลื่อนขั้น', `${sum('stageMoves')} ครั้ง`],
    ['ใบเสนอราคาที่ออก', `${sum('quotationsIssued')} ใบ`],
    ['ยืนยันคำสั่งซื้อ', `${confirmed.length} งาน · ${formatMoney(confirmed.reduce((t, o) => t + o.value, 0))}`],
    ['ปิดการขายไม่สำเร็จ', `${sum('lost')} งาน`],
    ['เข้าพบแล้ว / นัดหมาย', `${sum('visitsDone')} / ${sum('visitsPlanned')}${waiting > 0 ? ` · รอบันทึกผล ${waiting}` : ''}`],
  ];
  return (
    <dl className="m-0 grid grid-cols-2 gap-x-4 gap-y-3 mobile:grid-cols-1 mobile:gap-y-0 mobile:divide-y mobile:divide-border-subtle">
      {rows.map(([label, value]) => (
        <div key={label} className="grid gap-0.5 mobile:flex mobile:items-baseline mobile:justify-between mobile:py-2.5">
          <dt className="text-xs font-bold text-text-muted">{label}</dt>
          <dd className="m-0 text-sm font-bold tabular-nums">{value}</dd>
        </div>
      ))}
    </dl>
  );
}

function DueCell({ order }) {
  const next = order.nextPayment;
  if (next) {
    return (
      <span className="grid gap-0.5">
        <span>{weekRange(next.week).replace(/ \d{4}$/, '')}</span>
        {order.slipped ? <StatusBadge tone="warning">เลื่อนกำหนด</StatusBadge> : null}
      </span>
    );
  }
  if (order.outstanding <= 0) return <span className="text-text-muted">รับชำระครบ</span>;
  return <Link to={`/tickets/${order.ticketId}?tab=money`} className="font-bold text-link">ระบุวันที่</Link>;
}

/** ยอดที่สั่งซื้อแล้ว: one row per order ticket; `showOwner` adds the rep under the project (team view). */
export function OrdersTable({ orders, catalog, showOwner = false }) {
  const columns = [
    {
      key: 'project', header: 'โครงการ',
      render: (o) => (
        <span className="min-w-0">
          <JobLink id={o.ticketId}>{nameOf(o)}</JobLink>
          <Sub>{[o.code, showOwner ? o.ownerName : o.customerName].filter(Boolean).join(' · ')}</Sub>
        </span>
      ),
    },
    { key: 'value', header: 'มูลค่าที่สั่งซื้อ', align: 'right', render: (o) => formatMoney(o.value) },
    { key: 'stage', header: 'ขั้นงาน', render: (o) => <span className="text-xs">{stageText(catalog, o.salesStage)}</span> },
    { key: 'received', header: 'รับชำระแล้ว', align: 'right', render: (o) => (o.received > 0 ? formatMoney(o.received) : '–') },
    {
      key: 'next', header: 'ยอดรับงวดถัดไป', align: 'right',
      render: (o) => (o.nextPayment ? <span>{formatMoney(o.nextPayment.amount)}<Sub>{PAYMENT_KIND[o.nextPayment.kind]}</Sub></span> : '–'),
    },
    { key: 'due', header: 'กำหนดรับเงิน', render: (o) => <DueCell order={o} /> },
  ];
  return (
    <DataTable
      {...table}
      columns={columns}
      rows={orders}
      getRowKey={(o) => o.ticketId}
      gridClassName="grid-cols-[minmax(0,2.2fr)_minmax(6rem,1fr)_minmax(0,1.5fr)_minmax(6rem,1fr)_minmax(6rem,1fr)_minmax(6.5rem,1fr)]"
      mobileCard={(o) => (
        <Card
          id={o.ticketId}
          title={nameOf(o)}
          sub={[o.code, showOwner ? o.ownerName : null].filter(Boolean).join(' · ')}
          lines={[stageText(catalog, o.salesStage), o.nextPayment ? `${PAYMENT_KIND[o.nextPayment.kind]} ${formatMoney(o.nextPayment.amount)} · กำหนดรับ ${weekRange(o.nextPayment.week).replace(/ \d{4}$/, '')}` : o.outstanding > 0 ? `${formatMoney(o.outstanding)} ยังไม่ระบุวันรับเงิน` : 'รับชำระครบ']}
          right={formatMoney(o.value)}
          rightSub={o.slipped ? <StatusBadge tone="warning">เลื่อนกำหนด</StatusBadge> : null}
        />
      )}
      emptyState={{ title: 'ยังไม่มีงานที่สั่งซื้อ', description: 'เมื่อยืนยันคำสั่งซื้อในพอร์ทัล งานจะปรากฏที่นี่' }}
    />
  );
}

/** ยอดคาดหวัง: the first five pre-order rows by value, the rest behind the all-deals link. */
export function ExpectedTable({ rows, catalog }) {
  const columns = [
    {
      key: 'project', header: 'โครงการ',
      render: (d) => (<span className="min-w-0"><JobLink id={d.ticketId}>{nameOf(d)}</JobLink><Sub>{d.orderCodes?.[0] ?? d.quotationNumber ?? 'ยังไม่มีใบเสนอราคา'}</Sub></span>),
    },
    { key: 'value', header: 'มูลค่า', align: 'right', render: (d) => (d.value != null ? formatMoney(d.value) : '–') },
    { key: 'stage', header: 'ขั้นงาน', render: (d) => <span className="text-xs">{stageText(catalog, d.salesStage)}</span> },
    { key: 'buyer', header: 'ผู้ออกแบบ / ผู้ซื้อ', render: (d) => <span className="text-xs">{d.designerName || '–'}<Sub>{d.buyerName}</Sub></span> },
    { key: 'quarter', header: 'ไตรมาสที่คาด', render: (d) => quarterLabel(d.expectedQuarter) || '–' },
    { key: 'touched', header: 'อัปเดตล่าสุด', render: (d) => (d.lastTouchedAt ? dayMonth(d.lastTouchedAt.slice(0, 10)) : '–') },
  ];
  return (
    <DataTable
      {...table}
      columns={columns}
      rows={rows}
      getRowKey={(d) => d.ticketId}
      gridClassName="grid-cols-[minmax(0,2.2fr)_minmax(6rem,1fr)_minmax(0,1.6fr)_minmax(0,1.4fr)_minmax(5rem,0.8fr)_minmax(5rem,0.8fr)]"
      mobileCard={(d) => (
        <Card
          id={d.ticketId}
          title={nameOf(d)}
          sub={[d.orderCodes?.[0], quarterLabel(d.expectedQuarter) && `คาด ${quarterLabel(d.expectedQuarter)}`].filter(Boolean).join(' · ')}
          lines={[stageText(catalog, d.salesStage)]}
          right={d.value != null ? formatMoney(d.value) : '–'}
        />
      )}
      emptyState={{ title: 'ไม่มีงานที่รอใบสั่งซื้อ' }}
    />
  );
}

/** Visits grouped by day; a past PLANNED visit offers เข้าพบแล้ว. */
export function VisitsByDay({ visits, weekStart, todayIso, onDone, busyId, showOwner = false }) {
  const days = Array.from({ length: 7 }, (_, i) => addDaysIso(weekStart, i))
    .filter((iso) => isoWeekdayIndex(iso) < 5 || visits.some((v) => v.plannedDate === iso && v.status !== 'CANCELLED'));
  return (
    <div className="grid grid-cols-5 gap-3 nav-drawer:grid-cols-1">
      {days.map((iso) => {
        const mine = visits.filter((v) => v.plannedDate === iso && v.status !== 'CANCELLED');
        const done = mine.filter((v) => v.status === 'DONE').length;
        return (
          <div key={iso} className="grid content-start gap-1.5 rounded-md border border-border-subtle p-3">
            <h4 className="m-0 flex items-baseline justify-between gap-2 text-sm font-extrabold">
              <span>{DAY_NAMES[isoWeekdayIndex(iso)]} {Number(iso.slice(8))}{iso === todayIso ? <small className="ml-1 text-xs font-bold text-primary">วันนี้</small> : null}</span>
              <small className="text-xs font-normal tabular-nums text-text-muted">{done} / {mine.length}</small>
            </h4>
            {mine.length === 0 ? <small className="text-xs text-text-faint">ไม่มีนัด</small> : null}
            {mine.map((v) => (
              <div key={v.id} className="flex items-start gap-2 text-sm">
                <Icon name={v.status === 'DONE' ? 'check' : 'calendar'} size={16} className={v.status === 'DONE' ? 'mt-0.5 text-success' : 'mt-0.5 text-text-faint'} />
                <span className="grid min-w-0 flex-1 gap-0.5">
                  <span className="font-bold">{v.contactName}</span>
                  <small className="text-xs text-text-muted">{[v.plannedTime, v.ticketTitle, showOwner ? v.ownerName : null].filter(Boolean).join(' · ')}</small>
                  {v.status === 'PLANNED' && v.plannedDate <= todayIso ? (
                    <Button variant="secondary" size="sm" className="mt-1 justify-self-start mobile:min-h-[44px]" disabled={busyId === v.id} onClick={() => onDone(v.id)}>เข้าพบแล้ว</Button>
                  ) : null}
                </span>
              </div>
            ))}
          </div>
        );
      })}
    </div>
  );
}

/** Team: one row per rep; the name opens that rep's own report. */
export function RepTable({ reps, orders, hrefFor, todayIso }) {
  const columns = [
    { key: 'name', header: 'พนักงานขาย', render: (r) => <Link to={hrefFor(r.ownerId)} className="font-bold text-link no-underline hover:underline">{r.name}</Link> },
    { key: 'orders', header: 'สั่งซื้อแล้ว', align: 'right', render: (r) => `${r.ordersOpen} งาน · ${formatMoney(orders.filter((o) => o.ownerId === r.ownerId).reduce((t, o) => t + o.value, 0))}` },
    { key: 'dated', header: 'ระบุวันรับเงินแล้ว', align: 'right', render: (r) => `${r.ordersWithDate} / ${r.ordersOpen}` },
    { key: 'confirmed', header: 'ยืนยันคำสั่งซื้อ', align: 'right', render: (r) => r.ordersConfirmed },
    { key: 'moves', header: 'เลื่อนขั้น', align: 'right', render: (r) => r.stageMoves },
    { key: 'quotes', header: 'ใบเสนอราคา', align: 'right', render: (r) => r.quotationsIssued },
    { key: 'visits', header: 'เข้าพบ', align: 'right', render: (r) => `${r.visitsDone} / ${r.visitsPlanned}` },
    { key: 'last', header: 'อัปเดตล่าสุด', render: (r) => (!r.lastEntryAt ? '–' : r.lastEntryAt.slice(0, 10) === todayIso ? 'วันนี้' : dayMonth(r.lastEntryAt.slice(0, 10))) },
  ];
  return (
    <DataTable
      {...table}
      columns={columns}
      rows={reps}
      getRowKey={(r) => r.ownerId}
      gridClassName="grid-cols-[minmax(0,1.6fr)_minmax(0,1.7fr)_minmax(0,1.1fr)_repeat(4,minmax(4.5rem,0.8fr))_minmax(5rem,0.9fr)]"
      mobileCard={(r) => (
        <Link to={hrefFor(r.ownerId)} className="grid gap-1 px-3.5 py-3 text-text no-underline">
          <span className="flex items-baseline justify-between gap-3"><b className="text-sm">{r.name}</b><small className="text-xs text-text-muted">อัปเดตล่าสุด {!r.lastEntryAt ? '–' : r.lastEntryAt.slice(0, 10) === todayIso ? 'วันนี้' : dayMonth(r.lastEntryAt.slice(0, 10))}</small></span>
          <small className="text-xs text-text-muted">สั่งซื้อแล้ว {r.ordersOpen} งาน · ระบุวันรับเงิน {r.ordersWithDate} / {r.ordersOpen}</small>
          <small className="text-xs text-text-muted">ยืนยันคำสั่งซื้อ {r.ordersConfirmed} · เลื่อนขั้น {r.stageMoves} · ใบเสนอราคา {r.quotationsIssued} · เข้าพบ {r.visitsDone} / {r.visitsPlanned}</small>
        </Link>
      )}
      emptyState={{ title: 'ยังไม่มีพนักงานขายในทีม' }}
    />
  );
}

/** Team: orders confirmed this week. Deals lost have counts only (no per-deal list in the report). */
export function MovementTable({ orders }) {
  const columns = [
    { key: 'project', header: 'โครงการ', render: (o) => (<span className="min-w-0"><JobLink id={o.ticketId}>{nameOf(o)}</JobLink><Sub>{[o.code, o.ownerName].filter(Boolean).join(' · ')}</Sub></span>) },
    { key: 'what', header: 'ความเคลื่อนไหว', render: () => 'ยืนยันคำสั่งซื้อ' },
    { key: 'value', header: 'มูลค่า', align: 'right', render: (o) => formatMoney(o.value) },
  ];
  return (
    <DataTable
      {...table}
      columns={columns}
      rows={orders}
      getRowKey={(o) => o.ticketId}
      gridClassName="grid-cols-[minmax(0,2fr)_minmax(0,1.2fr)_minmax(6rem,1fr)]"
      mobileCard={(o) => <Card id={o.ticketId} title={nameOf(o)} sub={[o.code, o.ownerName].filter(Boolean).join(' · ')} lines={['ยืนยันคำสั่งซื้อ']} right={formatMoney(o.value)} />}
      emptyState={{ title: 'สัปดาห์นี้ยังไม่มีการยืนยันคำสั่งซื้อ' }}
    />
  );
}

/** Team: orders whose next payment is past due (a slip, not an error). */
export function SlipTable({ orders }) {
  const columns = [
    { key: 'project', header: 'โครงการ', render: (o) => (<span className="min-w-0"><JobLink id={o.ticketId}>{nameOf(o)}</JobLink><Sub>{[o.code, o.ownerName].filter(Boolean).join(' · ')}</Sub></span>) },
    { key: 'amount', header: 'ยอด', align: 'right', render: (o) => (<span>{formatMoney(o.nextPayment.amount)}<Sub>{PAYMENT_KIND[o.nextPayment.kind]}</Sub></span>) },
    { key: 'was', header: 'กำหนดเดิม', render: (o) => weekRange(o.nextPayment.week).replace(/ \d{4}$/, '') },
  ];
  return (
    <DataTable
      {...table}
      columns={columns}
      rows={orders}
      getRowKey={(o) => o.ticketId}
      gridClassName="grid-cols-[minmax(0,2fr)_minmax(6rem,1fr)_minmax(0,1.2fr)]"
      mobileCard={(o) => <Card id={o.ticketId} title={nameOf(o)} sub={[o.code, o.ownerName].filter(Boolean).join(' · ')} lines={[`กำหนดเดิม ${weekRange(o.nextPayment.week).replace(/ \d{4}$/, '')}`]} right={formatMoney(o.nextPayment.amount)} rightSub={<small className="text-xs font-normal text-text-muted">{PAYMENT_KIND[o.nextPayment.kind]}</small>} />}
      emptyState={{ title: 'ไม่มียอดรับเงินที่เลื่อนกำหนด' }}
    />
  );
}

