import { useMemo, useState } from 'react';
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
  canApproveDealQuotation, canCreateDealQuotationStandalone, DEAL_QUOTATION_AMOUNT_OPTIONS,
  DEAL_QUOTATION_EMAIL_OPTIONS, DEAL_QUOTATION_STATUS_TABS, dealQuotationListSeesAll, dealQuotationRepOptions,
  dealQuotationStatusLabel, dealQuotationStatusTab, defaultDealQuotationStatusTab,
  filterDealQuotationRows, isDealQuotationReadOnlyViewer,
} from './quotationMeta.js';

// DataTable's `gridClassName` becomes the desktop `<tr>`'s own class; the shared
// `.table-head`/`.data-row` rules in styles.css set `display:grid` with no
// `grid-template-columns`. A Tailwind arbitrary-value utility supplies it without a page-specific
// CSS class (CLAUDE.md: "Do not add new page-specific CSS files"): เลขที่ / ลูกค้า·โครงการ /
// พนักงานขาย / ยอดรวม / สถานะ / วันที่, six columns weighted by how much text they carry.
//
// ── Column floors: FIXED, identical in every row ──────────────────────────────────────────────
// `minmax(0, …)` lets the grid shrink a column BELOW its own text, and the cells are
// `white-space: nowrap` with `overflow: clip` (styles.css's `.data-row > td`), so the loss appears
// as SILENTLY truncated content: at a 721px viewport `QT-2026-0005-2` rendered `QT-2026-000…`, and
// the `-N` suffix is the ONLY thing distinguishing a revision from the document it replaces.
//
// The floors are FIXED lengths, never `min-content`: the head row and each data row are SEPARATE
// grid containers, so `min-content` sizes every row from ITS OWN content and the columns stagger
// row to row (same fix as QuotationDocumentView.jsx's ITEM_GRID).
//
// Sized from each column's widest realistic value in the live cell typography, rounded up. Money
// and dates are sized against the FALLBACK font, not Sarabun: Sarabun arrives from Google Fonts
// with `display=swap`, and on the on-prem deployment (if fonts.googleapis.com is unreachable)
// `system-ui` renders permanently, ~14% wider.
//
//   เลขที่   6.875rem/110px  `QT-2026-9999-99` = 108.4px in the monospace stack (a system stack, so
//                            no swap risk). Cannot wrap: breaking a document number mid-token would
//                            be worse than truncating.
//   ยอดรวม  8.25rem/132px   `฿99,999,999.99` = 114px in Sarabun but 130.5px in the fallback. The
//                            same floor QuotationDocumentView.jsx uses for เป็นเงิน, deliberately:
//                            a grand total is at least as large as any line total on it.
//   สถานะ   4.75rem/76px    the widest badge label, อนุมัติแล้ว, plus the badge's own padding.
//   วันที่   6.5rem/104px    ALL TWELVE months measured: `เม.ย.` is the widest (four glyph
//                            clusters) at 100.13px in the fallback; `พ.ค.` 96.74 and `ต.ค.` 96.16
//                            have under 2px to spare. Never size a Thai date column from one
//                            sample month. The cell renders a bare string, so it truncates rather
//                            than wraps (see the note on พนักงานขาย below for why it is NOT wrapped
//                            in a <span>).
//   พนักงานขาย —            NO floor, deliberately. It wraps, and the longest real surname
//                            (`วงศ์ประเสริฐ`, 82.5px) exceeds any floor this budget can afford, so
//                            a floor could never prevent a mid-word break. Both free-text columns
//                            share what the four floored columns leave, by their `fr` weights.
//
// ลูกค้า / โครงการ and พนักงานขาย keep `minmax(0, …)`: they are the two columns of free text, both
// wrap, and so they are the two that can give up width without losing anything.
//
// ⚠️ This string must stay ONE unbroken literal. Splitting it across a `+` concatenation leaves
// Tailwind's scanner unable to see the arbitrary value, so the rule is never emitted,
// `grid-template-columns` goes unset, and the row collapses to a single implicit track. Lint, the
// unit suite and `npm run build` ALL stay green; only reading `dist/assets/*.css` shows it. The
// guard test asserts this file's own source text for that reason.
//
// ⚠️ At a browser root font-size above about 18.6px the floors (30rem total) exceed the 559px the
// row has at a 721px viewport. The wrapping <section> is `overflow-x-auto`, so that degrades to a
// horizontal scrollbar rather than losing anything.
const LIST_TABLE_GRID = 'grid-cols-[minmax(6.875rem,1fr)_minmax(0,2.2fr)_minmax(0,1.3fr)_minmax(8.25rem,1.1fr)_minmax(4.75rem,1fr)_minmax(6.5rem,1fr)]';

// The tab set is DEAL_QUOTATION_STATUS_TABS (quotationMeta.js). ร่าง and ถูกแทนที่ are deliberately
// NOT tabs: a plain untouched draft is the rep's own working copy and a superseded document is
// history. Both still render their own badge on a row reached any other way
// (dealQuotationStatusLabel knows all five).
//
// Approvers (sales_manager/ceo) lead with the "รออนุมัติ" queue, the work waiting on THEM.
// sales/import/account have no approval queue of their own, so they land on "ทั้งหมด".
// canApproveDealQuotation (quotationMeta.js) is the single source of truth, shared with the
// editor's own อนุมัติ/ไม่อนุมัติ button gate.

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
 * ใบเสนอราคา list. Route-guarded by ROLE_PERMISSIONS.canViewDealQuotations; see
 * app/permissions.js PATH_GUARDS for '/quotations'. `sales` receives only its own deals'
 * quotations already scoped server-side (see dealQuotations.list's doc comment) -- this page adds
 * no client-side ownership filter.
 */
export function QuotationListPage({ user }) {
  const queryClient = useQueryClient();
  const isApprover = canApproveDealQuotation(user);

  // The active tab lives in the URL (`?status=`), not in component state, so a tab is linkable,
  // survives a refresh and a back button, and an approver can paste "the รออนุมัติ queue" to a
  // colleague. An ABSENT param means "this role's default tab"; an unrecognised one falls back to
  // the same, rather than rendering an empty list for a typo'd or retired key.
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

  // Rep filter: only sales_manager/ceo see every deal on this list, so only they get a "filter by
  // พนักงานขาย" control; everyone else's rows are already scoped to their own deals server-side.
  // Options are derived from the CURRENT rows (quotationMeta.js's doc explains why), so switching
  // status tabs can shrink the option set out from under a previously-picked value; the
  // `repOptions.some(...)` fallback below keeps a stale repFilter from silently hiding every row.
  // The อีเมลลูกค้า filter is not a scope question, so EVERY role gets it.
  const seesAll = dealQuotationListSeesAll(user);
  const repOptions = useMemo(() => (seesAll ? dealQuotationRepOptions(rows) : []), [seesAll, rows]);
  const [repFilter, setRepFilter] = useState('');
  const [amountFilter, setAmountFilter] = useState('');
  const [emailFilter, setEmailFilter] = useState('');
  const effectiveRepFilter = seesAll && repOptions.some((option) => option.id === repFilter) ? repFilter : '';
  const effectiveAmountFilter = seesAll ? amountFilter : '';
  const filteredRows = useMemo(
    () => filterDealQuotationRows(rows, { repId: effectiveRepFilter, amount: effectiveAmountFilter, email: emailFilter }),
    [rows, effectiveRepFilter, effectiveAmountFilter, emailFilter],
  );

  // The filters sit in a plain row ABOVE DataTable's own search input (see the JSX below, shared by
  // every breakpoint), so column headers stay plain text at every width and `columns` does not
  // diverge by role.
  const columns = COLUMNS;

  // One request for all five tab counts, scoped server-side exactly like the list itself. Counts
  // are advisory chrome: a failed or in-flight counts call renders the tabs with NO number rather
  // than blocking the list or showing a zero that would read as "nothing here".
  const countsQuery = useQuery({
    queryKey: queryKeys.dealQuotationCounts(),
    // The BARE DealQuotationCountsDto: DealQuotationController#counts returns the record with no
    // envelope. No `r?.counts ?? r` tolerance, so a disagreement between the controller, hrApi's
    // comment and the mock surfaces in the contract test and the mock shape test.
    queryFn: () => api.dealQuotations.counts().then((r) => r ?? null),
  });
  const counts = countsQuery.data ?? null;

  return (
    <PageStack>
      <PageHeader
        title="ใบเสนอราคา"
        // "ของคุณ" is only true for the rep whose own rows these are. import/account are read-only
        // viewers of OTHER people's quotations (isDealQuotationReadOnlyViewer), so the possessive
        // would be wrong for them: a third branch rather than a reworded second one, because the
        // approver line is right as it stands.
        subtitle={isApprover
          ? 'ใบเสนอราคาที่รอการอนุมัติ และทั้งหมด'
          : isDealQuotationReadOnlyViewer(user)
            ? 'ใบเสนอราคาทั้งหมด (ดูอย่างเดียว)'
            : 'ใบเสนอราคาของคุณ'}
        actions={(
          <>
            {canCreateDealQuotationStandalone(user) ? (
              // The entry point into /quotations/new with no ?ticket=: the same role-only gate the
              // editor page checks before rendering DealCustomerCard for that URL, so this link and
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
        {/* Filter row: พนักงานขาย/ยอดรวม for see-all roles (sales_manager/ceo), อีเมลลูกค้า for everyone -- moved OUT of the
            column headers and rendered here, above DataTable's own
            search input (which renders inside `data-table-toolbar`), at every breakpoint. Not
            via DataTable's `toolbarExtra`: that prop renders INLINE alongside the search field in
            the same flex-wrap row, so on a narrow viewport it wraps BELOW the search box only
            because it comes second in DOM order, not clearly above it the way this block is.
            Compact, content-sized selects (not full-width) — this is a filter chip row, not a
            form — wrapping onto a second line on a narrow screen instead of stretching full width.
            44px touch target restored under `mobile:` even though the box itself stays small. */}
        <div className="mb-3 flex flex-wrap items-end gap-3">
          {seesAll ? (
            <>
              {repOptions.length > 0 ? (
                <label className="flex flex-col gap-1">
                  <span className="text-2xs font-extrabold uppercase tracking-wide text-text-muted">พนักงานขาย</span>
                  <select
                    value={effectiveRepFilter}
                    onChange={(event) => setRepFilter(event.target.value)}
                    aria-label="กรองตามพนักงานขาย"
                    className="h-8 mobile:min-h-[44px] w-auto rounded border border-border bg-surface px-2 text-xs text-text"
                  >
                    <option value="">ทั้งหมด</option>
                    {repOptions.map((option) => (
                      <option key={option.id} value={option.id}>{option.name}</option>
                    ))}
                  </select>
                </label>
              ) : null}
              <label className="flex flex-col gap-1">
                <span className="text-2xs font-extrabold uppercase tracking-wide text-text-muted">ยอดรวม</span>
                <select
                  value={effectiveAmountFilter}
                  onChange={(event) => setAmountFilter(event.target.value)}
                  aria-label="กรองตามยอดรวม"
                  className="h-8 mobile:min-h-[44px] w-auto rounded border border-border bg-surface px-2 text-xs text-text"
                >
                  <option value="">ทั้งหมด</option>
                  {DEAL_QUOTATION_AMOUNT_OPTIONS.map((option) => (
                    <option key={option.value} value={option.value}>{option.label}</option>
                  ))}
                </select>
              </label>
            </>
          ) : null}
          <label className="flex flex-col gap-1">
            <span className="text-2xs font-extrabold uppercase tracking-wide text-text-muted">อีเมลลูกค้า</span>
            <select
              value={emailFilter}
              onChange={(event) => setEmailFilter(event.target.value)}
              aria-label="กรองตามอีเมลลูกค้า"
              className="h-8 mobile:min-h-[44px] w-auto rounded border border-border bg-surface px-2 text-xs text-text"
            >
              <option value="">ทั้งหมด</option>
              {DEAL_QUOTATION_EMAIL_OPTIONS.map((option) => (
                <option key={option.value} value={option.value}>{option.label}</option>
              ))}
            </select>
          </label>
        </div>

        <DataTable
          columns={columns}
          rows={filteredRows}
          unfilteredTotal={rows.length}
          getRowKey={(row) => row.id}
          gridClassName={LIST_TABLE_GRID}
          mobileCard={(row) => <QuotationCard row={row} />}
          searchable
          searchPlaceholder="ค้นหาลูกค้า / โครงการ"
          // The filters render outside DataTable and are always visible regardless of this flag, as
          // is DataTable's own search box. What this keeps is the COLUMN HEADER ROW when a filter
          // narrows the list to zero rows, so the table keeps its shape under the empty-state line.
          // See-all roles always; everyone else only while their email filter is set, so a rep with
          // genuinely no quotations still gets the plain empty-state panel.
          retainHeaderWhenEmpty={seesAll || emailFilter !== ''}
          loading={listQuery.isLoading}
          emptyState={{ icon: 'fileText', title: 'ไม่มีใบเสนอราคาในเงื่อนไขนี้' }}
        />
      </div>
    </PageStack>
  );
}
