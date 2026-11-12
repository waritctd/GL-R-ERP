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
import { bangkokTodayIso, formatMoney, formatThaiDate } from '../../utils/format.js';
import { partyStatusLabel } from '../tickets/dealPartyMeta.js';
import {
  canApproveDealQuotation, canCreateDealQuotationStandalone, DEAL_QUOTATION_AMOUNT_OPTIONS,
  DEAL_QUOTATION_EMAIL_OPTIONS, DEAL_QUOTATION_PERIOD_OPTIONS, DEAL_QUOTATION_STATUS_TABS, dealQuotationListSeesAll, dealQuotationRepOptions,
  dealQuotationPeriodStart, dealQuotationStatusLabel, dealQuotationStatusTab, defaultDealQuotationStatusTab,
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

// ── "จัดกลุ่มตามโครงการ" (deal-party-model.md, prototype screen 1) ─────────────────────────────
// EVERY quotation sits under its deal's group row, a deal with a single quotation included, so every row
// reads the same way: the deal (and its project) on top, its quotations nested beneath. Grouping is always on. The rows
// of a group come out inside DataTable's own row mechanism (a synthetic row whose first cell spans the
// grid, the rest hidden), so search, pagination and the mobile cards all keep working. Grouping turns
// column sorting off: a sort would scatter a group's rows away from their header.
//
// Revisions of one document (the same number with a `-<n>` suffix: QT-2026-0303-1 … -5) are listed under
// the version in force (the newest approved one, else the newest). They are OPEN by default; the
// document's "ซ่อนเวอร์ชันก่อนหน้า N" button folds them away.
const IN_FORCE_STATUSES = new Set(['APPROVED', 'ACCEPTED', 'ISSUED', 'SENT']);
const EMPTY_SET = new Set();

// Only a 1-2 digit tail is a revision suffix: a bare number such as QT-2026-0901 ends in a 4-digit
// running sequence and must never be folded with its neighbours (same rule as dealDocumentVersions.js).
const REVISION_SUFFIX = /-(\d{1,2})$/;

function versionOf(row) {
  const m = REVISION_SUFFIX.exec(String(row.number ?? ''));
  return m ? Number(m[1]) : null;
}

/** The versions of ONE document, newest first, the one in force (newest approved, else newest) split off. */
function splitInForce(versions) {
  const newestFirst = [...versions].sort((a, b) => (versionOf(b) ?? 0) - (versionOf(a) ?? 0) || Number(b.id) - Number(a.id));
  const inForce = newestFirst.find((v) => IN_FORCE_STATUSES.has(v.docStatus)) ?? newestFirst[0];
  return { inForce, older: newestFirst.filter((v) => v !== inForce) };
}

const KNOWN_ROLES = ['DESIGNER', 'OWNER', 'BUYER'];

// A row that takes part in the bid: a contractor's quotation, or one whose recipient was never recorded (every
// contractor bid from before the field existed, V205's merged sibling bids among them). Designer and owner
// quotations are not bids, so they get no number, no outcome chip and do not make a deal "รอผลประมูล".
function isBidRow(row) {
  return row.recipientType !== 'DESIGNER' && row.recipientType !== 'OWNER';
}

function countRoles(members) {
  const seen = { DESIGNER: new Set(), OWNER: new Set(), BUYER: new Set() };
  for (const m of members) {
    if (seen[m.recipientType]) seen[m.recipientType].add(m.partyId ?? m.customerName ?? m.id);
  }
  return Object.fromEntries(KNOWN_ROLES.map((r) => [r, seen[r].size]));
}

/**
 * The rows in server order, each deal's rows pulled together under a group row placed where its newest row
 * was. Member rows are copied with `inGroup: true`. Older versions are open unless their document key is in
 * `collapsed`; `onToggleVersions(docKey)` is what a row's version button calls.
 */
export function groupQuotationRows(rows, { collapsed = EMPTY_SET, onToggleVersions = null } = {}) {
  const byTicket = new Map();
  for (const row of rows) {
    if (row.ticketId == null) continue;
    if (!byTicket.has(row.ticketId)) byTicket.set(row.ticketId, []);
    byTicket.get(row.ticketId).push(row);
  }
  const out = [];
  const emitted = new Set();
  for (const row of rows) {
    const members = byTicket.get(row.ticketId);
    if (!members) { out.push(row); continue; }
    if (emitted.has(row.ticketId)) continue;
    emitted.add(row.ticketId);
    const headline = members.find((m) => m.partyHeadline) ?? null;
    const searchText = members.map((m) => `${m.number} ${m.customerName ?? ''} ${m.projectName ?? ''} ${m.salesRepName ?? ''} ${m.docStatus ?? ''}`).join(' ');
    // Numbered per contractor: one party's quotations sit together under one number, the ยอดหลัก
    // party first, the rest in server order.
    const byParty = new Map();
    for (const m of members) {
      const key = m.partyId ?? `quotation-${m.id}`;
      if (!byParty.has(key)) byParty.set(key, []);
      byParty.get(key).push(m);
    }
    const parties = [...byParty.values()].sort((a, b) => Number(b.some((m) => m.partyHeadline)) - Number(a.some((m) => m.partyHeadline)));
    const bidders = parties.filter((party) => party.some(isBidRow));
    out.push({
      isGroup: true,
      id: `group-${row.ticketId}`,
      ticketId: row.ticketId,
      ticketCode: row.ticketCode,
      projectName: members.find((m) => m.projectName)?.projectName ?? null,
      partyCount: parties.length,
      // Parties bidding (contractors, plus recipients never recorded): more than one makes the deal a bid.
      bidderCount: bidders.length,
      // Who the deal's quotations are for, by role: distinct parties per DESIGNER / OWNER / BUYER. A row whose
      // recipient was never recorded (UNSPECIFIED, every quotation from before the field existed) counts nowhere.
      roleCounts: countRoles(members),
      versionCount: members.length,
      decided: members.some((m) => m.partyStatus === 'AWARDED' || m.partyStatus === 'AWARDED_SPLIT'),
      headlineAmount: headline?.grandTotal ?? null,
      // What a search over the group header matches: any of its rows' own text, older versions included.
      searchText,
    });
    const ordered = [];
    parties.forEach((party) => {
      const bidderNo = bidders.indexOf(party) + 1;
      // Documents of this contractor: rows sharing a revision-suffixed number are versions of one document.
      const docs = new Map();
      for (const m of party) {
        const base = versionOf(m) == null ? null : String(m.number).replace(REVISION_SUFFIX, '');
        const key = base == null ? `row-${m.id}` : `${m.ticketId}:${base}`;
        if (!docs.has(key)) docs.set(key, []);
        docs.get(key).push(m);
      }
      for (const [docKey, versions] of docs) {
        const { inForce, older } = splitInForce(versions);
        const open = !collapsed.has(docKey);
        // Only bidders are numbered (1, 2, … in ยอดหลัก-first order); a designer or owner quotation gets no number.
        const base = { inGroup: true, groupIndex: bidderNo || null, groupContractors: bidders.length, docKey };
        ordered.push({
          ...inForce, ...base,
          olderCount: older.length, versionsOpen: open, versionNo: versionOf(inForce),
          onToggleVersions: onToggleVersions ? () => onToggleVersions(docKey) : null,
        });
        if (open) for (const o of older) ordered.push({ ...o, ...base, isOlderVersion: true, versionNo: versionOf(o) });
      }
    });
    // The header counts documents (a revision chain is one), so it can say how many quotations the deal holds.
    out[out.length - 1].documentCount = ordered.filter((m) => !m.isOlderVersion).length;
    ordered.forEach((m, i) => out.push({
      ...m, groupSize: ordered.length, groupSearchText: searchText,
      ...(i === 0 ? { groupFirst: true } : {}), ...(i === ordered.length - 1 ? { groupLast: true } : {}),
    }));
  }
  return out;
}

// Who a quotation is for. Not every quotation goes to a contractor: a designer and the project owner are
// quoted too, each with its own colour and icon so the role reads at a glance. A quotation whose recipient
// was never recorded (UNSPECIFIED) gets no chip at all (owner ruling 2026-11-08: "just label the one you can").
const RECIPIENT_CHIP = {
  DESIGNER: { label: 'ผู้ออกแบบ', icon: 'pencil', className: 'border-warning-border bg-warning-bg-soft text-warning' },
  OWNER: { label: 'เจ้าของโครงการ', icon: 'home', className: 'border-transparent bg-phase-4-bg text-phase-4' },
  BUYER: { label: 'ผู้รับเหมา', icon: 'building', className: 'border-transparent bg-phase-3-bg text-phase-3' },
};

export function RecipientChip({ role, count = null }) {
  const chip = RECIPIENT_CHIP[role];
  if (!chip) return null;
  return (
    <span
      data-testid="quotation-recipient-chip"
      data-role={role}
      className={cn('inline-flex min-h-[18px] items-center gap-1 whitespace-nowrap rounded-full border px-1.5 text-2xs font-extrabold', chip.className)}
    >
      <Icon name={chip.icon} size={10} />
      {chip.label}
      {count != null && count > 1 ? <span className="font-bold">{`×${count}`}</span> : null}
    </span>
  );
}

/** What the deal holds, for the header's third line: how many quotations, for whom, and the bid state. */
function GroupSummary({ row }) {
  const docs = row.documentCount ?? 1;
  const counts = row.roleCounts ?? {};
  return (
    <span className="flex flex-wrap items-center gap-x-1.5 gap-y-1 text-2xs text-text-muted mobile:text-xs" data-testid="quotation-group-summary">
      <span>{`ใบเสนอราคา ${docs} รายการ${row.versionCount > docs ? ` (${row.versionCount} เวอร์ชัน)` : ''}`}</span>
      {KNOWN_ROLES.filter((r) => counts[r]).map((r) => <RecipientChip key={r} role={r} count={counts[r]} />)}
      {(row.bidderCount ?? 0) > 1 ? (
        <span>
          {row.decided
            ? 'บันทึกผลประมูลแล้ว'
            : `รอผลประมูล${row.headlineAmount != null ? ` · ยอดหลัก ${formatMoney(row.headlineAmount)}` : ''}`}
        </span>
      ) : null}
    </span>
  );
}

// The deal is the container: its label leads ("ดีล DL-…" with the deal icon), the project is the title, and
// the third line says what sits beneath it, so on a phone the cards below read as this deal's quotations.
function GroupRowContent({ row }) {
  return (
    // Desktop: ONE line (deal · project · what it holds · เปิดดีล), so the header costs a row, not three.
    // Phone: stacked, deal label and เปิดดีล on top, the project as the card's title.
    <span className="flex min-w-0 flex-wrap items-center gap-x-3 gap-y-0.5 mobile:flex-col mobile:flex-nowrap mobile:items-stretch" data-testid="quotation-group-row">
      <span className="contents mobile:flex mobile:min-w-0 mobile:items-center mobile:justify-between mobile:gap-3">
        <span className="inline-flex min-w-0 shrink-0 items-center gap-1.5 text-2xs font-extrabold uppercase tracking-wide text-info" data-testid="quotation-group-deal">
          <Icon name="briefcase" size={13} />
          <span className="truncate">{`ดีล ${row.ticketCode ?? `#${row.ticketId}`}`}</span>
          {row.decided ? <StatusBadge tone="neutral">ตัดสินแล้ว</StatusBadge> : null}
        </span>
        {row.ticketId != null ? (
          <Link
            to={`/tickets/${row.ticketId}`}
            aria-label={`เปิดดีล ${row.ticketCode ?? row.ticketId}`}
            className="order-last ml-auto inline-flex min-h-6 shrink-0 items-center text-xs font-bold text-info underline mobile:order-none mobile:ml-0 mobile:min-h-[44px]"
          >
            เปิดดีล →
          </Link>
        ) : null}
      </span>
      <strong className="min-w-0 text-sm font-extrabold leading-snug text-text [overflow-wrap:anywhere] mobile:text-md">
        {row.projectName ?? 'ไม่ระบุโครงการ'}
      </strong>
      <GroupSummary row={row} />
    </span>
  );
}

/** "เวอร์ชันก่อนหน้า N" on a document's in-force row, the v-tag on an opened older version. */
function VersionControls({ row }) {
  if (row.isOlderVersion) {
    return <span className="font-mono text-2xs text-text-muted">{row.versionNo != null ? `v${row.versionNo}` : ''}</span>;
  }
  if (!row.olderCount) return null;
  return (
    <span className="flex flex-wrap items-center gap-1.5">
      <button
        type="button"
        data-testid="quotation-versions-toggle"
        aria-expanded={row.versionsOpen}
        className={cn(
          'inline-flex min-h-6 items-center gap-1 rounded-full border px-2 text-2xs font-extrabold mobile:min-h-[44px]',
          row.versionsOpen ? 'border-primary bg-primary-bg text-info-dark' : 'border-border-strong bg-surface text-text-secondary hover:bg-surface-hover',
        )}
        onClick={(event) => { event.stopPropagation(); row.onToggleVersions?.(); }}
      >
        {row.versionsOpen ? `ซ่อนเวอร์ชันก่อนหน้า ${row.olderCount}` : `เวอร์ชันก่อนหน้า ${row.olderCount}`}
        <Icon name={row.versionsOpen ? 'chevronUp' : 'chevronDown'} size={10} />
      </button>
      {row.versionNo != null ? <span className="font-mono text-2xs text-text-muted">{`ใช้อยู่: v${row.versionNo}`}</span> : null}
    </span>
  );
}

function GroupIndexCircle({ n }) {
  return (
    <span
      data-testid="quotation-group-index"
      className="inline-flex size-5 shrink-0 items-center justify-center rounded-full bg-info-bg text-2xs font-bold text-info"
    >
      {n}
    </span>
  );
}

/** The party outcome line a grouped row shows in place of its project name. */
function PartyChipLine({ row }) {
  const chip = partyStatusLabel(row.partyStatus);
  return (
    <span className="flex flex-wrap items-center gap-1.5">
      {!row.isOlderVersion ? <RecipientChip role={row.recipientType} /> : null}
      {row.partyStatus && !row.isOlderVersion && isBidRow(row) ? <StatusBadge tone={chip.tone}>{chip.label}</StatusBadge> : null}
      <VersionControls row={row} />
    </span>
  );
}

const COLUMNS = [
  {
    key: 'number',
    header: 'เลขที่',
    sortable: true,
    searchAccessor: (row) => (row.isGroup ? row.searchText : row.number),
    render: (row) => (row.isGroup ? <GroupRowContent row={row} /> : (
      <span className={cn('inline-flex items-center', row.isOlderVersion && 'pl-4')}>
        <Link to={`/quotations/${row.id}`} className="text-xs text-info underline">
          <code>{row.number}</code>
        </Link>
      </span>
    )),
  },
  {
    key: 'customer',
    header: 'ลูกค้า / ผู้รับใบเสนอราคา',
    searchAccessor: (row) => (row.isGroup ? row.searchText : `${row.customerName ?? ''} ${row.projectName ?? ''}`),
    // <span>, NOT <div>, and that is load-bearing rather than a style preference. styles.css
    // clips on the CELL — `.data-row > td` is `white-space: nowrap; text-overflow: ellipsis` — and
    // its wrap escape hatch only reaches `> strong`, `> small` and `> span`. A <div> child
    // therefore inherits the nowrap and this column cannot absorb any squeeze at all, which is
    // what made it lose 29px at 721px. `flex flex-col` still stacks the two lines: a Tailwind
    // utility beats the escape hatch's `display: block` on layer order (styles.css is
    // `layer(legacy)`), so only the wrapping changes.
    // A grouped row puts the customer and its chips on ONE line (wrapping only when the column is too narrow),
    // with compact badges, so a quotation costs one table line. Desktop only: the phone renders QuotationCard.
    render: (row) => (row.isGroup ? null : (
      <span className={cn('flex gap-0.5', row.inGroup ? 'flex-row flex-wrap items-center gap-x-2 [&_.status-badge]:min-h-5 [&_.status-badge]:px-2 [&_.status-badge]:py-0 [&_.status-badge]:text-2xs' : 'flex-col')}>
        <span className="flex items-center gap-2 text-sm font-bold">
          {row.inGroup && row.groupContractors > 1 && row.groupIndex && !row.isOlderVersion ? <GroupIndexCircle n={row.groupIndex} /> : null}
          {row.customerName ?? '-'}
        </span>
        {row.inGroup
          ? <PartyChipLine row={row} />
          : row.projectName ? <span className="text-2xs text-text-muted">{row.projectName}</span> : null}
      </span>
    )),
  },
  {
    key: 'salesRepName',
    header: 'พนักงานขาย',
    searchAccessor: (row) => (row.isGroup ? null : row.salesRepName),
    // Wrapped in a <span> for the same reason as ลูกค้า above: a bare string leaves the cell with
    // no child element, so styles.css's wrap escape hatch has nothing to match and a two-word Thai
    // name is truncated rather than broken across two lines. `ภิญญาดา วงศ์ประเสริฐ` needs 147.8px
    // on one line and this column is floored at 64px, so wrapping is what keeps it whole.
    render: (row) => (row.isGroup ? null : <span className="text-xs">{row.salesRepName ?? '-'}</span>),
  },
  {
    key: 'grandTotal',
    header: 'ยอดรวม',
    align: 'right',
    sortable: true,
    render: (row) => (row.isGroup ? null : <span className="tabular-nums text-sm font-bold">{formatMoney(row.grandTotal)}</span>),
  },
  {
    key: 'status',
    header: 'สถานะ',
    sortable: true,
    searchAccessor: (row) => (row.isGroup ? null : row.docStatus),
    render: (row) => {
      if (row.isGroup) return null;
      const status = dealQuotationStatusLabel(row.docStatus);
      return <span className="[&_.status-badge]:min-h-5 [&_.status-badge]:px-2 [&_.status-badge]:py-0 [&_.status-badge]:text-2xs"><StatusBadge tone={status.tone}>{status.label}</StatusBadge></span>;
    },
  },
  {
    key: 'quotationDate',
    header: 'วันที่',
    sortable: true,
    render: (row) => (row.isGroup ? null : <span className="text-xs">{formatThaiDate(row.quotationDate)}</span>),
  },
];

// The list is always grouped, so no column sorts: a sort would tear a deal's rows out from under its header.
// Every row of a group answers a search with the whole group's text, so a match keeps the group whole.
const GROUPED_COLUMNS = COLUMNS.map((column) => ({
  ...column,
  sortable: false,
  searchAccessor: (row) => {
    if (row.inGroup) return column.key === 'number' ? row.groupSearchText : null;
    return column.searchAccessor ? column.searchAccessor(row) : null;
  },
}));

// One group = header row + member rows read as ONE box. Desktop (<tr>): the header gets a tinted 2px top edge,
// members an inset 4px info rail, the last member a 2px bottom edge. Mobile (<li> cards, gap 10px in
// .record-card-list): the rows are joined into one box by cancelling that gap with a negative margin and sharing
// one border colour, and every member is INDENTED behind a vertical connector in the deal's colour, so the
// cards read as children of the deal header above them. An older version sits one step further in, dimmed.
export function quotationRowClassName(row) {
  if (row.isGroup) {
    return 'py-1.5 bg-info-bg-alt [&>td:first-child]:col-span-full [&>td:not(:first-child)]:hidden border-t-2 border-t-info-border mobile:border-t mobile:border-info-border mobile:rounded-b-none mobile:py-2.5';
  }
  const dim = row.partyStatus === 'NOT_AWARDED' ? 'opacity-60' : row.isOlderVersion ? 'bg-surface-muted text-text-muted' : '';
  if (!row.inGroup) return dim;
  return [
    dim,
    'py-1.5 mobile:py-4',
    'shadow-[inset_4px_0_0_var(--color-info)]',
    // A page boundary can split a group: the first card on a page drops the pull-up, the last closes its corners.
    'mobile:-mt-[10px] mobile:first:mt-0 mobile:border-info-border mobile:border-t-0 mobile:shadow-none mobile:last:rounded-b-md',
    // The connector: a 2px line down the card's left, inside the shared border, with the content indented past it.
    'mobile:relative mobile:before:absolute mobile:before:top-0 mobile:before:bottom-0 mobile:before:w-0.5 mobile:before:bg-info-border',
    // …and a short branch from the line into the card at its first text line; the last card's line ends at its branch.
    'mobile:after:absolute mobile:after:top-6 mobile:after:h-0.5 mobile:after:w-3 mobile:after:bg-info-border',
    row.isOlderVersion ? 'mobile:pl-12 mobile:before:left-8 mobile:after:left-8' : 'mobile:pl-9 mobile:before:left-4 mobile:after:left-4',
    row.groupLast ? 'mobile:before:bottom-auto mobile:before:h-6' : '',
    row.groupFirst ? '' : 'mobile:border-t mobile:[border-top-style:dashed]',
    row.groupLast ? 'mobile:rounded-t-none mobile:rounded-b-md border-b-2 border-b-info-border mobile:border-b' : 'mobile:rounded-none',
  ].filter(Boolean).join(' ');
}

function QuotationCard({ row }) {
  if (row.isGroup) return <GroupRowContent row={row} />;
  const status = dealQuotationStatusLabel(row.docStatus);
  const body = (
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
      {row.inGroup
        ? <PartyChipLine row={row} />
        : row.projectName ? <span className="min-w-0 truncate text-xs text-text-muted">{row.projectName}</span> : null}
      <span className="min-w-0 truncate text-xs text-text-muted">
        {row.salesRepName ?? '-'} · {formatThaiDate(row.quotationDate)}
      </span>
      <span className="tabular-nums text-md font-extrabold text-text">{formatMoney(row.grandTotal)}</span>
    </>
  );
  if (!row.inGroup) return body;
  return (
    <div className="flex min-w-0 gap-3">
      {row.groupContractors > 1 && row.groupIndex && !row.isOlderVersion ? <GroupIndexCircle n={row.groupIndex} /> : null}
      <div className="flex min-w-0 flex-1 flex-col gap-2">{body}</div>
    </div>
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
  const [periodFilter, setPeriodFilter] = useState('');
  const effectiveRepFilter = seesAll && repOptions.some((option) => option.id === repFilter) ? repFilter : '';
  const effectiveAmountFilter = seesAll ? amountFilter : '';
  const filteredRows = useMemo(
    () => filterDealQuotationRows(rows, { repId: effectiveRepFilter, amount: effectiveAmountFilter, email: emailFilter, period: periodFilter }),
    [rows, effectiveRepFilter, effectiveAmountFilter, emailFilter, periodFilter],
  );

  // The filters sit in a plain row ABOVE DataTable's own search input (see the JSX below, shared by
  // every breakpoint), so column headers stay plain text at every width and `columns` does not
  // diverge by role.
  // Older versions are open by default; this Set holds the documents the rep folded away.
  const [collapsedVersions, setCollapsedVersions] = useState(() => new Set());
  const toggleVersions = (docKey) => setCollapsedVersions((current) => {
    const next = new Set(current);
    if (!next.delete(docKey)) next.add(docKey);
    return next;
  });
  const tableRows = useMemo(
    () => groupQuotationRows(filteredRows, { collapsed: collapsedVersions, onToggleVersions: toggleVersions }),
    [filteredRows, collapsedVersions],
  );
  // The footer's "(กรองจาก M)" denominator: the same grouping and the same folded versions, unfiltered, so
  // folding a document's versions does not read as a filter.
  const unfilteredTotal = useMemo(
    () => groupQuotationRows(rows, { collapsed: collapsedVersions }).length,
    [rows, collapsedVersions],
  );

  // One request for all five tab counts, scoped server-side exactly like the list itself. Counts
  // are advisory chrome: a failed or in-flight counts call renders the tabs with NO number rather
  // than blocking the list or showing a zero that would read as "nothing here".
  const periodFrom = periodFilter ? dealQuotationPeriodStart(periodFilter, bangkokTodayIso()) : '';
  const countsQuery = useQuery({
    queryKey: queryKeys.dealQuotationCounts(periodFrom || undefined),
    // The BARE DealQuotationCountsDto: DealQuotationController#counts returns the record with no
    // envelope. No `r?.counts ?? r` tolerance, so a disagreement between the controller, hrApi's
    // comment and the mock surfaces in the contract test and the mock shape test.
    queryFn: () => api.dealQuotations.counts(periodFrom ? { from: periodFrom } : undefined).then((r) => r ?? null),
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
      <div className="flex flex-wrap items-center gap-2 rounded-lg border border-border bg-surface p-3">
      <div className="flex flex-wrap items-center gap-2" role="tablist" aria-label="สถานะใบเสนอราคา">
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
          <label className="flex flex-col gap-1">
            <span className="text-2xs font-extrabold uppercase tracking-wide text-text-muted">ช่วงเวลา</span>
            <select
              value={periodFilter}
              onChange={(event) => setPeriodFilter(event.target.value)}
              aria-label="กรองตามช่วงเวลา"
              className="h-8 mobile:min-h-[44px] w-auto rounded border border-border bg-surface px-2 text-xs text-text"
            >
              {DEAL_QUOTATION_PERIOD_OPTIONS.map((option) => (
                <option key={option.value} value={option.value}>{option.label}</option>
              ))}
            </select>
          </label>
        </div>

        <DataTable
          columns={GROUPED_COLUMNS}
          rows={tableRows}
          // Group rows count as rows in the footer, so the "(กรองจาก M)" denominator counts them too.
          unfilteredTotal={unfilteredTotal}
          getRowKey={(row) => row.id}
          gridClassName={LIST_TABLE_GRID}
          rowClassName={quotationRowClassName}
          mobileCard={(row) => <QuotationCard row={row} />}
          searchable
          searchPlaceholder="ค้นหาลูกค้า / โครงการ"
          // The filters render outside DataTable and are always visible regardless of this flag, as
          // is DataTable's own search box. What this keeps is the COLUMN HEADER ROW when a filter
          // narrows the list to zero rows, so the table keeps its shape under the empty-state line.
          // See-all roles always; everyone else only while their email filter is set, so a rep with
          // genuinely no quotations still gets the plain empty-state panel.
          retainHeaderWhenEmpty={seesAll || emailFilter !== '' || periodFilter !== ''}
          loading={listQuery.isLoading}
          emptyState={{ icon: 'fileText', title: 'ไม่มีใบเสนอราคาในเงื่อนไขนี้' }}
        />
      </div>
    </PageStack>
  );
}
