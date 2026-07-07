import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { api, ROLE_PERMISSIONS } from '../../api/index.js';
import { DataTable } from '../../components/common/DataTable.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { PageHeader } from '../../components/common/PageHeader.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { formatThaiDate, ticketStatusLabel } from '../../utils/format.js';
import { TicketCreateModal } from './TicketCreateModal.jsx';

const TONE_BORDER = {
  neutral: '#94a3b8',
  warning: '#f59e0b',
  info:    '#3b82f6',
  success: '#22c55e',
  danger:  '#ef4444',
};

const TONE_ACTIVE = {
  primary: { bg: '#1e40af', color: '#fff',    border: '#1e40af' },
  neutral: { bg: '#f1f5f9', color: '#475569', border: '#94a3b8' },
  warning: { bg: '#fef3c7', color: '#b45309', border: '#f59e0b' },
  info:    { bg: '#dbeafe', color: '#1d4ed8', border: '#3b82f6' },
  success: { bg: '#dcfce7', color: '#15803d', border: '#22c55e' },
  danger:  { bg: '#fee2e2', color: '#b91c1c', border: '#ef4444' },
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
        rowStyle={(ticket) => ({
          borderLeft: `4px solid ${TONE_BORDER[ticketStatusLabel(ticket.status).tone] ?? '#94a3b8'}`,
        })}
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
    render: (ticket) => <code style={{ fontSize: 12 }}>{ticket.code}</code>,
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
