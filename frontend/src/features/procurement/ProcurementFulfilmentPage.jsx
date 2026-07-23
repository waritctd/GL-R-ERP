import { useMemo } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { Button } from '../../components/common/Button.jsx';
import { EmptyState } from '../../components/common/EmptyState.jsx';
import { Panel } from '../../components/common/Layout.jsx';
import { PageHeader } from '../../components/common/PageHeader.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { formatThaiDate, fulfilmentStatusLabel } from '../../utils/format.js';
import { nextFulfilmentActionCode, IMPORT_ACTION_LABELS } from '../tickets/importActions.js';
import { ProcurementListPage } from './ProcurementListPage.jsx';

// Same stage order the ImportOverview conveyor pulse uses, so the two
// surfaces present a deal's fulfilment stage identically.
const STAGE_ORDER = ['issueImportRequest', 'markIrSent', 'markShipping', 'markGoodsReceived', 'recordDelivery'];

function FulfilmentRow({ ticket, actionCode, navigate }) {
  const status = ticket.fulfillmentStatus ? fulfilmentStatusLabel(ticket.fulfillmentStatus) : null;
  return (
    <div className="flex flex-wrap items-center justify-between gap-3 border-t border-border-subtle px-4 py-3 first:border-t-0 max-[720px]:flex-col max-[720px]:items-stretch">
      <div className="min-w-0 flex-1">
        <div className="flex flex-wrap items-center gap-2">
          <strong className="min-w-0 truncate text-sm font-extrabold text-text">
            {ticket.customerName || ticket.title}
          </strong>
          {ticket.overdue ? <StatusBadge tone="danger">เกินกำหนด</StatusBadge> : null}
        </div>
        <span className="block truncate text-2xs text-text-muted">
          <code>{ticket.code}</code>
          {status ? ` · ${status.label}` : ''}
          {ticket.dueDate ? ` · กำหนด ${formatThaiDate(ticket.dueDate)}` : ''}
        </span>
      </div>
      <Button type="button" size="sm" onClick={() => navigate(`/tickets/${ticket.id}`)} className="shrink-0">
        {IMPORT_ACTION_LABELS[actionCode]}
      </Button>
    </div>
  );
}

/**
 * "จัดซื้อ & นำเข้า" — role-scoped views (Import build, docs/role-scoped-views.md).
 * Combines two surfaces Import used to reach separately: its own deal-level
 * fulfilment worklist (section 1, built from the SAME nextFulfilmentActionCode
 * helper DealFulfilmentPanel/ImportOverview use — never a second source of
 * truth for "what stage is this deal at") and the raw per-factory purchase
 * order list (section 2, ProcurementListPage reused wholesale, unchanged).
 * /factory-purchase-orders/:id stays registered in App.jsx for direct PO
 * detail deep-links from section 2's rows.
 */
export function ProcurementFulfilmentPage({ user, showToast }) {
  const navigate = useNavigate();

  const ticketsQuery = useQuery({
    queryKey: queryKeys.ticketList(''),
    queryFn: () => api.tickets.list({}).then((r) => r?.tickets ?? []),
  });
  const tickets = useMemo(() => ticketsQuery.data ?? [], [ticketsQuery.data]);

  const fulfilmentRows = useMemo(() => {
    const rows = tickets
      .map((ticket) => ({ ticket, actionCode: nextFulfilmentActionCode(ticket) }))
      .filter((row) => row.actionCode);
    return rows.sort((a, b) => {
      if (Boolean(a.ticket.overdue) !== Boolean(b.ticket.overdue)) return a.ticket.overdue ? -1 : 1;
      const stageDiff = STAGE_ORDER.indexOf(a.actionCode) - STAGE_ORDER.indexOf(b.actionCode);
      if (stageDiff !== 0) return stageDiff;
      return new Date(a.ticket.updatedAt || 0) - new Date(b.ticket.updatedAt || 0);
    });
  }, [tickets]);

  return (
    <div className="page-stack">
      <PageHeader
        title="จัดซื้อ & นำเข้า"
        subtitle="งานส่งมอบ/รับเข้าคลังของดีลที่รับผิดชอบ + ใบสั่งซื้อโรงงานทั้งหมด"
      />

      <Panel title="งานรับเข้าคลัง / ส่งมอบ" className="!p-0 overflow-hidden">
        {ticketsQuery.isLoading ? (
          <p className="px-4 py-6 text-sm text-text-muted">กำลังโหลด...</p>
        ) : fulfilmentRows.length === 0 ? (
          <EmptyState
            icon="check"
            title="ไม่มีงานส่งมอบที่ต้องดำเนินการตอนนี้"
            description="งานรับเข้าคลังและส่งมอบทั้งหมดดำเนินการครบแล้ว"
          />
        ) : (
          <div className="flex flex-col">
            {fulfilmentRows.map(({ ticket, actionCode }) => (
              <FulfilmentRow key={ticket.id} ticket={ticket} actionCode={actionCode} navigate={navigate} />
            ))}
          </div>
        )}
      </Panel>

      <ProcurementListPage user={user} showToast={showToast} />
    </div>
  );
}
