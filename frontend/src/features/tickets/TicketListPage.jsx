import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { api, ROLE_PERMISSIONS } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { Button } from '../../components/common/Button.jsx';
import { DataTable } from '../../components/common/DataTable.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { FilterBar } from '../../components/common/Layout.jsx';
import { PageHeader } from '../../components/common/PageHeader.jsx';
import { WorklistFilters } from '../../components/common/WorklistFilters.jsx';
import { useIsMobile } from '../../hooks/useIsMobile.js';
import { SalesTabs } from '../sales/SalesTabs.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import {
  dealLifecycleLabel,
  dealLostReasonLabel,
  dealStageLabel,
  formatMoney,
  formatThaiDate,
  fulfilmentStatusLabel,
  overdueBadgeLabel,
} from '../../utils/format.js';
import { StageProgressBar } from './DealStageStepper.jsx';
import { dealHref } from '../finance/dealHref.js';
import { dealInScope } from './salesViewScope.js';
import {
  EMPTY_STAGE_CATALOG, findStage, routePositionForChannel, stageIndexIn, useStageCatalog,
} from './stageCatalog.js';
import { routeName } from './stageMeta.js';
import { TicketCreateModal } from './TicketCreateModal.jsx';

// Same selector Modal.jsx traps on — kept identical so the two overlay
// surfaces agree on what "focusable" means.
const FOCUSABLE = 'a[href], button:not([disabled]), textarea, input, select, [tabindex]:not([tabindex="-1"])';

// Role-scoped views, Phase A: roles whose list page distinguishes "my
// worklist" from "every deal I may read" — everyone else (sales already only
// ever receives its own deals from the API; ceo/sales_manager are oversight
// and want the full list) has no such distinction, so no toggle is shown.
const WORKLIST_ROLES = new Set(['import', 'account']);

function scopeCopy(role, inboxOnly) {
  if (role === 'import') {
    return {
      eyebrow: 'ขอบเขตฝ่ายนำเข้า',
      title: inboxOnly ? 'ต้องดำเนินการ' : 'ทั้งหมด',
      description: inboxOnly
        ? 'ดีลที่อยู่ในช่วงขอราคา จัดซื้อ หรือนำเข้า'
        : 'ทุกดีลที่ฝ่ายนำเข้าอ่านได้',
    };
  }
  if (role === 'account') {
    return {
      eyebrow: 'ขอบเขตฝ่ายบัญชี',
      title: inboxOnly ? 'ต้องดำเนินการ' : 'ทั้งหมด',
      description: inboxOnly
        ? 'ดีลที่มีงานรับเงินหรือติดตามชำระ'
        : 'ทุกดีลที่ฝ่ายบัญชีอ่านได้',
    };
  }
  if (role === 'sales') {
    return {
      eyebrow: 'ขอบเขตฝ่ายขาย',
      title: 'ดีลของฉัน',
      description: 'ดีลที่อยู่ในความรับผิดชอบของคุณ',
    };
  }
  if (role === 'sales_manager') {
    return {
      eyebrow: 'ขอบเขตผู้จัดการฝ่ายขาย',
      title: 'ภาพรวมทีมขาย',
      description: 'มุมมองกำกับดูแลของทีมขาย',
    };
  }
  if (role === 'ceo') {
    return {
      eyebrow: 'ขอบเขตผู้บริหาร',
      title: 'ทุกดีลที่มองเห็นได้',
      description: 'มุมมองติดตามท่อขายและสถานะงาน',
    };
  }
  return {
    eyebrow: 'ขอบเขต',
    title: 'รายการที่มองเห็นได้',
    description: 'ตามสิทธิ์ของบทบาทปัจจุบัน',
  };
}

// One-line reason a deal is sitting in `role`'s worklist right now (per-card emphasis, e.g.
// account: the pending money action, import: lead with the pricing queue). Presentation only, like
// dealInScope in salesViewScope.js, but specific to this page's cards.
function worklistReason(role, deal, catalog = EMPTY_STAGE_CATALOG) {
  if (role === 'account') {
    if (deal.paymentStatus === 'DEPOSIT_NOTICE_ISSUED') return 'รอยืนยันรับมัดจำ';
    if (deal.paymentStatus === 'AWAITING_FINAL_PAYMENT') return 'รอชำระส่วนที่เหลือ';
    if (deal.overdue) return 'เกินกำหนดชำระ';
    return null;
  }
  if (role === 'import') {
    const meta = findStage(catalog, deal.salesStage);
    if (meta?.phase === 2 || meta?.phase === 3) return 'รอคำขอราคา';
    if (deal.salesStage === 'PROCUREMENT') return 'ดำเนินการนำเข้า (IR / จัดส่ง)';
    if (['DELIVERY_SCHEDULING', 'DELIVERED'].includes(deal.salesStage)) return 'ส่งมอบ / จองสต็อก';
    return null;
  }
  return null;
}

// Thai phase names. The phase ID LIST is the backend catalog's (see stageCatalog.js); only the
// wording is ours. Kept in step with DealStageStepper's own PHASE_NAME by the design system, not by
// a shared constant — these are two different renderings of the same five words.
const PHASE_NAME = { 1: 'การเข้าถึงโครงการ', 2: 'งานสเปค', 3: 'ประมูลและเจรจา', 4: 'คำสั่งซื้อและนำเข้า', 5: 'ส่งมอบและปิดงาน' };
const phaseName = (id) => PHASE_NAME[id] ?? `เฟส ${id}`;

// Per-phase dot accents. Static class map — Tailwind's scanner needs the full
// class names in source, so no `bg-phase-${id}` interpolation.
const PHASE_STYLES = {
  1: { dot: 'bg-phase-1' },
  2: { dot: 'bg-phase-2' },
  3: { dot: 'bg-phase-3' },
  4: { dot: 'bg-phase-4' },
  5: { dot: 'bg-phase-5' },
};

const STALE_DAYS = 7;
const LIFECYCLE_FILTERS = [
  { value: '', label: 'ทั้งหมด', tone: 'neutral' },
  { value: 'ON_HOLD', label: dealLifecycleLabel('ON_HOLD').label, tone: dealLifecycleLabel('ON_HOLD').tone },
  { value: 'DORMANT', label: dealLifecycleLabel('DORMANT').label, tone: dealLifecycleLabel('DORMANT').tone },
  { value: 'CLOSED_LOST', label: dealLifecycleLabel('CLOSED_LOST').label, tone: dealLifecycleLabel('CLOSED_LOST').tone },
  { value: 'CANCELLED', label: dealLifecycleLabel('CANCELLED').label, tone: dealLifecycleLabel('CANCELLED').tone },
  { value: 'COMPLETED', label: dealLifecycleLabel('COMPLETED').label, tone: dealLifecycleLabel('COMPLETED').tone },
];
// Statuses meaning "goods are in and delivery hasn't started" — deliberately a two-item SUBSET of
// importActions.js's three-item delivery-ready list (DELIVERY_READY_FULFILMENT_STATUSES also
// includes PARTIALLY_DELIVERED), not a reuse of that constant: PARTIALLY_DELIVERED already has its
// own 'partial_delivery' flag below, and folding it into this one too would make the two filters
// overlap instead of the crisp, non-overlapping pair this is meant to be. Not imported from
// importActions.js either way — that list is module-private there, and this is a narrower,
// page-local grouping for the list filter, not the fulfilment-chain decision itself.
const AWAITING_DELIVERY_STATUSES = ['GOODS_RECEIVED', 'FROM_STOCK'];
const FLAG_FILTERS = [
  { value: 'overdue', label: overdueBadgeLabel(true).label, tone: overdueBadgeLabel(true).tone },
  {
    value: 'awaiting_delivery',
    // 'รอส่งมอบ' is a literal label, not fulfilmentStatusLabel(...).label — that helper labels ONE
    // status ('สินค้าถึงโกดังแล้ว' / 'สินค้าจากสต็อก'), but this filter groups TWO statuses under one
    // "awaiting delivery" flag, so no single call to it produces the right words. The TONE is still
    // borrowed from it ('success', shared by both matched statuses) for consistency with how
    // GOODS_RECEIVED/FROM_STOCK already read everywhere else on this page.
    label: 'รอส่งมอบ',
    tone: fulfilmentStatusLabel('GOODS_RECEIVED').tone,
  },
  { value: 'partial_delivery', label: fulfilmentStatusLabel('PARTIALLY_DELIVERED').label, tone: fulfilmentStatusLabel('PARTIALLY_DELIVERED').tone },
];

function daysSince(iso) {
  if (!iso) return null;
  const diff = Date.now() - new Date(iso).getTime();
  return Math.max(0, Math.floor(diff / 86400000));
}

function DaysBadge({ stageUpdatedAt }) {
  const days = daysSince(stageUpdatedAt);
  if (days == null) return <span>-</span>;
  const stale = days > STALE_DAYS;
  return (
    <span className={`text-xs font-bold ${stale ? 'text-warning' : 'text-text-muted'}`}>
      {stale ? <Icon name="clock" size={12} /> : null} {days === 0 ? 'วันนี้' : `${days} วัน`}
    </span>
  );
}

function matchesSearch(deal, query) {
  const value = query.trim().toLowerCase();
  if (!value) return true;
  return [
    deal.code,
    deal.customerName,
    deal.projectName,
    deal.title,
    deal.createdByName,
  ].filter(Boolean).join(' ').toLowerCase().includes(value);
}

// ── Manager live pipeline (sales_manager/ceo) ──
// `deal.stale` (no deal_activity logged in 7 days; computed server/mock-side, see
// dealTrackingMeta.js's computeStale) is unrelated to the DaysBadge/STALE_DAYS above (days since
// the STAGE last changed): "has anyone followed up recently", not "how long in this stage".
const MANAGER_PIPELINE_ROLES = new Set(['sales_manager', 'ceo']);

/**
 * No-recent-activity badge, shown per-deal for the manager pipeline view. There is no win%
 * badge here: it is a forecast, not progress, and the win-weighted forecast already appears in
 * `TeamPipelineSummary` below the table.
 */
function TrackingBadges({ deal }) {
  if (!deal.stale) return null;
  return (
    <span className="flex flex-wrap items-center gap-1">
      <StatusBadge tone="warning">
        <Icon name="clock" size={11} /> เงียบ
      </StatusBadge>
    </span>
  );
}

/**
 * Groups every deal the viewer may see into the three buckets that replace a
 * rep's weekly Excel report: won (stage ≥ ORDER_RECEIVED, still ACTIVE),
 * expected (ACTIVE, pre-order — the win-weighted forecast), and lost
 * (CLOSED_LOST). Paused (ON_HOLD/DORMANT) and terminal-but-not-lost
 * (CANCELLED/COMPLETED) deals are deliberately excluded from all three: they
 * are neither a live forecast nor a definitive loss, so folding them into
 * either bucket would misstate it.
 */
function groupDealsForPipeline(deals, catalog) {
  const won = [];
  const expected = [];
  const lost = [];
  for (const deal of deals) {
    if (deal.lifecycle === 'CLOSED_LOST') { lost.push(deal); continue; }
    if (deal.lifecycle !== 'ACTIVE') continue;
    const idx = stageIndexIn(catalog, deal.salesStage);
    const orderReceivedIdx = stageIndexIn(catalog, 'ORDER_RECEIVED');
    // orderReceivedIdx is -1 until the catalog loads; without the guard every deal would count as
    // "won" for a frame, which would misstate the forecast on screen.
    (orderReceivedIdx >= 0 && idx >= orderReceivedIdx ? won : expected).push(deal);
  }
  const sumValue = (list) => list.reduce((sum, deal) => sum + (Number(deal.amountPayable) || 0), 0);
  const forecast = expected.reduce((sum, deal) => {
    // The SERVER's number (TicketSummaryDto.effectiveWinProbability). This is money-shaped output:
    // a hand-copied stage→% table drifted (it lacked QUOTE_OWNER, so every S5 deal contributed 0).
    // `?? 0` makes an unserved value under-count rather than turn the whole forecast into NaN.
    const win = deal.effectiveWinProbability ?? 0;
    return sum + (Number(deal.amountPayable) || 0) * (win / 100);
  }, 0);
  return {
    won: { deals: won, count: won.length, total: sumValue(won) },
    expected: { deals: expected, count: expected.length, total: sumValue(expected), forecast },
    lost: { deals: lost, count: lost.length, total: sumValue(lost) },
    staleActiveCount: [...won, ...expected].filter((deal) => deal.stale).length,
  };
}

/**
 * "ภาพรวมทีม": the live-pipeline replacement for the weekly report, computed off the same deal
 * list the table below renders.
 */
function TeamPipelineSummary({ groups }) {
  const items = [
    { key: 'won', label: 'ยอดที่สั่งซื้อแล้ว', tone: 'success', group: groups.won },
    { key: 'expected', label: 'ยอดคาดหวัง', tone: 'info', group: groups.expected },
    { key: 'lost', label: 'ขายไม่ได้', tone: 'danger', group: groups.lost },
  ];
  return (
    <section className="ticket-pipeline-summary" aria-labelledby="ticket-pipeline-title">
      <div className="ticket-pipeline-heading">
        <h2 id="ticket-pipeline-title">ภาพรวมทีม</h2>
        {groups.staleActiveCount > 0 ? (
          <StatusBadge tone="warning">
            <Icon name="clock" size={12} /> {groups.staleActiveCount} ดีลเงียบ (ไม่มีการติดตาม 7 วัน)
          </StatusBadge>
        ) : null}
      </div>
      <dl className="ticket-pipeline-metrics">
        {items.map((item) => (
          <div key={item.key}>
            <dt>{item.label}</dt>
            <dd>
              <strong>{formatMoney(item.group.total)}</strong>
              <StatusBadge tone={item.tone}>{item.group.count} ดีล</StatusBadge>
            </dd>
            {item.key === 'expected' ? (
              <small>คาดการณ์ถ่วงน้ำหนัก: {formatMoney(item.group.forecast)}</small>
            ) : null}
          </div>
        ))}
      </dl>
    </section>
  );
}

// `showTracking` (sales_manager/ceo only) renders the "เงียบ" stale badge
// here rather than in `ProgressCell`. It lives in the `stage` column, not
// `progress`, specifically because the tablet band (721–1040px, see
// styles.css) hides the `progress` column to fit the row — the stale flag
// would otherwise silently disappear for a manager at that width even
// though nothing about it stopped being true.
function WorkStageCell({ deal, role, catalog, showTracking = false }) {
  const reason = worklistReason(role, deal, catalog);
  return (
    <span className="flex min-w-0 flex-col gap-1">
      {reason ? <StatusBadge tone="info">{reason}</StatusBadge> : null}
      <DealStageCell deal={deal} catalog={catalog} />
      {showTracking ? <TrackingBadges deal={deal} /> : null}
    </span>
  );
}

// Lifecycles where the deal is not actively moving through the pipeline: a bare "ขั้นตอน N/14"
// for one of these would assert live progress. Naming the lifecycle alongside the fraction keeps
// it honest: "where it stood when it stopped", not "where it is heading".
const PAUSED_OR_TERMINAL_LIFECYCLES = new Set(['CANCELLED', 'COMPLETED', 'ON_HOLD', 'DORMANT']);

/**
 * Textual equivalent for the segmented progress bar's colour-coded fill (never colour alone).
 * `CLOSED_LOST` keeps its own fixed copy (no stage fraction reads as progress on a lost deal). A
 * deal with no resolvable `salesStage` has nothing to report a fraction of. Every other non-ACTIVE
 * lifecycle gets its label prefixed onto the fraction (see PAUSED_OR_TERMINAL_LIFECYCLES); a live
 * ACTIVE deal gets the bare fraction.
 */
function progressReadout(deal, catalog) {
  if (deal.lifecycle === 'CLOSED_LOST') return 'ไม่คืบหน้า (เสียงาน)';
  const idx = stageIndexIn(catalog, deal.salesStage);
  // The lifecycle prefix is decided BEFORE the "no resolvable stage" early return: a
  // CANCELLED/COMPLETED/ON_HOLD/DORMANT deal with a null/unrecognized `salesStage` has no fraction
  // but still has a lifecycle to report. This readout is the one place such a deal gets a
  // lifecycle mention at all (DealStageCell also badges CANCELLED/COMPLETED).
  const lifecyclePrefixed = PAUSED_OR_TERMINAL_LIFECYCLES.has(deal.lifecycle);
  if (idx < 0) {
    return lifecyclePrefixed ? dealLifecycleLabel(deal.lifecycle).label : 'ยังไม่ระบุขั้นตอน';
  }
  // Position and total are on THIS deal's route, so a buyer-direct deal at S8 reads 4/11 (same as
  // its deal page), not the catalog-wide 8/15. The route comes from the served `catalog.routes`
  // keyed by the row's `entryChannel`; a per-row decisions fetch would be an N+1. No served route
  // (UNSPECIFIED, unknown, absent) is all fifteen.
  const { position, total } = routePositionForChannel(catalog, deal.salesStage, deal.entryChannel);
  const fraction = `ขั้นตอน ${position}/${total}`;
  return lifecyclePrefixed ? `${dealLifecycleLabel(deal.lifecycle).label} · ${fraction}` : fraction;
}

/**
 * The `ความคืบหน้า` (progress) column: the segmented stage bar plus its textual equivalent. Only
 * the fraction is shown, with no completion percentage (it said the same thing twice).
 */
function ProgressCell({ deal, catalog }) {
  const lost = deal.lifecycle === 'CLOSED_LOST';
  return (
    <span className="flex min-w-0 flex-col gap-1">
      <StageProgressBar catalog={catalog} salesStage={deal.salesStage} lost={lost} entryChannel={deal.entryChannel} />
      <span className="text-2xs font-bold tabular-nums text-text-muted">
        {progressReadout(deal, catalog)}
      </span>
    </span>
  );
}

// NOTE: 'lost' keys on lifecycle, never on lostReason. Since V57 the reason
// SURVIVES a reopen, so a live reopened deal still carries one — testing the
// reason would render it as เสียงาน and drop it out of the phase counts.
function DealStageCell({ deal, catalog }) {
  if (deal.lifecycle === 'CLOSED_LOST') {
    const lost = dealLostReasonLabel(deal.lostReason);
    return <StatusBadge tone="danger">เสียงาน · {lost.label}</StatusBadge>;
  }
  const stage = dealStageLabel(deal.salesStage, deal.entryChannel);
  // null (render nothing) for UNSPECIFIED / unknown / absent: a route is never invented.
  const route = routeName(deal.entryChannel);
  const meta = findStage(catalog, deal.salesStage);
  // Badges CANCELLED/COMPLETED too (not only ON_HOLD/DORMANT): in the 721–1040px tablet band
  // (see styles.css) the `progress` column is hidden, so this column is the only other place those
  // lifecycles get a mention. Reuses PAUSED_OR_TERMINAL_LIFECYCLES so the two stay in sync.
  const showLifecycleBadge = PAUSED_OR_TERMINAL_LIFECYCLES.has(deal.lifecycle);
  const lifecycle = dealLifecycleLabel(deal.lifecycle);
  return (
    <span className="flex min-w-0 flex-col gap-0.5">
      <span className="flex flex-wrap items-center gap-1">
        <StatusBadge tone={stage.tone}>
          {meta ? `${meta.no}. ` : ''}{stage.label}
        </StatusBadge>
        {showLifecycleBadge ? <StatusBadge tone={lifecycle.tone}>{lifecycle.label}</StatusBadge> : null}
      </span>
      {route ? <span data-testid="deal-route-marker" className="pl-0.5 text-2xs text-text-muted">{route}</span> : null}
    </span>
  );
}

/**
 * Mobile record card for a deal: identity, stage, progress, owner, freshness. `reason`, when
 * given, is a one-line worklist chip ("why this deal is in your queue right now") shown under the
 * customer name; import's card uses it to lead with the pricing/procurement queue. `showTracking`
 * (sales_manager/ceo only) adds the win%/stale badges below the stage, the mobile equivalent of
 * the manager-only DataTable column.
 *
 * Mobile-only: the desktop table's whole row is the open target (DataTable's onRowClick), but a
 * card isn't a table row, so it keeps this explicit button (see DataTable.jsx's onRowClick doc).
 */
function DealOpenButton({ deal, onOpen }) {
  return (
    <Button
      variant="secondary"
      size="sm"
      type="button"
      className="ticket-card-open-button"
      onClick={() => onOpen(deal)}
      aria-label={`เปิดดีล ${deal.code}`}
    >
      เปิด
      <Icon name="chevronRight" size={14} />
    </Button>
  );
}

function DealCard({ deal, catalog, reason = null, showTracking = false, onOpen }) {
  const stage = dealStageLabel(deal.salesStage, deal.entryChannel);
  const route = routeName(deal.entryChannel);
  const stageMetaInfo = findStage(catalog, deal.salesStage);
  const freshnessText = overdueBadgeLabel(deal.stageUpdatedAt)?.label || formatThaiDate(deal.stageUpdatedAt ?? deal.updatedAt);
  return (
    <>
      <div className="ticket-card-topline">
        <code>{deal.code}</code>
        <span>{freshnessText}</span>
      </div>

      <strong className="ticket-card-title">
        {deal.customerName || deal.title}
      </strong>
      {deal.projectName ? (
        <span className="ticket-card-project">{deal.projectName}</span>
      ) : null}

      <div className="ticket-card-work">
        <span>ขั้นตอน / เหตุผลงาน</span>
        <strong>{stageMetaInfo ? `${stageMetaInfo.no}. ` : ''}{stage.label}</strong>
        {route ? <span data-testid="deal-route-marker">{route}</span> : null}
        {reason ? <small>{reason}</small> : null}
      </div>
      <StageProgressBar
        catalog={catalog}
        salesStage={deal.salesStage}
        lost={deal.lifecycle === 'CLOSED_LOST'}
        entryChannel={deal.entryChannel}
      />
      {showTracking ? <TrackingBadges deal={deal} /> : null}

      <span className="ticket-card-owner">
        {[deal.createdByName, formatThaiDate(deal.createdAt)].filter(Boolean).join(' · ')}
      </span>
      <DealOpenButton deal={deal} onOpen={onOpen} />
    </>
  );
}

/**
 * Account's money-worklist card: leads with the amount and the specific
 * pending money action instead of the pipeline stage (account doesn't act on
 * the pipeline stage directly — it acts on a payment).
 */
function MoneyWorklistCard({ deal, onOpen }) {
  const reason = worklistReason('account', deal);
  return (
    <>
      <div className="ticket-card-topline">
        <code>{deal.code}</code>
        <DaysBadge stageUpdatedAt={deal.stageUpdatedAt} />
      </div>

      <strong className="ticket-card-title">
        {deal.customerName || deal.title}
      </strong>

      <div className="flex flex-wrap items-baseline gap-x-2 gap-y-0.5">
        <strong className="text-lg font-extrabold text-text">
          {formatMoney(deal.amountOutstanding ?? 0)}
        </strong>
        <span className="text-2xs text-text-muted">
          คงเหลือ จากยอด {formatMoney(deal.amountPayable ?? 0)}
        </span>
      </div>

      {reason ? (
        <StatusBadge tone={deal.overdue ? 'danger' : 'warning'}>{reason}</StatusBadge>
      ) : (
        <StatusBadge tone="neutral">ไม่มีรายการรอดำเนินการ</StatusBadge>
      )}

      <span className="ticket-card-owner">
        {[deal.createdByName, formatThaiDate(deal.createdAt)].filter(Boolean).join(' · ')}
      </span>
      <DealOpenButton deal={deal} onOpen={onOpen} />
    </>
  );
}

function buildDealColumns({ role, isManagerView, catalog }) {
  return [
    {
      key: 'customer',
      header: 'ดีล / ลูกค้า / โครงการ',
      searchAccessor: (deal) => [deal.code, deal.customerName, deal.projectName, deal.title].filter(Boolean).join(' '),
      // This Link (not the row's `onRowClick`, a mouse-only convenience) is the row's keyboard and
      // assistive-tech activation target, like PricingRequestQueuePage.jsx's `requestCode` column.
      // The 721–1040px tablet band renders this desktop table (`useIsMobile` is <=720px) with no
      // hover, so it is also the only visible cue that a row is tappable.
      render: (deal) => (
        <span className="flex min-w-0 flex-col gap-0.5">
          <Link
            to={dealHref(role, deal.id)}
            className="block truncate font-bold text-link underline decoration-1 underline-offset-2 hover:decoration-2"
          >
            {deal.customerName || deal.title}
          </Link>
          <span className="block truncate text-2xs text-text-muted">
            {[deal.projectName, deal.code].filter(Boolean).join(' · ')}
          </span>
        </span>
      ),
    },
    {
      key: 'createdByName',
      header: 'ผู้ดูแล',
      searchAccessor: (deal) => deal.createdByName || '',
      render: (deal) => <span>{deal.createdByName}</span>,
    },
    {
      key: 'stage',
      header: 'ขั้นตอน / เหตุผลงาน',
      sortable: true,
      sortAccessor: (deal) => (deal.lifecycle === 'CLOSED_LOST' ? -1 : findStage(catalog, deal.salesStage)?.no ?? 0),
      render: (deal) => <WorkStageCell deal={deal} role={role} catalog={catalog} showTracking={isManagerView} />,
    },
    {
      key: 'progress',
      header: 'ความคืบหน้า',
      render: (deal) => <ProgressCell deal={deal} catalog={catalog} />,
    },
    {
      key: 'date',
      header: 'อัปเดตล่าสุด',
      sortable: true,
      sortAccessor: (deal) => new Date(deal.stageUpdatedAt ?? deal.updatedAt),
      render: (deal) => <DaysBadge stageUpdatedAt={deal.stageUpdatedAt ?? deal.updatedAt} />,
    },
    // Row-level navigation (onRowClick, wired on the <DataTable> below) replaces a repeated
    // `เปิด ›` button. There is no "ขั้นตอนถัดไป" next-action column (dealWorklistActions.js):
    // `/tickets` is gated on canViewDealPipeline (sales/sales_manager/ceo only, permissions.js), and
    // that resolver only returned a verb for account/import, who never reach this page.
  ];
}

export function TicketListPage({ user, showToast }) {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  // Phase filter + search live in the URL so list → detail → back keeps them. The stage cell does
  // not show the legacy ticket status.
  const [searchParams, setSearchParams] = useSearchParams();
  const phaseFilter = searchParams.get('phase') ?? '';
  const lifecycleFilter = searchParams.get('life') ?? '';
  const flagFilter = searchParams.get('flag') ?? '';
  // Role-scoped views, Phase A: import/account default to their own worklist
  // (dealInScope) — ?inbox=0 opts back into every deal the role may read.
  // sales/ceo/sales_manager have no such distinction (see WORKLIST_ROLES).
  const hasWorklistDistinction = WORKLIST_ROLES.has(user.role);
  const inboxOnly = hasWorklistDistinction && searchParams.get('inbox') !== '0';
  const searchText = searchParams.get('q') ?? '';
  // Manager live pipeline, sales_manager/ceo only.
  const isManagerView = MANAGER_PIPELINE_ROLES.has(user.role);
  const [creating, setCreating] = useState(false);
  const filterToggleRef = useRef(null);
  const filterSheetRef = useRef(null);
  // The LIFECYCLE/FLAGS chip rows sit behind a collapsed "ตัวกรองเพิ่มเติม" expander (collapsed by
  // default for every role), opened automatically when one becomes active (e.g. a deep link with
  // ?life=... or ?flag=...).
  //
  // Openness is state-driven, not `open || hasActiveFilter`: that made the sheet impossible to
  // dismiss while a filter was applied (close button, scrim and Escape became no-ops), which is
  // unacceptable for a modal bottom sheet at <=720px. An applied filter is still never hidden: the
  // always-visible "ตัวกรองที่ใช้" summary row and the toggle's count badge keep reporting it.
  const [moreFiltersOpen, setMoreFiltersOpen] = useState(
    () => Boolean(searchParams.get('life') || searchParams.get('flag')),
  );

  const canCreate = ROLE_PERMISSIONS.canCreateTickets.includes(user.role);

  // The pipeline's shape (stage order, phases, gates), fetched once from GET /api/meta/deal-stages.
  // Deliberately NOT folded into `loading` below: the deal list must still render if the
  // enumeration is slow. Every lookup over it is written to answer safely for the empty catalog.
  const { catalog: stageCatalog } = useStageCatalog();

  const ticketsQuery = useQuery({
    queryKey: queryKeys.ticketList(''),
    queryFn: () => api.tickets.list({}).then((response) => response.tickets || []),
  });
  const allDeals = useMemo(() => ticketsQuery.data ?? [], [ticketsQuery.data]);
  const loading = ticketsQuery.isLoading;
  const refreshing = ticketsQuery.isFetching && !ticketsQuery.isLoading;
  const openDeal = useCallback((deal) => {
    navigate(dealHref(user.role, deal.id));
  }, [navigate, user.role]);

  useEffect(() => {
    if (ticketsQuery.error) showToast('error', ticketsQuery.error.message || 'โหลดข้อมูลไม่สำเร็จ');
  }, [ticketsQuery.error, showToast]);

  // Phase strip doubles as a compact filter — never a 14-stage tab bar.
  // Lifecycle buckets live in the additional filters below, so phase counts stay active-only.
  const phaseCounts = useMemo(() => {
    const counts = {};
    for (const phaseId of stageCatalog.phases) counts[phaseId] = 0;
    for (const deal of allDeals) {
      if (deal.lifecycle === 'ACTIVE') {
        const meta = findStage(stageCatalog, deal.salesStage);
        if (meta) counts[meta.phase] += 1;
      }
    }
    return counts;
  }, [allDeals, stageCatalog]);

  const lifecycleCounts = useMemo(() => {
    const counts = {
      '': allDeals.length,
      ON_HOLD: 0,
      DORMANT: 0,
      CLOSED_LOST: 0,
      CANCELLED: 0,
      COMPLETED: 0,
    };
    for (const deal of allDeals) {
      const key = deal.lifecycle;
      if (Object.hasOwn(counts, key)) counts[key] += 1;
    }
    return counts;
  }, [allDeals]);

  const flagCounts = useMemo(() => ({
    overdue: allDeals.filter((deal) => deal.overdue).length,
    awaiting_delivery: allDeals.filter((deal) => AWAITING_DELIVERY_STATUSES.includes(deal.fulfillmentStatus)).length,
    partial_delivery: allDeals.filter((deal) => deal.fulfillmentStatus === 'PARTIALLY_DELIVERED').length,
  }), [allDeals]);

  // Manager live pipeline: computed off the FULL unfiltered list (independent of the
  // phase/lifecycle/flag chips), like flagCounts/lifecycleCounts above, so the overview is stable
  // whatever the table is filtered to.
  const pipelineGroups = useMemo(
    () => (isManagerView ? groupDealsForPipeline(allDeals, stageCatalog) : null),
    [allDeals, isManagerView, stageCatalog],
  );

  // How many of allDeals are in this role's worklist right now (counts-from-allDeals, independent
  // of the other filters); used by the inbox toggle chips below.
  const inboxCounts = useMemo(() => (hasWorklistDistinction ? {
    inbox: allDeals.filter((deal) => dealInScope(user.role, deal, stageCatalog)).length,
    all: allDeals.length,
  } : null), [allDeals, hasWorklistDistinction, stageCatalog, user.role]);

  const deals = useMemo(() => {
    return allDeals.filter((deal) => {
      const lost = deal.lifecycle === 'CLOSED_LOST';
      // Phase cards are the active-pipeline funnel (counts are ACTIVE-only), so the phase
      // filter matches on ACTIVE too — paused/terminal deals are reached via the lifecycle
      // chips below. This keeps each phase card's count equal to the rows it filters to.
      const phaseOk = !phaseFilter
        || (deal.lifecycle === 'ACTIVE' && findStage(stageCatalog, deal.salesStage)?.phase === Number(phaseFilter));
      const lifeOk = !lifecycleFilter || (lifecycleFilter === 'CLOSED_LOST' ? lost : deal.lifecycle === lifecycleFilter);
      const flagOk = !flagFilter
        || (flagFilter === 'overdue' && deal.overdue)
        || (flagFilter === 'awaiting_delivery' && AWAITING_DELIVERY_STATUSES.includes(deal.fulfillmentStatus))
        || (flagFilter === 'partial_delivery' && deal.fulfillmentStatus === 'PARTIALLY_DELIVERED');
      const inboxOk = !inboxOnly || dealInScope(user.role, deal, stageCatalog);
      const searchOk = matchesSearch(deal, searchText);
      return phaseOk && lifeOk && flagOk && inboxOk && searchOk;
    });
  }, [allDeals, flagFilter, lifecycleFilter, phaseFilter, inboxOnly, stageCatalog, user.role, searchText]);

  const hasActiveMoreFilters = Boolean(lifecycleFilter || flagFilter);
  const showMoreFilters = moreFiltersOpen;
  const activeMoreFiltersCount = (lifecycleFilter ? 1 : 0) + (flagFilter ? 1 : 0);
  // <=720px the same markup is a fixed bottom sheet over a scrim (a real modal); above that it is
  // an inline disclosure panel with the scrim hidden (styles.css: .ticket-filter-backdrop). Modal
  // semantics are breakpoint-scoped: Escape closes at any width, but the focus trap, initial focus
  // move and inerting the page behind only apply where the sheet covers the page.
  const isMobile = useIsMobile();
  const mobileSheetOpen = showMoreFilters && isMobile;

  // A lifecycle/flag filter becoming active reveals the sheet, so a deep link
  // or an external filter change is never silently applied behind a closed
  // panel. Toggling a chip from inside the sheet is a no-op here (already
  // open), and closing the sheet by hand does not re-trigger this — the effect
  // only fires on the false -> true transition.
  useEffect(() => {
    if (hasActiveMoreFilters) setMoreFiltersOpen(true);
  }, [hasActiveMoreFilters]);
  const scope = scopeCopy(user.role, inboxOnly);
  const activeFilterCount = [
    searchText.trim(),
    phaseFilter,
    lifecycleFilter,
    flagFilter,
    hasWorklistDistinction && searchParams.get('inbox') === '0' ? 'all' : '',
  ].filter(Boolean).length;
  const activeFilters = useMemo(() => {
    const filters = [];
    if (searchText.trim()) filters.push('ค้นหาในรายการ');
    if (phaseFilter) {
      if (stageCatalog.phases.includes(Number(phaseFilter))) {
        filters.push(`เฟส ${phaseFilter}: ${phaseName(Number(phaseFilter))}`);
      }
    }
    if (lifecycleFilter) filters.push(`สถานะงาน: ${dealLifecycleLabel(lifecycleFilter).label}`);
    if (flagFilter) {
      const flag = FLAG_FILTERS.find((item) => item.value === flagFilter);
      if (flag) filters.push(`สัญญาณ: ${flag.label}`);
    }
    if (hasWorklistDistinction && searchParams.get('inbox') === '0') filters.push('ขอบเขต: ทั้งหมด');
    return filters;
  }, [flagFilter, hasWorklistDistinction, lifecycleFilter, phaseFilter, searchParams, searchText, stageCatalog]);

  // Phase chips, WorklistFilters-driven. The "all" chip is named after its action ("ทั้งหมด"),
  // since clicking it clears the phase filter and lists every deal including
  // CLOSED_LOST/CANCELLED/COMPLETED, and it carries no count: the filtered total is stated once, in
  // DataTable's own footer, and the per-phase chips keep counts that match what selecting them
  // filters to.
  const phaseChipItems = useMemo(() => [
    { key: '', label: 'ทั้งหมด' },
    ...stageCatalog.phases.map((phaseId) => ({
      key: String(phaseId),
      label: `เฟส ${phaseId} · ${phaseName(phaseId)}`,
      count: phaseCounts[phaseId],
      dotClassName: PHASE_STYLES[phaseId]?.dot,
    })),
  ], [phaseCounts, stageCatalog]);

  function selectPhase(key) {
    // Clicking the already-active chip clears back to "all".
    updateParam('phase', key && key === phaseFilter ? '' : key);
  }

  // Memoized like phaseChipItems: WorklistFilters keys its scroll/resize-affordance effect on
  // `[items]`, so a new array identity each render would tear down and re-attach its listener.
  const inboxChipItems = useMemo(() => (hasWorklistDistinction ? [
    { key: '', label: 'ต้องดำเนินการ', count: inboxCounts.inbox },
    { key: '0', label: 'ทั้งหมด', count: inboxCounts.all },
  ] : null), [hasWorklistDistinction, inboxCounts]);

  const tableColumns = useMemo(
    () => buildDealColumns({ role: user.role, isManagerView, catalog: stageCatalog }),
    [isManagerView, stageCatalog, user.role],
  );

  const emptyDescription = useMemo(() => {
    if (searchText.trim()) return 'ไม่พบดีลที่ตรงกับคำค้นหาและตัวกรองที่เลือก';
    if (phaseFilter) {
      return stageCatalog.phases.includes(Number(phaseFilter))
        ? `ไม่มีดีลในเฟส ${phaseFilter} · ${phaseName(Number(phaseFilter))}`
        : 'ไม่มีดีลในเฟสที่เลือก';
    }
    if (flagFilter === 'overdue') return 'ไม่มีดีลที่เกินกำหนดชำระ';
    if (flagFilter === 'awaiting_delivery') return 'ไม่มีดีลที่รอส่งมอบ';
    if (flagFilter === 'partial_delivery') return 'ไม่มีดีลที่ส่งมอบบางส่วน';
    if (lifecycleFilter) return `ไม่มีดีลในสถานะ${dealLifecycleLabel(lifecycleFilter).label}`;
    if (inboxOnly) return 'ไม่มีดีลที่ต้องดำเนินการตอนนี้ — ลองดูแท็บ "ทั้งหมด"';
    return 'ยังไม่มีดีลในเงื่อนไขที่เลือก';
  }, [flagFilter, lifecycleFilter, inboxOnly, phaseFilter, searchText, stageCatalog]);

  function invalidateTicketsList() {
    return queryClient.invalidateQueries({ queryKey: ['tickets', 'list'] });
  }

  function updateParam(key, value) {
    setSearchParams((prev) => {
      const next = new URLSearchParams(prev);
      if (value) next.set(key, value); else next.delete(key);
      return next;
    }, { replace: true });
  }

  function clearFilters() {
    setSearchParams(new URLSearchParams(), { replace: true });
    setMoreFiltersOpen(false);
  }

  const closeMoreFilters = useCallback(() => {
    setMoreFiltersOpen(false);
    const restoreFocus = typeof window.requestAnimationFrame === 'function'
      ? window.requestAnimationFrame
      : (callback) => window.setTimeout(callback, 0);
    restoreFocus(() => filterToggleRef.current?.focus());
  }, []);

  // Escape closes the sheet at every width; Tab is only trapped when the sheet
  // is the mobile modal. Mirrors the keyboard contract of Modal.jsx so the two
  // overlay surfaces behave identically for a keyboard user.
  useEffect(() => {
    if (!showMoreFilters) return undefined;

    function onKeyDown(event) {
      if (event.key === 'Escape') {
        event.stopPropagation();
        closeMoreFilters();
        return;
      }
      if (event.key !== 'Tab' || !mobileSheetOpen) return;
      const sheet = filterSheetRef.current;
      const items = Array.from(sheet?.querySelectorAll(FOCUSABLE) ?? []);
      if (items.length === 0) return;
      const first = items[0];
      const last = items[items.length - 1];
      // Focus that has escaped the sheet (or never entered it) is pulled back
      // in. `inert` already does this in browsers that support it; this keeps
      // the trap correct where it does not.
      if (!sheet?.contains(document.activeElement)) {
        event.preventDefault();
        first.focus();
        return;
      }
      if (event.shiftKey && document.activeElement === first) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault();
        first.focus();
      }
    }

    document.addEventListener('keydown', onKeyDown);
    return () => document.removeEventListener('keydown', onKeyDown);
  }, [showMoreFilters, mobileSheetOpen, closeMoreFilters]);

  // Opening the modal sheet moves focus into it. Focus is restored to the
  // toggle by closeMoreFilters, which every dismissal path routes through.
  useEffect(() => {
    if (!mobileSheetOpen) return;
    const sheet = filterSheetRef.current;
    const items = Array.from(sheet?.querySelectorAll(FOCUSABLE) ?? []);
    (items[0] ?? sheet)?.focus();
  }, [mobileSheetOpen]);

  const createMutation = useMutation({
    mutationFn: (payload) => api.tickets.create(payload),
    onSuccess: (response) => {
      setCreating(false);
      showToast('success', 'สร้างดีลเรียบร้อย');
      invalidateTicketsList();
      // Commit 6: a new deal starts as an empty DRAFT with no price-request
      // flow of its own any more (see TicketService.create, commit 5) — land
      // the user straight on the deal page, where PricingRequestPanel prompts
      // them to create the pricing request that actually starts pricing.
      const newTicketId = response?.ticket?.summary?.id;
      if (newTicketId != null) navigate(`/tickets/${newTicketId}`);
    },
  });

  async function handleCreate(payload) {
    await createMutation.mutateAsync(payload);
  }

  // The modal sheet is portalled to <body> so the page root itself can be
  // marked inert while it is open — an ancestor cannot inert its own overlay.
  // On desktop the sheet must stay in the page's normal flow (it is a
  // `.page-stack` child), so it renders in place there.
  function renderFilterSheet(sheet) {
    return mobileSheetOpen ? createPortal(sheet, document.body) : sheet;
  }

  return (
    <div
      className="grid w-full grid-cols-1 gap-[18px] min-w-0 max-w-[1320px] ticket-list-page"
      // `inert` removes the page behind the sheet from focus order, pointer hit-testing and the
      // accessibility tree. React 18 has no boolean-prop support for it, so the empty string emits
      // the bare HTML attribute; aria-hidden covers browsers without inert.
      {...(mobileSheetOpen ? { inert: '', 'aria-hidden': 'true' } : {})}
    >
      <SalesTabs role={user.role} />
      <PageHeader
        title="รายการดีล"
        subtitle="ติดตามดีลตามขอบเขตบทบาทและตัวกรองที่เลือก"
        // Denser mobile heading/actions treatment, set here directly: a legacy-layer override via
        // a `.ticket-list-page` ancestor selector in styles.css would lose to Tailwind's utilities
        // layer (see styles-css-loses-to-tailwind-utilities).
        headingClassName="mobile:leading-[1.15]"
        subtitleClassName="mobile:mt-0.5 mobile:text-sm mobile:leading-[1.35]"
        actionsClassName="mobile:grid mobile:w-full mobile:grid-cols-[44px_minmax(0,1fr)] mobile:gap-2 [&>*:only-child]:mobile:col-start-1"
        actions={(
          <>
            <Button
              variant="icon"
              type="button"
              onClick={invalidateTicketsList}
              loading={refreshing}
              title="รีเฟรชรายการดีล"
              aria-label="รีเฟรชรายการดีล"
            >
              <Icon name="refresh" />
            </Button>
            {canCreate ? (
              <Button type="button" onClick={() => setCreating(true)}>
                <Icon name="plus" />
                สร้างดีลใหม่
              </Button>
            ) : null}
          </>
        )}
      />

      {/* One bordered filter band: scope + phase + search/more-filters/clear. DataTable's own
          footer stays the single statement of "how many rows matched". */}
      <section
        className="flex flex-col gap-3 rounded-md border border-border bg-surface p-3.5 mobile:gap-2.5 mobile:p-2.5"
        aria-label="ขอบเขตและตัวกรองรายการดีล"
      >
        <div className="flex flex-wrap items-baseline gap-x-2 gap-y-1">
          <span className="text-2xs font-extrabold text-text-muted">{scope.eyebrow}</span>
          {!hasWorklistDistinction ? <StatusBadge tone="neutral">{scope.title}</StatusBadge> : null}
          <span className="text-xs text-text-muted">{scope.description}</span>
        </div>

        {inboxChipItems ? (
          <WorklistFilters
            ariaLabel="เลือกขอบเขตรายการ"
            items={inboxChipItems}
            activeKey={searchParams.get('inbox') ?? ''}
            onSelect={(key) => updateParam('inbox', key)}
          />
        ) : null}

        <WorklistFilters
          ariaLabel="ตัวกรองเฟสของดีล"
          items={phaseChipItems}
          activeKey={phaseFilter}
          onSelect={selectPhase}
        />

        {/* Spacing/reflow live here as utilities, not in styles.css: index.css orders
            `@layer theme, legacy, utilities`, so a `.ticket-filter-bar` rule in the legacy layer
            always loses to FilterBar's own utilities. `mobile:` is the shared <=720px variant. */}
        <FilterBar
          className="ticket-filter-bar !border-0 !bg-transparent !p-0 gap-3 mobile:grid mobile:grid-cols-[minmax(0,1fr)_auto] mobile:items-center mobile:gap-2"
          aria-label="ค้นหาและตัวกรองรายการดีล"
        >
          <label className="ticket-filter-search search-field">
            <span className="sr-only">ค้นหาดีล</span>
            <Icon name="search" />
            <input
              type="search"
              value={searchText}
              placeholder="ค้นหาเลขที่ / บริษัท / โครงการ / ผู้ดูแล"
              onChange={(event) => updateParam('q', event.target.value)}
              aria-label="ค้นหาดีล"
            />
          </label>
          <Button
            ref={filterToggleRef}
            variant="secondary"
            type="button"
            aria-expanded={showMoreFilters}
            onClick={() => setMoreFiltersOpen((current) => !current)}
          >
            <Icon name="setting" size={16} />
            ตัวกรอง
            {activeMoreFiltersCount > 0 ? (
              <StatusBadge tone="info">{activeMoreFiltersCount}</StatusBadge>
            ) : null}
          </Button>
          {activeFilterCount > 0 ? (
            <Button variant="text" type="button" className="ticket-filter-clear" onClick={clearFilters}>
              ล้างทั้งหมด
            </Button>
          ) : null}
          <StatusBadge tone={activeFilterCount > 0 ? 'info' : 'neutral'}>
            {activeFilterCount > 0 ? `${activeFilterCount} ตัวกรอง` : 'ไม่มีตัวกรอง'}
          </StatusBadge>
        </FilterBar>

        {activeFilters.length > 0 ? (
          <div className="ticket-active-filters" aria-label="ตัวกรองที่ใช้">
            <span>ตัวกรองที่ใช้</span>
            {activeFilters.map((filter) => (
              <StatusBadge key={filter} tone="neutral">{filter}</StatusBadge>
            ))}
          </div>
        ) : null}
      </section>

      {showMoreFilters ? renderFilterSheet(
        <>
          <button
            type="button"
            className="ticket-filter-backdrop"
            aria-label="ปิดตัวกรองเพิ่มเติม"
            onClick={closeMoreFilters}
          />
          <section
            ref={filterSheetRef}
            className="ticket-filter-sheet"
            aria-label="ตัวกรองเพิ่มเติม"
            // Only the mobile bottom sheet is a modal dialog. On desktop the
            // same node stays an inline labelled region, so announcing it as a
            // dialog there would be a lie to a screen reader.
            role={mobileSheetOpen ? 'dialog' : 'region'}
            aria-modal={mobileSheetOpen ? 'true' : undefined}
            tabIndex={mobileSheetOpen ? -1 : undefined}
          >
            <div className="ticket-filter-sheet-header">
              <h2>ตัวกรองเพิ่มเติม</h2>
              <Button
                variant="icon"
                size="sm"
                type="button"
                title="ปิดตัวกรองเพิ่มเติม"
                aria-label="ปิดตัวกรองเพิ่มเติม"
                onClick={closeMoreFilters}
              >
                <Icon name="close" size={16} />
              </Button>
            </div>
            <div className="ticket-filter-group">
              <span>สถานะงาน</span>
              {LIFECYCLE_FILTERS.map((item) => {
                const active = lifecycleFilter === item.value;
                return (
                  <button
                    key={item.value || 'all'}
                    type="button"
                    aria-pressed={active}
                    className={`ticket-filter-chip${active ? ' is-active' : ''}`}
                    onClick={() => updateParam('life', active ? '' : item.value)}
                  >
                    <span>{item.label}</span>
                    <StatusBadge tone={item.tone}>{lifecycleCounts[item.value] ?? 0}</StatusBadge>
                  </button>
                );
              })}
            </div>
            <div className="ticket-filter-group">
              <span>สัญญาณงาน</span>
              {FLAG_FILTERS.map((item) => {
                const active = flagFilter === item.value;
                return (
                  <button
                    key={item.value}
                    type="button"
                    aria-pressed={active}
                    className={`ticket-filter-chip${active ? ' is-active' : ''}`}
                    onClick={() => updateParam('flag', active ? '' : item.value)}
                  >
                    <span>{item.label}</span>
                    <StatusBadge tone={item.tone}>{flagCounts[item.value] ?? 0}</StatusBadge>
                  </button>
                );
              })}
            </div>
          </section>
        </>,
      ) : null}

      <DataTable
        columns={tableColumns}
        rows={deals}
        getRowKey={(deal) => deal.id}
        gridClassName="ticket-worklist-table"
        // No repeated `เปิด ›` column: the row's onClick is a mouse-only convenience for opening
        // the deal. The identity cell's own `<Link to={`/tickets/${deal.id}`}>` above is the
        // keyboard/assistive-tech affordance, since a `<tr role="button">` would prune every other
        // cell (customer name, ผู้ดูแล, stage badge, "ขั้นตอน N/14") from the accessibility tree.
        // Mobile cards come from `mobileCard` below, separate from `columns`, and carry their own
        // `DealOpenButton`.
        onRowClick={openDeal}
        // `allDeals` is the whole role-scoped population before the phase/lifecycle/flag/inbox/search
        // filters; it gives the "filtered out of how many" denominator. DataTable appends the
        // clause only when it differs from what matched.
        unfilteredTotal={allDeals.length}
        mobileCard={(deal) => (
          user.role === 'account'
            ? <MoneyWorklistCard deal={deal} onOpen={openDeal} />
            : <DealCard deal={deal} catalog={stageCatalog} reason={worklistReason(user.role, deal, stageCatalog)} showTracking={isManagerView} onOpen={openDeal} />
        )}
        initialSort={{ key: 'date', dir: 'desc' }}
        loading={loading}
        error={ticketsQuery.error}
        onRetry={() => ticketsQuery.refetch()}
        emptyState={{
          icon: 'fileText',
          title: activeFilterCount > 0 ? 'ไม่พบดีลที่ตรงกับตัวกรอง' : 'ไม่มีดีล',
          description: emptyDescription,
        }}
      />

      {/* Summary metrics come after the worklist ("next action before summary metrics"). */}
      {isManagerView && pipelineGroups ? <TeamPipelineSummary groups={pipelineGroups} /> : null}

      {creating ? (
        <TicketCreateModal user={user} onClose={() => setCreating(false)} onSubmit={handleCreate} />
      ) : null}
    </div>
  );
}
