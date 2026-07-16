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
import { dealLostReasonLabel, dealStageLabel, formatThaiDate, ticketStatusLabel } from '../../utils/format.js';
import { StageProgressBar } from './DealStageStepper.jsx';
import { SALES_PHASES, stageMeta } from './stageMeta.js';
import { TicketCreateModal } from './TicketCreateModal.jsx';

// Mirrors the StatusBadge tone palette (see src/styles.css .status-* rules) so the
// filter-tab accent never drifts from the shared status-color tokens. The three
// `border` accents (f59e0b/3b82f6/ef4444) are dot-accent colors with no existing
// design token equivalent — left as literals deliberately (see handoff notes).
const TONE_ACTIVE = {
  primary: { bg: 'var(--color-info-dot)', color: 'var(--color-surface)', border: 'var(--color-info-dot)' },
  neutral: { bg: 'var(--color-surface-subtle)', color: 'var(--color-icon-muted)', border: 'var(--color-text-faint)' },
  warning: { bg: 'var(--color-warning-bg)', color: 'var(--color-warning)', border: '#f59e0b' },
  info:    { bg: 'var(--color-info-bg)', color: 'var(--color-info)', border: '#3b82f6' },
  success: { bg: 'var(--color-success-bg)', color: 'var(--color-success-dark)', border: 'var(--color-success-soft)' },
  danger:  { bg: 'var(--color-danger-bg)', color: 'var(--color-danger-dark)', border: '#ef4444' },
};

// Operational queue filters (secondary row): import/CEO/account work off these,
// and dashboard queue cards deep-link here via ?status=... — keep them intact.
const STATUS_TABS = [
  { value: '',                 label: 'ทั้งหมด',              tone: 'primary' },
  { value: 'draft',            label: 'ดีลเริ่มต้น (ยังไม่ขอราคา)', tone: 'neutral' },
  { value: 'submitted',        label: 'รอรับเรื่อง',          tone: 'warning' },
  { value: 'in_review',        label: 'กำลังดำเนินการ',       tone: 'info'    },
  { value: 'price_proposed',   label: 'รอการอนุมัติ',         tone: 'warning' },
  { value: 'approved',         label: 'อนุมัติแล้ว',          tone: 'success' },
  { value: 'quotation_issued', label: 'ออกใบเสนอราคาแล้ว',   tone: 'success' },
  { value: 'document_issued',  label: 'ออกใบแจ้งยอดแล้ว',    tone: 'success' },
  { value: 'closed',           label: 'ปิดแล้ว',              tone: 'neutral' },
  { value: 'cancelled',        label: 'ยกเลิกแล้ว',           tone: 'danger'  },
];

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
  if (deal.lostReason) {
    const lost = dealLostReasonLabel(deal.lostReason);
    return <StatusBadge tone="danger">เสียงาน · {lost.label}</StatusBadge>;
  }
  const stage = dealStageLabel(deal.salesStage);
  const meta = stageMeta(deal.salesStage);
  const operational = ticketStatusLabel(deal.status);
  return (
    <span className="flex min-w-0 flex-col gap-0.5">
      <StatusBadge tone={stage.tone}>
        {meta ? `${meta.no}. ` : ''}{stage.label}
      </StatusBadge>
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
      <StageProgressBar salesStage={deal.salesStage} lost={!!deal.lostReason} />

      <span className="min-w-0 truncate text-xs text-text-muted">
        {[deal.createdByName, formatThaiDate(deal.createdAt)].filter(Boolean).join(' · ')}
      </span>
    </>
  );
}

export function TicketListPage({ user, showToast }) {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  // Filters + search live in the URL so dashboard queue cards can deep-link via
  // ?status=..., and list → detail → back keeps the active filters.
  const [searchParams, setSearchParams] = useSearchParams();
  const statusFilter = searchParams.get('status') ?? '';
  const phaseFilter = searchParams.get('phase') ?? '';
  const searchText = searchParams.get('q') ?? '';
  const [creating, setCreating] = useState(false);

  const canCreate = ROLE_PERMISSIONS.canCreateTickets.includes(user.role);

  const ticketsQuery = useQuery({
    queryKey: queryKeys.ticketList(statusFilter),
    queryFn: () => api.tickets.list(statusFilter ? { status: statusFilter } : {}).then((response) => response.tickets || []),
  });
  const allDeals = useMemo(() => ticketsQuery.data ?? [], [ticketsQuery.data]);
  const loading = ticketsQuery.isLoading || ticketsQuery.isFetching;

  useEffect(() => {
    if (ticketsQuery.error) showToast('error', ticketsQuery.error.message || 'โหลดข้อมูลไม่สำเร็จ');
  }, [ticketsQuery.error, showToast]);

  // Phase summary cards double as filters — never a 14-stage tab bar.
  // 'lost' is its own bucket; lost deals are excluded from phase counts.
  const phaseCounts = useMemo(() => {
    const counts = { lost: 0 };
    for (const phase of SALES_PHASES) counts[phase.id] = 0;
    for (const deal of allDeals) {
      if (deal.lostReason) counts.lost += 1;
      else {
        const meta = stageMeta(deal.salesStage);
        if (meta) counts[meta.phase] += 1;
      }
    }
    return counts;
  }, [allDeals]);

  const deals = useMemo(() => {
    if (!phaseFilter) return allDeals;
    if (phaseFilter === 'lost') return allDeals.filter((d) => d.lostReason);
    return allDeals.filter((d) => !d.lostReason && stageMeta(d.salesStage)?.phase === Number(phaseFilter));
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
        actions={canCreate ? (
          <button type="button" className="primary-button" onClick={() => setCreating(true)}>
            <Icon name="plus" />
            สร้างดีลใหม่
          </button>
        ) : null}
      />

      {/* Phase summary cards double as filters — no 14-stage tab bar. */}
      <div className="grid grid-cols-2 gap-2 sm:grid-cols-3 lg:grid-cols-6">
        {SALES_PHASES.map((phase) => {
          const isActive = phaseFilter === String(phase.id);
          return (
            <button
              key={phase.id}
              type="button"
              aria-pressed={isActive}
              className={`flex flex-col gap-1 rounded-xl border px-3 py-2.5 text-left ${
                isActive ? 'border-info bg-info-bg-alt' : 'border-border bg-surface'
              }`}
              onClick={() => updateParam('phase', isActive ? '' : String(phase.id))}
            >
              <span className={`text-xl font-extrabold leading-none ${isActive ? 'text-info' : 'text-text'}`}>
                {phaseCounts[phase.id]}
              </span>
              <span className={`text-2xs font-bold leading-tight ${isActive ? 'text-info' : 'text-text-muted'}`}>
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
          <span className="text-xl font-extrabold leading-none text-danger-dark">{phaseCounts.lost}</span>
          <span className="text-2xs font-bold leading-tight text-danger-dark">เสียงาน</span>
        </button>
      </div>

      <div className="status-tabs">
        {STATUS_TABS.map((tab) => {
          const isActive = statusFilter === tab.value;
          const ts = TONE_ACTIVE[tab.tone];
          const activeStyle = isActive
            ? { background: ts.bg, color: ts.color, borderColor: ts.border, fontWeight: 700 }
            : {};
          const dot = tab.tone !== 'primary'
            ? <span style={{ width: 8, height: 8, borderRadius: '50%', background: ts.border, display: 'inline-block', flexShrink: 0 }} />
            : null;
          return (
            <button
              key={tab.value}
              type="button"
              className={`status-tab${isActive ? ' active' : ''}`}
              style={activeStyle}
              onClick={() => updateParam('status', tab.value)}
            >
              {dot}
              {tab.label}
            </button>
          );
        })}
        <button type="button" className="icon-button" onClick={invalidateTicketsList} title="รีเฟรช" aria-label="รีเฟรช">
          <Icon name="refresh" />
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
