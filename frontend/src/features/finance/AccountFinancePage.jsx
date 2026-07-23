import { useEffect, useMemo } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { Button } from '../../components/common/Button.jsx';
import { EmptyState } from '../../components/common/EmptyState.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { PageHeader } from '../../components/common/PageHeader.jsx';
import { FilterBar, PageStack } from '../../components/common/Layout.jsx';
import { SkeletonText } from '../../components/common/Skeleton.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { useIsMobile } from '../../hooks/useIsMobile.js';
import { cn } from '../../utils/cn.js';
import { formatMoney, ticketStatusLabel } from '../../utils/format.js';
import { nextAccountAction } from '../tickets/accountActions.js';

// One tab per step of the money lifecycle this page walks — deposit -> final
// payment -> close-ready -> record-invoice/commission — plus "เกินกำหนด" (cuts
// across every step) and "ทั้งหมด". Keys are exactly nextAccountAction()'s own
// `action.key`, so filtering never drifts from what the CTA column shows.
const STAGE_FILTERS = [
  { key: '', label: 'ทั้งหมด' },
  { key: 'chaseOverdue', label: 'เกินกำหนด' },
  { key: 'confirmDeposit', label: 'รอรับมัดจำ' },
  { key: 'confirmFinalPayment', label: 'รอชำระส่วนที่เหลือ' },
  { key: 'confirmCloseReady', label: 'รอปิดงาน' },
  { key: 'recordInvoiceCommission', label: 'บันทึกใบกำกับ + ออกค่าคอม' },
];

const CTA_VARIANT = {
  confirmDeposit: 'success',
  confirmFinalPayment: 'success',
  chaseOverdue: 'danger',
  confirmCloseReady: 'primary',
  recordInvoiceCommission: 'text',
};

function dedupeById(...lists) {
  const seen = new Map();
  lists.flat().forEach((ticket) => {
    if (ticket && !seen.has(ticket.id)) seen.set(ticket.id, ticket);
  });
  return [...seen.values()];
}

function rowAmount(ticket) {
  const outstanding = Number(ticket.amountOutstanding ?? 0);
  return outstanding > 0 ? outstanding : Number(ticket.amountPayable ?? 0);
}

/**
 * งานการเงิน — account's deep workspace behind the Overview: the full
 * money-lifecycle worklist (deposit -> final payment -> close-ready ->
 * record-invoice+commission), filterable by stage. Same `nextAccountAction`
 * helper as AccountOverview.jsx — one source of truth for "what does account
 * do next on this deal", never a second copy.
 *
 * Same data-scope limitation as AccountOverview (see its own doc comment):
 * close-ready/CLOSED_PAID deals fall outside account's server-side ticket-
 * list scope today, so those two stages will read empty under the current
 * backend even though the filter/column wiring is correct end-to-end.
 */
export function AccountFinancePage({ showToast }) {
  const navigate = useNavigate();
  const isMobile = useIsMobile();
  const [searchParams, setSearchParams] = useSearchParams();
  const stageFilter = searchParams.get('stage') ?? '';

  const scopedQuery = useQuery({
    queryKey: queryKeys.ticketList(''),
    queryFn: () => api.tickets.list({}).then((r) => r.tickets ?? []),
  });
  const closedPaidQuery = useQuery({
    queryKey: queryKeys.ticketListBySalesStage('CLOSED_PAID'),
    queryFn: () => api.tickets.list({ salesStage: 'CLOSED_PAID' }).then((r) => r.tickets ?? []),
  });

  useEffect(() => {
    const error = scopedQuery.error || closedPaidQuery.error;
    if (error) showToast?.('error', error.message || 'โหลดข้อมูลไม่สำเร็จ');
  }, [scopedQuery.error, closedPaidQuery.error, showToast]);

  const loading = scopedQuery.isLoading || closedPaidQuery.isLoading;

  const worklist = useMemo(() => {
    const allTickets = dedupeById(scopedQuery.data ?? [], closedPaidQuery.data ?? []);
    return allTickets
      .map((ticket) => ({ ticket, action: nextAccountAction(ticket) }))
      .filter((row) => row.action != null)
      .sort((a, b) => {
        if (a.action.urgent !== b.action.urgent) return a.action.urgent ? -1 : 1;
        const dueA = a.ticket.dueDate ? new Date(a.ticket.dueDate).getTime() : Infinity;
        const dueB = b.ticket.dueDate ? new Date(b.ticket.dueDate).getTime() : Infinity;
        if (dueA !== dueB) return dueA - dueB;
        return rowAmount(b.ticket) - rowAmount(a.ticket);
      });
  }, [scopedQuery.data, closedPaidQuery.data]);

  const stageCounts = useMemo(() => {
    const counts = Object.fromEntries(STAGE_FILTERS.map((s) => [s.key, 0]));
    worklist.forEach(({ action }) => {
      counts[''] += 1;
      if (Object.hasOwn(counts, action.key)) counts[action.key] += 1;
    });
    return counts;
  }, [worklist]);

  const filtered = useMemo(
    () => (stageFilter ? worklist.filter((row) => row.action.key === stageFilter) : worklist),
    [worklist, stageFilter],
  );

  function setStageFilter(key) {
    setSearchParams((prev) => {
      const next = new URLSearchParams(prev);
      if (key) next.set('stage', key); else next.delete('stage');
      return next;
    });
  }

  return (
    <PageStack>
      <PageHeader
        title="งานการเงิน"
        subtitle="ไล่ตามวงจรเงินของทุกดีล — มัดจำ → ชำระส่วนที่เหลือ → ปิดงาน → บันทึกใบกำกับ/ออกค่าคอม"
      />

      <FilterBar>
        {STAGE_FILTERS.map((stage) => (
          <button
            key={stage.key || 'all'}
            type="button"
            onClick={() => setStageFilter(stage.key)}
            className={cn(
              'rounded-lg px-3.5 py-1.5 text-sm font-bold border border-border',
              stageFilter === stage.key ? 'bg-info-bg-alt text-info border-info-border' : 'bg-surface text-text-muted hover:text-text',
            )}
          >
            {stage.label}
            <span className="ml-1.5 tabular-nums opacity-70">{stageCounts[stage.key] ?? 0}</span>
          </button>
        ))}
      </FilterBar>

      {loading ? (
        <section className="panel" aria-busy="true" aria-label="กำลังโหลดงานการเงิน">
          <SkeletonText lines={6} />
        </section>
      ) : filtered.length === 0 ? (
        <EmptyState
          icon="badgeDollar"
          title="ไม่มีรายการในหมวดนี้"
          description={stageFilter ? 'ลองดูแท็บ "ทั้งหมด"' : 'ไม่มีดีลที่ต้องดำเนินการด้านการเงินตอนนี้'}
        />
      ) : isMobile ? (
        <div className="flex flex-col gap-2.5">
          {filtered.map(({ ticket, action }) => {
            const st = ticketStatusLabel(ticket.status);
            return (
              <button
                key={ticket.id}
                type="button"
                onClick={() => navigate(action.to)}
                className="flex w-full min-w-0 flex-col items-stretch gap-2 rounded-md border border-solid border-border bg-surface p-4 text-left cursor-pointer hover:bg-surface-hover"
              >
                <div className="flex min-w-0 items-start justify-between gap-3">
                  <code className="min-w-0 truncate text-xs text-text-muted">{ticket.code}</code>
                  <StatusBadge tone={st.tone}>{st.label}</StatusBadge>
                </div>
                <strong className="min-w-0 truncate text-md leading-snug font-extrabold text-text">
                  {ticket.customerName || ticket.title}
                </strong>
                <div className="flex items-center justify-between gap-3">
                  <span className="tabular-nums text-sm font-bold text-text">{formatMoney(rowAmount(ticket))}</span>
                  <Button type="button" variant={CTA_VARIANT[action.key] ?? 'secondary'} className="min-h-11 flex-1 max-w-[220px]" onClick={(e) => { e.stopPropagation(); navigate(action.to); }}>
                    {action.label}
                  </Button>
                </div>
              </button>
            );
          })}
        </div>
      ) : (
        <section className="panel !p-0 overflow-hidden">
          <div className="ticket-table table-head" role="row">
            <span role="columnheader">ดีล</span>
            <span role="columnheader">ขั้นตอนที่ต้องทำ</span>
            <span role="columnheader">ยอดเงิน</span>
            <span role="columnheader" />
          </div>
          {filtered.map(({ ticket, action }) => {
            const st = ticketStatusLabel(ticket.status);
            return (
              <div key={ticket.id} className="ticket-table data-row" role="row">
                <span role="cell" className="flex min-w-0 flex-col gap-0.5">
                  <strong className="block truncate text-text">{ticket.customerName || ticket.title}</strong>
                  <code className="block truncate text-2xs text-text-muted">{ticket.code}</code>
                </span>
                <span role="cell" className="flex flex-wrap items-center gap-2">
                  <StatusBadge tone={st.tone}>{st.label}</StatusBadge>
                  <span className={cn('text-xs font-bold', action.urgent ? 'text-danger' : 'text-text-muted')}>{action.label}</span>
                </span>
                <span role="cell" className="tabular-nums font-bold text-text">{formatMoney(rowAmount(ticket))}</span>
                <span role="cell">
                  <Button type="button" variant={CTA_VARIANT[action.key] ?? 'secondary'} onClick={() => navigate(action.to)}>
                    {action.label}
                    <Icon name="chevronRight" size={14} />
                  </Button>
                </span>
              </div>
            );
          })}
        </section>
      )}
    </PageStack>
  );
}
