import { ShippingSwitch } from './ShippingSwitch.jsx';
import { useMemo, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useNavigate } from 'react-router-dom';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { Button, buttonVariants } from '../../components/common/Button.jsx';
import { DataTable } from '../../components/common/DataTable.jsx';
import { EmptyState } from '../../components/common/EmptyState.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { PageHeader } from '../../components/common/PageHeader.jsx';
import { PageStack, Panel } from '../../components/common/Layout.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { cn } from '../../utils/cn.js';
import { formatThaiDate, fulfilmentStatusLabel } from '../../utils/format.js';
import { AdvanceRoundSheet } from './AdvanceRoundSheet.jsx';
import { DownloadIrButton } from './DownloadIrButton.jsx';
import { PlanRoundSheet } from './PlanRoundSheet.jsx';
import {
  compareRoundEta, formatQty, irRows, lotProjectName, lotProduct, lotWho, lotWhoSub, roundIrSummary, roundProjectNames, roundArrivalKey, roundReference, roundSummaryLine, roundTitle, STEP_CHIPS, stepPillText, stepTone, waitingFactories,
} from './shipmentRounds.js';
import { useRoundUndo } from './useRoundUndo.jsx';

// Whole literals (Tailwind JIT), one track per column: the waiting table has 6, the rounds table 7.
const WAITING_GRID = 'grid-cols-[minmax(0,1.1fr)_minmax(0,0.9fr)_minmax(0,1.6fr)_minmax(0,1.4fr)_minmax(0,0.9fr)_minmax(0,1fr)]';
const ROUNDS_GRID = 'grid-cols-[minmax(0,1.2fr)_minmax(0,1fr)_minmax(0,1.15fr)_minmax(0,1fr)_minmax(0,1.35fr)_minmax(0,0.9fr)_minmax(11rem,0.8fr)]';

const arrivalText = (r) => (r.facts?.arrivedOn ? `ถึงไทยแล้ว ${formatThaiDate(r.facts.arrivedOn)}`
  : r.facts?.etaOn ? formatThaiDate(r.facts.etaOn) : 'ยังไม่ระบุ');

/** A round's IR numbers; past `limit` the rest stay one tap away. */
function IrChips({ rows, limit }) {
  const [open, setOpen] = useState(false);
  const shown = open ? rows : rows.slice(0, limit);
  const hidden = rows.length - shown.length;
  return (
    <span className="flex flex-wrap items-center gap-1">
      {shown.map((row) => <code key={row.key} className="max-w-full break-all rounded-sm bg-surface-subtle px-1.5 text-xs text-text-secondary">{row.docNumber}</code>)}
      {hidden > 0 ? (
        <button type="button" onClick={() => setOpen(true)} aria-label={`แสดงเลขที่ IR อีก ${hidden} ใบ`}
          className="inline-flex min-h-11 min-w-11 cursor-pointer items-center justify-center rounded-md border-0 bg-transparent text-xs font-bold text-primary hover:bg-surface-hover">
          +{hidden}
        </button>
      ) : null}
    </span>
  );
}

/**
 * การขนส่ง — import's queue of SHIPMENT ROUNDS (N01 on a phone, ND1 from desktop width). A round is one factory shipment;
 * advancing it moves every IR on it. ISSUED IRs with quantity on no round wait in "รอจัดรอบขนส่ง" (planning a round
 * comes in a later slice). One request feeds the page (GET /api/shipment-rounds); the IR-level actions (lead time,
 * order email, PDFs) live on each IR's own page, which every IR row links to.
 * `readOnly`: the warehouse planner reads the rounds (no actions, no legacy-deal list). `embedded`: inside the merged
 * การขนส่ง page, which owns the title and the leg switch.
 */
export function ImportFulfilmentPage({ user, showToast, readOnly = false, embedded = false }) {
  const queryClient = useQueryClient();
  const editable = (user?.role === 'import' || user?.role === 'ceo') && !readOnly;
  const [step, setStep] = useState('ALL');
  const [waitingPicked, setWaitingPicked] = useState(false);
  const [search, setSearch] = useState('');
  const [etaDescending, setEtaDescending] = useState(false);
  const [advancing, setAdvancing] = useState(null);
  const [planning, setPlanning] = useState(false);
  const navigate = useNavigate();
  const undo = useRoundUndo({ showToast });

  const listQuery = useQuery({
    queryKey: queryKeys.shipmentRounds(),
    queryFn: () => api.shipmentRounds.list(),
  });
  // The legacy section: deals still on the pre-V184 chain (IR_ISSUED / IR_SENT / SHIPPING) with no stored IR row. The deal
  // list is ONE request (no per-deal fan-out); a deal whose IRs sit on a round or wait for one is not legacy, so those
  // ticket ids come from the rounds response itself.
  const ticketsQuery = useQuery({
    queryKey: queryKeys.ticketList(''),
    queryFn: () => api.tickets.list({}).then((r) => r?.tickets ?? []),
    enabled: !readOnly,
  });
  const rounds = useMemo(() => listQuery.data?.rounds ?? [], [listQuery.data]);
  const waiting = useMemo(() => listQuery.data?.waiting ?? [], [listQuery.data]);
  const chip = waitingPicked && waiting.length > 0 ? 'WAITING' : step;

  const counts = useMemo(() => {
    const c = { ALL: rounds.length };
    rounds.forEach((r) => { c[r.importStep] = (c[r.importStep] ?? 0) + 1; });
    return c;
  }, [rounds]);

  const term = search.trim().toLowerCase();
  const legacyDeals = useMemo(() => {
    const tracked = new Set([...rounds.flatMap((r) => r.lots.map((l) => l.ticketId)), ...waiting.map((w) => w.ticketId)].filter((id) => id != null));
    return (ticketsQuery.data ?? [])
      .filter((t) => ['IR_ISSUED', 'IR_SENT', 'SHIPPING'].includes(t.fulfillmentStatus) && !tracked.has(t.id))
      .filter((t) => !term || [t.customerName, t.code, t.projectName, t.title].some((f) => (f ?? '').toLowerCase().includes(term)))
      .sort((a, b) => (a.overdue === b.overdue ? 0 : a.overdue ? -1 : 1)
        || (a.customerName ?? a.code ?? '').localeCompare(b.customerName ?? b.code ?? '', 'th'));
  }, [ticketsQuery.data, rounds, waiting, term]);
  const visibleWaiting = useMemo(() => (chip === 'ALL' || chip === 'WAITING' ? waiting : [])
    .filter((w) => !term || [w.factoryName, w.docNumber, w.ticketCode, w.customerName, lotProjectName(w)]
      .some((f) => (f ?? '').toLowerCase().includes(term))), [waiting, chip, term]);
  const visibleRounds = useMemo(() => rounds
    .filter((r) => chip === 'ALL' || chip === r.importStep)
    .filter((r) => !term || [roundTitle(r), r.facts?.label, r.facts?.vessel, r.facts?.proformaNo, r.facts?.invoiceNo, ...(r.documents ?? []).map((d) => d.docNo),
      ...r.lots.flatMap((l) => [l.docNumber, l.ticketCode, l.customerName, l.projectName])]
      .some((f) => (f ?? '').toLowerCase().includes(term)))
    .sort((a, b) => compareRoundEta(a, b, etaDescending)), [rounds, chip, term, etaDescending]);

  const waitingColumns = [
    { key: 'factory', header: 'โรงงาน', render: (w) => <b>{w.factoryName}</b> },
    {
      key: 'ir',
      header: 'ใบขอซื้อ',
      render: (w) => (w.ticketId == null ? <code>{w.docNumber}</code> : <Link to={`/import/deals/${w.ticketId}`} className="text-info underline"><code>{w.docNumber}</code></Link>),
    },
    {
      key: 'who',
      header: 'โครงการ / ลูกค้า',
      render: (w) => (
        <span className="flex flex-col gap-0.5">
          <span className="text-sm font-bold">{lotWho(w)}</span>
          <span className="text-2xs text-text-muted">{lotWhoSub(w)}</span>
        </span>
      ),
    },
    { key: 'items', header: 'สินค้า', render: (w) => <span className="text-sm">{w.lines.map((l) => lotProduct(l)).join(', ')}</span> },
    {
      key: 'remaining',
      header: 'ยังไม่จัดรอบ',
      render: (w) => <span className="tabular-nums font-bold">{w.lines.length > 1 ? `${w.lines.length} รายการ` : `${formatQty(w.lines[0].remainingQty)} ${w.lines[0].unit ?? ''}`}</span>,
    },
    {
      key: 'status',
      header: 'สถานะ',
      render: (w) => (
        <span className="flex flex-col items-start gap-1">
          <StatusBadge tone="warning">รอจัดรอบ</StatusBadge>
          {w.ticketId == null ? <DownloadIrButton importRequestId={w.importRequestId} docNumber={w.docNumber} factoryName={w.factoryName} showToast={showToast} /> : null}
        </span>
      ),
    },
  ];

  const advanceButton = (round, className) => (editable && round.importStep !== 'RECEIVED' ? (
    <Button type="button" variant="secondary" className={className} onClick={() => setAdvancing(round)} data-testid={`round-advance-${round.id}`}>เลื่อนขั้นตอน</Button>
  ) : null);
  const roundColumns = [
    {
      key: 'project',
      header: 'โครงการ',
      render: (r) => (
        <span className="flex flex-col gap-0.5">
          <Link to={`/fulfilment/rounds/${r.id}`} className="break-words text-sm font-bold text-text no-underline hover:underline">
            {roundProjectNames(r) || 'ไม่ระบุโครงการ'}
          </Link>
          {roundReference(r) && <span className="break-words text-2xs text-text-muted">{roundReference(r)}</span>}
        </span>
      ),
    },
    { key: 'factory', header: 'โรงงาน', render: (r) => <span className="break-words">{r.factoryName || '—'}</span> },
    { key: 'step', header: 'ขั้นตอน', render: (r) => <StatusBadge tone={stepTone(r.importStep)}>{stepPillText(r.importStep)}</StatusBadge> },
    {
      key: 'shipping',
      header: 'คาดว่าถึงไทย',
      render: (r) => {
        const shipping = roundSummaryLine(r);
        return (
          <span className="flex flex-col gap-0.5">
            <span className={roundArrivalKey(r) ? 'text-sm font-semibold' : 'text-xs text-text-muted'}>
              {arrivalText(r)}
            </span>
            {shipping && <span className="text-2xs text-text-muted">{shipping}</span>}
          </span>
        );
      },
    },
    { key: 'irNos', header: 'เลขที่ IR', render: (r) => <IrChips rows={irRows(r)} limit={3} /> },
    {
      key: 'qty',
      header: 'จำนวน',
      render: (r) => {
        const sum = roundIrSummary(r);
        return (
          <span className="flex flex-col gap-0.5">
            <span className="text-sm font-bold tabular-nums">{sum.qty}</span>
            <span className="text-2xs text-text-muted">{sum.mix}</span>
          </span>
        );
      },
    },
    {
      key: 'actions',
      header: '',
      render: (r) => (
        <span className="flex items-center justify-end gap-1 whitespace-nowrap">
          {advanceButton(r)}
          <Link to={`/fulfilment/rounds/${r.id}`} aria-label="รายละเอียด" title="รายละเอียด"
            className="inline-flex size-11 shrink-0 items-center justify-center rounded-md text-icon-muted no-underline hover:bg-surface-hover">
            <Icon name="chevronRight" size={18} />
          </Link>
        </span>
      ),
    },
  ];

  const waitingCard = (w) => (
    <div className="flex items-start justify-between gap-3" data-testid="waiting-card">
      <span className="flex min-w-0 flex-col gap-0.5">
        <b className="text-sm">{w.factoryName}</b>
        <small className="text-text-muted">
          {w.ticketId == null ? <code>{w.docNumber}</code> : <Link to={`/import/deals/${w.ticketId}`} className="text-info underline"><code>{w.docNumber}</code></Link>}
          {' · '}{lotWho(w)} · เหลือ {w.lines.map((l) => `${formatQty(l.remainingQty)} ${l.unit ?? ''}`.trim()).join(', ')}
        </small>
      </span>
      <span className="flex shrink-0 flex-col items-end gap-1">
        <StatusBadge tone="warning">รอจัดรอบ</StatusBadge>
        {w.ticketId == null ? <DownloadIrButton importRequestId={w.importRequestId} docNumber={w.docNumber} factoryName={w.factoryName} showToast={showToast} /> : null}
      </span>
    </div>
  );
  const roundCard = (r) => {
    const sum = roundIrSummary(r);
    const advance = advanceButton(r, 'w-full');
    return (
      <div className="flex flex-col gap-2.5" data-testid="round-card" data-round-id={r.id}>
        <div className="flex items-center justify-between gap-2">
          <div className="min-w-0">
            <Link to={`/fulfilment/rounds/${r.id}`} className="inline-flex min-h-11 items-center break-words text-base font-bold text-text no-underline">
              {roundProjectNames(r) || 'ไม่ระบุโครงการ'}
            </Link>
            <p className="m-0 break-words text-xs text-text-muted">{['โรงงาน · ' + (r.factoryName || '—'), roundReference(r)].filter(Boolean).join(' · ')}</p>
          </div>
          <span className="shrink-0"><StatusBadge tone={stepTone(r.importStep)}>{stepPillText(r.importStep)}</StatusBadge></span>
        </div>
        <p className="m-0 text-sm font-semibold">{r.facts?.arrivedOn ? '' : 'คาดว่าถึงไทย · '}{arrivalText(r)}</p>
        {roundSummaryLine(r) ? <p className="m-0 text-sm text-text-muted">{roundSummaryLine(r)}</p> : null}
        <IrChips rows={irRows(r)} limit={2} />
        <div className="flex items-baseline justify-between gap-3 text-sm">
          <span className="text-text-muted">{sum.mix}</span>
          <b className="text-right tabular-nums">{sum.qty}</b>
        </div>
        <div className={cn('grid gap-2', advance ? 'grid-cols-2' : 'grid-cols-1')}>
          <Link to={`/fulfilment/rounds/${r.id}`} className={cn(buttonVariants({ variant: 'secondary' }), 'w-full no-underline')}>รายละเอียด</Link>
          {advance}
        </div>
      </div>
    );
  };

  // The banner stands in for the list, which still opens by itself when a search hits a waiting IR or nothing else is on the page.
  const waitingOpen = chip === 'WAITING' || (chip === 'ALL' && (Boolean(term) || (!listQuery.isLoading && rounds.length === 0)));
  const canToggleWaiting = chip === 'WAITING' ? step !== 'ALL' || rounds.length > 0 : !waitingOpen;
  const nothing = !listQuery.isLoading && !listQuery.isError && !ticketsQuery.isLoading && !ticketsQuery.isError && rounds.length === 0 && waiting.length === 0
    && (ticketsQuery.data ?? []).every((t) => !['IR_ISSUED', 'IR_SENT', 'SHIPPING'].includes(t.fulfillmentStatus));
  return (
    <PageStack>
      {embedded ? null : <ShippingSwitch user={user} current="rounds" />}
      {embedded ? null : <PageHeader
        title="การขนส่ง"
        subtitle="เลื่อนทีละรอบ ทุกใบขอซื้อในรอบขยับพร้อมกัน"
        actions={(
          <Button type="button" variant="icon" onClick={() => queryClient.invalidateQueries({ queryKey: queryKeys.shipmentRounds() })} title="รีเฟรช" aria-label="รีเฟรช">
            <Icon name="refresh" />
          </Button>
        )}
      />}

      {waiting.length > 0 ? (
        <section className="flex flex-wrap items-center gap-3 rounded-md border border-warning-border bg-warning-bg-soft px-4 py-2.5 mobile:flex-col mobile:items-stretch" data-testid="waiting-banner">
          <p className="m-0 min-w-0 flex-1 text-sm">
            <b className="text-warning-dark">{waiting.length} ใบขอซื้อรอจัดรอบ</b>{' '}
            <span className="text-text-muted">จาก {new Set(waiting.map((w) => w.factoryName)).size} โรงงาน</span>
          </p>
          <span className="flex gap-2 mobile:[&>*]:flex-1">
            {canToggleWaiting ? (
              <Button type="button" variant="secondary" aria-expanded={waitingOpen} onClick={() => setWaitingPicked(chip !== 'WAITING')} data-testid="waiting-toggle">
                {chip === 'WAITING' ? 'ซ่อนรายการ' : 'ดูรายการ'}
              </Button>
            ) : null}
            {editable && waitingFactories(waiting).length > 0 ? (
              <Button type="button" variant="primary" onClick={() => setPlanning(true)} data-testid="plan-round">จัดรอบขนส่ง</Button>
            ) : null}
          </span>
        </section>
      ) : null}

      <div role="group" aria-label="กรองตามขั้นตอน"
        className="flex min-w-0 max-w-full gap-2 overflow-x-auto min-[1041px]:grid min-[1041px]:grid-cols-[minmax(6.5rem,0.7fr)_repeat(6,minmax(0,1fr))] min-[1041px]:gap-0 min-[1041px]:overflow-hidden min-[1041px]:rounded-md min-[1041px]:border min-[1041px]:border-border min-[1041px]:bg-surface">
        {STEP_CHIPS.map((o) => {
          const active = chip === o.key;
          return (
            <button
              key={o.key}
              type="button"
              aria-pressed={active}
              data-testid={`step-chip-${o.key}`}
              onClick={() => { setStep(o.key); setWaitingPicked(false); }}
              className={cn(
                'inline-flex min-h-11 shrink-0 items-center gap-1.5 rounded-full border px-3 text-sm font-bold',
                'min-[1041px]:min-h-[5.75rem] min-[1041px]:min-w-0 min-[1041px]:flex-col min-[1041px]:items-start min-[1041px]:justify-center min-[1041px]:gap-0.5 min-[1041px]:rounded-none min-[1041px]:border-0 min-[1041px]:border-b-[3px] min-[1041px]:border-l min-[1041px]:border-l-border-subtle min-[1041px]:px-3.5 min-[1041px]:text-left min-[1041px]:first:border-l-0',
                active ? 'border-primary bg-primary/10 text-primary min-[1041px]:border-b-primary' : 'border-border bg-surface text-text hover:bg-surface-hover min-[1041px]:border-b-transparent',
              )}
            >
              <small className={cn('hidden text-xs font-semibold min-[1041px]:block', active ? 'text-primary' : 'text-text-muted')}>{o.s}</small>
              <span className="order-last tabular-nums opacity-70 min-[1041px]:order-none min-[1041px]:text-2xl min-[1041px]:leading-tight min-[1041px]:opacity-100">{counts[o.key] ?? 0}</span>
              <span className="whitespace-nowrap min-[1041px]:text-xs min-[1041px]:font-semibold">{o.label}</span>
            </button>
          );
        })}
      </div>

      <label className="flex justify-end">
        <span className="sr-only">ค้นหารอบขนส่ง</span>
        <input type="search" value={search} onChange={(e) => setSearch(e.target.value)} placeholder="ค้นหาโครงการ / โรงงาน / IR / ลูกค้า / เรือ"
          className="min-h-11 w-80 rounded-md border border-border-input bg-surface px-3 text-sm mobile:w-full mobile:text-base" data-testid="fulfilment-search" />
      </label>

      {listQuery.isError ? (
        <Panel className="border-danger-border bg-danger-bg" data-testid="fulfilment-error">
          <p className="m-0 text-sm text-danger-dark">โหลดรอบขนส่งไม่สำเร็จ — {listQuery.error?.message || 'ลองรีเฟรชหน้านี้อีกครั้ง'}</p>
        </Panel>
      ) : null}

      {ticketsQuery.isError ? (
        <Panel className="border-danger-border bg-danger-bg" data-testid="fulfilment-deals-error">
          <p className="m-0 flex flex-wrap items-center gap-3 text-sm text-danger-dark">
            โหลดรายการงานเดิมไม่สำเร็จ — {ticketsQuery.error?.message || 'ลองอีกครั้ง'}
            <Button type="button" variant="secondary" onClick={() => ticketsQuery.refetch()}>ลองอีกครั้ง</Button>
          </p>
        </Panel>
      ) : null}

      {nothing ? (
        <EmptyState icon="check" title="ยังไม่มีรอบขนส่ง" description="เมื่อมีการออกใบขอซื้อ ใบขอซื้อจะขึ้นที่นี่ให้จัดรอบและเลื่อนขั้นตอน" />
      ) : (
        <>
          {waitingOpen && visibleWaiting.length > 0 ? (
            <section className="flex flex-col gap-2" data-testid="waiting-section">
              <h2 className="m-0 flex items-baseline gap-2 text-base font-bold">
                รอจัดรอบขนส่ง
                <span className="text-2xs font-normal text-text-muted">{visibleWaiting.length} ใบขอซื้อ · {new Set(visibleWaiting.map((w) => w.factoryName)).size} โรงงาน</span>
              </h2>
              <DataTable
                columns={waitingColumns}
                rows={visibleWaiting}
                getRowKey={(w) => w.importRequestId}
                gridClassName={WAITING_GRID}
                mobileCard={waitingCard}
                loading={listQuery.isLoading}
                emptyState={{ icon: 'check', title: 'ไม่มีใบขอซื้อที่รอจัดรอบ' }}
              />
            </section>
          ) : null}
          {chip !== 'WAITING' && (visibleRounds.length > 0 || listQuery.isLoading) ? (
            <section className="flex flex-col gap-2" data-testid="rounds-section">
              <div className="flex flex-wrap items-center justify-between gap-2">
                <h2 className="m-0 flex items-baseline gap-2 text-base font-bold">
                  รอบขนส่ง
                  <span className="text-2xs font-normal text-text-muted">{visibleRounds.length} รอบ</span>
                </h2>
                <Button variant="secondary" size="sm" className="mobile:min-h-11" onClick={() => setEtaDescending((d) => !d)} data-testid="eta-sort">
                  คาดว่าถึงไทย · {etaDescending ? 'ช้าสุดก่อน ↓' : 'เร็วสุดก่อน ↑'}
                </Button>
              </div>
              <DataTable
                columns={roundColumns}
                rows={visibleRounds}
                getRowKey={(r) => r.id}
                gridClassName={ROUNDS_GRID}
                mobileCard={roundCard}
                loading={listQuery.isLoading}
                emptyState={{ icon: 'check', title: 'ไม่มีรอบขนส่งในขั้นตอนนี้' }}
              />
            </section>
          ) : null}
          {chip === 'ALL' && legacyDeals.length > 0 ? (
            <section className="rounded-xl border border-dashed border-border bg-surface p-3" data-testid="fulfilment-legacy">
              <p className="mb-2 text-xs font-bold text-text-muted">
                งานอื่นที่ยังไม่ติดตามแบบรายโรงงาน — เปิดที่หน้ารายละเอียดงานเพื่อดำเนินการ
              </p>
              <div className="flex flex-col gap-1.5">
                {legacyDeals.map((deal) => (
                  <div key={deal.id}
                    className="flex flex-wrap items-center justify-between gap-2 rounded-md border border-border-subtle px-3 py-2 text-xs"
                    data-testid="fulfilment-legacy-deal">
                    <span className="flex flex-wrap items-center gap-2">
                      <strong className="text-text">{deal.customerName || deal.title || deal.code}</strong>
                      {deal.overdue ? <StatusBadge tone="danger">เกินกำหนด</StatusBadge> : null}
                      {deal.fulfillmentStatus ? (
                        <span className="text-2xs text-text-muted">{fulfilmentStatusLabel(deal.fulfillmentStatus).label}</span>
                      ) : null}
                    </span>
                    <Link to={`/import/deals/${deal.id}`} className="inline-flex min-h-11 items-center text-info underline">
                      เปิดงาน <code>{deal.code}</code>
                    </Link>
                  </div>
                ))}
              </div>
            </section>
          ) : null}
          {!listQuery.isLoading && visibleWaiting.length === 0 && visibleRounds.length === 0 && legacyDeals.length === 0 ? (
            <EmptyState icon="check" title="ไม่มีรายการในตัวกรองนี้" description={'ลองล้างคำค้นหรือเลือก "ทั้งหมด"'} />
          ) : null}
        </>
      )}

      {embedded ? null : <p className="text-2xs text-text-muted">การส่งมอบสินค้าให้ลูกค้าไม่ได้อยู่ในหน้านี้ — บันทึกที่แท็บ ส่งของ ของงานนั้น</p>}

      {advancing ? (
        <AdvanceRoundSheet
          round={advancing}
          showToast={showToast}
          onClose={() => setAdvancing(null)}
          onAdvanced={(round) => { setAdvancing(null); undo.offer(round); }}
        />
      ) : null}
      {planning ? (
        <PlanRoundSheet
          waiting={waiting}
          showToast={showToast}
          onClose={() => setPlanning(false)}
          onDone={(round) => { setPlanning(false); if (round?.id) navigate(`/fulfilment/rounds/${round.id}`); }}
        />
      ) : null}
      {undo.snackbar}
    </PageStack>
  );
}
