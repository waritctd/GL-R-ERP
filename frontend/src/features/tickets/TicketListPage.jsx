import { useEffect, useMemo, useState } from 'react';
import { api, ROLE_PERMISSIONS } from '../../api/index.js';
import { EmptyState } from '../../components/common/EmptyState.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { PageHeader } from '../../components/common/PageHeader.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { formatThaiDate, ticketStatusLabel } from '../../utils/format.js';
import { TicketCreateModal } from './TicketCreateModal.jsx';

const PAGE_SIZE = 10;

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

const STATUS_TABS = [
  { value: '', label: 'ทั้งหมด',              tone: 'primary' },
  { value: 'submitted',        label: 'รอรับเรื่อง',          tone: 'warning' },
  { value: 'in_review',        label: 'กำลังดำเนินการ',       tone: 'info'    },
  { value: 'price_proposed',   label: 'รอการอนุมัติ',         tone: 'warning' },
  { value: 'approved',         label: 'อนุมัติแล้ว',         tone: 'success' },
  { value: 'document_issued',  label: 'ออกใบแจ้งยอดแล้ว',   tone: 'success' },
  { value: 'closed',           label: 'ปิดแล้ว',             tone: 'neutral' },
];

const STATUS_ORDER = [
  'draft', 'submitted', 'in_review', 'price_proposed',
  'approved', 'document_issued', 'closed', 'cancelled', 'rejected',
];

function SortHeader({ label, sortKey, active, dir, onSort }) {
  return (
    <button
      type="button"
      onClick={() => onSort(sortKey)}
      style={{
        display: 'inline-flex', alignItems: 'center', gap: 4,
        background: 'none', border: 'none', padding: 0, cursor: 'pointer',
        fontWeight: 700, fontSize: 12, color: active ? '#1e40af' : 'inherit',
        textTransform: 'uppercase', letterSpacing: '0.05em',
      }}
    >
      {label}
      <Icon
        name={active ? (dir === 'asc' ? 'chevronUp' : 'chevronDown') : 'chevronDown'}
        size={13}
        style={{ opacity: active ? 1 : 0.3 }}
      />
    </button>
  );
}

export function TicketListPage({ user, onOpenTicket, showToast }) {
  const [tickets, setTickets] = useState([]);
  const [loading, setLoading] = useState(true);
  const [statusFilter, setStatusFilter] = useState('');
  const [creating, setCreating] = useState(false);
  const [sortKey, setSortKey] = useState('date');
  const [sortDir, setSortDir] = useState('desc');
  const [page, setPage] = useState(1);

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
    setPage(1);
  }, [statusFilter]);

  function handleSort(key) {
    if (key === sortKey) {
      setSortDir((d) => (d === 'asc' ? 'desc' : 'asc'));
    } else {
      setSortKey(key);
      setSortDir('desc');
    }
    setPage(1);
  }

  async function handleCreate(payload) {
    await api.tickets.create(payload);
    setCreating(false);
    showToast('success', 'สร้างใบขอราคาเรียบร้อย');
    loadTickets(statusFilter);
  }

  const sorted = useMemo(() => {
    const copy = [...tickets];
    const dir = sortDir === 'asc' ? 1 : -1;
    if (sortKey === 'date') {
      copy.sort((a, b) => dir * (new Date(a.createdAt) - new Date(b.createdAt)));
    } else {
      copy.sort((a, b) => {
        const ai = STATUS_ORDER.indexOf(a.status);
        const bi = STATUS_ORDER.indexOf(b.status);
        return dir * (ai - bi);
      });
    }
    return copy;
  }, [tickets, sortKey, sortDir]);

  const totalPages = Math.max(1, Math.ceil(sorted.length / PAGE_SIZE));
  const safePage = Math.min(page, totalPages);
  const pageRows = sorted.slice((safePage - 1) * PAGE_SIZE, safePage * PAGE_SIZE);
  const from = sorted.length === 0 ? 0 : (safePage - 1) * PAGE_SIZE + 1;
  const to = Math.min(safePage * PAGE_SIZE, sorted.length);

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

      <section className="table-panel">
        <div className="ticket-table table-head">
          <span>เลขที่</span>
          <span>บริษัท / โครงการ</span>
          <span>ผู้ดูแล (Sales)</span>
          <SortHeader label="สถานะ" sortKey="status" active={sortKey === 'status'} dir={sortDir} onSort={handleSort} />
          <SortHeader label="วันที่สร้าง" sortKey="date" active={sortKey === 'date'} dir={sortDir} onSort={handleSort} />
        </div>

        {loading ? (
          <div className="table-row" style={{ justifyContent: 'center', color: '#94a3b8' }}>กำลังโหลด...</div>
        ) : sorted.length === 0 ? (
          <EmptyState icon="fileText" title="ไม่มีใบขอราคา" description="ยังไม่มีรายการในสถานะที่เลือก" />
        ) : pageRows.map((ticket) => {
          const status = ticketStatusLabel(ticket.status);
          return (
            <button
              key={ticket.id}
              type="button"
              className="ticket-table table-row"
              onClick={() => onOpenTicket(ticket.id)}
              style={{ borderLeft: `4px solid ${TONE_BORDER[status.tone] ?? '#94a3b8'}` }}
            >
              <code style={{ fontSize: 12 }}>{ticket.code}</code>
              <span><strong>{ticket.customerName || ticket.title}</strong></span>
              <span>{ticket.createdByName}</span>
              <StatusBadge tone={status.tone}>{status.label}</StatusBadge>
              <span>{formatThaiDate(ticket.createdAt)}</span>
            </button>
          );
        })}

        {!loading && sorted.length > 0 && (
          <footer className="pagination">
            <span style={{ fontSize: 13 }}>แสดง {from}–{to} จาก {sorted.length} รายการ</span>
            <div>
              <button
                type="button"
                className="icon-button"
                onClick={() => setPage((p) => Math.max(1, p - 1))}
                disabled={safePage === 1}
                title="หน้าก่อนหน้า"
                aria-label="หน้าก่อนหน้า"
              >
                <Icon name="chevronLeft" size={16} />
              </button>
              <span style={{ fontSize: 13, minWidth: 72, textAlign: 'center' }}>
                หน้า {safePage} / {totalPages}
              </span>
              <button
                type="button"
                className="icon-button"
                onClick={() => setPage((p) => Math.min(totalPages, p + 1))}
                disabled={safePage === totalPages}
                title="หน้าถัดไป"
                aria-label="หน้าถัดไป"
              >
                <Icon name="chevronRight" size={16} />
              </button>
            </div>
          </footer>
        )}
      </section>

      {creating ? (
        <TicketCreateModal onClose={() => setCreating(false)} onSubmit={handleCreate} />
      ) : null}
    </div>
  );
}
