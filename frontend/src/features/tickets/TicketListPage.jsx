import { useEffect, useState } from 'react';
import { api, ROLE_PERMISSIONS } from '../../api/index.js';
import { EmptyState } from '../../components/common/EmptyState.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { PageHeader } from '../../components/common/PageHeader.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { formatThaiDate, ticketPriorityLabel, ticketStatusLabel } from '../../utils/format.js';
import { TicketCreateModal } from './TicketCreateModal.jsx';

const STATUS_TABS = [
  { value: '', label: 'ทั้งหมด' },
  { value: 'draft', label: 'แบบร่าง' },
  { value: 'submitted', label: 'รอรับเรื่อง' },
  { value: 'in_review', label: 'กำลังดำเนินการ' },
  { value: 'price_proposed', label: 'รอการอนุมัติ' },
  { value: 'approved', label: 'อนุมัติแล้ว' },
  { value: 'quotation_issued', label: 'ออกใบเสนอราคาแล้ว' },
  { value: 'closed', label: 'ปิดแล้ว' },
];

export function TicketListPage({ user, onOpenTicket, showToast }) {
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

  useEffect(() => { loadTickets(statusFilter); }, [statusFilter]);

  async function handleCreate(payload) {
    await api.tickets.create(payload);
    setCreating(false);
    showToast('success', 'สร้างใบขอราคาเรียบร้อย');
    loadTickets(statusFilter);
  }

  const rows = tickets;

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
        {STATUS_TABS.map((tab) => (
          <button
            key={tab.value}
            type="button"
            className={`status-tab${statusFilter === tab.value ? ' active' : ''}`}
            onClick={() => setStatusFilter(tab.value)}
          >
            {tab.label}
          </button>
        ))}
        <button type="button" className="icon-button" onClick={() => loadTickets(statusFilter)} title="รีเฟรช">
          <Icon name="refresh" />
        </button>
      </div>

      <section className="table-panel">
        <div className="ticket-table table-head">
          <span>เลขที่</span>
          <span>หัวข้อ / ลูกค้า</span>
          <span>ผู้ดูแล</span>
          <span>ความสำคัญ</span>
          <span>สถานะ</span>
          <span>สร้างโดย</span>
          <span>วันที่สร้าง</span>
        </div>

        {loading ? (
          <div className="table-row" style={{ justifyContent: 'center', color: '#94a3b8' }}>กำลังโหลด...</div>
        ) : rows.length === 0 ? (
          <EmptyState icon="fileText" title="ไม่มีใบขอราคา" description="ยังไม่มีรายการในสถานะที่เลือก" />
        ) : rows.map((ticket) => {
          const status = ticketStatusLabel(ticket.status);
          const priority = ticketPriorityLabel(ticket.priority);
          return (
            <button
              key={ticket.id}
              type="button"
              className="ticket-table table-row"
              onClick={() => onOpenTicket(ticket.id)}
            >
              <code style={{ fontSize: 12 }}>{ticket.code}</code>
              <span>
                <strong>{ticket.title}</strong>
                <small>{ticket.customerName || '-'}</small>
              </span>
              <span>{ticket.assignedToName || <span style={{ color: '#94a3b8' }}>ยังไม่ได้รับมอบ</span>}</span>
              <StatusBadge tone={priority.tone}>{priority.label}</StatusBadge>
              <StatusBadge tone={status.tone}>{status.label}</StatusBadge>
              <span>{ticket.createdByName}</span>
              <span>{formatThaiDate(ticket.createdAt)}</span>
            </button>
          );
        })}

        {!loading && rows.length > 0 && (
          <footer className="pagination">
            <span>แสดง {rows.length} รายการ</span>
          </footer>
        )}
      </section>

      {creating ? (
        <TicketCreateModal onClose={() => setCreating(false)} onSubmit={handleCreate} />
      ) : null}
    </div>
  );
}
