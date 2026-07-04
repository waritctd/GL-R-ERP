import { useEffect, useState } from 'react';
import { api, ROLE_PERMISSIONS } from '../../api/index.js';
import { EmptyState } from '../../components/common/EmptyState.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { formatMoney, formatThaiDate, ticketStatusLabel } from '../../utils/format.js';

const EVENT_KIND_LABEL = {
  CREATED:            'สร้างใบขอราคา',
  SUBMITTED:          'ส่งเรื่องเข้าระบบ',
  PICKED_UP:          'รับมอบหมาย',
  PRICE_PROPOSED:     'เสนอราคาสินค้า',
  APPROVED:           'อนุมัติ',
  REJECTED:           'ปฏิเสธ',
  DOCUMENT_ISSUED:    'ออกใบแจ้งยอดมัดจำ',
  REVISION_REQUESTED: 'ขอแก้ไข',
  CLOSED:             'ปิดเรื่อง',
  CANCELLED:          'ยกเลิก',
  EDITED:             'แก้ไขรายการสินค้า',
  COMMENTED:          'ความคิดเห็น',
  COMMENT:            'ความคิดเห็น',
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

export function TicketDetailPage({ user, ticketId, onBack, onOpenDocument, showToast }) {
  const [ticket, setTicket] = useState(null);
  const [loading, setLoading] = useState(true);
  const [actionLoading, setActionLoading] = useState(false);

  // Propose-price mode
  const [proposeMode, setProposeMode] = useState(false);
  const [draftRaw, setDraftRaw] = useState({});              // itemId → rawPrice (string)
  const [draftFactoryCurr, setDraftFactoryCurr] = useState({}); // factoryName → { currency, unit }
  const [proposeNote, setProposeNote] = useState('');

  // Factory configs + email drafts
  const [factoryConfigs, setFactoryConfigs] = useState({});   // factoryName → config
  const [emailDraft, setEmailDraft] = useState(null);         // { factory, to, subject, body } | null
  const [emailSending, setEmailSending] = useState(false);

  // Edit-items mode
  const [editMode, setEditMode] = useState(false);
  const [editDraft, setEditDraft] = useState([]);
  const [editNote, setEditNote] = useState('');

  // Reject form
  const [showRejectForm, setShowRejectForm] = useState(false);
  const [rejectReason, setRejectReason] = useState('');

  // R4: CEO price calculation
  const [calcLoading, setCalcLoading] = useState(false);

  // R5: Attachments
  const [attachments, setAttachments] = useState([]);
  const [attachLoading, setAttachLoading] = useState(false);
  const [uploadingFile, setUploadingFile] = useState(false);

  // Revision form
  const [showReviseForm, setShowReviseForm] = useState(false);
  const [reviseScope, setReviseScope] = useState('QTY_OR_NOTE');
  const [reviseReason, setReviseReason] = useState('');

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

  async function loadAttachments() {
    setAttachLoading(true);
    try {
      const res = await api.attachments.list(ticketId);
      setAttachments(res.attachments ?? []);
    } catch { /* non-critical */ } finally {
      setAttachLoading(false);
    }
  }

  useEffect(() => {
    if (ticketId) {
      loadTicket();
      loadAttachments();
    }
  }, [ticketId]);

  async function doAction(fn, successMsg) {
    setActionLoading(true);
    try {
      const response = await fn();
      setTicket(response.ticket);
      showToast('success', successMsg);
      setProposeMode(false);
      setEditMode(false);
      setEditDraft([]);
      setEditNote('');
      setShowRejectForm(false);
      setRejectReason('');
      setShowReviseForm(false);
      setReviseReason('');
      setCommentText('');
      setDraftRaw({});
      setDraftFactoryCurr({});
      setProposeNote('');
      setEmailDraft(null);
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

  const { summary, items, events, quotations } = ticket;
  const st = summary.status;
  const role = user.role;
  const isOwner = user.id === summary.createdById;

  const showProposed = ROLE_PERMISSIONS.canProposePrices.includes(role) || ROLE_PERMISSIONS.canApproveReject.includes(role);
  const showCalcBreakdown = ROLE_PERMISSIONS.canApproveReject.includes(role) && items.some((it) => it.calcedCost != null);
  const itemsGridCols = showCalcBreakdown
    ? 'minmax(0,1.4fr) minmax(0,1fr) minmax(0,0.5fr) minmax(0,0.9fr) minmax(0,0.9fr) minmax(0,0.9fr)'
    : showProposed
      ? 'minmax(0,1.8fr) minmax(0,1.2fr) minmax(0,0.6fr) minmax(0,1.1fr) minmax(0,1.1fr)'
      : 'minmax(0,1.8fr) minmax(0,1.2fr) minmax(0,0.6fr) minmax(0,1.1fr)';

  const EDITABLE_STATUSES = ['submitted', 'in_review', 'price_proposed'];
  const can = {
    pickup:            st === 'submitted'       && ROLE_PERMISSIONS.canPickupTickets.includes(role),
    propose:           st === 'in_review'       && ROLE_PERMISSIONS.canProposePrices.includes(role),
    calculatePrices:   st === 'price_proposed'  && ROLE_PERMISSIONS.canApproveReject.includes(role),
    approve:           st === 'price_proposed'  && ROLE_PERMISSIONS.canApproveReject.includes(role),
    reject:            st === 'price_proposed'  && ROLE_PERMISSIONS.canApproveReject.includes(role),
    generateDocument: st === 'approved'        && ROLE_PERMISSIONS.canCreateTickets.includes(role) && (isOwner || role === 'admin'),
    revise:           (st === 'approved' || st === 'document_issued') && ROLE_PERMISSIONS.canCreateTickets.includes(role) && (isOwner || role === 'admin'),
    close:            st === 'document_issued' && ROLE_PERMISSIONS.canCreateTickets.includes(role) && (isOwner || role === 'admin'),
    cancel:           !TERMINAL.includes(st)   && (isOwner || role === 'admin'),
    comment:          !TERMINAL.includes(st),
    editItems: EDITABLE_STATUSES.includes(st) && (
      (ROLE_PERMISSIONS.canCreateTickets.includes(role) && isOwner) ||
      (ROLE_PERMISSIONS.canPickupTickets.includes(role))
    ),
  };

  const hasActions = Object.values(can).some(Boolean);

  const status = ticketStatusLabel(st);

  async function initPropose() {
    // load factory configs first so we can init currency defaults
    let fcMap = factoryConfigs;
    if (Object.keys(fcMap).length === 0) {
      try {
        const res = await api.factoryConfigs.list();
        fcMap = {};
        (res.factories ?? []).forEach((fc) => { fcMap[fc.factoryName] = fc; });
        setFactoryConfigs(fcMap);
      } catch { /* non-critical */ }
    }

    // init raw price per item (carry over existing rawPrice if re-opening)
    const rawMap = {};
    items.forEach((item) => { rawMap[item.id] = item.rawPrice != null ? String(item.rawPrice) : ''; });

    // init currency/unit per factory group (from config or existing item data)
    const currMap = {};
    const groups = groupByFactory(items);
    groups.forEach(({ factory, items: gItems }) => {
      const fc = fcMap[factory];
      // prefer existing raw data on items, fallback to config, fallback to THB/piece
      const firstWithRaw = gItems.find((it) => it.rawCurrency);
      currMap[factory] = {
        currency: firstWithRaw?.rawCurrency ?? fc?.currency ?? 'THB',
        unit:     firstWithRaw?.rawUnit     ?? fc?.unit     ?? 'piece',
      };
    });

    setDraftRaw(rawMap);
    setDraftFactoryCurr(currMap);
    setProposeMode(true);
  }

  async function handleProposePrice() {
    const payload = {
      items: items.map((item) => {
        const rawPriceStr = draftRaw[item.id] ?? '';
        const fc = draftFactoryCurr[item.factory] ?? {};
        return {
          brand: item.brand, model: item.model, color: item.color,
          texture: item.texture, size: item.size, factory: item.factory ?? null,
          qty: item.qty, qtySqm: item.qtySqm ?? null,
          rawPrice:    rawPriceStr !== '' ? Number(rawPriceStr) : null,
          rawCurrency: fc.currency ?? null,
          rawUnit:     fc.unit     ?? null,
          proposedPrice: null, // calculated in R4
          currency: 'THB',
        };
      }),
      note: proposeNote.trim() || null,
    };
    await doAction(() => api.tickets.proposePrice(ticketId, payload), 'ส่งราคาเสนอเรียบร้อย');
  }

  // Group items by factory for Import propose-price view
  function groupByFactory(itemList) {
    const groups = [];
    const seen = new Map();
    itemList.forEach((item) => {
      const key = item.factory || '(ไม่ระบุโรงงาน)';
      if (!seen.has(key)) { seen.set(key, []); groups.push({ factory: key, items: seen.get(key) }); }
      seen.get(key).push(item);
    });
    return groups;
  }

  function buildEmailDraft(factory, groupItems) {
    const fc = factoryConfigs[factory] ?? {};
    const currency = fc.currency ?? 'THB';
    const unit = fc.unit ?? 'piece';
    const unitLabel = unit === 'sqm' ? 'ตร.ม.' : 'แผ่น';
    const itemLines = groupItems.map((item, i) => {
      const qty = unit === 'sqm' ? (item.qtySqm != null ? `${Number(item.qtySqm).toFixed(2)} ตร.ม.` : `${item.qty} แผ่น`) : `${item.qty} แผ่น`;
      return `${i + 1}. ${item.brand} ${item.model} ${item.color} ${item.texture} ${item.size} — ${qty}`;
    }).join('\n');
    return {
      factory,
      to: fc.email ?? '',
      subject: `ขอราคาสินค้า — ${summary.code} — ${factory}`,
      body: `เรียน ${factory}\n\nทางบริษัท จี แอล แอนด์ อาร์ จำกัด ขอทราบราคาสินค้าดังต่อไปนี้\n\nใบขอราคาเลขที่: ${summary.code}\nลูกค้า: ${summary.customerName || summary.title}\n\nรายการสินค้า:\n${itemLines}\n\nกรุณาตอบกลับราคาสินค้าในสกุลเงิน ${currency} ต่อ ${unitLabel}\n\nขอบคุณครับ/ค่ะ\nฝ่ายนำเข้า\nบริษัท จี แอล แอนด์ อาร์ จำกัด`,
    };
  }

  async function sendFactoryEmail() {
    if (!emailDraft) return;
    setEmailSending(true);
    try {
      await api.factoryConfigs.sendEmail(ticketId, emailDraft);
      showToast('success', `ส่งอีเมลถึง ${emailDraft.factory} แล้ว`);
      setEmailDraft(null);
    } catch (err) {
      showToast('error', err.message || 'ส่งอีเมลไม่สำเร็จ');
    } finally {
      setEmailSending(false);
    }
  }

  async function handleCalculatePrices() {
    setCalcLoading(true);
    try {
      const response = await api.tickets.calculatePrices(ticketId);
      setTicket(response.ticket);
      showToast('success', 'คำนวณราคาเรียบร้อย — ตรวจสอบราคาแล้วกดอนุมัติได้เลย');
    } catch (error) {
      showToast('error', error.message || 'คำนวณราคาไม่สำเร็จ');
    } finally {
      setCalcLoading(false);
    }
  }

  async function handleUploadAttachment(e) {
    const file = e.target.files?.[0];
    if (!file) return;
    const attachType = file.name.toLowerCase().includes('po') ? 'PO' : 'OTHER';
    setUploadingFile(true);
    try {
      await api.attachments.upload(ticketId, file, attachType);
      await loadAttachments();
      showToast('success', `แนบไฟล์ ${file.name} แล้ว`);
    } catch (err) {
      showToast('error', err.message || 'อัปโหลดไม่สำเร็จ');
    } finally {
      setUploadingFile(false);
      e.target.value = '';
    }
  }

  async function handleDeleteAttachment(id, name) {
    if (!window.confirm(`ลบไฟล์ "${name}" ออก?`)) return;
    try {
      await api.attachments.delete(id);
      setAttachments((prev) => prev.filter((a) => a.id !== id));
      showToast('success', 'ลบไฟล์แล้ว');
    } catch (err) {
      showToast('error', err.message || 'ลบไม่สำเร็จ');
    }
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
          <h1 style={{ margin: 0, fontSize: 22, fontWeight: 800, color: '#0f172a' }}>{summary.customerName || summary.title}</h1>
          <div style={{ display: 'flex', gap: 8, alignItems: 'center', marginTop: 8, flexWrap: 'wrap' }}>
            <code style={{ fontSize: 13, background: '#f1f5f9', padding: '2px 8px', borderRadius: 4 }}>{summary.code}</code>
            <StatusBadge tone={status.tone}>{status.label}</StatusBadge>
            {summary.hasEdits && (
              <StatusBadge tone="warning">✎ มีการแก้ไข</StatusBadge>
            )}
          </div>
        </div>
        <button type="button" className="icon-button" onClick={loadTicket} title="รีเฟรช" aria-label="รีเฟรช">
          <Icon name="refresh" />
        </button>
      </header>

      {hasActions && (
        <section className="panel" style={{ background: '#f8fafc' }}>
          <div className="panel-header">
            <h2>การดำเนินการ</h2>
          </div>
          <div style={{ padding: '12px 18px', display: 'flex', flexWrap: 'wrap', gap: 10 }}>
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

            {can.calculatePrices && (
              <button type="button" className="secondary-button" disabled={calcLoading || actionLoading}
                onClick={handleCalculatePrices}
                style={{ background: '#eff6ff', borderColor: '#93c5fd', color: '#1d4ed8' }}>
                <Icon name="calculator" size={14} />
                {calcLoading ? 'กำลังคำนวณ...' : 'คำนวณราคา (CIF)'}
              </button>
            )}

            {can.approve && (
              <button type="button" className="primary-button" disabled={actionLoading || calcLoading}
                onClick={() => doAction(() => api.tickets.approve(ticketId), 'อนุมัติราคาแล้ว')}>
                <Icon name="check" size={14} />
                อนุมัติ
              </button>
            )}

            {can.reject && !showRejectForm && (
              <button type="button" className="secondary-button" disabled={actionLoading}
                onClick={() => setShowRejectForm(true)}
                style={{ color: '#dc2626', borderColor: '#fca5a5' }}>
                <Icon name="close" size={14} />
                ไม่อนุมัติ
              </button>
            )}

            {can.generateDocument && (
              <button type="button" className="primary-button" disabled={actionLoading}
                onClick={() => onOpenDocument && onOpenDocument(ticketId)}>
                <Icon name="fileText" size={14} />
                ออกใบแจ้งยอดมัดจำ
              </button>
            )}

            {can.revise && !showReviseForm && (
              <button type="button" className="secondary-button" disabled={actionLoading}
                onClick={() => setShowReviseForm(true)}>
                <Icon name="pencil" size={14} />
                ขอแก้ไข (Revise)
              </button>
            )}

            {can.close && (
              <button type="button" className="primary-button" disabled={actionLoading}
                onClick={() => doAction(() => api.tickets.close(ticketId), 'ปิดใบขอราคาแล้ว')}>
                <Icon name="check" size={14} />
                ปิดเรื่อง
              </button>
            )}

            {can.editItems && !editMode && (
              <button type="button" className="secondary-button" disabled={actionLoading}
                onClick={() => {
                  setEditDraft(items.map((item) => ({ ...item })));
                  setEditNote('');
                  setEditMode(true);
                }}>
                <Icon name="pencil" size={14} />
                แก้ไขรายการสินค้า
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
                <textarea rows={2} value={rejectReason} onChange={(e) => setRejectReason(e.target.value)}
                  placeholder="ระบุเหตุผล..." style={{ marginTop: 4 }} />
              </label>
              <div style={{ display: 'flex', gap: 8 }}>
                <button type="button" className="secondary-button" onClick={handleReject} disabled={actionLoading}
                  style={{ color: '#dc2626', borderColor: '#fca5a5' }}>
                  ยืนยันไม่อนุมัติ
                </button>
                <button type="button" className="secondary-button" onClick={() => { setShowRejectForm(false); setRejectReason(''); }} disabled={actionLoading}>
                  ยกเลิก
                </button>
              </div>
            </div>
          )}

          {showReviseForm && (
            <div style={{ padding: '0 18px 14px', display: 'flex', flexDirection: 'column', gap: 10, borderTop: '1px solid #e6eaf0' }}>
              <div style={{ fontSize: 13, fontWeight: 600, paddingTop: 12 }}>ประเภทการแก้ไข</div>
              {[
                { value: 'QTY_OR_NOTE',  label: 'แก้จำนวน / หมายเหตุ / % มัดจำ', sub: 'ไม่ต้องอนุมัติใหม่ — ออกเอกสาร Rev ใหม่ได้เลย' },
                { value: 'PRICE_CHANGE', label: 'แก้ราคา / ส่วนลดต่อหน่วย',       sub: 'CEO ต้องอนุมัติใหม่' },
                { value: 'NEW_ITEM',     label: 'เพิ่มสินค้าใหม่',                sub: 'Import ตั้งราคา → CEO อนุมัติ' },
              ].map((opt) => (
                // eslint-disable-next-line jsx-a11y/label-has-associated-control -- label nests the radio control; its text is the dynamic opt.label
                <label key={opt.value} style={{ display: 'flex', gap: 10, alignItems: 'flex-start', cursor: 'pointer', fontSize: 13 }}>
                  <input type="radio" name="reviseScope" value={opt.value}
                    checked={reviseScope === opt.value}
                    onChange={() => setReviseScope(opt.value)}
                    style={{ marginTop: 3, flexShrink: 0 }} />
                  <span>
                    <strong>{opt.label}</strong>
                    <span style={{ display: 'block', fontSize: 12, color: '#64748b' }}>{opt.sub}</span>
                  </span>
                </label>
              ))}
              <label style={{ fontSize: 13, fontWeight: 600 }}>
                เหตุผลการแก้ไข *
                <textarea rows={2} value={reviseReason} onChange={(e) => setReviseReason(e.target.value)}
                  placeholder="ระบุเหตุผล..." style={{ marginTop: 4 }} />
              </label>
              <div style={{ display: 'flex', gap: 8 }}>
                <button type="button" className="primary-button" disabled={actionLoading || !reviseReason.trim()}
                  onClick={() => {
                    if (!reviseReason.trim()) { showToast('error', 'กรุณาระบุเหตุผล'); return; }
                    doAction(() => api.tickets.revision(ticketId, { scope: reviseScope, reason: reviseReason.trim() }), 'ส่งคำขอแก้ไขแล้ว');
                  }}>
                  ยืนยันขอแก้ไข
                </button>
                <button type="button" className="secondary-button" disabled={actionLoading}
                  onClick={() => { setShowReviseForm(false); setReviseReason(''); }}>
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
            {summary.projectName && <InfoRow label="โครงการ" value={summary.projectName} />}
            {summary.contactName && (
              <InfoRow label="ผู้ติดต่อ" value={summary.contactName} />
            )}
            <InfoRow label="สร้างโดย" value={summary.createdByName} />
            <InfoRow label="วันที่สร้าง" value={formatThaiDate(summary.createdAt)} />
            <InfoRow label="เจ้าหน้าที่นำเข้า" value={summary.assignedToName} />
            <InfoRow label="อัปเดตล่าสุด" value={formatThaiDate(summary.updatedAt)} />
          </section>

          <section className="table-panel">
            <div className="panel-header" style={{ padding: '14px 18px', borderBottom: '1px solid #e6eaf0', display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
              <h2>รายการสินค้า ({editMode ? editDraft.length : items.length} รายการ)</h2>
            </div>

            {editMode ? (
              <div style={{ padding: '14px 18px' }}>
                {editDraft.map((item, index) => (
                  <div key={index} style={{ border: '1px solid #e2e8f0', borderRadius: 8, padding: '12px 14px', marginBottom: 10, background: '#f8fafc' }}>
                    <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 8 }}>
                      <span style={{ fontSize: 12, fontWeight: 700, color: '#64748b' }}>รายการที่ {index + 1}</span>
                      {editDraft.length > 1 && (
                        <button type="button" className="icon-button" style={{ color: '#ef4444' }} aria-label={`ลบรายการที่ ${index + 1}`}
                          onClick={() => setEditDraft((d) => d.filter((_, i) => i !== index))}>
                          <Icon name="close" size={14} />
                        </button>
                      )}
                    </div>
                    <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 8 }}>
                      {[
                        { key: 'brand', label: 'ชื่อยี่ห้อ', placeholder: 'เช่น SCG, Cotto' },
                        { key: 'model', label: 'ชื่อรุ่น', placeholder: 'ชื่อรุ่น' },
                        { key: 'color', label: 'สี', placeholder: 'เช่น ขาว, เทา' },
                        { key: 'texture', label: 'เนื้อผิว', placeholder: 'เช่น ด้าน, มัน' },
                        { key: 'size', label: 'ขนาด', placeholder: 'เช่น 60x60 ซม.' },
                        { key: 'factory', label: 'โรงงาน', placeholder: 'เช่น SCG Ceramics' },
                      ].map(({ key, label, placeholder }) => (
                        <label key={key} style={{ margin: 0 }}>
                          <span style={{ fontSize: 12 }}>{label}</span>
                          <input value={item[key] || ''} placeholder={placeholder}
                            onChange={(e) => setEditDraft((d) => d.map((r, i) => i === index ? { ...r, [key]: e.target.value } : r))} />
                        </label>
                      ))}
                      <label style={{ margin: 0 }}>
                        <span style={{ fontSize: 12 }}>จำนวน</span>
                        <input type="number" min="1" value={item.qty || 1}
                          onChange={(e) => setEditDraft((d) => d.map((r, i) => i === index ? { ...r, qty: e.target.value } : r))} />
                      </label>
                      {ROLE_PERMISSIONS.canProposePrices.includes(role) && (
                        <label style={{ margin: 0, gridColumn: '1 / -1' }}>
                          <span style={{ fontSize: 12 }}>ราคาที่เสนอ (บาท)</span>
                          <input type="number" min="0" step="0.01"
                            value={item.proposedPrice ?? ''}
                            placeholder="ราคา/หน่วย"
                            onChange={(e) => setEditDraft((d) => d.map((r, i) => i === index ? { ...r, proposedPrice: e.target.value === '' ? null : Number(e.target.value) } : r))} />
                        </label>
                      )}
                    </div>
                  </div>
                ))}
                <button type="button" className="secondary-button"
                  onClick={() => setEditDraft((d) => [...d, { brand: '', model: '', color: '', texture: '', size: '', qty: 1, proposedPrice: null }])}
                  style={{ marginBottom: 12 }}>
                  <Icon name="plus" size={14} /> เพิ่มรายการ
                </button>
                <label style={{ fontSize: 13, display: 'block', marginBottom: 10 }}>
                  หมายเหตุการแก้ไข
                  <input value={editNote} onChange={(e) => setEditNote(e.target.value)} placeholder="ระบุสาเหตุที่แก้ไข (ถ้ามี)" style={{ marginTop: 4 }} />
                </label>
                <div style={{ display: 'flex', gap: 8 }}>
                  <button type="button" className="primary-button" disabled={actionLoading}
                    onClick={() => doAction(() => api.tickets.editItems(ticketId, {
                      items: editDraft.map((item) => ({
                        brand: item.brand, model: item.model, color: item.color,
                        texture: item.texture, size: item.size,
                        factory: item.factory || null,
                        qty: Number(item.qty) || 1,
                        proposedPrice: item.proposedPrice != null && item.proposedPrice !== '' ? Number(item.proposedPrice) : null,
                        currency: item.currency ?? 'THB',
                      })),
                      note: editNote.trim() || null,
                    }), 'บันทึกการแก้ไขแล้ว')}>
                    บันทึกการแก้ไข
                  </button>
                  <button type="button" className="secondary-button" disabled={actionLoading}
                    onClick={() => { setEditMode(false); setEditDraft([]); setEditNote(''); }}>
                    ยกเลิก
                  </button>
                </div>
              </div>
            ) : (
              <>
                <div className="ticket-items-table table-head" style={{ gridTemplateColumns: itemsGridCols }}>
                  <span>ยี่ห้อ / รุ่น</span>
                  <span>สี / เนื้อผิว</span>
                  <span>จำนวน</span>
                  {showCalcBreakdown ? (
                    <>
                      <span>ราคาโรงงาน</span>
                      <span>ต้นทุน (THB/ชิ้น)</span>
                      <span>ราคาขาย (THB/ชิ้น)</span>
                    </>
                  ) : showProposed ? (
                    <>
                      <span>ราคาที่เสนอ {proposeMode ? '(แก้ไข)' : ''}</span>
                      <span>ราคาที่อนุมัติ</span>
                    </>
                  ) : (
                    <span>ราคาที่อนุมัติ</span>
                  )}
                </div>
                {items.length === 0 ? (
                  <EmptyState title="ไม่มีรายการสินค้า" />
                ) : proposeMode ? (
                  groupByFactory(items).map(({ factory, items: groupItems }) => {
                    const fc = factoryConfigs[factory];
                    const isDraftOpen = emailDraft?.factory === factory;
                    return (
                      <div key={factory}>
                        {/* Factory group header */}
                        <div style={{ padding: '8px 18px', background: '#f1f5f9', fontSize: 12, fontWeight: 700, color: '#1e3a5f', borderTop: '1px solid #e2e8f0', display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
                          <Icon name="building" size={13} />
                          <span>{factory}</span>
                          <span style={{ fontWeight: 400, color: '#64748b' }}>({groupItems.length} รายการ)</span>
                          {fc?.email && <span style={{ fontWeight: 400, color: '#94a3b8', fontSize: 11 }}>· {fc.email}</span>}
                          {/* สกุลเงิน/หน่วย selector */}
                          <div style={{ display: 'flex', alignItems: 'center', gap: 4, marginLeft: 4 }}>
                            <span style={{ fontWeight: 400, color: '#64748b', fontSize: 11 }}>ราคาต่อ:</span>
                            <select
                              value={draftFactoryCurr[factory]?.currency ?? 'THB'}
                              onChange={(e) => setDraftFactoryCurr((p) => ({ ...p, [factory]: { ...p[factory], currency: e.target.value } }))}
                              style={{ fontSize: 11, padding: '2px 4px', border: '1px solid #cbd5e1', borderRadius: 4, background: '#fff', cursor: 'pointer' }}>
                              {['THB','EUR','USD','JPY','CNY','GBP'].map((c) => <option key={c}>{c}</option>)}
                            </select>
                            <select
                              value={draftFactoryCurr[factory]?.unit ?? 'piece'}
                              onChange={(e) => setDraftFactoryCurr((p) => ({ ...p, [factory]: { ...p[factory], unit: e.target.value } }))}
                              style={{ fontSize: 11, padding: '2px 4px', border: '1px solid #cbd5e1', borderRadius: 4, background: '#fff', cursor: 'pointer' }}>
                              <option value="piece">แผ่น</option>
                              <option value="sqm">ตร.ม.</option>
                              <option value="box">กล่อง</option>
                            </select>
                          </div>
                          <button type="button" className="secondary-button"
                            style={{ marginLeft: 'auto', fontSize: 11, padding: '2px 10px' }}
                            onClick={() => setEmailDraft(isDraftOpen ? null : buildEmailDraft(factory, groupItems))}>
                            <Icon name="fileText" size={12} />
                            {isDraftOpen ? 'ปิดร่างอีเมล' : 'ร่างอีเมล'}
                          </button>
                        </div>

                        {/* Email draft panel */}
                        {isDraftOpen && emailDraft && (
                          <div style={{ margin: '0 18px 8px', padding: '12px', border: '1px solid #bfdbfe', borderRadius: 8, background: '#eff6ff', fontSize: 13 }}>
                            <p style={{ margin: '0 0 8px', fontWeight: 700, fontSize: 12, color: '#1d4ed8' }}>ร่างอีเมลถึง {factory}</p>
                            <label style={{ display: 'block', marginBottom: 6 }}>
                              <span style={{ fontSize: 11, color: '#64748b' }}>ถึง (To)</span>
                              <input value={emailDraft.to} onChange={(e) => setEmailDraft((d) => ({ ...d, to: e.target.value }))} style={{ marginTop: 2 }} />
                            </label>
                            <label style={{ display: 'block', marginBottom: 6 }}>
                              <span style={{ fontSize: 11, color: '#64748b' }}>หัวข้อ (Subject)</span>
                              <input value={emailDraft.subject} onChange={(e) => setEmailDraft((d) => ({ ...d, subject: e.target.value }))} style={{ marginTop: 2 }} />
                            </label>
                            <label style={{ display: 'block', marginBottom: 8 }}>
                              <span style={{ fontSize: 11, color: '#64748b' }}>เนื้อหา</span>
                              <textarea rows={8} value={emailDraft.body} onChange={(e) => setEmailDraft((d) => ({ ...d, body: e.target.value }))} style={{ marginTop: 2, fontFamily: 'monospace', fontSize: 12 }} />
                            </label>
                            <div style={{ display: 'flex', gap: 8 }}>
                              <button type="button" className="primary-button" disabled={emailSending || !emailDraft.to} onClick={sendFactoryEmail} style={{ fontSize: 12 }}>
                                {emailSending ? 'กำลังส่ง...' : 'ส่งอีเมล'}
                              </button>
                              <button type="button" className="secondary-button" onClick={() => setEmailDraft(null)} style={{ fontSize: 12 }}>ปิด</button>
                            </div>
                          </div>
                        )}

                        {/* Items */}
                        {groupItems.map((item, i) => {
                          const selectedCurr = draftFactoryCurr[factory] ?? {};
                          const currLabel = selectedCurr.currency ?? 'THB';
                          const unitLabel = selectedCurr.unit === 'sqm' ? 'ตร.ม.' : selectedCurr.unit === 'box' ? 'กล่อง' : 'แผ่น';
                          return (
                            <div key={item.id ?? i} className="ticket-items-table table-row" style={{ gridTemplateColumns: itemsGridCols }}>
                              <span>
                                <strong>{item.brand}</strong>
                                {item.model && <small style={{ color: '#64748b' }}>{item.model}</small>}
                              </span>
                              <span style={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
                                {item.color && <span>{item.color}</span>}
                                {item.texture && <small style={{ color: '#64748b' }}>{item.texture}</small>}
                                {item.size && <small style={{ color: '#94a3b8' }}>{item.size}</small>}
                              </span>
                              <span>
                                {item.qty} แผ่น
                                {item.qtySqm != null && <small style={{ display: 'block', color: '#94a3b8' }}>{Number(item.qtySqm).toFixed(2)} ตร.ม.</small>}
                              </span>
                              <div style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
                                <input type="number" min="0" step="0.0001"
                                  value={draftRaw[item.id] ?? ''}
                                  onChange={(e) => setDraftRaw((prev) => ({ ...prev, [item.id]: e.target.value }))}
                                  placeholder={`ราคา/${unitLabel}`}
                                  style={{ width: 110, padding: '4px 8px', border: '1px solid #93c5fd', borderRadius: 4, fontSize: 13 }} />
                                <span style={{ fontSize: 11, color: '#2563eb', whiteSpace: 'nowrap' }}>{currLabel}/{unitLabel}</span>
                              </div>
                              <code>{formatMoney(item.approvedPrice)}</code>
                            </div>
                          );
                        })}
                      </div>
                    );
                  })
                ) : items.map((item, i) => (
                  <div key={item.id ?? i} className="ticket-items-table table-row" style={{ gridTemplateColumns: itemsGridCols }}>
                    <span>
                      <strong>{item.brand}</strong>
                      {item.model && <small style={{ color: '#64748b' }}>{item.model}</small>}
                      {item.factory && <small style={{ color: '#94a3b8', fontSize: 11 }}>{item.factory}</small>}
                    </span>
                    <span style={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
                      {item.color && <span>{item.color}</span>}
                      {item.texture && <small style={{ color: '#64748b' }}>{item.texture}</small>}
                      {item.size && <small style={{ color: '#94a3b8' }}>{item.size}</small>}
                    </span>
                    <span>
                      {item.qty} แผ่น
                      {item.qtySqm != null && <small style={{ display: 'block', color: '#94a3b8' }}>{Number(item.qtySqm).toFixed(2)} ตร.ม.</small>}
                    </span>
                    {showCalcBreakdown ? (
                      <>
                        <span style={{ fontSize: 12 }}>
                          {item.rawPrice != null
                            ? <><strong>{Number(item.rawPrice).toLocaleString('th-TH', { minimumFractionDigits: 2 })}</strong><small style={{ color: '#94a3b8' }}> {item.rawCurrency}/{item.rawUnit === 'sqm' ? 'ตร.ม.' : 'แผ่น'}</small></>
                            : <span style={{ color: '#94a3b8' }}>-</span>}
                          {item.calcConfigVersion && <small style={{ display: 'block', color: '#94a3b8', fontSize: 10 }}>config v{item.calcConfigVersion}</small>}
                        </span>
                        <code style={{ color: '#0369a1' }}>{item.calcedCost != null ? formatMoney(item.calcedCost) : '—'}</code>
                        <code style={{ color: '#059669', fontWeight: 700 }}>{item.calcedPrice != null ? formatMoney(item.calcedPrice) : '—'}</code>
                      </>
                    ) : (
                      <>
                        {showProposed && <code>{formatMoney(item.proposedPrice)}</code>}
                        <code>{formatMoney(item.approvedPrice)}</code>
                      </>
                    )}
                  </div>
                ))}

                {proposeMode && (
                  <div style={{ padding: '12px 18px', display: 'flex', flexDirection: 'column', gap: 8, borderTop: '1px solid #e6eaf0' }}>
                    <label style={{ fontSize: 13 }}>
                      หมายเหตุราคา
                      <input value={proposeNote} onChange={(e) => setProposeNote(e.target.value)}
                        placeholder="ข้อมูลเพิ่มเติมเกี่ยวกับราคา (ถ้ามี)" style={{ marginTop: 4 }} />
                    </label>
                    <div style={{ display: 'flex', gap: 8 }}>
                      <button type="button" className="primary-button" onClick={handleProposePrice} disabled={actionLoading}>
                        ยืนยันราคาเสนอ
                      </button>
                      <button type="button" className="secondary-button"
                        onClick={() => { setProposeMode(false); setDraftRaw({}); setDraftFactoryCurr({}); setProposeNote(''); setEmailDraft(null); }} disabled={actionLoading}>
                        ยกเลิก
                      </button>
                    </div>
                  </div>
                )}
              </>
            )}
          </section>

          {/* R5: Attachments */}
          <section className="panel">
            <div className="panel-header" style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
              <h2>ไฟล์แนบ (PO / ใบเซ็น)</h2>
              {!TERMINAL.includes(st) && (
                <label style={{ cursor: 'pointer' }}>
                  <input type="file" style={{ display: 'none' }} onChange={handleUploadAttachment}
                    accept=".pdf,.doc,.docx,.xls,.xlsx,.png,.jpg,.jpeg" />
                  <span className="secondary-button" style={{ fontSize: 12, padding: '4px 10px', display: 'inline-flex', alignItems: 'center', gap: 4 }}>
                    <Icon name="upload" size={13} />
                    {uploadingFile ? 'กำลังอัปโหลด...' : 'แนบไฟล์'}
                  </span>
                </label>
              )}
            </div>
            {attachLoading ? (
              <p style={{ padding: '12px 18px', color: '#94a3b8', fontSize: 13 }}>กำลังโหลด...</p>
            ) : attachments.length === 0 ? (
              <p style={{ padding: '12px 18px', color: '#94a3b8', fontSize: 13 }}>ยังไม่มีไฟล์แนบ</p>
            ) : (
              <div style={{ padding: '8px 18px', display: 'flex', flexDirection: 'column', gap: 6 }}>
                {attachments.map((att) => (
                  <div key={att.id} style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '6px 10px', background: '#f8fafc', borderRadius: 6, border: '1px solid #e2e8f0' }}>
                    <Icon name="paperclip" size={13} style={{ color: '#64748b', flexShrink: 0 }} />
                    <span style={{ flex: 1, fontSize: 13, color: '#0f172a', wordBreak: 'break-all' }}>{att.fileName}</span>
                    <span style={{ fontSize: 11, color: '#94a3b8', whiteSpace: 'nowrap', background: '#f1f5f9', padding: '1px 6px', borderRadius: 99 }}>
                      {att.attachType}
                    </span>
                    <a href={api.attachments.fileUrl(att.id)} target="_blank" rel="noreferrer"
                      style={{ fontSize: 12, color: '#2563eb', textDecoration: 'none', whiteSpace: 'nowrap' }}>
                      ดูไฟล์
                    </a>
                    {!TERMINAL.includes(st) && (
                      <button type="button" className="icon-button"
                        style={{ color: '#ef4444', flexShrink: 0 }}
                        onClick={() => handleDeleteAttachment(att.id, att.fileName)}>
                        <Icon name="close" size={13} />
                      </button>
                    )}
                  </div>
                ))}
              </div>
            )}
          </section>

          {(quotations && quotations.length > 0) && (
            <section className="panel">
              <div className="panel-header">
                <h2>ใบเสนอราคา</h2>
              </div>
              {quotations.map((q) => (
                <div key={q.id} style={{ padding: '10px 18px', borderBottom: '1px solid #f1f5f9', display: 'flex', alignItems: 'flex-start', gap: 10 }}>
                  <div style={{ flexShrink: 0, marginTop: 2 }}>
                    <span style={{
                      fontSize: 11, fontWeight: 700, borderRadius: 4, padding: '2px 7px',
                      background: q.docStatus === 'SUPERSEDED' ? '#f1f5f9' : q.docStatus === 'ISSUED' ? '#dcfce7' : '#eff6ff',
                      color: q.docStatus === 'SUPERSEDED' ? '#94a3b8' : q.docStatus === 'ISSUED' ? '#16a34a' : '#2563eb',
                    }}>Rev {q.quotationVersion}</span>
                  </div>
                  <div style={{ flex: 1, minWidth: 0 }}>
                    <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
                      <span style={{ fontWeight: 600, fontSize: 13 }}>{q.number}</span>
                      <span style={{
                        fontSize: 10, borderRadius: 3, padding: '1px 5px', fontWeight: 600,
                        background: q.docStatus === 'SUPERSEDED' ? '#f1f5f9' : q.docStatus === 'ISSUED' ? '#dcfce7' : '#eff6ff',
                        color: q.docStatus === 'SUPERSEDED' ? '#94a3b8' : q.docStatus === 'ISSUED' ? '#16a34a' : '#3b82f6',
                      }}>{q.docStatus}</span>
                    </div>
                    <div style={{ fontSize: 12, color: '#475569', marginTop: 2 }}>
                      ยอดรวม {formatMoney(q.totalAmount)} · ออกโดย {q.issuedByName} · {formatThaiDate(q.issuedAt)}
                    </div>
                  </div>
                </div>
              ))}
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
            ) : [...events].reverse().map((event) => {
              let snapItems = null;
              if (event.kind === 'PRICE_PROPOSED' && event.itemSnapshot) {
                try { snapItems = JSON.parse(event.itemSnapshot); } catch { snapItems = null; }
              }
              return (
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
                    {snapItems && snapItems.length > 0 && (
                      <div style={{ margin: '6px 0 0', fontSize: 11, color: '#475569', background: '#f8fafc', borderRadius: 4, padding: '6px 10px', borderLeft: '3px solid #94a3b8' }}>
                        <div style={{ fontWeight: 600, marginBottom: 4, color: '#64748b' }}>รายการสินค้า ณ เวลาที่เสนอราคา</div>
                        {snapItems.map((it, i) => (
                          <div key={i} style={{ paddingBottom: 2 }}>
                            {it.brand} {it.model} — {it.qty} ชิ้น
                            {it.rawPrice != null && (
                              <span style={{ color: '#94a3b8', marginLeft: 4 }}>
                                @ {it.rawPrice} {it.rawCurrency}/{it.rawUnit}
                              </span>
                            )}
                          </div>
                        ))}
                      </div>
                    )}
                    <small style={{ color: '#94a3b8', fontSize: 11 }}>{formatThaiDate(event.createdAt)}</small>
                  </div>
                </div>
              );
            })}
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
