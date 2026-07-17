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
import { dealLifecycleLabel, dealLostReasonLabel, dealStageLabel, formatThaiDate, ticketStatusLabel } from '../../utils/format.js';
import { StageProgressBar } from './DealStageStepper.jsx';
import { SALES_PHASES, stageMeta } from './stageMeta.js';
import { TicketCreateModal } from './TicketCreateModal.jsx';

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

function DealStageCell({ deal }) {
  if (deal.lifecycle === 'CLOSED_LOST' || deal.lostReason) {
    const lost = dealLostReasonLabel(deal.lostReason);
    return <StatusBadge tone="danger">เสียงาน · {lost.label}</StatusBadge>;
  }
  const stage = dealStageLabel(deal.salesStage);
  const meta = stageMeta(deal.salesStage);
  const operational = ticketStatusLabel(deal.status);
  const paused = ['ON_HOLD', 'DORMANT'].includes(deal.lifecycle);
  const lifecycle = dealLifecycleLabel(deal.lifecycle);
  return (
    <span className="flex min-w-0 flex-col gap-0.5">
      <span className="flex flex-wrap items-center gap-1">
        <StatusBadge tone={stage.tone}>
          {meta ? `${meta.no}. ` : ''}{stage.label}
        </StatusBadge>
        {paused ? <StatusBadge tone={lifecycle.tone}>{lifecycle.label}</StatusBadge> : null}
      </span>
      <span className="pl-0.5 text-2xs text-text-muted">{operational.label}</span>
    </span>
  );
}

/** Mobile record card for a deal: identity, stage, progress, owner, freshness. */
function DealCard({ deal }) {
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

      <DealStageCell deal={deal} />
      <StageProgressBar salesStage={deal.salesStage} lost={deal.lifecycle === 'CLOSED_LOST' || !!deal.lostReason} />

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
  // phase cards (user decision) — the stage cell's sublabel still shows where
  // the paperwork stands per deal.
  const [searchParams, setSearchParams] = useSearchParams();
  const phaseFilter = searchParams.get('phase') ?? '';
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
  // 'lost' is its own lifecycle bucket; paused deals keep their phase.
  const phaseCounts = useMemo(() => {
    const counts = { lost: 0 };
    for (const phase of SALES_PHASES) counts[phase.id] = 0;
    for (const deal of allDeals) {
      if (deal.lifecycle === 'CLOSED_LOST' || deal.lostReason) counts.lost += 1;
      else {
        const meta = stageMeta(deal.salesStage);
        if (meta) counts[meta.phase] += 1;
      }
    }
    return counts;
  }, [allDeals]);

  const deals = useMemo(() => {
    if (!phaseFilter) return allDeals;
    if (phaseFilter === 'lost') return allDeals.filter((d) => d.lifecycle === 'CLOSED_LOST' || d.lostReason);
    return allDeals.filter((d) => d.lifecycle !== 'CLOSED_LOST' && !d.lostReason && stageMeta(d.salesStage)?.phase === Number(phaseFilter));
  }, [allDeals, phaseFilter]);

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
    onSuccess: () => {
      setCreating(false);
      showToast('success', 'สร้างดีลเรียบร้อย');
      invalidateTicketsList();
    },
  });

  async function handleCreate(payload) {
    await createMutation.mutateAsync(payload);
  }

  return (
    <div className="page-stack">
      <SalesTabs />
      <PageHeader
        title="งานขาย"
        subtitle="ดีลทั้งหมด · 1 ดีล = 1 ใบขอราคา ภายใต้โครงการ"
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
        <button
          type="button"
          aria-pressed={phaseFilter === 'lost'}
          className={`flex flex-col gap-1 rounded-xl border px-3 py-2.5 text-left ${
            phaseFilter === 'lost' ? 'border-danger bg-danger-bg' : 'border-border bg-surface'
          }`}
          onClick={() => updateParam('phase', phaseFilter === 'lost' ? '' : 'lost')}
        >
          <span className="flex items-center gap-1.5">
            <span aria-hidden="true" className="h-2 w-2 shrink-0 rounded-full bg-danger" />
            <span className="text-xl font-extrabold leading-none text-danger-dark">{phaseCounts.lost}</span>
          </span>
          <span className="text-2xs font-bold leading-tight text-danger-dark">เสียงาน</span>
        </button>
      </div>

      <DataTable
        columns={DEAL_COLUMNS}
        rows={deals}
        getRowKey={(deal) => deal.id}
        gridClassName="ticket-table"
        onRowClick={(deal) => navigate(`/tickets/${deal.id}`)}
        mobileCard={(deal) => <DealCard deal={deal} />}
        searchable
        searchValue={searchText}
        onSearchChange={(value) => updateParam('q', value)}
        searchPlaceholder="ค้นหาเลขที่ / บริษัท / โครงการ / ผู้ดูแล"
        initialSort={{ key: 'date', dir: 'desc' }}
        loading={loading}
        emptyState={{
          icon: 'fileText',
          title: 'ไม่มีดีล',
          description: 'ยังไม่มีดีลในเงื่อนไขที่เลือก',
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
    sortAccessor: (deal) => (deal.lostReason ? -1 : stageMeta(deal.salesStage)?.no ?? 0),
    render: (deal) => <DealStageCell deal={deal} />,
  },
  {
    key: 'progress',
    header: 'ความคืบหน้า',
    render: (deal) => <StageProgressBar salesStage={deal.salesStage} lost={!!deal.lostReason} />,
  },
  {
    key: 'date',
    header: 'อัปเดตล่าสุด',
    sortable: true,
    sortAccessor: (deal) => new Date(deal.stageUpdatedAt ?? deal.updatedAt),
    render: (deal) => <DaysBadge stageUpdatedAt={deal.stageUpdatedAt ?? deal.updatedAt} />,
  },
];
