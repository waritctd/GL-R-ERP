import { useEffect, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { api, ROLE_PERMISSIONS } from '../../api/index.js';
import { DataTable } from '../../components/common/DataTable.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { PageHeader } from '../../components/common/PageHeader.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { formatThaiDate, ticketStatusLabel } from '../../utils/format.js';
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

const STATUS_TABS = [
  { value: '',                 label: 'ทั้งหมด',              tone: 'primary' },
  { value: 'submitted',        label: 'รอรับเรื่อง',          tone: 'warning' },
  { value: 'in_review',        label: 'กำลังดำเนินการ',       tone: 'info'    },
  { value: 'price_proposed',   label: 'รอการอนุมัติ',         tone: 'warning' },
  { value: 'approved',         label: 'อนุมัติแล้ว',          tone: 'success' },
  { value: 'quotation_issued', label: 'ออกใบเสนอราคาแล้ว',   tone: 'success' },
  { value: 'document_issued',  label: 'ออกใบแจ้งยอดแล้ว',    tone: 'success' },
  { value: 'closed',           label: 'ปิดแล้ว',              tone: 'neutral' },
  { value: 'cancelled',        label: 'ยกเลิกแล้ว',           tone: 'danger'  },
];

const STATUS_ORDER = [
  'draft', 'submitted', 'in_review', 'price_proposed',
  'approved', 'quotation_issued', 'document_issued', 'closed', 'cancelled', 'rejected',
];


/**
 * Mobile record card for a price request. The desktop grid crushes five columns
 * into ~34–90px each at 375px, which truncates every field to a stub ("PR-…",
 * "คุณส…") and clips the status badge. This shows only what a user needs to
 * identify the record and decide the next step: code, customer, status, owner,
 * and date. Full detail stays one tap away on the detail page.
 */
function TicketCard({ ticket }) {
  const status = ticketStatusLabel(ticket.status);
  const subLabel = ticket.status === 'quotation_issued'
    ? TRACK_LABEL[ticket.paymentStatus] ?? TRACK_LABEL[ticket.fulfillmentStatus] ?? null
    : null;

  return (
    <>
      <div className="flex min-w-0 items-start justify-between gap-3">
        <code className="min-w-0 truncate text-xs text-text-muted">{ticket.code}</code>
        <span className="flex shrink-0 flex-col items-end gap-1">
          <StatusBadge tone={status.tone}>{status.label}</StatusBadge>
          {subLabel ? <span className="text-2xs text-text-muted">{subLabel}</span> : null}
        </span>
      </div>

      <strong className="min-w-0 text-md leading-snug font-extrabold text-text">
        {ticket.customerName || ticket.title}
      </strong>

      <span className="min-w-0 truncate text-xs text-text-muted">
        {[ticket.createdByName, formatThaiDate(ticket.createdAt)].filter(Boolean).join(' · ')}
      </span>
    </>
  );
}

export function TicketListPage({ user, showToast }) {
  const navigate = useNavigate();
  // Status filter + search text live in the URL (not local state) so that:
  // (a) a dashboard queue card can land here pre-filtered via ?status=..., and
  // (b) list → detail → back preserves whatever filter/search was active,
  // instead of resetting to "ทั้งหมด" every time (previously statusFilter was
  // plain useState, so onBack navigating to a bare '/tickets' lost it).
  const [searchParams, setSearchParams] = useSearchParams();
  const statusFilter = searchParams.get('status') ?? '';
  const searchText = searchParams.get('q') ?? '';
  const [tickets, setTickets] = useState([]);
  const [loading, setLoading] = useState(true);
  const [creating, setCreating] = useState(false);

  const canCreate = ROLE_PERMISSIONS.canCreateTickets.includes(user.role);

  function updateParam(key, value) {
    setSearchParams((prev) => {
      const next = new URLSearchParams(prev);
      if (value) next.set(key, value); else next.delete(key);
      return next;
    }, { replace: true });
  }

  function setStatusFilter(value) {
    updateParam('status', value);
  }

  function setSearchText(value) {
    updateParam('q', value);
  }

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
    // eslint-disable-next-line react-hooks/exhaustive-deps -- loadTickets closes over statusFilter, which is already the effect's dependency
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
        mobileCard={(ticket) => <TicketCard ticket={ticket} />}
        searchable
        searchValue={searchText}
        onSearchChange={setSearchText}
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

const TRACK_LABEL = {
  CUSTOMER_CONFIRMED:     'P: ลูกค้ายืนยันแล้ว',
  DEPOSIT_NOTICE_ISSUED:  'P: ออกใบแจ้งมัดจำแล้ว',
  DEPOSIT_PAID:           'P: รับมัดจำแล้ว',
  AWAITING_FINAL_PAYMENT: 'P: รอชำระส่วนที่เหลือ',
  FULLY_PAID:             'P: ชำระครบแล้ว',
  IR_ISSUED:              'F: ออก IR แล้ว',
  IR_SENT:                'F: ส่ง IR แล้ว',
  SHIPPING:               'F: กำลังขนส่ง',
  GOODS_RECEIVED:         'F: รับสินค้าแล้ว',
};

const TICKET_COLUMNS = [
  {
    key: 'customer',
    header: 'บริษัท / โครงการ',
    searchAccessor: (ticket) => [ticket.code, ticket.customerName, ticket.title].filter(Boolean).join(' '),
    // Primary entity (company/project) leads, bold and full-size; the request
    // code is secondary metadata underneath in muted mono — matches the
    // mobile card's hierarchy (code above title there; company is still the
    // thing a desktop user scans for first in a wide row).
    render: (ticket) => (
      <span className="flex min-w-0 flex-col gap-0.5">
        <strong className="block truncate text-text">{ticket.customerName || ticket.title}</strong>
        <code className="block truncate text-2xs text-text-muted">{ticket.code}</code>
      </span>
    ),
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
      const subLabel = ticket.status === 'quotation_issued'
        ? TRACK_LABEL[ticket.paymentStatus] ?? TRACK_LABEL[ticket.fulfillmentStatus] ?? null
        : null;
      return (
        <span style={{ display: 'flex', flexDirection: 'column', gap: 3 }}>
          <StatusBadge tone={status.tone}>{status.label}</StatusBadge>
          {subLabel && (
            <span style={{ fontSize: 11, color: '#64748b', paddingLeft: 2 }}>{subLabel}</span>
          )}
        </span>
      );
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
