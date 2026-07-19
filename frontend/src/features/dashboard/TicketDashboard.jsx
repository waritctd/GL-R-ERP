import { useEffect, useMemo } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { PageHeader } from '../../components/common/PageHeader.jsx';
import { SalesTabs } from '../sales/SalesTabs.jsx';
import { Skeleton, SkeletonCard } from '../../components/common/Skeleton.jsx';
import { StatCard } from '../../components/common/StatCard.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { useIsMobile } from '../../hooks/useIsMobile.js';
import { formatThaiDate, ticketStatusLabel } from '../../utils/format.js';
import { hasPermission } from '../../app/permissions.js';
import { ActionQueue } from './ActionQueue.jsx';

function GreetingSubtitle({ role }) {
  if (role === 'sales') return 'ภาพรวมใบขอราคาของคุณ';
  if (role === 'import') return 'ภาพรวมใบขอราคาทั้งหมด — ฝ่ายนำเข้า';
  if (role === 'ceo') return 'ภาพรวมใบขอราคาทั้งหมด — ผู้บริหาร';
  return 'ภาพรวมใบขอราคา';
}

const SHOW_SALES_ROLES = ['import', 'ceo'];

/**
 * Mobile record card for the "recent tickets" strip. The desktop 5-column
 * grid (เลขที่ / บริษัท / ผู้ขาย / สถานะ / วันที่) crushes to unreadable stubs
 * below 720px, same failure mode as the full ticket list — reuses the same
 * minimal fields: code, customer, status badge, owner · date.
 */
function RecentTicketCard({ ticket, showOwner }) {
  const status = ticketStatusLabel(ticket.status);
  return (
    <>
      <div className="flex min-w-0 items-start justify-between gap-3">
        <code className="min-w-0 truncate text-xs text-text-muted">{ticket.code}</code>
        <StatusBadge tone={status.tone}>{status.label}</StatusBadge>
      </div>

      <strong className="min-w-0 truncate text-md leading-snug font-extrabold text-text">
        {ticket.customerName || ticket.title}
      </strong>

      <span className="min-w-0 truncate text-xs text-text-muted">
        {[showOwner ? ticket.createdByName : null, formatThaiDate(ticket.createdAt)].filter(Boolean).join(' · ')}
      </span>
    </>
  );
}

export function TicketDashboard({ user, employee, showToast }) {
  const navigate = useNavigate();
  const isMobile = useIsMobile();

  const summaryQuery = useQuery({
    queryKey: queryKeys.dashboardSummary(),
    queryFn: () => api.dashboard.summary(),
  });
  // Shares the SAME cache entry as TicketListPage's "ทั้งหมด" tab
  // (queryKeys.ticketList('')) — a mutation invalidating ['tickets','list']
  // anywhere in the app refreshes this recent-tickets strip too, instead of
  // the dashboard only ever refreshing on mount like before.
  const recentQuery = useQuery({
    queryKey: queryKeys.ticketList(''),
    queryFn: () => api.tickets.list({}),
  });
  // Commit 6: summary.submitted is permanently 0 for new deals now that ticket
  // creation no longer auto-submits (TicketService.create/submit, commit 5) —
  // "รอรับเรื่อง (Import)" now counts the PricingRequest queue instead.
  const pricingQueueQuery = useQuery({
    queryKey: queryKeys.pricingRequestQueue({ status: 'SUBMITTED' }),
    queryFn: () => api.pricingRequests.queue({ status: 'SUBMITTED', activeOnly: true }).then((r) => r.items ?? []),
  });

  const summaryPayload = summaryQuery.data;
  const nextSummary = useMemo(
    () => summaryPayload?.summary ?? summaryPayload ?? null,
    [summaryPayload],
  );
  const summary = nextSummary?.tickets ?? nextSummary;
  // Same api.dashboard.summary() call as `summary` (no extra request) — just
  // keeping the notifications slice it already returns instead of discarding it,
  // so the needs-action queue below can show unread count honestly.
  const notifications = nextSummary?.notifications ?? null;

  const ticketsRes = recentQuery.data;
  const recent = useMemo(() => {
    const tickets = Array.isArray(ticketsRes) ? ticketsRes : (ticketsRes?.tickets ?? []);
    return tickets.slice(0, 6);
  }, [ticketsRes]);

  const loading = summaryQuery.isLoading || recentQuery.isLoading;

  useEffect(() => {
    const error = summaryQuery.error || recentQuery.error;
    if (error) showToast('error', error.message || 'โหลดข้อมูลไม่สำเร็จ');
  }, [summaryQuery.error, recentQuery.error, showToast]);

  const greeting = `สวัสดี, ${employee?.nickName || employee?.nameTh || user.name}`;

  // Only the ticket statuses THIS role can act on (permissions.js is the
  // source of truth): import picks up submitted tickets, ceo approves
  // prices, sales generates the quotation once approved. A count the
  // viewer cannot act on (e.g. "submitted" for a sales rep, who is waiting
  // on Import) is intentionally left non-clickable, not omitted — showing it
  // as a clickable action would misrepresent whose job it is.
  // No canPickupTickets here any more: the "รอรับเรื่อง" surfaces below now point
  // at the PricingRequest queue, which gates on canViewPricingRequestQueue.
  const canApprove = hasPermission(user.role, 'canApproveReject');
  const canQuote = hasPermission(user.role, 'canGenerateQuotation');
  const canViewPricingQueue = hasPermission(user.role, 'canViewPricingRequestQueue');
  // Each card navigates straight to its matching TicketListPage status tab
  // (see STATUS_TABS in TicketListPage.jsx) instead of the unfiltered list —
  // previously every card landed on "ทั้งหมด" and made the user re-apply the
  // exact filter the card already named.
  const queueItems = summary ? [
    { key: 'submitted', label: 'รอรับเรื่อง (Import)', value: pricingQueueQuery.data?.length ?? 0, to: canViewPricingQueue ? () => navigate('/pricing-requests') : undefined },
    { key: 'priceProposed', label: 'รอการอนุมัติราคา (CEO)', value: summary.priceProposed, to: canApprove ? () => navigate('/tickets') : undefined },
    { key: 'approved', label: 'อนุมัติแล้ว รอออกใบเสนอราคา', value: summary.approved, to: canQuote ? () => navigate('/tickets') : undefined },
    { key: 'notifications', label: 'แจ้งเตือนยังไม่อ่าน', value: notifications?.unread ?? 0 },
  ] : [];

  return (
    <div className="page-stack">
      <SalesTabs role={user.role} />
      <PageHeader title={greeting} subtitle={<GreetingSubtitle role={user.role} />} />

      {loading ? (
        <div aria-busy="true" aria-label="กำลังโหลดข้อมูลแดชบอร์ด">
          <div className="stat-grid">
            <SkeletonCard lines={1} />
            <SkeletonCard lines={1} />
            <SkeletonCard lines={1} />
            <SkeletonCard lines={1} />
          </div>
          <div className="stat-grid" style={{ marginTop: 14 }}>
            <SkeletonCard lines={1} />
            <SkeletonCard lines={1} />
            <SkeletonCard lines={1} />
            <SkeletonCard lines={1} />
          </div>
          <section className="panel" style={{ padding: 0, overflow: 'hidden', marginTop: 14 }}>
            <div className="panel-header" style={{ padding: '14px 18px' }}>
              <Skeleton width="30%" height={16} />
            </div>
            <div style={{ padding: '12px 18px', display: 'flex', flexDirection: 'column', gap: 10 }}>
              {[0, 1, 2, 3].map((i) => (
                <div key={i} style={{ display: 'flex', gap: 12, alignItems: 'center' }}>
                  <Skeleton width={60} height={14} />
                  <Skeleton width="40%" height={14} />
                  <Skeleton width={70} height={18} radius="var(--radius-pill)" />
                  <Skeleton width={80} height={14} />
                </div>
              ))}
            </div>
          </section>
        </div>
      ) : summary && (
        <>
          <ActionQueue items={queueItems} />

          <div className="stat-grid">
            <StatCard icon="fileText"  label="เปิดอยู่ทั้งหมด"   value={summary.totalOpen}       helper="Total open"         tone="indigo" onClick={() => navigate('/tickets')} />
            {/* Same reason as the ActionQueue entry above: summary.submitted is
                permanently 0 for new deals since ticket creation stopped
                auto-submitting, so this tile counts the PricingRequest queue. */}
            <StatCard icon="clock"     label="รอรับเรื่อง"         value={pricingQueueQuery.data?.length ?? 0} helper="Pricing requests" tone="amber"  onClick={canViewPricingQueue ? () => navigate('/pricing-requests') : undefined} />
            <StatCard icon="users"     label="กำลังดำเนินการ"     value={summary.inReview}        helper="In review"          tone="blue"   onClick={() => navigate('/tickets')} />
            <StatCard icon="clipboard" label="รอการอนุมัติ"        value={summary.priceProposed}   helper="Awaiting approval"  tone="amber"  onClick={canApprove ? () => navigate('/tickets') : undefined} />
          </div>

          <div className="stat-grid">
            <StatCard icon="check"     label="อนุมัติแล้ว"         value={summary.approved}        helper="Approved"           tone="teal"   onClick={() => navigate('/tickets')} />
            <StatCard icon="fileText"  label="ออกใบเสนอราคาแล้ว"  value={summary.quotationIssued} helper="Quotation issued"   tone="teal"   onClick={() => navigate('/tickets')} />
            <StatCard icon="badge"     label="ปิดแล้วเดือนนี้"    value={summary.closedThisMonth} helper="Closed this month"  tone="indigo" onClick={() => navigate('/tickets')} />
            <StatCard icon="close"     label="ยกเลิกเดือนนี้"     value={summary.cancelledThisMonth} helper="Cancelled this month" tone="neutral" onClick={() => navigate('/tickets')} />
          </div>

          <div className="stat-grid">
            <StatCard icon="clock"     label="พักไว้ชั่วคราว"      value={summary.onHold ?? 0}             helper="On hold"             tone="amber"  onClick={() => navigate('/tickets?life=ON_HOLD')} />
            <StatCard icon="clock"     label="ดีลเงียบ"            value={summary.dormant ?? 0}            helper="Dormant"             tone="neutral" onClick={() => navigate('/tickets?life=DORMANT')} />
            <StatCard icon="badge"     label="เกินกำหนดชำระ"       value={summary.paymentOverdue ?? 0}     helper="Payment overdue"     tone="rose"   onClick={() => navigate('/tickets?flag=overdue')} />
            <StatCard icon="clipboard" label="ส่งมอบบางส่วน"       value={summary.partiallyDelivered ?? 0} helper="Partially delivered" tone="amber"  onClick={() => navigate('/tickets?flag=partial_delivery')} />
          </div>

          {recent.length > 0 && (
            <section className="panel" style={{ padding: 0, overflow: 'hidden' }}>
              <div className="panel-header" style={{ padding: '14px 18px' }}>
                <h2>ใบขอราคาล่าสุด</h2>
              </div>
              {isMobile ? (
                <div className="flex flex-col gap-2.5 p-3">
                  {recent.map((ticket) => (
                    <button
                      key={ticket.id}
                      type="button"
                      onClick={() => navigate(`/tickets/${ticket.id}`)}
                      className="flex w-full min-w-0 flex-col items-stretch gap-2 rounded-md border border-solid border-border bg-surface p-4 text-left cursor-pointer hover:bg-surface-hover"
                    >
                      <RecentTicketCard ticket={ticket} showOwner={SHOW_SALES_ROLES.includes(user.role)} />
                    </button>
                  ))}
                </div>
              ) : (
                <>
                  {/* Shares .table-head/.data-row + .ticket-table with TicketListPage
                      (src/features/tickets/TicketListPage.jsx) so hierarchy, hover,
                      and column widths stay identical between the two ticket views
                      instead of drifting via a second hand-rolled inline-style table. */}
                  <div className="ticket-table table-head" role="row">
                    <span role="columnheader">บริษัท / โครงการ</span>
                    <span role="columnheader">{SHOW_SALES_ROLES.includes(user.role) ? 'ผู้ขาย' : ''}</span>
                    <span role="columnheader">สถานะ</span>
                    <span role="columnheader">วันที่สร้าง</span>
                  </div>
                  {recent.map((ticket) => {
                    const st = ticketStatusLabel(ticket.status);
                    return (
                      <button
                        key={ticket.id}
                        type="button"
                        onClick={() => navigate(`/tickets/${ticket.id}`)}
                        className="ticket-table data-row"
                        role="row"
                      >
                        <span role="cell" className="flex min-w-0 flex-col gap-0.5">
                          <strong className="block truncate text-text">{ticket.customerName || ticket.title}</strong>
                          <code className="block truncate text-2xs text-text-muted">{ticket.code}</code>
                        </span>
                        <span role="cell">
                          {SHOW_SALES_ROLES.includes(user.role) ? (ticket.createdByName || '—') : ''}
                        </span>
                        <span role="cell">
                          <StatusBadge tone={st.tone}>{st.label}</StatusBadge>
                        </span>
                        <span role="cell">{formatThaiDate(ticket.createdAt)}</span>
                      </button>
                    );
                  })}
                </>
              )}
            </section>
          )}
        </>
      )}
    </div>
  );
}
