import { useMemo, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { Button, buttonVariants } from '../../components/common/Button.jsx';
import { DataTable } from '../../components/common/DataTable.jsx';
import { EmptyState } from '../../components/common/EmptyState.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { PageHeader } from '../../components/common/PageHeader.jsx';
import { FilterBar, PageStack, Panel } from '../../components/common/Layout.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { cn } from '../../utils/cn.js';
import { formatThaiDate, fulfilmentStatusLabel } from '../../utils/format.js';
import { AdvanceRoundSheet } from './AdvanceRoundSheet.jsx';
import {
  formatQty, lotLabel, lotProduct, lotWho, roundIrCount, roundSummaryLine, roundTitle, STEP_CHIPS, stepPillText, stepTone,
} from './shipmentRounds.js';
import { useRoundUndo } from './useRoundUndo.jsx';

// Whole literals (Tailwind JIT), one track per column: the waiting table has 5, the rounds table 7.
const WAITING_GRID = 'grid-cols-[minmax(0,1.1fr)_minmax(0,0.9fr)_minmax(0,1.6fr)_minmax(0,1.5fr)_minmax(0,1fr)]';
const ROUNDS_GRID = 'grid-cols-[minmax(0,1.5fr)_minmax(0,1.1fr)_minmax(0,0.9fr)_minmax(0,0.8fr)_minmax(0,0.8fr)_minmax(0,1.1fr)_minmax(0,1.3fr)]';

/**
 * การขนส่ง — import's queue of SHIPMENT ROUNDS (N01 on a phone, ND1 from desktop width). A round is one factory shipment;
 * advancing it moves every IR on it. ISSUED IRs with quantity on no round wait in "รอจัดรอบขนส่ง" (planning a round
 * comes in a later slice). One request feeds the page (GET /api/shipment-rounds); the IR-level actions (lead time,
 * order email, PDFs) live on each IR's own page, which every IR row links to.
 */
export function ImportFulfilmentPage({ user, showToast }) {
  const queryClient = useQueryClient();
  const editable = user?.role === 'import' || user?.role === 'ceo';
  const [chip, setChip] = useState('ALL');
  const [search, setSearch] = useState('');
  const [advancing, setAdvancing] = useState(null);
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
  });
  const rounds = useMemo(() => listQuery.data?.rounds ?? [], [listQuery.data]);
  const waiting = useMemo(() => listQuery.data?.waiting ?? [], [listQuery.data]);

  const counts = useMemo(() => {
    const c = { ALL: rounds.length + waiting.length, WAITING: waiting.length };
    rounds.forEach((r) => { c[r.importStep] = (c[r.importStep] ?? 0) + 1; });
    return c;
  }, [rounds, waiting]);

  const term = search.trim().toLowerCase();
  const legacyDeals = useMemo(() => {
    const tracked = new Set([...rounds.flatMap((r) => r.lots.map((l) => l.ticketId)), ...waiting.map((w) => w.ticketId)]);
    return (ticketsQuery.data ?? [])
      .filter((t) => ['IR_ISSUED', 'IR_SENT', 'SHIPPING'].includes(t.fulfillmentStatus) && !tracked.has(t.id))
      .filter((t) => !term || [t.customerName, t.code, t.projectName, t.title].some((f) => (f ?? '').toLowerCase().includes(term)))
      .sort((a, b) => (a.overdue === b.overdue ? 0 : a.overdue ? -1 : 1)
        || (a.customerName ?? a.code ?? '').localeCompare(b.customerName ?? b.code ?? '', 'th'));
  }, [ticketsQuery.data, rounds, waiting, term]);
  const visibleWaiting = useMemo(() => (chip === 'ALL' || chip === 'WAITING' ? waiting : [])
    .filter((w) => !term || [w.factoryName, w.docNumber, w.ticketCode, w.customerName, w.projectName]
      .some((f) => (f ?? '').toLowerCase().includes(term))), [waiting, chip, term]);
  const visibleRounds = useMemo(() => rounds
    .filter((r) => chip === 'ALL' || chip === r.importStep)
    .filter((r) => !term || [roundTitle(r), r.facts?.vessel, r.facts?.proformaNo, r.facts?.invoiceNo,
      ...r.lots.flatMap((l) => [l.docNumber, l.ticketCode, l.customerName, l.projectName])]
      .some((f) => (f ?? '').toLowerCase().includes(term))), [rounds, chip, term]);

  const waitingColumns = [
    { key: 'factory', header: 'โรงงาน', render: (w) => <b>{w.factoryName}</b> },
    {
      key: 'ir',
      header: 'ใบขอซื้อ',
      render: (w) => <Link to={`/import/deals/${w.ticketId}`} className="text-info underline"><code>{w.docNumber}</code></Link>,
    },
    {
      key: 'who',
      header: 'โครงการ / ลูกค้า',
      render: (w) => (
        <span className="flex flex-col gap-0.5">
          <span className="text-sm font-bold">{w.projectName || w.customerName || w.ticketCode}</span>
          <span className="text-2xs text-text-muted">งาน {w.ticketCode}</span>
        </span>
      ),
    },
    { key: 'items', header: 'สินค้า', render: (w) => <span className="text-sm">{w.lines.map((l) => lotProduct(l)).join(', ')}</span> },
    {
      key: 'remaining',
      header: 'ยังไม่จัดรอบ',
      render: (w) => <span className="tabular-nums font-bold">{w.lines.length > 1 ? `${w.lines.length} รายการ` : `${formatQty(w.lines[0].remainingQty)} ${w.lines[0].unit ?? ''}`}</span>,
    },
  ];

  const openAdvance = (round) => setAdvancing(round);
  const roundActions = (round) => (
    <span className="flex flex-wrap items-center justify-end gap-2">
      <Link to={`/fulfilment/rounds/${round.id}`} className={cn(buttonVariants({ variant: 'secondary', size: 'sm' }), 'no-underline')}>รายละเอียด</Link>
      {editable && round.importStep !== 'RECEIVED' ? (
        <Button type="button" size="sm" variant="secondary" onClick={() => openAdvance(round)} data-testid={`round-advance-${round.id}`}>เลื่อนขั้นตอน</Button>
      ) : null}
    </span>
  );
  const roundColumns = [
    {
      key: 'round',
      header: 'รอบ',
      render: (r) => (
        <span className="flex flex-col gap-0.5">
          <Link to={`/fulfilment/rounds/${r.id}`} className="text-sm font-bold text-text underline">{roundTitle(r)}</Link>
          <span className="text-2xs text-text-muted">{r.facts?.proformaNo || r.facts?.invoiceNo || ''}</span>
        </span>
      ),
    },
    { key: 'step', header: 'ขั้นตอน', render: (r) => <StatusBadge tone={stepTone(r.importStep)}>{stepPillText(r.importStep)}</StatusBadge> },
    { key: 'vessel', header: 'เรือ', render: (r) => r.facts?.vessel || '-' },
    { key: 'etd', header: 'เรือออก', render: (r) => formatThaiDate(r.facts?.etdActualOn) },
    { key: 'eta', header: 'คาดว่าถึง', render: (r) => formatThaiDate(r.facts?.etaOn) },
    {
      key: 'irs',
      header: 'ใบขอซื้อในรอบ',
      render: (r) => (
        <span className="flex flex-col gap-0.5">
          <span className="text-sm font-bold">{roundIrCount(r)} ใบ</span>
          <span className="text-2xs text-text-muted">{r.lots.map((l) => l.docNumber).filter((v, i, a) => a.indexOf(v) === i).join(', ')}</span>
        </span>
      ),
    },
    { key: 'actions', header: '', render: roundActions },
  ];

  const waitingCard = (w) => (
    <div className="flex items-start justify-between gap-3 rounded-md border border-border bg-surface p-3" data-testid="waiting-card">
      <span className="flex min-w-0 flex-col gap-0.5">
        <b className="text-sm">{w.factoryName}</b>
        <small className="text-text-muted">
          <Link to={`/import/deals/${w.ticketId}`} className="text-info underline"><code>{w.docNumber}</code></Link>
          {' · '}{w.projectName || w.customerName || w.ticketCode} · เหลือ {w.lines.map((l) => `${formatQty(l.remainingQty)} ${l.unit ?? ''}`.trim()).join(', ')}
        </small>
      </span>
      <StatusBadge tone="warning">รอจัดรอบ</StatusBadge>
    </div>
  );
  const roundCard = (r) => (
    <div className="flex flex-col gap-2 rounded-md border border-border bg-surface p-3" data-testid="round-card" data-round-id={r.id}>
      <b className="text-sm">{roundTitle(r)}</b>
      <span className="flex flex-wrap items-center gap-2"><StatusBadge tone={stepTone(r.importStep)}>{stepPillText(r.importStep)}</StatusBadge></span>
      {roundSummaryLine(r) ? <p className="m-0 text-xs text-text-muted">{roundSummaryLine(r)}</p> : null}
      <ul className="m-0 flex list-none flex-col gap-1 border-t border-border-subtle p-0 pt-2">
        {r.lots.map((l) => (
          <li key={l.lotId} className="flex items-start justify-between gap-3 text-sm">
            <span className="flex min-w-0 flex-col">
              <b>{lotWho(l)}</b>
              <small className="text-text-muted"><code>{l.docNumber}</code> · {lotLabel(l)}</small>
            </span>
            <span className="shrink-0 font-bold tabular-nums">{formatQty(l.qty)} {l.unit ?? ''}</span>
          </li>
        ))}
      </ul>
      <div className="border-t border-border-subtle pt-2">{roundActions(r)}</div>
    </div>
  );

  const nothing = !listQuery.isLoading && !listQuery.isError && rounds.length === 0 && waiting.length === 0
    && (ticketsQuery.data ?? []).every((t) => !['IR_ISSUED', 'IR_SENT', 'SHIPPING'].includes(t.fulfillmentStatus));
  return (
    <PageStack>
      <PageHeader
        title="การขนส่ง"
        subtitle="เลื่อนขั้นตอนทีละรอบขนส่ง · ทุกใบขอซื้อในรอบขยับพร้อมกัน · หนึ่งใบขอซื้อแบ่งไปได้หลายรอบ"
        actions={(
          <Button type="button" variant="icon" onClick={() => queryClient.invalidateQueries({ queryKey: queryKeys.shipmentRounds() })} title="รีเฟรช" aria-label="รีเฟรช">
            <Icon name="refresh" />
          </Button>
        )}
      />

      <FilterBar>
        <div className="flex min-w-0 max-w-full gap-2 overflow-x-auto" role="group" aria-label="กรองตามขั้นตอน">
          {STEP_CHIPS.map((o) => {
            const active = chip === o.key;
            return (
              <button
                key={o.key}
                type="button"
                aria-pressed={active}
                data-testid={`step-chip-${o.key}`}
                onClick={() => setChip(o.key)}
                className={cn(
                  'inline-flex min-h-11 shrink-0 items-center gap-1.5 rounded-full border px-3 text-sm font-bold',
                  active ? 'border-primary bg-primary/10 text-primary' : 'border-border bg-surface text-text hover:bg-surface-hover',
                )}
              >
                {o.label}<span className="tabular-nums opacity-70">{counts[o.key] ?? 0}</span>
              </button>
            );
          })}
        </div>
        <label className="ml-auto flex items-center gap-2 mobile:ml-0 mobile:w-full">
          <span className="sr-only">ค้นหารอบขนส่ง</span>
          <input type="search" value={search} onChange={(e) => setSearch(e.target.value)} placeholder="ค้นหาโรงงาน / ใบขอซื้อ / ลูกค้า / เรือ"
            className="min-h-11 w-64 rounded-md border border-border-input px-3 text-sm mobile:w-full" data-testid="fulfilment-search" />
        </label>
      </FilterBar>

      {listQuery.isError ? (
        <Panel className="border-danger-border bg-danger-bg" data-testid="fulfilment-error">
          <p className="m-0 text-sm text-danger-dark">โหลดรอบขนส่งไม่สำเร็จ — {listQuery.error?.message || 'ลองรีเฟรชหน้านี้อีกครั้ง'}</p>
        </Panel>
      ) : null}

      {nothing ? (
        <EmptyState icon="check" title="ยังไม่มีรอบขนส่ง" description="เมื่อมีการออกใบขอซื้อ ใบขอซื้อจะขึ้นที่นี่ให้จัดรอบและเลื่อนขั้นตอน" />
      ) : (
        <>
          {visibleWaiting.length > 0 || listQuery.isLoading ? (
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
              <h2 className="m-0 flex items-baseline gap-2 text-base font-bold">
                รอบขนส่ง
                <span className="text-2xs font-normal text-text-muted">{visibleRounds.length} รอบ</span>
              </h2>
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

      <p className="text-2xs text-text-muted">การส่งมอบสินค้าให้ลูกค้าไม่ได้อยู่ในหน้านี้ — บันทึกที่แท็บ ส่งของ ของงานนั้น</p>

      {advancing ? (
        <AdvanceRoundSheet
          round={advancing}
          showToast={showToast}
          onClose={() => setAdvancing(null)}
          onAdvanced={(round) => { setAdvancing(null); undo.offer(round); }}
        />
      ) : null}
      {undo.snackbar}
    </PageStack>
  );
}
