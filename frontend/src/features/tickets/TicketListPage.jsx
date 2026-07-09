import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { api, ROLE_PERMISSIONS } from '../../api/index.js';
import { DataTable } from '../../components/common/DataTable.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { PageHeader } from '../../components/common/PageHeader.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { cn } from '../../utils/cn.js';
import { formatThaiDate, ticketStatusLabel } from '../../utils/format.js';
import { TicketCreateModal } from './TicketCreateModal.jsx';

// NOTE: warning/info/danger border tones (#f59e0b, #3b82f6, #ef4444) are not
// covered by the project's design-token mapping (closest tokens are
// warning/info/danger at different shades), so they are deliberately left as
// literals here per the migration's rule 4. See migration report.
const TONE_BORDER = {
  neutral: 'border-l-text-faint',
  warning: 'border-l-[#f59e0b]',
  info:    'border-l-[#3b82f6]',
  success: 'border-l-success-soft',
  danger:  'border-l-[#ef4444]',
};

const TONE_ACTIVE = {
  primary: 'bg-info-dot text-surface border-info-dot font-bold',
  neutral: 'bg-surface-subtle text-icon-muted border-text-faint font-bold',
  warning: 'bg-warning-bg text-warning border-[#f59e0b] font-bold',
  info:    'bg-info-bg text-info border-[#3b82f6] font-bold',
  success: 'bg-success-bg text-success-dark border-success-soft font-bold',
  danger:  'bg-danger-bg text-danger-dark border-[#ef4444] font-bold',
};

const TONE_DOT_BG = {
  neutral: 'bg-text-faint',
  warning: 'bg-[#f59e0b]',
  info:    'bg-[#3b82f6]',
  success: 'bg-success-soft',
  danger:  'bg-[#ef4444]',
};

const STATUS_ORDER = [
  'draft', 'submitted', 'in_review', 'price_proposed',
  'approved', 'rejected', 'quotation_issued', 'document_issued',
  'closed', 'cancelled',
];

const STATUS_TABS = [
  { value: '', label: 'ทั้งหมด',              tone: 'primary' },
  { value: 'submitted',        label: 'รอรับเรื่อง',          tone: 'warning' },
  { value: 'in_review',        label: 'กำลังดำเนินการ',       tone: 'info'    },
  { value: 'price_proposed',   label: 'รอการอนุมัติ',         tone: 'warning' },
  { value: 'approved',         label: 'อนุมัติแล้ว',         tone: 'success' },
  { value: 'document_issued',  label: 'ออกใบแจ้งยอดแล้ว',   tone: 'success' },
  { value: 'closed',           label: 'ปิดแล้ว',             tone: 'neutral' },
];

export function TicketListPage({ user, showToast }) {
  const navigate = useNavigate();
  const [tickets, setTickets] = useState([]);
  const [loading, setLoading] = useState(true);
  const [statusFilter, setStatusFilter] = useState('');
  const [creating, setCreating] = useState(false);

  const canCreate = ROLE_PERMISSIONS.canCreateTickets.includes(user.role);

  async function loadTickets(status = statusFilter) {
    setLoading(true);
    try {
      const params = status ? { status } : {};
      const response = await api.tickets.list(params);
      setTickets(response.tickets);
    } catch (error) {
      showToast('error', error.message || 'โหลดข้อมูลไม่สำเร็จ');
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    loadTickets(statusFilter);
  }, [statusFilter]);

  async function handleCreate(payload) {
    await api.tickets.create(payload);
    setCreating(false);
    showToast('success', 'สร้างใบขอราคาเรียบร้อย');
    loadTickets(statusFilter);
  }

  return (
    <div className="page-stack">
      <PageHeader
        title="ใบขอราคา"
        subtitle="Price Requests"
        actions={canCreate ? (
          <button type="button" className="primary-button" onClick={() => setCreating(true)}>
            <Icon name="plus" />
            สร้างใบขอราคาใหม่
          </button>
        ) : null}
      />

      <div className="status-tabs">
        {STATUS_TABS.map((tab) => {
          const isActive = statusFilter === tab.value;
          const dot = tab.tone !== 'primary'
            ? <span className={cn('inline-block w-2 h-2 rounded-full shrink-0', TONE_DOT_BG[tab.tone])} />
            : null;
          return (
            <button
              key={tab.value}
              type="button"
              className={cn('status-tab', isActive && ['active', TONE_ACTIVE[tab.tone]])}
              onClick={() => setStatusFilter(tab.value)}
            >
              {dot}
              {tab.label}
            </button>
          );
        })}
        <button type="button" className="icon-button" onClick={() => loadTickets(statusFilter)} title="รีเฟรช" aria-label="รีเฟรช">
          <Icon name="refresh" />
        </button>
      </div>

      <DataTable
        columns={TICKET_COLUMNS}
        rows={tickets}
        getRowKey={(ticket) => ticket.id}
        gridClassName="ticket-table"
        onRowClick={(ticket) => navigate(`/tickets/${ticket.id}`)}
        rowClassName={(ticket) => cn('border-l-4', TONE_BORDER[ticketStatusLabel(ticket.status).tone] ?? 'border-l-text-faint')}
        searchable
        searchPlaceholder="ค้นหาเลขที่ / บริษัท / ผู้ดูแล"
        initialSort={{ key: 'date', dir: 'desc' }}
        loading={loading}
        emptyState={{
          icon: 'fileText',
          title: 'ไม่มีใบขอราคา',
          description: 'ยังไม่มีรายการในสถานะที่เลือก',
        }}
      />

      {creating ? (
        <TicketCreateModal onClose={() => setCreating(false)} onSubmit={handleCreate} />
      ) : null}
    </div>
  );
}

const TICKET_COLUMNS = [
  {
    key: 'code',
    header: 'เลขที่',
    searchAccessor: (ticket) => ticket.code || '',
    render: (ticket) => <code className="text-xs">{ticket.code}</code>,
  },
  {
    key: 'customer',
    header: 'บริษัท / โครงการ',
    searchAccessor: (ticket) => ticket.customerName || ticket.title || '',
    render: (ticket) => <span><strong>{ticket.customerName || ticket.title}</strong></span>,
  },
  {
    key: 'createdByName',
    header: 'ผู้ดูแล (Sales)',
    searchAccessor: (ticket) => ticket.createdByName || '',
    render: (ticket) => <span>{ticket.createdByName}</span>,
  },
  {
    key: 'status',
    header: 'สถานะ',
    sortable: true,
    sortAccessor: (ticket) => STATUS_ORDER.indexOf(ticket.status),
    render: (ticket) => {
      const status = ticketStatusLabel(ticket.status);
      return <StatusBadge tone={status.tone}>{status.label}</StatusBadge>;
    },
  },
  {
    key: 'date',
    header: 'วันที่สร้าง',
    sortable: true,
    sortAccessor: (ticket) => new Date(ticket.createdAt),
    render: (ticket) => <span>{formatThaiDate(ticket.createdAt)}</span>,
  },
];
