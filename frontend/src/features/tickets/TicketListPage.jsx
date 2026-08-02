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
  ticketStatusLabel,
} from '../../utils/format.js';
import { StageProgressBar } from './DealStageStepper.jsx';
import { dealInScope } from './salesViewScope.js';
import { SALES_PHASES, SALES_STAGES, stageIndex, stageMeta } from './stageMeta.js';
import { TicketCreateModal } from './TicketCreateModal.jsx';
import { effectiveWinProbability } from './dealTrackingMeta.js';

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

// One-line reason a deal is sitting in `role`'s worklist right now — the
// per-card emphasis the brief asks for ("account: … the pending money
// action", "import: lead with the pricing queue"). Presentation only, same
// spirit as dealInScope in salesViewScope.js but specific enough to this
// page's cards that it isn't worth exporting from that shared module.
function worklistReason(role, deal) {
  if (role === 'account') {
    if (deal.paymentStatus === 'DEPOSIT_NOTICE_ISSUED') return 'รอยืนยันรับมัดจำ';
    if (deal.paymentStatus === 'AWAITING_FINAL_PAYMENT') return 'รอชำระส่วนที่เหลือ';
    if (deal.overdue) return 'เกินกำหนดชำระ';
    return null;
  }
  if (role === 'import') {
    const meta = stageMeta(deal.salesStage);
    if (meta?.phase === 2 || meta?.phase === 3) return 'รอคำขอราคา';
    if (deal.salesStage === 'PROCUREMENT') return 'ดำเนินการนำเข้า (IR / จัดส่ง)';
    if (['DELIVERY_SCHEDULING', 'DELIVERED'].includes(deal.salesStage)) return 'ส่งมอบ / จองสต็อก';
    return null;
  }
  return null;
}

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
const FLAG_FILTERS = [
  { value: 'overdue', label: overdueBadgeLabel(true).label, tone: overdueBadgeLabel(true).tone },
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

// ── Manager live pipeline (V83, Slice B1/B2 "kill the weekly report" — handoff
// 103): the weekly-report replacement for sales_manager/ceo. `deal.stale` (no
// deal_activity logged in 7 days — computed server/mock-side, see
// dealTrackingMeta.js's computeStale) is unrelated to the DaysBadge/STALE_DAYS
// above (days since the STAGE last changed) — this is "has anyone followed up
// recently", not "how long has this deal sat in its stage".
const MANAGER_PIPELINE_ROLES = new Set(['sales_manager', 'ceo']);
const ORDER_RECEIVED_IDX = stageIndex('ORDER_RECEIVED');

/**
 * No-recent-activity badge, shown per-deal for the manager pipeline view.
 * Used to also carry a win% badge (`effectiveWinProbability`), but that was
 * a forecasting number masquerading as a progress number — the win-weighted
 * forecast already appears in `TeamPipelineSummary` below the table, so
 * dropping it here (owner decision) loses nothing, it just stops repeating
 * it in the wrong place.
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
function groupDealsForPipeline(deals) {
  const won = [];
  const expected = [];
  const lost = [];
  for (const deal of deals) {
    if (deal.lifecycle === 'CLOSED_LOST') { lost.push(deal); continue; }
    if (deal.lifecycle !== 'ACTIVE') continue;
    const idx = stageIndex(deal.salesStage);
    (idx >= ORDER_RECEIVED_IDX ? won : expected).push(deal);
  }
  const sumValue = (list) => list.reduce((sum, deal) => sum + (Number(deal.amountPayable) || 0), 0);
  const forecast = expected.reduce((sum, deal) => {
    const win = effectiveWinProbability(deal.winProbabilityOverride, deal.salesStage);
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
 * "ภาพรวมทีม" — the live-pipeline replacement for the weekly report: what
 * used to require every rep to email an Excel sheet is now this panel,
 * computed straight off the same deal list the table below renders.
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
function WorkStageCell({ deal, role, showTracking = false }) {
  const reason = worklistReason(role, deal);
  return (
    <span className="flex min-w-0 flex-col gap-1">
      {reason ? <StatusBadge tone="info">{reason}</StatusBadge> : null}
      <DealStageCell deal={deal} />
      {showTracking ? <TrackingBadges deal={deal} /> : null}
    </span>
  );
}

// Lifecycles where the deal is not currently, actively moving through the
// pipeline — a bare "ขั้นตอน N/14" fraction for one of these would newly
// assert live progress that the old legend-less bar never claimed. Naming
// the lifecycle alongside the fraction keeps the readout honest: "where it
// stood when it stopped", not "where it is heading right now".
const PAUSED_OR_TERMINAL_LIFECYCLES = new Set(['CANCELLED', 'COMPLETED', 'ON_HOLD', 'DORMANT']);

/**
 * Textual equivalent for the segmented progress bar's colour-coded fill
 * (never colour alone). `CLOSED_LOST` keeps its own fixed copy (no stage
 * fraction reads as progress on a lost deal). A deal with no resolvable
 * `salesStage` has nothing to report a fraction of. Every other non-ACTIVE
 * lifecycle gets its label prefixed onto the fraction (see
 * PAUSED_OR_TERMINAL_LIFECYCLES); a live ACTIVE deal gets the bare fraction.
 */
function progressReadout(deal) {
  if (deal.lifecycle === 'CLOSED_LOST') return 'ไม่คืบหน้า (เสียงาน)';
  const idx = stageIndex(deal.salesStage);
  const total = SALES_STAGES.length;
  // FIX F: the lifecycle prefix must be decided BEFORE the "no resolvable
  // stage" early return, not after it. A CANCELLED/COMPLETED/ON_HOLD/DORMANT
  // deal with a null/unrecognized `salesStage` (idx < 0) has no fraction to
  // show, but it still has a lifecycle to report — testing that ordering the
  // old way around meant such a deal displayed nothing anywhere saying it
  // was cancelled. (DealStageCell now also badges CANCELLED/COMPLETED here
  // — see FIX F6 — but this progress-column readout is the one place a deal
  // with no resolvable stage still gets a lifecycle mention at all.)
  const lifecyclePrefixed = PAUSED_OR_TERMINAL_LIFECYCLES.has(deal.lifecycle);
  if (idx < 0) {
    return lifecyclePrefixed ? dealLifecycleLabel(deal.lifecycle).label : 'ยังไม่ระบุขั้นตอน';
  }
  const fraction = `ขั้นตอน ${idx + 1}/${total}`;
  return lifecyclePrefixed ? `${dealLifecycleLabel(deal.lifecycle).label} · ${fraction}` : fraction;
}

/**
 * The `ความคืบหน้า` (progress) column: the segmented stage bar plus its
 * textual equivalent. Used to also show a completion percentage alongside
 * the fraction ("62% · ขั้นตอน 9/14") — with the manager-only win% badge
 * gone from this column (owner decision, see `TrackingBadges`), the percent
 * and the fraction were just saying the same thing twice, so only the
 * fraction remains.
 */
function ProgressCell({ deal }) {
  const lost = deal.lifecycle === 'CLOSED_LOST';
  return (
    <span className="flex min-w-0 flex-col gap-1">
      <StageProgressBar salesStage={deal.salesStage} lost={lost} />
      <span className="text-2xs font-bold tabular-nums text-text-muted">
        {progressReadout(deal)}
      </span>
    </span>
  );
}

// NOTE: 'lost' keys on lifecycle, never on lostReason. Since V57 the reason
// SURVIVES a reopen, so a live reopened deal still carries one — testing the
// reason would render it as เสียงาน and drop it out of the phase counts.
function DealStageCell({ deal }) {
  if (deal.lifecycle === 'CLOSED_LOST') {
    const lost = dealLostReasonLabel(deal.lostReason);
    return <StatusBadge tone="danger">เสียงาน · {lost.label}</StatusBadge>;
  }
  const stage = dealStageLabel(deal.salesStage);
  const meta = stageMeta(deal.salesStage);
  const operational = ticketStatusLabel(deal.status);
  // FIX F6 (review-remediation): used to badge only ON_HOLD/DORMANT, so a
  // CANCELLED or COMPLETED deal looked indistinguishable from a live one in
  // this column — and specifically in this column, because the 721–1040px
  // tablet band (see styles.css) hides the `progress` column outright, which
  // is the only other place CANCELLED/COMPLETED ever got a mention
  // (progressReadout's lifecycle prefix). Reuse the same
  // PAUSED_OR_TERMINAL_LIFECYCLES set progressReadout already keys off, so
  // the two stay in sync.
  const showLifecycleBadge = PAUSED_OR_TERMINAL_LIFECYCLES.has(deal.lifecycle);
  const lifecycle = dealLifecycleLabel(deal.lifecycle);
  // Since 03b5ba9 stopped ticket-level auto-submit, every newly created deal's
  // legacy `status` is frozen at 'draft' forever — it no longer advances with
  // real workflow (that now lives on the deal's PricingRequest(s) and
  // salesStage instead). Showing "แบบร่าง" under the stage badge for those
  // deals is not just uninformative, it actively misleads a reader into
  // thinking nothing has happened. Older deals that already progressed past
  // draft before that change still have a meaningful legacy status, so keep
  // showing it for them.
  const showOperational = deal.status !== 'draft';
  return (
    <span className="flex min-w-0 flex-col gap-0.5">
      <span className="flex flex-wrap items-center gap-1">
        <StatusBadge tone={stage.tone}>
          {meta ? `${meta.no}. ` : ''}{stage.label}
        </StatusBadge>
        {showLifecycleBadge ? <StatusBadge tone={lifecycle.tone}>{lifecycle.label}</StatusBadge> : null}
      </span>
      {showOperational ? (
        <span className="pl-0.5 text-2xs text-text-muted">{operational.label}</span>
      ) : null}
    </span>
  );
}

/**
 * Mobile record card for a deal: identity, stage, progress, owner, freshness.
 * `reason`, when given, is a one-line worklist chip ("why this deal is in
 * your queue right now") shown right under the customer name — used by
 * import's card to lead with the pricing/procurement queue instead of
 * making the viewer read the full 14-stage label first. `showTracking`
 * (sales_manager/ceo only) adds the win%/stale badges below the stage —
 * the mobile equivalent of the manager-only DataTable column.
 */
// Mobile-only now (Task 2d): the desktop table's own repeated `เปิด ›`
// column is gone in favor of DataTable's onRowClick — the whole row is the
// open target there. Mobile cards keep this explicit button since a card
// isn't a table row DataTable can attach onRowClick to (see DataTable.jsx's
// onRowClick doc comment).
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

function DealCard({ deal, reason = null, showTracking = false, onOpen }) {
  const stage = dealStageLabel(deal.salesStage);
  const stageMetaInfo = stageMeta(deal.salesStage);
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
        {reason ? <small>{reason}</small> : null}
      </div>
      <StageProgressBar salesStage={deal.salesStage} lost={deal.lifecycle === 'CLOSED_LOST'} />
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

function buildDealColumns({ role, isManagerView }) {
  return [
    {
      key: 'customer',
      header: 'ดีล / ลูกค้า / โครงการ',
      searchAccessor: (deal) => [deal.code, deal.customerName, deal.projectName, deal.title].filter(Boolean).join(' '),
      // FIX A: this Link (not the row's own `onRowClick`, which DataTable now
      // treats as a mouse-only convenience) is the row's keyboard and
      // assistive-tech activation target, following the pattern
      // PricingRequestQueuePage.jsx already argues for at its `requestCode`
      // column. It also fixes the 721–1040px tablet band, which renders this
      // desktop table (`useIsMobile` is <=720px) with no hover and — before
      // this fix — no visible cue at all that a row was tappable.
      render: (deal) => (
        <span className="flex min-w-0 flex-col gap-0.5">
          <Link
            to={`/tickets/${deal.id}`}
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
      sortAccessor: (deal) => (deal.lifecycle === 'CLOSED_LOST' ? -1 : stageMeta(deal.salesStage)?.no ?? 0),
      render: (deal) => <WorkStageCell deal={deal} role={role} showTracking={isManagerView} />,
    },
    {
      key: 'progress',
      header: 'ความคืบหน้า',
      render: (deal) => <ProgressCell deal={deal} />,
    },
    {
      key: 'date',
      header: 'อัปเดตล่าสุด',
      sortable: true,
      sortAccessor: (deal) => new Date(deal.stageUpdatedAt ?? deal.updatedAt),
      render: (deal) => <DaysBadge stageUpdatedAt={deal.stageUpdatedAt ?? deal.updatedAt} />,
    },
    // Row-level navigation (onRowClick, wired on the <DataTable> below)
    // replaced the repeated `เปิด ›` button that used to close every row —
    // identical on all 15, carrying no information. There used to be a
    // trailing "ขั้นตอนถัดไป" (next action) column here too
    // (dealWorklistActions.js), but `/tickets` is gated on
    // canViewDealPipeline (sales/sales_manager/ceo only — permissions.js),
    // and that resolver only ever returned a verb for account/import, who
    // can never reach this page. The column was therefore a header plus an
    // empty cell on every single row for every real viewer — removed
    // outright (owner decision) rather than kept as permanent dead weight.
  ];
}

export function TicketListPage({ user, showToast }) {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  // Phase filter + search live in the URL so list → detail → back keeps them.
  // The old operational ?status= chips row was removed as redundant with the
  // phase cards (user decision) — the stage cell's sublabel shows where the
  // legacy ticket-status paperwork stands, for deals where that status still
  // means something (see DealStageCell's showOperational: it is suppressed
  // for deals frozen at 'draft').
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
  // Manager live pipeline (V83, Slice B1/B2 — handoff 103): the weekly-report
  // replacement, sales_manager/ceo only.
  const isManagerView = MANAGER_PIPELINE_ROLES.has(user.role);
  const [creating, setCreating] = useState(false);
  const filterToggleRef = useRef(null);
  const filterSheetRef = useRef(null);
  // Owner feedback (role-scoped views, Sales branch): the LIFECYCLE/FLAGS chip
  // rows were competing with the deal list for attention. They stay fully
  // functional (same URL-param filters as before) but sit behind a collapsed
  // "ตัวกรองเพิ่มเติม" expander now — collapsed by default for every role, and
  // opened automatically whenever one of them becomes active (e.g. a deep link
  // with ?life=... or ?flag=...) so an applied filter is surfaced, not hidden.
  //
  // Phase 4A: openness is now genuinely state-driven rather than
  // `open || hasActiveFilter`. The old derivation made the sheet impossible to
  // dismiss while a lifecycle/flag filter was applied — the close button, the
  // scrim and Escape all became no-ops, which is unacceptable for what is a
  // modal bottom sheet at <=720px. An applied filter is still never hidden:
  // the always-visible "ตัวกรองที่ใช้" summary row and the count badge on the
  // toggle keep reporting it after the sheet is closed.
  const [moreFiltersOpen, setMoreFiltersOpen] = useState(
    () => Boolean(searchParams.get('life') || searchParams.get('flag')),
  );

  const canCreate = ROLE_PERMISSIONS.canCreateTickets.includes(user.role);

  const ticketsQuery = useQuery({
    queryKey: queryKeys.ticketList(''),
    queryFn: () => api.tickets.list({}).then((response) => response.tickets || []),
  });
  const allDeals = useMemo(() => ticketsQuery.data ?? [], [ticketsQuery.data]);
  const loading = ticketsQuery.isLoading;
  const refreshing = ticketsQuery.isFetching && !ticketsQuery.isLoading;
  const openDeal = useCallback((deal) => {
    navigate(`/tickets/${deal.id}`);
  }, [navigate]);

  useEffect(() => {
    if (ticketsQuery.error) showToast('error', ticketsQuery.error.message || 'โหลดข้อมูลไม่สำเร็จ');
  }, [ticketsQuery.error, showToast]);

  // Phase strip doubles as a compact filter — never a 14-stage tab bar.
  // Lifecycle buckets live in the additional filters below, so phase counts stay active-only.
  const phaseCounts = useMemo(() => {
    const counts = {};
    for (const phase of SALES_PHASES) counts[phase.id] = 0;
    for (const deal of allDeals) {
      if (deal.lifecycle === 'ACTIVE') {
        const meta = stageMeta(deal.salesStage);
        if (meta) counts[meta.phase] += 1;
      }
    }
    return counts;
  }, [allDeals]);

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
    partial_delivery: allDeals.filter((deal) => deal.fulfillmentStatus === 'PARTIALLY_DELIVERED').length,
  }), [allDeals]);

  // Manager live pipeline: computed off the FULL unfiltered list (independent of
  // the phase/lifecycle/flag chips below), same "counts-from-allDeals" convention
  // as flagCounts/lifecycleCounts above — a stable overview regardless of
  // whatever the viewer happens to be filtering the table to right now.
  const pipelineGroups = useMemo(
    () => (isManagerView ? groupDealsForPipeline(allDeals) : null),
    [allDeals, isManagerView],
  );

  // Role-scoped views, Phase A: how many of allDeals are actually in this
  // role's worklist right now — same counts-from-allDeals convention as
  // lifecycleCounts/flagCounts above (independent of the other active
  // filters), used by the inbox toggle chips below.
  const inboxCounts = useMemo(() => (hasWorklistDistinction ? {
    inbox: allDeals.filter((deal) => dealInScope(user.role, deal)).length,
    all: allDeals.length,
  } : null), [allDeals, hasWorklistDistinction, user.role]);

  const deals = useMemo(() => {
    return allDeals.filter((deal) => {
      const lost = deal.lifecycle === 'CLOSED_LOST';
      // Phase cards are the active-pipeline funnel (counts are ACTIVE-only), so the phase
      // filter matches on ACTIVE too — paused/terminal deals are reached via the lifecycle
      // chips below. This keeps each phase card's count equal to the rows it filters to.
      const phaseOk = !phaseFilter
        || (deal.lifecycle === 'ACTIVE' && stageMeta(deal.salesStage)?.phase === Number(phaseFilter));
      const lifeOk = !lifecycleFilter || (lifecycleFilter === 'CLOSED_LOST' ? lost : deal.lifecycle === lifecycleFilter);
      const flagOk = !flagFilter
        || (flagFilter === 'overdue' && deal.overdue)
        || (flagFilter === 'partial_delivery' && deal.fulfillmentStatus === 'PARTIALLY_DELIVERED');
      const inboxOk = !inboxOnly || dealInScope(user.role, deal);
      const searchOk = matchesSearch(deal, searchText);
      return phaseOk && lifeOk && flagOk && inboxOk && searchOk;
    });
  }, [allDeals, flagFilter, lifecycleFilter, phaseFilter, inboxOnly, user.role, searchText]);

  const hasActiveMoreFilters = Boolean(lifecycleFilter || flagFilter);
  const showMoreFilters = moreFiltersOpen;
  const activeMoreFiltersCount = (lifecycleFilter ? 1 : 0) + (flagFilter ? 1 : 0);
  // <=720px the same markup is a fixed bottom sheet over a scrim — a real
  // modal — while above that breakpoint it is an inline disclosure panel with
  // the scrim hidden (styles.css: .ticket-filter-backdrop { display: none }
  // until the mobile media query). Modal semantics are therefore
  // breakpoint-scoped: Escape closes at any width, but the focus trap, the
  // initial focus move and inerting the page behind only apply where the sheet
  // actually covers the page.
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
      const phase = SALES_PHASES.find((item) => String(item.id) === phaseFilter);
      if (phase) filters.push(`เฟส ${phase.id}: ${phase.name}`);
    }
    if (lifecycleFilter) filters.push(`สถานะงาน: ${dealLifecycleLabel(lifecycleFilter).label}`);
    if (flagFilter) {
      const flag = FLAG_FILTERS.find((item) => item.value === flagFilter);
      if (flag) filters.push(`สัญญาณ: ${flag.label}`);
    }
    if (hasWorklistDistinction && searchParams.get('inbox') === '0') filters.push('ขอบเขต: ทั้งหมด');
    return filters;
  }, [flagFilter, hasWorklistDistinction, lifecycleFilter, phaseFilter, searchParams, searchText]);

  // Phase chips, WorklistFilters-driven (Task 1b/2). The "all" chip used to
  // read "ดีลที่ดำเนินอยู่" ("active deals") over a count (`activePipelineCount`)
  // that was deliberately ACTIVE-only — but the chip's action (`updateParam
  // ('phase', '')`) clears the phase filter entirely, which lists every
  // deal including CLOSED_LOST/CANCELLED/COMPLETED. The label matched its
  // own count but misdescribed what clicking it actually does. Fixed (owner
  // decision) by naming the chip after its action ("ทั้งหมด" — all) and
  // dropping the count altogether rather than attaching a second, different
  // number to it: the single statement of the filtered total is DataTable's
  // own footer below, and the per-phase chips keep their own (honest, each
  // matching exactly what selecting it filters to) counts.
  const phaseChipItems = useMemo(() => [
    { key: '', label: 'ทั้งหมด' },
    ...SALES_PHASES.map((phase) => ({
      key: String(phase.id),
      label: `เฟส ${phase.id} · ${phase.name}`,
      count: phaseCounts[phase.id],
      dotClassName: PHASE_STYLES[phase.id].dot,
    })),
  ], [phaseCounts]);

  function selectPhase(key) {
    // Clicking the already-active chip clears back to "all" — same toggle-off
    // the old hand-rolled phase strip offered.
    updateParam('phase', key && key === phaseFilter ? '' : key);
  }

  // Memoized like phaseChipItems above (not a fresh array literal every
  // render): WorklistFilters keys its scroll/resize-affordance effect on
  // `[items]`, so a new array identity on every parent render was tearing
  // that listener down and re-attaching it just as often, for no reason.
  const inboxChipItems = useMemo(() => (hasWorklistDistinction ? [
    { key: '', label: 'ต้องดำเนินการ', count: inboxCounts.inbox },
    { key: '0', label: 'ทั้งหมด', count: inboxCounts.all },
  ] : null), [hasWorklistDistinction, inboxCounts]);

  const tableColumns = useMemo(
    () => buildDealColumns({ role: user.role, isManagerView }),
    [isManagerView, user.role],
  );

  const emptyDescription = useMemo(() => {
    if (searchText.trim()) return 'ไม่พบดีลที่ตรงกับคำค้นหาและตัวกรองที่เลือก';
    if (phaseFilter) {
      const phase = SALES_PHASES.find((item) => String(item.id) === phaseFilter);
      return phase ? `ไม่มีดีลในเฟส ${phase.id} · ${phase.name}` : 'ไม่มีดีลในเฟสที่เลือก';
    }
    if (flagFilter === 'overdue') return 'ไม่มีดีลที่เกินกำหนดชำระ';
    if (flagFilter === 'partial_delivery') return 'ไม่มีดีลที่ส่งมอบบางส่วน';
    if (lifecycleFilter) return `ไม่มีดีลในสถานะ${dealLifecycleLabel(lifecycleFilter).label}`;
    if (inboxOnly) return 'ไม่มีดีลที่ต้องดำเนินการตอนนี้ — ลองดูแท็บ "ทั้งหมด"';
    return 'ยังไม่มีดีลในเงื่อนไขที่เลือก';
  }, [flagFilter, lifecycleFilter, inboxOnly, phaseFilter, searchText]);

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
      className="page-stack ticket-list-page"
      // `inert` removes the page behind the sheet from focus order, pointer
      // hit-testing and the accessibility tree in one attribute. React 18 has
      // no boolean-prop support for it, so the empty string is used to emit the
      // bare HTML attribute; aria-hidden covers browsers without inert yet.
      {...(mobileSheetOpen ? { inert: '', 'aria-hidden': 'true' } : {})}
    >
      <SalesTabs role={user.role} />
      <PageHeader
        title="รายการดีล"
        subtitle="ติดตามดีลตามขอบเขตบทบาทและตัวกรองที่เลือก"
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

      {/* Consolidated filter band (Task 2a): everything that used to be four
          stacked full-width sections — the scope banner (eyebrow/title/
          description + inbox toggle + a result-count restatement), and the
          phase strip — now lives inside this one bordered band, alongside
          search/more-filters/clear. Scope + phase + search are one filter
          band, not three; DataTable's own footer stays the single statement
          of "how many rows matched" (Task 2b) instead of restating it here
          too. */}
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

        {/* Spacing/reflow live here as utilities, not in styles.css: index.css
            orders `@layer theme, legacy, utilities`, so a `.ticket-filter-bar`
            rule in the legacy layer always loses to FilterBar's own utilities.
            The previous CSS needed `display: grid !important` to win and its
            gap/padding never applied at all. `mobile:` is the shared <=720px
            variant from the Phase 3.4 token work. */}
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
        // Task 2d: the repeated `เปิด ›` column (identical on every row) is
        // gone — the row's onClick is now a mouse-only convenience for
        // opening the deal (FIX A: the identity cell's own `<Link
        // to={`/tickets/${deal.id}`}>` above is the keyboard/assistive-tech
        // affordance, since a `<tr role="button">` would have pruned every
        // other cell — customer name, ผู้ดูแล, the stage badge, the
        // "ขั้นตอน N/14" readout — out of the accessibility tree). Same move
        // `/finance` never needed because its whole action column already
        // varied by row. Mobile cards are unaffected: they come from
        // `mobileCard` below, entirely separate from `columns`, and already
        // carry their own `DealOpenButton` affordance.
        onRowClick={openDeal}
        // FIX G: restores the "filtered out of how many" denominator the old
        // `.ticket-result-count` band used to show. `allDeals` is the whole
        // role-scoped population before the phase/lifecycle/flag/inbox/search
        // filters above are applied; DataTable only appends the clause when
        // this differs from what actually matched.
        unfilteredTotal={allDeals.length}
        mobileCard={(deal) => (
          user.role === 'account'
            ? <MoneyWorklistCard deal={deal} onOpen={openDeal} />
            : <DealCard deal={deal} reason={worklistReason(user.role, deal)} showTracking={isManagerView} onOpen={openDeal} />
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

      {/* Task 2c: summary metrics belong after the worklist, not before it
          (design principle #3, "next action before summary metrics") — this
          used to render above the phase strip. */}
      {isManagerView && pipelineGroups ? <TeamPipelineSummary groups={pipelineGroups} /> : null}

      {creating ? (
        <TicketCreateModal onClose={() => setCreating(false)} onSubmit={handleCreate} />
      ) : null}
    </div>
  );
}
