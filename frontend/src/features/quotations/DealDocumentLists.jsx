import { useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { DataTable } from '../../components/common/DataTable.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { cn } from '../../utils/cn.js';
import { formatMoney, formatThaiDate } from '../../utils/format.js';
import { dealQuotationListSeesAll, dealQuotationRepOptions } from './quotationMeta.js';

// เอกสารขาย's ใบแจ้งมัดจำ and ใบแจ้งหนี้ส่วนที่เหลือ tabs: every deal's notices / invoices in one list, each row naming the
// quotation, deal and customer it belongs to. Row shapes: backend DealDocumentRows. Navigation only here; editing and
// downloading are done on the deal's own pages.

const STATUS_BADGE = {
  DRAFT: { tone: 'warning', label: 'ฉบับร่าง' },
  ISSUED: { tone: 'success', label: 'ออกแล้ว' },
  SUPERSEDED: { tone: 'neutral', label: 'ถูกแทนที่แล้ว' },
};
const NO_NUMBER = 'ยังไม่ออกเลขที่เอกสาร';

export function DocStatusBadge({ status }) {
  const badge = STATUS_BADGE[status] ?? { tone: 'neutral', label: status ?? '-' };
  return <StatusBadge tone={badge.tone}>{badge.label}</StatusBadge>;
}

/** The two document tabs' audience (DepositNoticeService#dealDocumentScope): import gets 403 from both lists. */
export function canSeeDealDocumentLists(user) {
  return ['sales', 'sales_manager', 'ceo', 'account'].includes(user?.role);
}

const noticePath = (ticketId) => `/tickets/${ticketId}/deposit`;
const invoicePath = (ticketId) => `/tickets/${ticketId}?tab=money`;

/**
 * The documents made from one quotation row (`row.documents`, see DealDocumentRows.QuotationDocuments), as links to
 * where each lives. `heading` is for the phone card; the desktop column header says it already.
 */
export function QuotationDocuments({ row, heading = false }) {
  const docs = row.documents;
  if (!docs || (!docs.depositNotice && !(docs.remainingInvoices ?? []).length)) return null;
  const notice = docs.depositNotice;
  const linkClass = 'inline-flex min-h-6 min-w-0 flex-wrap items-center gap-x-2 gap-y-0.5 text-xs text-info underline mobile:min-h-[44px]';
  return (
    <span className="flex min-w-0 flex-col items-start gap-1" data-testid="quotation-documents">
      {heading ? <strong className="text-xs font-extrabold text-text">เอกสารต่อจากใบเสนอราคานี้</strong> : null}
      {notice && notice.forThisQuotation ? (
        <Link to={noticePath(row.ticketId)} className={linkClass} onClick={(event) => event.stopPropagation()}>
          <span className="text-text-muted no-underline">ใบแจ้งมัดจำ</span>
          <code>{notice.docNumber ?? 'ยังไม่ออกเลข'}</code>
          <DocStatusBadge status={notice.status} />
        </Link>
      ) : null}
      {notice && !notice.forThisQuotation ? (
        <span className="text-xs text-text-muted">
          {`ใบแจ้งมัดจำของงานนี้ (${notice.docNumber ?? 'ยังไม่ออกเลข'}) อ้างอิงใบเสนอราคาอื่น`}
        </span>
      ) : null}
      {(docs.remainingInvoices ?? []).map((invoice) => (
        <Link key={invoice.id} to={invoicePath(row.ticketId)} className={linkClass} onClick={(event) => event.stopPropagation()}>
          <span className="text-text-muted no-underline">ใบแจ้งหนี้ส่วนที่เหลือ</span>
          <code>{invoice.docNumber ?? (invoice.version ? `ฉบับร่าง -${invoice.version}` : 'ยังไม่ออกเลข')}</code>
          <DocStatusBadge status={invoice.status} />
        </Link>
      ))}
    </span>
  );
}

// DataTable's gridClassName must be ONE unbroken literal (Tailwind's scanner cannot see a concatenation; see
// QuotationListPage's LIST_TABLE_GRID). Document / from-quotation / customer / [rep] / amount / date / open.
const GRID = 'grid-cols-[minmax(9rem,1.2fr)_minmax(0,1.8fr)_minmax(0,1.4fr)_minmax(8.25rem,1fr)_minmax(7.5rem,1fr)_minmax(3.5rem,0.4fr)]';
const GRID_WITH_REP = 'grid-cols-[minmax(9rem,1.2fr)_minmax(0,1.8fr)_minmax(0,1.4fr)_minmax(0,1fr)_minmax(8.25rem,1fr)_minmax(7.5rem,1fr)_minmax(3.5rem,0.4fr)]';

const KINDS = {
  deposit: {
    listKey: 'depositNotices',
    queryKey: queryKeys.depositNoticeList,
    countsKey: queryKeys.depositNoticeCounts,
    fetchList: () => api.depositNotices.listAll(),
    fetchCounts: () => api.depositNotices.counts(),
    openPath: noticePath,
    amount: (row) => row.totalPayable,
    // deposit_percent is stored as a fraction (0.5 = 50%), as DepositNoticePage reads it.
    amountLabel: (row) => `มัดจำ ${Math.round(Number(row.depositPercent) * 100)}% รวม VAT`,
    dateOf: (row) => (row.status === 'DRAFT' ? row.updatedAt : row.issueDate ?? row.updatedAt),
    dateLabel: (row) => (row.status === 'DRAFT' ? 'บันทึกล่าสุด' : 'ออก'),
    emptyTitle: 'ยังไม่มีใบแจ้งยอดเงินรับมัดจำ',
    emptyHint: 'ใบแจ้งมัดจำออกจากหน้างานของแต่ละใบเสนอราคาที่อนุมัติแล้ว',
  },
  remaining: {
    listKey: 'remainingInvoices',
    queryKey: queryKeys.remainingInvoiceList,
    countsKey: queryKeys.remainingInvoiceCounts,
    fetchList: () => api.storedRemainingInvoices.listAll(),
    fetchCounts: () => api.storedRemainingInvoices.counts(),
    openPath: invoicePath,
    amount: (row) => row.grandTotal,
    amountLabel: () => 'ยอดชำระ',
    dateOf: (row) => (row.status === 'DRAFT' ? row.updatedAt : row.docDate ?? row.updatedAt),
    dateLabel: (row) => (row.status === 'DRAFT' ? 'บันทึกล่าสุด' : 'ออก'),
    emptyTitle: 'ยังไม่มีใบแจ้งหนี้ส่วนที่เหลือ',
    emptyHint: 'ใบแจ้งหนี้ส่วนที่เหลือออกจากแท็บเงินของงาน เมื่อสินค้าถึงขั้นรับ/ส่งมอบแล้ว',
  },
};

const STATUS_CHIPS = [
  { key: 'all', label: 'ทั้งหมด', countKey: 'all' },
  { key: 'DRAFT', label: 'ฉบับร่าง', countKey: 'draft' },
  { key: 'ISSUED', label: 'ออกแล้ว', countKey: 'issued' },
];

function searchText(row) {
  const o = row.origin;
  return [row.docNumber, o.quotationNumber, o.ticketCode, o.projectName, o.customerName, o.ownerName].filter(Boolean).join(' ');
}

function QuotationRef({ origin }) {
  return (
    <span className="flex min-w-0 flex-col gap-0.5" data-testid="doc-origin">
      <span className="text-2xs text-text-muted">จากใบเสนอราคา</span>
      {origin.quotationNumber ? (
        origin.quotationId != null ? (
          <Link to={`/quotations/${origin.quotationId}`} className="text-xs font-bold text-info underline mobile:min-h-[44px] mobile:leading-[44px]"><code>{origin.quotationNumber}</code></Link>
        ) : <code className="text-xs font-bold">{origin.quotationNumber}</code>
      ) : <span className="text-xs text-text-muted">-</span>}
      <span className="text-xs text-text-muted [overflow-wrap:anywhere]">
        {'งาน '}
        <Link to={`/tickets/${origin.ticketId}`} className="text-info underline">{origin.ticketCode ?? `#${origin.ticketId}`}</Link>
        {origin.projectName ? ` · ${origin.projectName}` : ''}
      </span>
    </span>
  );
}

/** The part of a row that says what the document is: number, status, who was paid, and (invoices) its versions. */
function DocIdentity({ row, kind, olderOpen, onToggleOlder }) {
  const older = row.olderVersions ?? [];
  return (
    <span className="flex min-w-0 flex-col items-start gap-1">
      <span className="flex flex-wrap items-center gap-1.5">
        {row.docNumber ? <code className="text-xs font-bold">{row.docNumber}</code> : <span className="text-xs text-text-muted">{NO_NUMBER}</span>}
        <DocStatusBadge status={row.status} />
        {kind === 'deposit' && row.status === 'ISSUED'
          ? <StatusBadge tone={row.moneyReceived ? 'success' : 'warning'}>{row.moneyReceived ? 'รับเงินแล้ว' : 'รอรับเงิน'}</StatusBadge>
          : null}
        {kind === 'remaining' && row.version > 1 ? <StatusBadge tone="neutral">{`ฉบับที่ ${row.version}`}</StatusBadge> : null}
      </span>
      {row.pendingRevision ? (
        <span className="text-xs font-bold text-warning">{`มีฉบับร่าง -${row.pendingRevision.nextVersion} รอออก`}</span>
      ) : null}
      {older.length > 0 ? (
        <>
          <button
            type="button"
            data-testid="older-versions-toggle"
            aria-expanded={olderOpen}
            onClick={onToggleOlder}
            className="inline-flex min-h-6 items-center gap-1 border-0 bg-transparent p-0 text-xs font-bold text-info mobile:min-h-[44px]"
          >
            {`${olderOpen ? 'ซ่อน' : 'ดู'}ฉบับก่อนหน้า ${older.length}`}
            <Icon name={olderOpen ? 'chevronUp' : 'chevronDown'} size={10} />
          </button>
          {olderOpen ? older.map((v) => (
            <span key={v.id} className="flex flex-wrap items-center gap-x-2 rounded border border-dashed border-border px-2 py-1 text-2xs text-text-muted">
              <code>{v.docNumber}</code>
              <StatusBadge tone="neutral">ถูกแทนที่แล้ว</StatusBadge>
              <span>{`${formatThaiDate(v.docDate)} · ${formatMoney(v.grandTotal)}`}</span>
            </span>
          )) : null}
        </>
      ) : null}
    </span>
  );
}

function OpenLink({ to, className }) {
  return (
    <Link to={to} className={cn('inline-flex min-h-6 items-center text-xs font-bold text-info underline mobile:min-h-[44px]', className)}>
      เปิด →
    </Link>
  );
}

function DocCard({ row, kind, cfg, olderOpen, onToggleOlder, seesAll }) {
  return (
    <div className="flex min-w-0 flex-col gap-2" data-testid="doc-card">
      <DocIdentity row={row} kind={kind} olderOpen={olderOpen} onToggleOlder={onToggleOlder} />
      <strong className="min-w-0 text-md font-extrabold leading-snug text-text [overflow-wrap:anywhere]">{row.origin.customerName ?? '-'}</strong>
      <span className="text-xs text-text-muted">
        {`${cfg.dateLabel(row)} ${formatThaiDate(cfg.dateOf(row))}`}{seesAll && row.origin.ownerName ? ` · ${row.origin.ownerName}` : ''}
      </span>
      <span className="flex items-baseline justify-between gap-3">
        <span className="text-xs text-text-muted">{cfg.amountLabel(row)}</span>
        <span className="tabular-nums text-md font-extrabold text-text">{formatMoney(cfg.amount(row))}</span>
      </span>
      <span className="rounded-md bg-surface-muted px-3 py-2"><QuotationRef origin={row.origin} /></span>
      <OpenLink to={cfg.openPath(row.origin.ticketId)} className="justify-center rounded-md border border-border-input" />
    </div>
  );
}

/** Quotation-less deals have no number to link, so a row with `quotationId == null` prints it plain (see QuotationRef). */
export function DealDocumentList({ kind, user }) {
  const cfg = KINDS[kind];
  // These two lists span every deal for account as well, so account gets the rep filter and owner column here too.
  const seesAll = dealQuotationListSeesAll(user) || user?.role === 'account';
  // account and ceo never receive drafts, so a ฉบับร่าง chip would only ever read 0.
  const seesDrafts = user?.role !== 'account' && user?.role !== 'ceo';
  const [status, setStatus] = useState('all');
  const [repFilter, setRepFilter] = useState('');
  const [openOlder, setOpenOlder] = useState(() => new Set());

  const listQuery = useQuery({ queryKey: cfg.queryKey(), queryFn: () => cfg.fetchList().then((r) => r?.[cfg.listKey] ?? []) });
  const countsQuery = useQuery({ queryKey: cfg.countsKey(), queryFn: () => cfg.fetchCounts().then((r) => r ?? null) });
  const rows = useMemo(() => listQuery.data ?? [], [listQuery.data]);
  const counts = countsQuery.data ?? null;

  const repOptions = useMemo(
    () => (seesAll ? dealQuotationRepOptions(rows.map((r) => ({ salesRepId: r.origin.ownerId, salesRepName: r.origin.ownerName }))) : []),
    [seesAll, rows],
  );
  const effectiveRep = repOptions.some((o) => o.id === repFilter) ? repFilter : '';
  const shown = useMemo(
    () => rows.filter((r) => (status === 'all' || r.status === status) && (!effectiveRep || String(r.origin.ownerId) === effectiveRep)),
    [rows, status, effectiveRep],
  );
  const toggleOlder = (id) => setOpenOlder((cur) => {
    const next = new Set(cur);
    if (!next.delete(id)) next.add(id);
    return next;
  });

  const columns = useMemo(() => [
    {
      key: 'doc',
      header: 'เอกสาร',
      searchAccessor: searchText,
      render: (row) => <DocIdentity row={row} kind={kind} olderOpen={openOlder.has(row.id)} onToggleOlder={() => toggleOlder(row.id)} />,
    },
    { key: 'origin', header: 'จากใบเสนอราคา / งาน', render: (row) => <QuotationRef origin={row.origin} /> },
    { key: 'customer', header: 'ลูกค้า', render: (row) => <span className="text-sm font-bold">{row.origin.customerName ?? '-'}</span> },
    ...(seesAll ? [{ key: 'owner', header: 'พนักงานขาย', render: (row) => <span className="text-xs">{row.origin.ownerName ?? '-'}</span> }] : []),
    {
      key: 'amount',
      header: 'ยอด',
      align: 'right',
      render: (row) => (
        <span className="flex flex-col items-end">
          <span className="tabular-nums text-sm font-bold">{formatMoney(cfg.amount(row))}</span>
          <span className="text-2xs text-text-muted">{cfg.amountLabel(row)}</span>
        </span>
      ),
    },
    { key: 'date', header: 'วันที่', render: (row) => <span className="text-xs">{`${cfg.dateLabel(row)} ${formatThaiDate(cfg.dateOf(row))}`}</span> },
    { key: 'open', header: '', render: (row) => <OpenLink to={cfg.openPath(row.origin.ticketId)} /> },
  ], [cfg, kind, seesAll, openOlder]);

  return (
    <div data-testid={`doc-list-${kind}`}>
      <div className="mb-3 flex flex-wrap items-end gap-3">
        <div className="flex flex-wrap items-center gap-2" role="group" aria-label="กรองตามสถานะเอกสาร">
          {STATUS_CHIPS.filter((chip) => chip.key !== 'DRAFT' || seesDrafts).map((chip) => {
            const active = status === chip.key;
            const count = counts?.[chip.countKey];
            return (
              <button
                key={chip.key}
                type="button"
                aria-pressed={active}
                onClick={() => setStatus(chip.key)}
                className={cn(
                  'inline-flex min-h-8 items-center gap-1.5 rounded-full border px-3 text-xs font-bold mobile:min-h-[44px]',
                  active ? 'border-primary bg-primary/10 text-primary' : 'border-border bg-surface hover:bg-surface-hover',
                )}
              >
                {chip.label}
                {Number.isFinite(count) ? <span className="tabular-nums rounded-full bg-surface-subtle px-1.5 text-2xs text-text-muted">{count}</span> : null}
              </button>
            );
          })}
        </div>
        {repOptions.length > 0 ? (
          <label className="flex flex-col gap-1">
            <span className="text-2xs font-extrabold uppercase tracking-wide text-text-muted">พนักงานขาย</span>
            <select
              value={effectiveRep}
              onChange={(event) => setRepFilter(event.target.value)}
              aria-label="กรองตามพนักงานขาย"
              className="h-8 w-auto rounded border border-border bg-surface px-2 text-xs text-text mobile:min-h-[44px]"
            >
              <option value="">ทั้งหมด</option>
              {repOptions.map((option) => <option key={option.id} value={option.id}>{option.name}</option>)}
            </select>
          </label>
        ) : null}
      </div>
      <DataTable
        columns={columns}
        rows={shown}
        unfilteredTotal={rows.length}
        getRowKey={(row) => row.id}
        gridClassName={seesAll ? GRID_WITH_REP : GRID}
        mobileCard={(row) => <DocCard row={row} kind={kind} cfg={cfg} olderOpen={openOlder.has(row.id)} onToggleOlder={() => toggleOlder(row.id)} seesAll={seesAll} />}
        searchable
        searchPlaceholder="ค้นหาลูกค้า / เลขที่เอกสาร / เลขใบเสนอราคา / โครงการ"
        loading={listQuery.isLoading}
        error={listQuery.isError}
        onRetry={() => listQuery.refetch()}
        emptyState={{
          icon: 'fileText',
          title: rows.length === 0 ? cfg.emptyTitle : 'ไม่มีเอกสารในเงื่อนไขนี้',
          description: rows.length === 0 ? cfg.emptyHint : undefined,
        }}
      />
    </div>
  );
}
