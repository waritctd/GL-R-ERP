import { useEffect, useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { api, ROLE_PERMISSIONS } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { DataTable } from '../../components/common/DataTable.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { PageHeader } from '../../components/common/PageHeader.jsx';
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
import { SALES_PHASES, stageMeta } from './stageMeta.js';
import { TicketCreateModal } from './TicketCreateModal.jsx';

// Role-scoped views, Phase A: roles whose list page distinguishes "my
// worklist" from "every deal I may read" — everyone else (sales already only
// ever receives its own deals from the API; ceo/sales_manager are oversight
// and want the full list) has no such distinction, so no toggle is shown.
const WORKLIST_ROLES = new Set(['import', 'account']);

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
    if (meta?.phase === 2 || meta?.phase === 3) return 'รอเสนอราคา (Pricing Request)';
    if (deal.salesStage === 'PROCUREMENT') return 'ดำเนินการนำเข้า (IR / จัดส่ง)';
    if (['DELIVERY_SCHEDULING', 'DELIVERED'].includes(deal.salesStage)) return 'ส่งมอบ / จองสต็อก';
    return null;
  }
  return null;
}

// Per-phase accents (design tokens --color-phase-N, adopted from the user's
// Claude Design prototype). Static class map — Tailwind's scanner needs the
// full class names in source, so no `bg-phase-${id}` interpolation.
const PHASE_STYLES = {
  1: { dot: 'bg-phase-1', active: 'border-phase-1 bg-phase-1-bg', num: 'text-phase-1' },
  2: { dot: 'bg-phase-2', active: 'border-phase-2 bg-phase-2-bg', num: 'text-phase-2' },
  3: { dot: 'bg-phase-3', active: 'border-phase-3 bg-phase-3-bg', num: 'text-phase-3' },
  4: { dot: 'bg-phase-4', active: 'border-phase-4 bg-phase-4-bg', num: 'text-phase-4' },
  5: { dot: 'bg-phase-5', active: 'border-phase-5 bg-phase-5-bg', num: 'text-phase-5' },
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
  const paused = ['ON_HOLD', 'DORMANT'].includes(deal.lifecycle);
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
        {paused ? <StatusBadge tone={lifecycle.tone}>{lifecycle.label}</StatusBadge> : null}
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
 * making the viewer read the full 14-stage label first.
 */
function DealCard({ deal, reason = null }) {
  return (
    <>
      <div className="flex min-w-0 items-start justify-between gap-3">
        <code className="min-w-0 truncate text-xs text-text-muted">{deal.code}</code>
        <DaysBadge stageUpdatedAt={deal.stageUpdatedAt} />
      </div>

      <strong className="min-w-0 text-md leading-snug font-extrabold text-text">
        {deal.customerName || deal.title}
      </strong>
      {deal.projectName ? (
        <span className="min-w-0 truncate text-xs text-text-muted">{deal.projectName}</span>
      ) : null}
      {reason ? <StatusBadge tone="info">{reason}</StatusBadge> : null}

      <DealStageCell deal={deal} />
      <StageProgressBar salesStage={deal.salesStage} lost={deal.lifecycle === 'CLOSED_LOST'} />

      <span className="min-w-0 truncate text-xs text-text-muted">
        {[deal.createdByName, formatThaiDate(deal.createdAt)].filter(Boolean).join(' · ')}
      </span>
    </>
  );
}

/**
 * Account's money-worklist card: leads with the amount and the specific
 * pending money action instead of the pipeline stage (account doesn't act on
 * the pipeline stage directly — it acts on a payment).
 */
function MoneyWorklistCard({ deal }) {
  const reason = worklistReason('account', deal);
  return (
    <>
      <div className="flex min-w-0 items-start justify-between gap-3">
        <code className="min-w-0 truncate text-xs text-text-muted">{deal.code}</code>
        <DaysBadge stageUpdatedAt={deal.stageUpdatedAt} />
      </div>

      <strong className="min-w-0 text-md leading-snug font-extrabold text-text">
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

      <span className="min-w-0 truncate text-xs text-text-muted">
        {[deal.createdByName, formatThaiDate(deal.createdAt)].filter(Boolean).join(' · ')}
      </span>
    </>
  );
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
  const [creating, setCreating] = useState(false);

  const canCreate = ROLE_PERMISSIONS.canCreateTickets.includes(user.role);

  const ticketsQuery = useQuery({
    queryKey: queryKeys.ticketList(''),
    queryFn: () => api.tickets.list({}).then((response) => response.tickets || []),
  });
  const allDeals = useMemo(() => ticketsQuery.data ?? [], [ticketsQuery.data]);
  const loading = ticketsQuery.isLoading || ticketsQuery.isFetching;

  useEffect(() => {
    if (ticketsQuery.error) showToast('error', ticketsQuery.error.message || 'โหลดข้อมูลไม่สำเร็จ');
  }, [ticketsQuery.error, showToast]);

  // Phase summary cards double as filters — never a 14-stage tab bar.
  // Lifecycle buckets live in the chip row below, so phase counts stay active-only.
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
      return phaseOk && lifeOk && flagOk && inboxOk;
    });
  }, [allDeals, flagFilter, lifecycleFilter, phaseFilter, inboxOnly, user.role]);

  const emptyDescription = useMemo(() => {
    if (flagFilter === 'overdue') return 'ไม่มีดีลที่เกินกำหนดชำระ';
    if (flagFilter === 'partial_delivery') return 'ไม่มีดีลที่ส่งมอบบางส่วน';
    if (lifecycleFilter) return `ไม่มีดีลในสถานะ${dealLifecycleLabel(lifecycleFilter).label}`;
    if (inboxOnly) return 'ไม่มีดีลที่ต้องดำเนินการตอนนี้ — ลองดูแท็บ "ทั้งหมด"';
    return 'ยังไม่มีดีลในเงื่อนไขที่เลือก';
  }, [flagFilter, lifecycleFilter, inboxOnly]);

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

  return (
    <div className="page-stack">
      <SalesTabs role={user.role} />
      <PageHeader
        title="งานขาย"
        subtitle="ดีลทั้งหมด · 1 ดีล = 1 Ticket ภายใต้โครงการ หนึ่งดีลอาจมีได้หลายใบขอราคา"
        actions={(
          <>
            <button type="button" className="icon-button" onClick={invalidateTicketsList} title="รีเฟรช" aria-label="รีเฟรช">
              <Icon name="refresh" />
            </button>
            {canCreate ? (
              <button type="button" className="primary-button" onClick={() => setCreating(true)}>
                <Icon name="plus" />
                สร้างดีลใหม่
              </button>
            ) : null}
          </>
        )}
      />

      {/* Phase summary cards double as filters — no 14-stage tab bar. */}
      <div className="grid grid-cols-2 gap-2 sm:grid-cols-3 lg:grid-cols-6">
        {SALES_PHASES.map((phase) => {
          const isActive = phaseFilter === String(phase.id);
          const style = PHASE_STYLES[phase.id];
          return (
            <button
              key={phase.id}
              type="button"
              aria-pressed={isActive}
              className={`flex flex-col gap-1 rounded-xl border px-3 py-2.5 text-left ${
                isActive ? style.active : 'border-border bg-surface'
              }`}
              onClick={() => updateParam('phase', isActive ? '' : String(phase.id))}
            >
              <span className="flex items-center gap-1.5">
                <span aria-hidden="true" className={`h-2 w-2 shrink-0 rounded-full ${style.dot}`} />
                <span className={`text-xl font-extrabold leading-none ${isActive ? style.num : 'text-text'}`}>
                  {phaseCounts[phase.id]}
                </span>
              </span>
              <span className={`text-2xs font-bold leading-tight ${isActive ? style.num : 'text-text-muted'}`}>
                เฟส {phase.id} · {phase.name}
              </span>
            </button>
          );
        })}
      </div>

      {hasWorklistDistinction ? (
        <div className="flex flex-wrap items-center gap-2 rounded-lg border border-border bg-surface p-3">
          <span className="text-2xs font-extrabold uppercase tracking-wide text-text-muted">แสดง</span>
          {[
            { value: '', label: 'ต้องดำเนินการ', count: inboxCounts.inbox },
            { value: '0', label: 'ทั้งหมด', count: inboxCounts.all },
          ].map((item) => {
            const active = (searchParams.get('inbox') ?? '') === item.value;
            return (
              <button
                key={item.value || 'all'}
                type="button"
                aria-pressed={active}
                className={`inline-flex min-h-8 items-center gap-1.5 rounded-full border px-3 text-xs font-bold ${
                  active ? 'border-primary bg-primary/10 text-primary' : 'border-border bg-surface hover:bg-surface-hover'
                }`}
                onClick={() => updateParam('inbox', item.value)}
              >
                <span>{item.label}</span>
                <StatusBadge tone={active ? 'info' : 'neutral'}>{item.count}</StatusBadge>
              </button>
            );
          })}
        </div>
      ) : null}

      <div className="flex flex-col gap-2 rounded-lg border border-border bg-surface p-3">
        <div className="flex flex-wrap items-center gap-2">
          <span className="text-2xs font-extrabold uppercase tracking-wide text-text-muted">Lifecycle</span>
          {LIFECYCLE_FILTERS.map((item) => {
            const active = lifecycleFilter === item.value;
            return (
              <button
                key={item.value || 'all'}
                type="button"
                aria-pressed={active}
                className={`inline-flex min-h-8 items-center gap-1.5 rounded-full border px-3 text-xs font-bold ${
                  active ? 'border-primary bg-primary/10 text-primary' : 'border-border bg-surface hover:bg-surface-hover'
                }`}
                onClick={() => updateParam('life', active ? '' : item.value)}
              >
                <span>{item.label}</span>
                <StatusBadge tone={item.tone}>{lifecycleCounts[item.value] ?? 0}</StatusBadge>
              </button>
            );
          })}
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <span className="text-2xs font-extrabold uppercase tracking-wide text-text-muted">Flags</span>
          {FLAG_FILTERS.map((item) => {
            const active = flagFilter === item.value;
            return (
              <button
                key={item.value}
                type="button"
                aria-pressed={active}
                className={`inline-flex min-h-8 items-center gap-1.5 rounded-full border px-3 text-xs font-bold ${
                  active ? 'border-primary bg-primary/10 text-primary' : 'border-border bg-surface hover:bg-surface-hover'
                }`}
                onClick={() => updateParam('flag', active ? '' : item.value)}
              >
                <span>{item.label}</span>
                <StatusBadge tone={item.tone}>{flagCounts[item.value] ?? 0}</StatusBadge>
              </button>
            );
          })}
        </div>
      </div>

      <DataTable
        columns={DEAL_COLUMNS}
        rows={deals}
        getRowKey={(deal) => deal.id}
        gridClassName="ticket-table"
        onRowClick={(deal) => navigate(`/tickets/${deal.id}`)}
        mobileCard={(deal) => (
          user.role === 'account'
            ? <MoneyWorklistCard deal={deal} />
            : <DealCard deal={deal} reason={worklistReason(user.role, deal)} />
        )}
        searchable
        searchValue={searchText}
        onSearchChange={(value) => updateParam('q', value)}
        searchPlaceholder="ค้นหาเลขที่ / บริษัท / โครงการ / ผู้ดูแล"
        initialSort={{ key: 'date', dir: 'desc' }}
        loading={loading}
        emptyState={{
          icon: 'fileText',
          title: 'ไม่มีดีล',
          description: emptyDescription,
        }}
      />

      {creating ? (
        <TicketCreateModal onClose={() => setCreating(false)} onSubmit={handleCreate} />
      ) : null}
    </div>
  );
}

const DEAL_COLUMNS = [
  {
    key: 'customer',
    header: 'ดีล / โครงการ',
    searchAccessor: (deal) => [deal.code, deal.customerName, deal.projectName, deal.title].filter(Boolean).join(' '),
    render: (deal) => (
      <span className="flex min-w-0 flex-col gap-0.5">
        <strong className="block truncate text-text">{deal.customerName || deal.title}</strong>
        <span className="block truncate text-2xs text-text-muted">
          {[deal.projectName, deal.code].filter(Boolean).join(' · ')}
        </span>
      </span>
    ),
  },
  {
    key: 'createdByName',
    header: 'ผู้ดูแล (Sales)',
    searchAccessor: (deal) => deal.createdByName || '',
    render: (deal) => <span>{deal.createdByName}</span>,
  },
  {
    key: 'stage',
    header: 'สถานะดีล',
    sortable: true,
    sortAccessor: (deal) => (deal.lifecycle === 'CLOSED_LOST' ? -1 : stageMeta(deal.salesStage)?.no ?? 0),
    render: (deal) => <DealStageCell deal={deal} />,
  },
  {
    key: 'progress',
    header: 'ความคืบหน้า',
    render: (deal) => <StageProgressBar salesStage={deal.salesStage} lost={deal.lifecycle === 'CLOSED_LOST'} />,
  },
  {
    key: 'date',
    header: 'อัปเดตล่าสุด',
    sortable: true,
    sortAccessor: (deal) => new Date(deal.stageUpdatedAt ?? deal.updatedAt),
    render: (deal) => <DaysBadge stageUpdatedAt={deal.stageUpdatedAt ?? deal.updatedAt} />,
  },
];
