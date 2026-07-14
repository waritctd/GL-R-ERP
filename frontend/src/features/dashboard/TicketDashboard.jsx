import { useCallback, useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { api } from '../../api/index.js';
import { PageHeader } from '../../components/common/PageHeader.jsx';
import { Skeleton, SkeletonCard } from '../../components/common/Skeleton.jsx';
import { StatCard } from '../../components/common/StatCard.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { formatThaiDate, ticketStatusLabel } from '../../utils/format.js';

const TONE_DOT = {
  neutral: '#94a3b8', warning: '#f59e0b',
  info: '#3b82f6', success: '#22c55e', danger: '#ef4444',
};

function GreetingSubtitle({ role }) {
  if (role === 'sales') return 'ภาพรวมใบขอราคาของคุณ';
  if (role === 'import') return 'ภาพรวมใบขอราคาทั้งหมด — ฝ่ายนำเข้า';
  if (role === 'ceo') return 'ภาพรวมใบขอราคาทั้งหมด — ผู้บริหาร';
  return 'ภาพรวมใบขอราคา';
}

const SHOW_SALES_ROLES = ['import', 'ceo'];

export function TicketDashboard({ user, employee, showToast }) {
  const navigate = useNavigate();
  const [summary, setSummary] = useState(null);
  const [recent, setRecent] = useState([]);
  const [loading, setLoading] = useState(true);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const [summaryRes, ticketsRes] = await Promise.all([
        api.dashboard.summary(),
        api.tickets.list({}),
      ]);
      const nextSummary = summaryRes?.summary ?? summaryRes ?? null;
      setSummary(nextSummary?.tickets ?? nextSummary);
      const tickets = Array.isArray(ticketsRes) ? ticketsRes : (ticketsRes?.tickets ?? []);
      setRecent(tickets.slice(0, 6));
    } catch (err) {
      showToast('error', err.message || 'โหลดข้อมูลไม่สำเร็จ');
    } finally {
      setLoading(false);
    }
  }, [showToast]);

  useEffect(() => {
    load();
  }, [load]);

  const greeting = `สวัสดี, ${employee?.nickName || employee?.nameTh || user.name}`;

  return (
    <div className="page-stack">
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
          <div className="stat-grid">
            <StatCard icon="fileText"  label="เปิดอยู่ทั้งหมด"   value={summary.totalOpen}       helper="Total open"         tone="indigo" />
            <StatCard icon="clock"     label="รอรับเรื่อง"         value={summary.submitted}       helper="Submitted"          tone="amber"  />
            <StatCard icon="users"     label="กำลังดำเนินการ"     value={summary.inReview}        helper="In review"          tone="blue"   />
            <StatCard icon="clipboard" label="รอการอนุมัติ"        value={summary.priceProposed}   helper="Awaiting approval"  tone="amber"  />
          </div>

          <div className="stat-grid">
            <StatCard icon="check"     label="อนุมัติแล้ว"         value={summary.approved}        helper="Approved"           tone="teal"   />
            <StatCard icon="fileText"  label="ออกใบเสนอราคาแล้ว"  value={summary.quotationIssued} helper="Quotation issued"   tone="teal"   />
            <StatCard icon="badge"     label="ปิดแล้วเดือนนี้"    value={summary.closedThisMonth} helper="Closed this month"  tone="indigo" />
            <StatCard icon="close"     label="ยกเลิกเดือนนี้"     value={summary.cancelledThisMonth} helper="Cancelled this month" tone="neutral" />
          </div>

          {recent.length > 0 && (
            <section className="panel" style={{ padding: 0, overflow: 'hidden' }}>
              <div className="panel-header" style={{ padding: '14px 18px' }}>
                <h2>ใบขอราคาล่าสุด</h2>
              </div>
              <div style={{
                display: 'grid',
                gridTemplateColumns: 'minmax(0,0.9fr) minmax(0,2.2fr) minmax(0,1.4fr) minmax(0,1.3fr) minmax(0,1fr)',
                gap: 12, padding: '6px 18px 6px 22px',
                borderBottom: '1px solid #e6eaf0',
                background: '#f8fafc',
                fontSize: 11, fontWeight: 700, color: '#94a3b8', textTransform: 'uppercase', letterSpacing: '0.05em',
              }}>
                <span>เลขที่</span>
                <span>บริษัท / โครงการ</span>
                <span>{SHOW_SALES_ROLES.includes(user.role) ? 'ผู้ขาย' : ''}</span>
                <span>สถานะ</span>
                <span>วันที่สร้าง</span>
              </div>
              {recent.map((ticket) => {
                const st = ticketStatusLabel(ticket.status);
                return (
                  <button
                    key={ticket.id}
                    type="button"
                    onClick={() => navigate(`/tickets/${ticket.id}`)}
                    style={{
                      width: '100%', display: 'grid',
                      gridTemplateColumns: 'minmax(0,0.9fr) minmax(0,2.2fr) minmax(0,1.4fr) minmax(0,1.3fr) minmax(0,1fr)',
                      gap: 12, alignItems: 'center',
                      padding: '12px 18px', border: 'none', borderBottom: '1px solid #f1f5f9',
                      background: '#fff', cursor: 'pointer', textAlign: 'left', color: '#334155',
                      borderLeft: `4px solid ${TONE_DOT[st.tone] ?? '#94a3b8'}`,
                    }}
                  >
                    <code style={{ fontSize: 12 }}>{ticket.code}</code>
                    <span style={{ overflow: 'hidden', whiteSpace: 'nowrap', textOverflow: 'ellipsis' }}>
                      <strong style={{ display: 'block', overflow: 'hidden', whiteSpace: 'nowrap', textOverflow: 'ellipsis' }}>{ticket.customerName || ticket.title}</strong>
                    </span>
                    <span style={{ overflow: 'hidden', whiteSpace: 'nowrap', textOverflow: 'ellipsis', fontSize: 13, color: '#64748b' }}>
                      {SHOW_SALES_ROLES.includes(user.role) ? (ticket.createdByName || '—') : ''}
                    </span>
                    <StatusBadge tone={st.tone}>{st.label}</StatusBadge>
                    <span style={{ fontSize: 13, color: '#94a3b8', whiteSpace: 'nowrap' }}>
                      {formatThaiDate(ticket.createdAt)}
                    </span>
                  </button>
                );
              })}
            </section>
          )}
        </>
      )}
    </div>
  );
}
