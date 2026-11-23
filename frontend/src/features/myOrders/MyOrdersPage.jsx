import { useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { Button, buttonVariants } from '../../components/common/Button.jsx';
import { ConfirmDialog } from '../../components/common/ConfirmDialog.jsx';
import { DataTable } from '../../components/common/DataTable.jsx';
import { EmptyState } from '../../components/common/EmptyState.jsx';
import { PageHeader } from '../../components/common/PageHeader.jsx';
import { FilterBar, PageStack, Panel } from '../../components/common/Layout.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { cn } from '../../utils/cn.js';
import { formatThaiDate } from '../../utils/format.js';
import { formatQty, PURPOSE_LABELS, stepLabel, stepTone } from '../fulfilment/shipmentRounds.js';
import { LotSheet } from '../tickets/IncomingGoodsPanel.jsx';
import { useMediaQuery } from '../../hooks/useIsMobile.js';
import { ShippingSwitch } from '../fulfilment/ShippingSwitch.jsx';
import { NewOrderPanel, NewOrderSheet, useNewOrderForm } from './NewOrderSheet.jsx';

// Whole literal, one track per column (7): for / who, product + lot, factory, quantity, status, expected, the open control.
const ORDERS_GRID = 'grid-cols-[minmax(0,1.2fr)_minmax(0,1.4fr)_minmax(0,0.9fr)_6rem_7.5rem_minmax(0,1fr)_5rem]';

const CHIPS = [{ key: 'ALL', label: 'ทั้งหมด' }, { key: 'PROJECT', label: 'โครงการ' }, { key: 'SAMPLE', label: 'ตัวอย่าง' },
  { key: 'SHOWROOM', label: 'โชว์รูม' }, { key: 'STOCK', label: 'สต็อก' }];

const kindLabel = (kind) => (kind === 'PROJECT' ? 'โครงการ' : PURPOSE_LABELS[kind] ?? kind);
const rowKey = (r) => (r.waiting ? `w-${r.importRequestId}-${r.product}` : `l-${r.lot.lotId}`);
const statusKey = (r) => (r.waiting ? 'WAITING' : r.lot.step);
const isArrived = (r) => !r.waiting && r.lot?.step === 'RECEIVED';
const lotNote = (r) => (r.waiting ? 'ส่วนที่ยังไม่จัดรอบ' : r.lot.lotCount > 1 ? `ล็อต ${r.lot.lotNo} จาก ${r.lot.lotCount}` : 'ครบทั้งใบ');
const whenText = (r) => {
  if (r.waiting) return 'ยังไม่มีกำหนด';
  if (r.lot.step === 'RECEIVED') return `ถึง ${formatThaiDate(r.lot.receivedOn ?? r.lot.arrivedOn)}`;
  return r.lot.expectedOn ? `คาดว่าถึง ${formatThaiDate(r.lot.expectedOn)}` : 'ยังไม่มีกำหนด';
};
const qtyText = (r) => {
  const q = `${formatQty(r.qty)} ${r.unit ?? ''}`.trim();
  const got = r.lot?.receivedQty;
  return got != null && Number(got) < Number(r.qty) ? `${formatQty(got)} จาก ${q}` : q;
};

/**
 * การขนส่ง (sales view, was ของที่ฉันสั่ง) — the ONE sales page for all incoming goods: every lot of every IR on the rep's project deals AND their
 * no-project orders (sample / showroom / stock), with unplanned quantity as a รอจัดรอบ row. A project row opens that deal's
 * ส่งของ tab; a no-project row opens the lot sheet. The sales manager sees the team's; the showroom grant sees SHOWROOM orders.
 */
export function MyOrdersPage({ user, showToast }) {
  const [chip, setChip] = useState('ALL');
  const [search, setSearch] = useState('');
  const [rep, setRep] = useState('');
  const [project, setProject] = useState('');
  const [status, setStatus] = useState('');
  const [creating, setCreating] = useState(false);
  const [open, setOpen] = useState(null);
  const [withdrawing, setWithdrawing] = useState(null);
  const queryClient = useQueryClient();
  const withdraw = useMutation({
    mutationFn: (row) => api.noProjectOrders.withdraw(row.importRequestId),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: queryKeys.myIncoming() });
      queryClient.invalidateQueries({ queryKey: queryKeys.shipmentRounds() });
      showToast?.('success', 'ยกเลิกคำขอแล้ว');
      setWithdrawing(null);
    },
    onError: (e) => { showToast?.('error', e?.message ?? 'ยกเลิกคำขอไม่สำเร็จ'); setWithdrawing(null); },
  });
  const query = useQuery({ queryKey: queryKeys.myIncoming(), queryFn: () => api.myIncoming.get(), retry: false });
  const canCreate = ['sales', 'sales_manager'].includes(user?.role) || Boolean(user?.canCreateQuotation);
  // From the desktop breakpoint the create form is a side panel beside the list (ND4); below it, the same form in a sheet.
  const desktop = useMediaQuery('(min-width: 1041px)');
  const sidePanel = canCreate && desktop;
  // Held here so a draft survives crossing the breakpoint (a tablet rotated mid-order).
  const orderForm = useNewOrderForm({ onDone: () => setCreating(false), showToast, wide: sidePanel, enabled: canCreate });

  const rows = useMemo(() => query.data?.rows ?? [], [query.data]);
  const term = search.trim().toLowerCase();
  const visible = useMemo(() => rows
    .filter((r) => chip === 'ALL' || r.kind === chip)
    .filter((r) => !rep || String(r.repId) === rep)
    .filter((r) => !project || (r.kind === 'PROJECT' && r.who === project))
    .filter((r) => !status || statusKey(r) === status)
    .filter((r) => !term || [r.who, r.ticketCode, r.docNumber, r.product, r.factoryName, r.requesterName, r.repName].some((f) => (f ?? '').toLowerCase().includes(term))), [rows, chip, term, rep, project, status]);
  const reps = useMemo(() => [...new Map(rows.filter((r) => r.repId != null).map((r) => [String(r.repId), r.repName ?? `#${r.repId}`])).entries()].sort((a, b) => a[1].localeCompare(b[1], 'th')), [rows]);
  const projects = useMemo(() => [...new Set(rows.filter((r) => r.kind === 'PROJECT').map((r) => r.who))].sort((a, b) => a.localeCompare(b, 'th')), [rows]);
  const statuses = useMemo(() => [...new Set(rows.map(statusKey))], [rows]);
  const incoming = visible.filter((r) => !isArrived(r));
  const arrived = visible.filter(isArrived);
  const counts = useMemo(() => Object.fromEntries(CHIPS.map((c) => [c.key, c.key === 'ALL' ? rows.length : rows.filter((r) => r.kind === c.key).length])), [rows]);

  const openControl = (r) => (r.ticketId != null
    ? (r.canOpenDeal === false ? null : <Link to={`/tickets/${r.ticketId}?tab=ship`} className={cn(buttonVariants({ variant: 'secondary', size: 'sm' }), 'no-underline')}>เปิดงาน</Link>)
    : (
      <span className="flex flex-col items-end gap-1">
        {r.waiting
          ? (r.canWithdraw ? <Button type="button" size="sm" variant="secondary" onClick={() => setWithdrawing(r)} data-testid={`order-withdraw-${r.importRequestId}`}>ยกเลิกคำขอ</Button> : null)
          : <Button type="button" size="sm" variant="secondary" onClick={() => setOpen(r)} data-testid={`order-open-${r.lot.lotId}`}>ดู</Button>}
      </span>));

  const columns = [
    { key: 'who', header: 'เพื่อ', render: (r) => (
      <span className="flex flex-col gap-0.5">
        <b className="text-sm">{r.who}</b>
        <small className="text-text-muted">{kindLabel(r.kind)}{r.ticketCode ? ` · ${r.ticketCode}` : ''}{r.repName ?? r.requesterName ? ` · ${r.repName ?? r.requesterName}` : ''}</small>
      </span>
    ) },
    { key: 'product', header: 'สินค้า', render: (r) => <span className="flex flex-col"><b className="text-sm">{r.product}</b><small className="text-text-muted"><code>{r.docNumber}</code> · {lotNote(r)}</small></span> },
    { key: 'factory', header: 'โรงงาน', render: (r) => <span className="whitespace-normal [overflow-wrap:anywhere]">{r.factoryName}</span> },
    { key: 'qty', header: 'จำนวน', render: (r) => <b className="whitespace-normal tabular-nums">{qtyText(r)}</b> },
    { key: 'status', header: 'สถานะ', render: (r) => (r.waiting ? <StatusBadge tone="warning">รอจัดรอบ</StatusBadge> : <StatusBadge tone={stepTone(r.lot.step)}>{stepLabel(r.lot.step)}</StatusBadge>) },
    { key: 'when', header: 'กำหนด', render: (r) => <span className="text-sm">{whenText(r)}</span> },
    { key: 'open', header: '', render: openControl },
  ];
  const card = (r) => (
    <div className="flex items-start justify-between gap-3 rounded-md border border-border bg-surface p-3" data-testid="order-card">
      <span className="flex min-w-0 flex-col gap-0.5">
        <b className="text-sm">{r.who}</b>
        <small className="text-text-muted">{kindLabel(r.kind)}{r.repName ?? r.requesterName ? ` · ${r.repName ?? r.requesterName}` : ''} · <code>{r.docNumber}</code></small>
        <small className="text-text-muted">{r.product} · {lotNote(r)}</small>
        <small className="text-text-muted">{r.waiting ? 'รอจัดรอบ' : stepLabel(r.lot.step)} · {whenText(r)}</small>
      </span>
      <span className="flex shrink-0 flex-col items-end gap-1 text-right"><b className="tabular-nums">{qtyText(r)}</b>{openControl(r)}</span>
    </div>
  );
  const table = (list, title, testId) => (list.length > 0 ? (
    <section className="flex flex-col gap-2" data-testid={testId}>
      <h2 className="m-0 flex items-baseline gap-2 text-base font-bold">{title}<span className="text-2xs font-normal text-text-muted">{list.length} รายการ</span></h2>
      <DataTable columns={columns} rows={list} getRowKey={rowKey} gridClassName={ORDERS_GRID} mobileCard={card} emptyState={{ icon: 'check', title: 'ไม่มีรายการ' }} />
    </section>
  ) : null);

  return (
    <PageStack>
      <ShippingSwitch user={user} current="orders" />
      <PageHeader
        title="การขนส่ง"
        subtitle="ของนำเข้าของทั้งทีมขายในที่เดียว กรองดูตามผู้ดูแล โครงการ หรือสถานะได้"
        actions={canCreate && !sidePanel ? <Button type="button" variant="primary" onClick={() => setCreating(true)} data-testid="new-order">+ สั่งของไม่มีโครงการ</Button> : null}
      />
      <div className={cn('grid grid-cols-1 gap-4', sidePanel && 'min-[1041px]:grid-cols-[minmax(0,1fr)_24rem] min-[1041px]:items-start')}>
        <div className="flex min-w-0 flex-col gap-4">
      <FilterBar>
        <div className="flex min-w-0 max-w-full gap-2 overflow-x-auto" role="group" aria-label="กรองตามประเภท">
          {CHIPS.map((c) => (
            <button key={c.key} type="button" aria-pressed={chip === c.key} onClick={() => setChip(c.key)} data-testid={`orders-chip-${c.key}`}
              className={cn('inline-flex min-h-11 shrink-0 items-center gap-1.5 rounded-full border px-3 text-sm font-bold',
                chip === c.key ? 'border-primary bg-primary/10 text-primary' : 'border-border bg-surface text-text hover:bg-surface-hover')}>
              {c.label}<span className="tabular-nums opacity-70">{counts[c.key] ?? 0}</span>
            </button>
          ))}
        </div>
        {[['rep', 'ผู้ดูแล', rep, setRep, reps], ['project', 'โครงการ', project, setProject, projects.map((p) => [p, p])],
          ['status', 'สถานะ', status, setStatus, statuses.map((s) => [s, s === 'WAITING' ? 'รอจัดรอบ' : stepLabel(s)])]].map(([key, label, value, set, options]) => (
          options.length > 1 ? (
            <label key={key} className="flex min-w-0 items-center gap-2 mobile:w-full">
              <span className="sr-only">{label}</span>
              <select value={value} onChange={(e) => set(e.target.value)} className="min-h-11 min-w-0 rounded-md border border-border-input px-2 text-sm mobile:w-full" data-testid={`orders-filter-${key}`}>
                <option value="">{label}: ทั้งหมด</option>
                {options.map(([v, text]) => <option key={v} value={v}>{text}</option>)}
              </select>
            </label>
          ) : null))}
        <label className="ml-auto flex items-center gap-2 mobile:ml-0 mobile:w-full">
          <span className="sr-only">ค้นหา</span>
          <input type="search" value={search} onChange={(e) => setSearch(e.target.value)} placeholder="ค้นหาสินค้า / ใบขอซื้อ / ลูกค้า / ผู้ดูแล"
            className="min-h-11 w-64 rounded-md border border-border-input px-3 text-sm mobile:w-full" data-testid="orders-search" />
        </label>
      </FilterBar>
      {query.isError ? <Panel className="border-danger-border bg-danger-bg"><p className="m-0 text-sm text-danger-dark">โหลดรายการไม่สำเร็จ — {query.error?.message}</p></Panel> : null}
      {!query.isLoading && !query.isError && rows.length === 0 ? (
        <EmptyState icon="check" title="ยังไม่มีของที่สั่ง" description="ของนำเข้าจากงานของคุณและคำขอสั่งของไม่มีโครงการจะขึ้นที่นี่" />
      ) : (
        <>
          {table(incoming, 'กำลังมา', 'orders-incoming')}
          {table(arrived, 'ถึงโกดังแล้ว (30 วันล่าสุด)', 'orders-arrived')}
          {!query.isLoading && rows.length > 0 && visible.length === 0 ? <EmptyState icon="check" title="ไม่มีรายการในตัวกรองนี้" /> : null}
        </>
      )}
        </div>
        {sidePanel ? <NewOrderPanel form={orderForm} /> : null}
      </div>
      <p className="text-2xs text-text-muted">แตะรายการของงานโครงการเพื่อไปที่แท็บ ส่งของ ของงานนั้น · เปิดได้เฉพาะงานของคุณ ส่วนของคนอื่นดูได้อย่างเดียว</p>
      <ConfirmDialog
        open={Boolean(withdrawing)}
        title="ยกเลิกคำขอสั่งของ"
        message={withdrawing ? `ยกเลิกคำขอ ${withdrawing.docNumber} (${withdrawing.product}) ใช่ไหม ใบขอซื้อจะถูกถอนออกจากคิวของฝ่ายนำเข้า` : ''}
        confirmLabel="ยกเลิกคำขอ"
        cancelLabel="ไม่ยกเลิก"
        busy={withdraw.isPending}
        onConfirm={() => withdraw.mutate(withdrawing)}
        onCancel={() => setWithdrawing(null)}
      />
      {creating && !sidePanel ? <NewOrderSheet form={orderForm} onClose={() => setCreating(false)} /> : null}
      {open ? <LotSheet ticketId={null} ir={{ docNumber: open.docNumber }} lot={open.lot} wantedBy={open.wantedBy} canMessage={false} showToast={showToast} onClose={() => setOpen(null)} /> : null}
    </PageStack>
  );
}
