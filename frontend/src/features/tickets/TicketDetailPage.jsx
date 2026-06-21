import { useEffect, useState } from 'react';
import { api, ROLE_PERMISSIONS } from '../../api/index.js';
import { EmptyState } from '../../components/common/EmptyState.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { formatMoney, formatThaiDate, ticketPriorityLabel, ticketStatusLabel } from '../../utils/format.js';

const EVENT_KIND_LABEL = {
  CREATED:          'สร้างใบขอราคา',
  SUBMITTED:        'ส่งเรื่องเข้าระบบ',
  PICKED_UP:        'รับมอบหมาย',
  PRICE_PROPOSED:   'เสนอราคาสินค้า',
  APPROVED:         'อนุมัติ',
  REJECTED:         'ปฏิเสธ',
  QUOTATION_ISSUED: 'ออกใบเสนอราคา',
  CLOSED:           'ปิดเรื่อง',
  CANCELLED:        'ยกเลิก',
  COMMENTED:        'ความคิดเห็น',
  COMMENT:          'ความคิดเห็น',
};

const TERMINAL = ['closed', 'cancelled'];

function eventDotClass(kind) {
  if (kind === 'CREATED') return 'event-dot created';
  if (kind === 'COMMENTED' || kind === 'COMMENT') return 'event-dot comment';
  return 'event-dot transition';
}

function InfoRow({ label, value }) {
  return (
    <div style={{ display: 'flex', gap: 8, padding: '6px 0', borderBottom: '1px solid #f1f5f9', fontSize: 13 }}>
      <span style={{ color: '#64748b', minWidth: 120 }}>{label}</span>
      <span style={{ fontWeight: 600, color: '#0f172a' }}>{value || '-'}</span>
    </div>
  );
}

export function TicketDetailPage({ user, ticketId, onBack, showToast }) {
  const [ticket, setTicket] = useState(null);
  const [loading, setLoading] = useState(true);
  const [actionLoading, setActionLoading] = useState(false);

  // Propose-price mode
  const [proposeMode, setProposeMode] = useState(false);
  const [draftPrices, setDraftPrices] = useState({});
  const [proposeNote, setProposeNote] = useState('');

  // Reject form
  const [showRejectForm, setShowRejectForm] = useState(false);
  const [rejectReason, setRejectReason] = useState('');

  // Comment
  const [commentText, setCommentText] = useState('');

  async function loadTicket() {
    setLoading(true);
    try {
      const response = await api.tickets.get(ticketId);
      setTicket(response.ticket);
    } catch (error) {
      showToast('error', error.message || 'โหลดข้อมูลไม่สำเร็จ');
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => { if (ticketId) loadTicket(); }, [ticketId]);

  async function doAction(fn, successMsg) {
    setActionLoading(true);
    try {
      const response = await fn();
      setTicket(response.ticket);
      showToast('success', successMsg);
      setProposeMode(false);
      setShowRejectForm(false);
      setRejectReason('');
      setCommentText('');
      setDraftPrices({});
      setProposeNote('');
    } catch (error) {
      showToast('error', error.message || 'เกิดข้อผิดพลาด');
    } finally {
      setActionLoading(false);
    }
  }

  if (loading) {
    return (
      <div className="page-stack">
        <div style={{ padding: 40, textAlign: 'center', color: '#94a3b8' }}>กำลังโหลด...</div>
      </div>
    );
  }

  if (!ticket) {
    return (
      <div className="page-stack">
        <EmptyState icon="fileText" title="ไม่พบใบขอราคา" description="กลับไปหน้ารายการ" />
        <button type="button" className="secondary-button" onClick={onBack}>
          <Icon name="chevronLeft" />
          กลับ
        </button>
      </div>
    );
  }

  const { summary, items, events, quotation } = ticket;
  const st = summary.status;
  const role = user.role;
  const isOwner = user.id === summary.createdById;

  const can = {
    submit:    st === 'draft'            && ROLE_PERMISSIONS.canCreateTickets.includes(role) && isOwner,
    pickup:    st === 'submitted'        && ROLE_PERMISSIONS.canPickupTickets.includes(role),
    propose:   st === 'in_review'        && ROLE_PERMISSIONS.canProposePrices.includes(role),
    approve:   st === 'price_proposed'   && ROLE_PERMISSIONS.canApproveReject.includes(role),
    reject:    st === 'price_proposed'   && ROLE_PERMISSIONS.canApproveReject.includes(role),
    quotation: st === 'approved'         && ROLE_PERMISSIONS.canGenerateQuotation.includes(role) && (isOwner || role === 'admin'),
    close:     st === 'quotation_issued' && ROLE_PERMISSIONS.canCreateTickets.includes(role) && (isOwner || role === 'admin'),
    cancel:    !TERMINAL.includes(st)    && (isOwner || role === 'admin'),
    comment:   !TERMINAL.includes(st),
  };

  const hasActions = Object.values(can).some(Boolean);

  const status = ticketStatusLabel(st);
  const priority = ticketPriorityLabel(summary.priority);

  function initPropose() {
    const map = {};
    items.forEach((item) => { map[item.id] = item.proposedPrice ?? ''; });
    setDraftPrices(map);
    setProposeMode(true);
  }

  async function handleProposePrice() {
    const payload = {
      items: items.map((item) => ({
        productCode: item.productCode ?? null,
        productName: item.productName,
        size: item.size ?? null,
        color: item.color ?? null,
        qty: item.qty,
        unit: item.unit ?? null,
        proposedPrice: draftPrices[item.id] !== '' ? Number(draftPrices[item.id]) : null,
        currency: item.currency ?? 'THB',
      })),
      note: proposeNote.trim() || null,
    };
    await doAction(() => api.tickets.proposePrice(ticketId, payload), 'ส่งราคาเสนอเรียบร้อย');
  }

  async function handleReject() {
    if (!rejectReason.trim()) { showToast('error', 'กรุณาระบุเหตุผลในการตีกลับ'); return; }
    await doAction(() => api.tickets.reject(ticketId, { reason: rejectReason.trim() }), 'ตีกลับใบขอราคาแล้ว');
  }

  async function handleComment() {
    if (!commentText.trim()) return;
    await doAction(() => api.tickets.comment(ticketId, { message: commentText.trim() }), 'เพิ่มความคิดเห็นแล้ว');
  }

  return (
    <div className="page-stack">
      <header style={{ display: 'flex', alignItems: 'flex-start', gap: 16, justifyContent: 'space-between' }}>
        <div>
          <button type="button" className="secondary-button" onClick={onBack} style={{ marginBottom: 12 }}>
            <Icon name="chevronLeft" size={14} />
            กลับ
          </button>
          <h1 style={{ margin: 0, fontSize: 22, fontWeight: 800, color: '#0f172a' }}>{summary.title}</h1>
          <div style={{ display: 'flex', gap: 8, alignItems: 'center', marginTop: 8 }}>
            <code style={{ fontSize: 13, background: '#f1f5f9', padding: '2px 8px', borderRadius: 4 }}>{summary.code}</code>
            <StatusBadge tone={status.tone}>{status.label}</StatusBadge>
            <StatusBadge tone={priority.tone}>{priority.label}</StatusBadge>
          </div>
        </div>
        <button type="button" className="icon-button" onClick={loadTicket} title="รีเฟรช">
          <Icon name="refresh" />
        </button>
      </header>

      {hasActions && (
        <section className="panel" style={{ background: '#f8fafc' }}>
          <div className="panel-header">
            <h2>การดำเนินการ</h2>
          </div>
          <div style={{ padding: '12px 18px', display: 'flex', flexWrap: 'wrap', gap: 10 }}>
            {can.submit && (
              <button type="button" className="primary-button" disabled={actionLoading}
                onClick={() => doAction(() => api.tickets.submit(ticketId), 'ส่งใบขอราคาเข้าระบบแล้ว')}>
                <Icon name="chevronRight" size={14} />
                ส่งเรื่อง
              </button>
            )}

            {can.pickup && (
              <button type="button" className="primary-button" disabled={actionLoading}
                onClick={() => doAction(() => api.tickets.pickup(ticketId), 'รับมอบหมายแล้ว')}>
                <Icon name="check" size={14} />
                รับเรื่อง
              </button>
            )}

            {can.propose && !proposeMode && (
              <button type="button" className="primary-button" disabled={actionLoading} onClick={initPropose}>
                <Icon name="pencil" size={14} />
                เสนอราคาสินค้า
              </button>
            )}

            {can.approve && (
              <button type="button" className="primary-button" disabled={actionLoading}
                onClick={() => doAction(() => api.tickets.approve(ticketId), 'อนุมัติราคาแล้ว')}>
                <Icon name="check" size={14} />
                อนุมัติ
              </button>
            )}

            {can.reject && !showRejectForm && (
              <button type="button" className="secondary-button" disabled={actionLoading}
                onClick={() => setShowRejectForm(true)}>
                <Icon name="close" size={14} />
                ตีกลับ
              </button>
            )}

            {can.quotation && (
              <button type="button" className="primary-button" disabled={actionLoading}
                onClick={() => doAction(() => api.tickets.quotation(ticketId), 'ออกใบเสนอราคาแล้ว')}>
                <Icon name="fileText" size={14} />
                ออกใบเสนอราคา
              </button>
            )}

            {can.close && (
              <button type="button" className="primary-button" disabled={actionLoading}
                onClick={() => doAction(() => api.tickets.close(ticketId), 'ปิดใบขอราคาแล้ว')}>
                <Icon name="check" size={14} />
                ปิดเรื่อง
              </button>
            )}

            {can.cancel && (
              <button type="button" className="secondary-button" disabled={actionLoading}
                style={{ marginLeft: 'auto', color: '#dc2626', borderColor: '#fca5a5' }}
                onClick={() => {
                  if (window.confirm('ยืนยันการยกเลิกใบขอราคานี้?')) {
                    doAction(() => api.tickets.cancel(ticketId), 'ยกเลิกใบขอราคาแล้ว');
                  }
                }}>
                ยกเลิก
              </button>
            )}
          </div>

          {showRejectForm && (
            <div style={{ padding: '0 18px 14px', display: 'flex', flexDirection: 'column', gap: 8 }}>
              <label style={{ fontSize: 13, fontWeight: 600 }}>
                เหตุผลในการตีกลับ *
                <textarea
                  rows={2}
                  value={rejectReason}
                  onChange={(e) => setRejectReason(e.target.value)}
                  placeholder="ระบุเหตุผล..."
                  style={{ marginTop: 4 }}
                />
              </label>
              <div style={{ display: 'flex', gap: 8 }}>
                <button type="button" className="secondary-button" onClick={handleReject} disabled={actionLoading}>
                  ยืนยันตีกลับ
                </button>
                <button type="button" className="secondary-button" onClick={() => { setShowRejectForm(false); setRejectReason(''); }} disabled={actionLoading}>
                  ยกเลิก
                </button>
              </div>
            </div>
          )}
        </section>
      )}

      <div className="ticket-detail-grid">
        <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
          <section className="panel">
            <div className="panel-header">
              <h2>ข้อมูลทั่วไป</h2>
            </div>
            <InfoRow label="ลูกค้า" value={summary.customerName} />
            <InfoRow label="สร้างโดย" value={summary.createdByName} />
            <InfoRow label="วันที่สร้าง" value={formatThaiDate(summary.createdAt)} />
            <InfoRow label="ผู้รับมอบหมาย" value={summary.assignedToName} />
            <InfoRow label="อัปเดตล่าสุด" value={formatThaiDate(summary.updatedAt)} />
          </section>

          <section className="table-panel">
            <div className="panel-header" style={{ padding: '14px 18px', borderBottom: '1px solid #e6eaf0', display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
              <h2>รายการสินค้า ({items.length} รายการ)</h2>
            </div>
            <div className="ticket-items-table table-head">
              <span>สินค้า</span>
              <span>จำนวน</span>
              <span>ราคาที่เสนอ {proposeMode ? '(แก้ไข)' : ''}</span>
              <span>ราคาที่อนุมัติ</span>
            </div>
            {items.length === 0 ? (
              <EmptyState title="ไม่มีรายการสินค้า" />
            ) : items.map((item, i) => (
              <div key={item.id ?? i} className="ticket-items-table table-row">
                <span>
                  <strong>{item.productName}</strong>
                  {(item.size || item.color) && (
                    <small style={{ color: '#64748b' }}>{[item.size, item.color].filter(Boolean).join(' / ')}</small>
                  )}
                </span>
                <span>{item.qty} {item.unit || ''}</span>
                {proposeMode ? (
                  <input
                    type="number"
                    min="0"
                    step="0.01"
                    value={draftPrices[item.id] ?? ''}
                    onChange={(e) => setDraftPrices((prev) => ({ ...prev, [item.id]: e.target.value }))}
                    placeholder="ราคา"
                    style={{ width: 120, padding: '4px 8px', border: '1px solid #cbd5e1', borderRadius: 4, fontSize: 13 }}
                  />
                ) : (
                  <code>{formatMoney(item.proposedPrice)}</code>
                )}
                <code>{formatMoney(item.approvedPrice)}</code>
              </div>
            ))}

            {proposeMode && (
              <div style={{ padding: '12px 18px', display: 'flex', flexDirection: 'column', gap: 8, borderTop: '1px solid #e6eaf0' }}>
                <label style={{ fontSize: 13 }}>
                  หมายเหตุราคา
                  <input
                    value={proposeNote}
                    onChange={(e) => setProposeNote(e.target.value)}
                    placeholder="ข้อมูลเพิ่มเติมเกี่ยวกับราคา (ถ้ามี)"
                    style={{ marginTop: 4 }}
                  />
                </label>
                <div style={{ display: 'flex', gap: 8 }}>
                  <button type="button" className="primary-button" onClick={handleProposePrice} disabled={actionLoading}>
                    ยืนยันราคาเสนอ
                  </button>
                  <button type="button" className="secondary-button" onClick={() => { setProposeMode(false); setDraftPrices({}); setProposeNote(''); }} disabled={actionLoading}>
                    ยกเลิก
                  </button>
                </div>
              </div>
            )}
          </section>

          {quotation && (
            <section className="panel">
              <div className="panel-header">
                <h2>ใบเสนอราคา</h2>
              </div>
              <InfoRow label="เลขที่ใบเสนอราคา" value={quotation.number} />
              <InfoRow label="ยอดรวม" value={formatMoney(quotation.totalAmount)} />
              <InfoRow label="ออกโดย" value={quotation.issuedByName} />
              <InfoRow label="วันที่ออก" value={formatThaiDate(quotation.issuedAt)} />
            </section>
          )}
        </div>

        <section className="panel">
          <div className="panel-header">
            <h2>ประวัติการดำเนินการ</h2>
          </div>
          <div className="ticket-events">
            {events.length === 0 ? (
              <p style={{ color: '#94a3b8', fontSize: 13 }}>ยังไม่มีประวัติ</p>
            ) : [...events].reverse().map((event) => (
              <div key={event.id} className="ticket-event">
                <span className={eventDotClass(event.kind)} />
                <div style={{ flex: 1, minWidth: 0 }}>
                  <strong style={{ display: 'block', fontSize: 13, color: '#0f172a' }}>
                    {EVENT_KIND_LABEL[event.kind] ?? event.kind}
                  </strong>
                  <span style={{ color: '#475569', fontSize: 12 }}>{event.actorName}</span>
                  {event.message && (
                    <p style={{ margin: '4px 0 0', fontSize: 12, color: '#64748b', background: '#f8fafc', borderRadius: 4, padding: '4px 8px' }}>
                      {event.message}
                    </p>
                  )}
                  <small style={{ color: '#94a3b8', fontSize: 11 }}>{formatThaiDate(event.createdAt)}</small>
                </div>
              </div>
            ))}
          </div>

          {can.comment && (
            <div style={{ padding: '12px 18px', borderTop: '1px solid #e6eaf0', display: 'flex', flexDirection: 'column', gap: 8 }}>
              <textarea
                rows={2}
                value={commentText}
                onChange={(e) => setCommentText(e.target.value)}
                placeholder="เพิ่มความคิดเห็น..."
                style={{ resize: 'vertical' }}
              />
              <button type="button" className="secondary-button" onClick={handleComment} disabled={actionLoading || !commentText.trim()}
                style={{ alignSelf: 'flex-end' }}>
                ส่งความคิดเห็น
              </button>
            </div>
          )}
        </section>
      </div>
    </div>
  );
}
