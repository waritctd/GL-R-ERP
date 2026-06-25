import { useEffect, useState } from 'react';
import { api } from '../../api/index.js';
import { PageHeader } from '../../components/common/PageHeader.jsx';
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

export function TicketDashboard({ user, employee, onOpenTicket, showToast }) {
  const [summary, setSummary] = useState(null);
  const [recent, setRecent] = useState([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    async function load() {
      setLoading(true);
      try {
        const [summaryRes, ticketsRes] = await Promise.all([
          api.dashboard.summary(),
          api.tickets.list({}),
        ]);
        setSummary(summaryRes.summary);
        setRecent(ticketsRes.tickets.slice(0, 6));
      } catch (err) {
        showToast('error', err.message || 'โหลดข้อมูลไม่สำเร็จ');
      } finally {
        setLoading(false);
      }
    }
    load();
  }, []);

  const greeting = `สวัสดี, ${employee?.nickName || employee?.nameTh || user.name}`;

  return (
    <div className="page-stack">
      <PageHeader title={greeting} subtitle={<GreetingSubtitle role={user.role} />} />

      {loading ? (
        <div style={{ padding: 40, textAlign: 'center', color: '#94a3b8' }}>กำลังโหลด...</div>
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
            <StatCard
              icon="close"
              label="ค้างเกิน 3 วัน"
              value={summary.overdueOver3Days}
              helper="Overdue > 3 days"
              tone={summary.overdueOver3Days > 0 ? 'rose' : 'indigo'}
            />
          </div>

          {recent.length > 0 && (
            <section className="panel" style={{ padding: 0, overflow: 'hidden' }}>
              <div className="panel-header" style={{ padding: '14px 18px' }}>
                <h2>ใบขอราคาล่าสุด</h2>
              </div>
              {recent.map((ticket) => {
                const st = ticketStatusLabel(ticket.status);
                return (
                  <button
                    key={ticket.id}
                    type="button"
                    onClick={() => onOpenTicket(ticket.id)}
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
                      <strong style={{ display: 'block', overflow: 'hidden', whiteSpace: 'nowrap', textOverflow: 'ellipsis' }}>{ticket.title}</strong>
                    </span>
                    <span style={{ overflow: 'hidden', whiteSpace: 'nowrap', textOverflow: 'ellipsis', fontSize: 13, color: '#64748b' }}>
                      {ticket.customerName || '—'}
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
