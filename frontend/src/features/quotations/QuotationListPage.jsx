import { useMemo } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useSearchParams } from 'react-router-dom';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { Button, buttonVariants } from '../../components/common/Button.jsx';
import { DataTable } from '../../components/common/DataTable.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { PageStack } from '../../components/common/Layout.jsx';
import { PageHeader } from '../../components/common/PageHeader.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { cn } from '../../utils/cn.js';
import { formatMoney, formatThaiDate } from '../../utils/format.js';
import {
  canApproveDealQuotation, canCreateDealQuotationStandalone, DEAL_QUOTATION_STATUS_TABS,
  dealQuotationStatusLabel, dealQuotationStatusTab, defaultDealQuotationStatusTab,
  isDealQuotationReadOnlyViewer,
} from './quotationMeta.js';

// DataTable's `gridClassName` becomes the desktop `<tr>`'s own class (alongside the shared
// `.table-head`/`.data-row` base rules in styles.css, which set `display:grid` with no
// `grid-template-columns` of their own -- see that file's `.table-head, .data-row` rule).
// Every other DataTable caller supplies that missing piece as a bespoke, page-specific class
// declared in styles.css (e.g. `.pricing-request-queue-table`) -- the legacy pattern this repo
// is retiring (CLAUDE.md: "Do not add new page-specific CSS files"). A Tailwind arbitrary-value
// utility expresses the same `grid-template-columns` without one: เลขที่ / ลูกค้า·โครงการ /
// พนักงานขาย / ยอดรวม / สถานะ / วันที่, six columns weighted by how much text they carry.
//
// ── Column floors: FIXED, identical in every row ──────────────────────────────────────────────
// Six `minmax(0, …)` tracks used to be the whole story, and `minmax(0, …)` lets the grid shrink a
// column BELOW its own text. The cells are `white-space: nowrap` with `overflow: clip`
// (styles.css's `.data-row > td`), so that loss never appears as overflow — it appears as SILENTLY
// truncated content. Measured at a 721px viewport (655px row, 559px of content budget after 36px
// padding and 60px of gaps): เลขที่ lost 28px, ลูกค้า/โครงการ 29px, พนักงานขาย 13px and วันที่ 14px,
// and `QT-2026-0005-2` rendered `QT-2026-000…` — the `-N` suffix is the ONLY thing distinguishing a
// revision from the document it replaces, so of everything on this row it is the worst to lose.
//
// The floors are FIXED lengths, never `min-content`: the head row and each data row are SEPARATE
// grid containers, so `min-content` sizes every row from ITS OWN content and the columns then
// stagger row to row (QuotationDocumentView.jsx shipped that by mistake and a review measured 23px
// of drift — read that file's ITEM_GRID comment, this is the same fix).
//
// Sized from each column's widest realistic value, measured in the live cell typography and
// rounded up. Money and dates are sized against the FALLBACK font, not Sarabun: Sarabun arrives
// from Google Fonts with `display=swap`, so during the swap window — and permanently on the on-prem
// deployment if that host cannot reach fonts.googleapis.com — `system-ui` renders instead and every
// figure is ~14% wider.
//
//   เลขที่   6.875rem/110px  `QT-2026-9999-99` = 108.4px in the monospace stack (a system stack, so
//                            no swap risk). Cannot wrap: breaking a document number mid-token would
//                            be worse than the truncation this replaces.
//   ยอดรวม  8.25rem/132px   `฿99,999,999.99` = 114px in Sarabun but 130.5px in the fallback. The
//                            same floor QuotationDocumentView.jsx uses for เป็นเงิน, deliberately —
//                            a grand total is at least as large as any line total on it.
//   สถานะ   4.75rem/76px    the widest badge label, อนุมัติแล้ว, plus the badge's own padding.
//   วันที่   6.125rem/98px   `30 พ.ย. 2569` = 95.9px in the fallback.
//   พนักงานขาย 4rem/64px     a floor only, not a fit: this column WRAPS (see its render), so it
//                            needs just enough to keep the longer of the two name words on a line.
//
// ลูกค้า / โครงการ keeps `minmax(0, …)` and is the designated absorber — it is the one column
// carrying free text over two stacked lines, so it can give up width without losing anything.
const LIST_TABLE_GRID = 'grid-cols-[minmax(6.875rem,1fr)_minmax(0,2.2fr)_minmax(4rem,1.3fr)_minmax(8.25rem,1.1fr)_minmax(4.75rem,1fr)_minmax(6.125rem,1fr)]';

// The tab set itself is DEAL_QUOTATION_STATUS_TABS (quotationMeta.js) — owner feedback F5,
// 2026-09-10 replaced the old six status chips (ทั้งหมด/รออนุมัติ/ร่าง/อนุมัติแล้ว/ถูกแทนที่/
// ยกเลิก) with the five the owner asked for. ร่าง and ถูกแทนที่ are deliberately GONE as tabs: a
// plain untouched draft is the rep's own working copy and never needed a queue of its own, and a
// superseded document is history. Both statuses still render their own badge on a row reached any
// other way, so nothing became unreachable — dealQuotationStatusLabel still knows all five.
//
// Approvers (sales_manager/ceo) lead with the "รออนุมัติ" queue -- that is the work waiting on
// THEM. sales/import/account have no approval queue of their own, so they land on "ทั้งหมด".
// #L7: canApproveDealQuotation (quotationMeta.js) instead of a page-local role set -- same
// role-only gate, single source of truth with the editor's own อนุมัติ/ไม่อนุมัติ button gate.

const COLUMNS = [
  {
    key: 'number',
    header: 'เลขที่',
    sortable: true,
    searchAccessor: (row) => row.number,
    render: (row) => (
      <Link to={`/quotations/${row.id}`} className="text-xs text-info underline">
        <code>{row.number}</code>
      </Link>
    ),
  },
  {
    key: 'customer',
    header: 'ลูกค้า / โครงการ',
    searchAccessor: (row) => `${row.customerName ?? ''} ${row.projectName ?? ''}`,
    // <span>, NOT <div>, and that is load-bearing rather than a style preference. styles.css
    // clips on the CELL — `.data-row > td` is `white-space: nowrap; text-overflow: ellipsis` — and
    // its wrap escape hatch only reaches `> strong`, `> small` and `> span`. A <div> child
    // therefore inherits the nowrap and this column cannot absorb any squeeze at all, which is
    // what made it lose 29px at 721px. `flex flex-col` still stacks the two lines: a Tailwind
    // utility beats the escape hatch's `display: block` on layer order (styles.css is
    // `layer(legacy)`), so only the wrapping changes.
    render: (row) => (
      <span className="flex flex-col">
        <span className="font-bold">{row.customerName ?? '-'}</span>
        {row.projectName ? <span className="text-2xs text-text-muted">{row.projectName}</span> : null}
      </span>
    ),
  },
  {
    key: 'salesRepName',
    header: 'พนักงานขาย',
    searchAccessor: (row) => row.salesRepName,
    // Wrapped in a <span> for the same reason as ลูกค้า above: a bare string leaves the cell with
    // no child element, so styles.css's wrap escape hatch has nothing to match and a two-word Thai
    // name is truncated rather than broken across two lines. `ภิญญาดา วงศ์ประเสริฐ` needs 147.8px
    // on one line and this column is floored at 64px, so wrapping is what keeps it whole.
    render: (row) => <span>{row.salesRepName ?? '-'}</span>,
  },
  {
    key: 'grandTotal',
    header: 'ยอดรวม',
    align: 'right',
    sortable: true,
    render: (row) => <span className="tabular-nums font-bold">{formatMoney(row.grandTotal)}</span>,
  },
  {
    key: 'status',
    header: 'สถานะ',
    sortable: true,
    searchAccessor: (row) => row.docStatus,
    render: (row) => {
      const status = dealQuotationStatusLabel(row.docStatus);
      return <StatusBadge tone={status.tone}>{status.label}</StatusBadge>;
    },
  },
  {
    key: 'quotationDate',
    header: 'วันที่',
    sortable: true,
    render: (row) => formatThaiDate(row.quotationDate),
  },
];

function QuotationCard({ row }) {
  const status = dealQuotationStatusLabel(row.docStatus);
  return (
    <>
      <div className="flex min-w-0 items-start justify-between gap-3">
        <Link to={`/quotations/${row.id}`} className="min-w-0 truncate text-xs text-info underline" onClick={(event) => event.stopPropagation()}>
          <code>{row.number}</code>
        </Link>
        <StatusBadge tone={status.tone}>{status.label}</StatusBadge>
      </div>
      <strong className="min-w-0 truncate text-md leading-snug font-extrabold text-text">
        {row.customerName ?? '-'}
      </strong>
      {row.projectName ? <span className="min-w-0 truncate text-xs text-text-muted">{row.projectName}</span> : null}
      <span className="min-w-0 truncate text-xs text-text-muted">
        {row.salesRepName ?? '-'} · {formatThaiDate(row.quotationDate)}
      </span>
      <span className="tabular-nums text-md font-extrabold text-text">{formatMoney(row.grandTotal)}</span>
    </>
  );
}

/**
 * ใบเสนอราคา list — Quotation v2 (QUOTATION-V2-PLAN.md). Route-guarded by
 * ROLE_PERMISSIONS.canViewDealQuotations; see app/permissions.js PATH_GUARDS for '/quotations'.
 * `sales` receives only its own deals' quotations already scoped server-side (see
 * dealQuotations.list's own doc comment) -- this page adds no client-side ownership filter on
 * top of that.
 */
export function QuotationListPage({ user }) {
  const queryClient = useQueryClient();
  const isApprover = canApproveDealQuotation(user);

  // The active tab lives in the URL (owner feedback F5: "driven by the URL (`?status=`)"), not in
  // component state -- so a tab is linkable, survives a refresh and a back button, and an approver
  // can paste "the รออนุมัติ queue" to a colleague and have them see the same thing. An ABSENT
  // param means "this role's default tab"; an unrecognised one falls back to the same, rather than
  // rendering an empty list for a typo'd or retired key.
  const [searchParams, setSearchParams] = useSearchParams();
  const requestedKey = searchParams.get('status');
  const activeTab = dealQuotationStatusTab(requestedKey)
    ?? dealQuotationStatusTab(defaultDealQuotationStatusTab(user))
    ?? DEAL_QUOTATION_STATUS_TABS[0];

  function selectTab(key) {
    // ALWAYS writes the key, ทั้งหมด included. Clearing the param instead would mean "the role
    // default" — so an approver clicking ทั้งหมด would silently bounce straight back to รออนุมัติ,
    // making that tab unreachable for exactly the role that has a default other than it.
    // `replace` so clicking through five tabs does not bury the page the rep arrived from under
    // five history entries.
    setSearchParams({ status: key }, { replace: true });
  }

  const listQuery = useQuery({
    queryKey: queryKeys.dealQuotationsList(activeTab.params),
    queryFn: () => api.dealQuotations.list(activeTab.params).then((r) => r.items ?? []),
  });
  const rows = useMemo(() => listQuery.data ?? [], [listQuery.data]);

  // One request for all five tab counts, scoped server-side exactly like the list itself. Counts
  // are advisory chrome: a failed or in-flight counts call renders the tabs with NO number rather
  // than blocking the list or showing a zero that would read as "nothing here".
  const countsQuery = useQuery({
    queryKey: queryKeys.dealQuotationCounts(),
    // The BARE DealQuotationCountsDto — DealQuotationController#counts returns the record with no
    // envelope. This used to read `r?.counts ?? r`, which meant a real disagreement between the
    // controller, hrApi's comment and the mock could never surface as a failure (review finding
    // MED-3); the tolerance is gone so the contract test and the mock shape test are the ones that
    // have to be right.
    queryFn: () => api.dealQuotations.counts().then((r) => r ?? null),
  });
  const counts = countsQuery.data ?? null;

  return (
    <PageStack>
      <PageHeader
        title="ใบเสนอราคา"
        // "ของคุณ" is only true for the rep whose own rows these are. import/account are
        // read-only viewers of OTHER people's quotations (isDealQuotationReadOnlyViewer), so the
        // possessive was simply wrong for them — a third branch rather than a reworded second one,
        // because the approver line is right as it stands.
        subtitle={isApprover
          ? 'ใบเสนอราคาที่รอการอนุมัติ และทั้งหมด'
          : isDealQuotationReadOnlyViewer(user)
            ? 'ใบเสนอราคาทั้งหมด (ดูอย่างเดียว)'
            : 'ใบเสนอราคาของคุณ'}
        actions={(
          <>
            {canCreateDealQuotationStandalone(user) ? (
              // Owner ask 2026-09-10 ("inline deal creation"): the entry point into
              // /quotations/new with no ?ticket= -- the same role-only gate the editor page
              // itself checks before rendering DealCustomerCard for that URL, so this link and
              // the page it leads to never disagree about who may land there.
              <Link to="/quotations/new" className={cn(buttonVariants({ variant: 'primary', size: 'sm' }))}>
                <Icon name="plus" size={14} />
                สร้างใบเสนอราคา
              </Link>
            ) : null}
            <Button
              type="button"
              variant="icon"
              onClick={() => queryClient.invalidateQueries({ queryKey: ['dealQuotations'] })}
              title="รีเฟรช"
              aria-label="รีเฟรช"
            >
              <Icon name="refresh" />
            </Button>
          </>
        )}
      />

      {/* `role="tablist"` rather than a bare row of buttons: these five ARE tabs over one list,
          and arrow-key semantics plus aria-selected is what a screen reader needs to say so. The
          panel they control is the table below. */}
      <div className="flex flex-wrap items-center gap-2 rounded-lg border border-border bg-surface p-3" role="tablist" aria-label="สถานะใบเสนอราคา">
        <span className="text-2xs font-extrabold uppercase tracking-wide text-text-muted">สถานะ</span>
        {DEAL_QUOTATION_STATUS_TABS.map((tab) => {
          const active = activeTab.key === tab.key;
          const count = counts?.[tab.countKey];
          return (
            <button
              key={tab.key}
              type="button"
              role="tab"
              aria-selected={active}
              aria-controls="quotation-list-panel"
              className={`inline-flex min-h-8 mobile:min-h-[44px] items-center gap-1.5 rounded-full border px-3 text-xs font-bold ${
                active ? 'border-primary bg-primary/10 text-primary' : 'border-border bg-surface hover:bg-surface-hover'
              }`}
              onClick={() => selectTab(tab.key)}
            >
              {tab.label}
              {Number.isFinite(count) ? (
                <span className={`tabular-nums rounded-full px-1.5 text-2xs ${active ? 'bg-primary/15' : 'bg-surface-subtle text-text-muted'}`}>
                  {count}
                </span>
              ) : null}
            </button>
          );
        })}
      </div>
      <div id="quotation-list-panel" role="tabpanel">

        <DataTable
          columns={COLUMNS}
          rows={rows}
          getRowKey={(row) => row.id}
          gridClassName={LIST_TABLE_GRID}
          mobileCard={(row) => <QuotationCard row={row} />}
          searchable
          loading={listQuery.isLoading}
          emptyState={{ icon: 'fileText', title: 'ไม่มีใบเสนอราคาในเงื่อนไขนี้' }}
        />
      </div>
    </PageStack>
  );
}
