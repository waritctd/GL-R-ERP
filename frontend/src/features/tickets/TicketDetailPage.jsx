import { useEffect, useState } from 'react';
import { api, ROLE_PERMISSIONS } from '../../api/index.js';
import { Breadcrumbs } from '../../components/common/Breadcrumbs.jsx';
import { ConfirmDialog } from '../../components/common/ConfirmDialog.jsx';
import { EmptyState } from '../../components/common/EmptyState.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { InfoTip } from '../../components/common/InfoTip.jsx';
import { Skeleton, SkeletonText } from '../../components/common/Skeleton.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { formatMoney, formatThaiDate, ticketStatusLabel } from '../../utils/format.js';
import { cn } from '../../utils/cn.js';

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
    <div className="flex gap-2 py-[6px] border-b border-surface-subtle text-[13px]">
      <span className="text-text-muted min-w-[120px]">{label}</span>
      <span className="font-semibold text-text">{value || '-'}</span>
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
  const [deletingAttachment, setDeletingAttachment] = useState(false);

  // Revision form
  const [showReviseForm, setShowReviseForm] = useState(false);
  const [reviseScope, setReviseScope] = useState('QTY_OR_NOTE');
  const [reviseReason, setReviseReason] = useState('');

  // Comment
  const [commentText, setCommentText] = useState('');

  // Confirmation dialogs (state-driven, replaces native browser confirm)
  const [confirm, setConfirm] = useState(null); // { kind: 'deleteAttachment', id, name } | { kind: 'cancelTicket' } | null

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
      <div className="page-stack" aria-busy="true" aria-label="กำลังโหลดข้อมูลใบขอราคา">
        <div className="flex items-start gap-4 justify-between">
          <div className="flex-1">
            <Skeleton width={80} height={28} radius="var(--radius-md)" className="skeleton" />
            <div className="mt-3">
              <Skeleton width="40%" height={22} />
            </div>
            <div className="mt-2">
              <Skeleton width={220} height={16} />
            </div>
          </div>
        </div>
        <section className="panel">
          <div className="panel-header">
            <Skeleton width="30%" height={16} />
          </div>
          <div className="py-[14px] px-[18px]">
            <SkeletonText lines={4} />
          </div>
        </section>
        <section className="table-panel">
          <div className="panel-header">
            <Skeleton width="30%" height={16} />
          </div>
          <div className="py-[14px] px-[18px]">
            <SkeletonText lines={5} />
          </div>
        </section>
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
    generateQuotation: (st === 'approved' || st === 'quotation_issued') && ROLE_PERMISSIONS.canGenerateQuotation.includes(role) && (isOwner || role === 'admin'),
    generateDocument: (st === 'approved' || st === 'quotation_issued') && ROLE_PERMISSIONS.canCreateTickets.includes(role) && (isOwner || role === 'admin'),
    revise:           (st === 'approved' || st === 'quotation_issued' || st === 'document_issued') && ROLE_PERMISSIONS.canCreateTickets.includes(role) && (isOwner || role === 'admin'),
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

  function handleDeleteAttachment(id, name) {
    setConfirm({ kind: 'deleteAttachment', id, name });
  }

  async function confirmDeleteAttachment(id) {
    setDeletingAttachment(true);
    try {
      await api.attachments.delete(id);
      setAttachments((prev) => prev.filter((a) => a.id !== id));
      showToast('success', 'ลบไฟล์แล้ว');
    } catch (err) {
      showToast('error', err.message || 'ลบไม่สำเร็จ');
    } finally {
      setDeletingAttachment(false);
      setConfirm(null);
    }
  }

  async function handleDownloadQuotation(quotationId, number, format) {
    try {
      const blob = format === 'pdf'
        ? await api.tickets.downloadQuotationPdf(ticketId, quotationId)
        : await api.tickets.downloadQuotationXlsx(ticketId, quotationId);
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = (number ?? 'quotation') + '.' + format;
      a.click();
      URL.revokeObjectURL(url);
    } catch (err) {
      showToast('error', err.message || 'ดาวน์โหลดไม่สำเร็จ');
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
      <Breadcrumbs items={[{ label: 'ใบขอราคา', onClick: onBack }, { label: summary.code || summary.customerName || summary.title }]} />
      <header className="flex items-start gap-4 justify-between">
        <div>
          <button type="button" className="secondary-button mb-3" onClick={onBack}>
            <Icon name="chevronLeft" size={14} />
            กลับ
          </button>
          <h1 className="m-0 text-[22px] font-extrabold text-text">{summary.customerName || summary.title}</h1>
          <div className="flex gap-2 items-center mt-2 flex-wrap">
            <code className="text-[13px] bg-surface-subtle py-[2px] px-2 rounded-[4px]">{summary.code}</code>
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
        <section className="panel bg-surface-muted">
          <div className="panel-header">
            <h2>การดำเนินการ</h2>
          </div>
          <div className="py-3 px-[18px] flex flex-wrap gap-[10px]">
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
              <button type="button" className={cn('secondary-button', 'bg-info-row-active text-info', 'border-[#93c5fd]')} disabled={calcLoading || actionLoading}
                onClick={handleCalculatePrices}>
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
              <button type="button" className={cn('secondary-button', 'text-danger', 'border-[#fca5a5]')} disabled={actionLoading}
                onClick={() => setShowRejectForm(true)}>
                <Icon name="close" size={14} />
                ไม่อนุมัติ
              </button>
            )}

            {can.generateQuotation && (
              <button type="button" className="secondary-button" disabled={actionLoading}
                onClick={() => doAction(() => api.tickets.quotation(ticketId), 'ออกใบเสนอราคาแล้ว')}>
                <Icon name="fileText" size={14} />
                {ticket.quotations && ticket.quotations.length > 0 ? 'ออกใบเสนอราคาใหม่ (Rev)' : 'ออกใบเสนอราคา'}
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
              <button type="button" className={cn('secondary-button', 'ml-auto text-danger', 'border-[#fca5a5]')} disabled={actionLoading}
                onClick={() => setConfirm({ kind: 'cancelTicket' })}>
                ยกเลิก
              </button>
            )}
          </div>

          {showRejectForm && (
            <div className="px-[18px] pb-[14px] flex flex-col gap-2">
              <label className="text-[13px] font-semibold">
                เหตุผลในการตีกลับ *
                <textarea rows={2} value={rejectReason} onChange={(e) => setRejectReason(e.target.value)}
                  placeholder="ระบุเหตุผล..." className="mt-1" />
              </label>
              <div className="flex gap-2">
                <button type="button" className={cn('secondary-button', 'text-danger', 'border-[#fca5a5]')} onClick={handleReject} disabled={actionLoading}>
                  ยืนยันไม่อนุมัติ
                </button>
                <button type="button" className="secondary-button" onClick={() => { setShowRejectForm(false); setRejectReason(''); }} disabled={actionLoading}>
                  ยกเลิก
                </button>
              </div>
            </div>
          )}

          {showReviseForm && (
            <div className="px-[18px] pb-[14px] flex flex-col gap-[10px] border-t border-border">
              <div className="text-[13px] font-semibold pt-3">ประเภทการแก้ไข</div>
              {[
                { value: 'QTY_OR_NOTE',  label: 'แก้จำนวน / หมายเหตุ / % มัดจำ', sub: 'ไม่ต้องอนุมัติใหม่ — ออกเอกสาร Rev ใหม่ได้เลย' },
                { value: 'PRICE_CHANGE', label: 'แก้ราคา / ส่วนลดต่อหน่วย',       sub: 'CEO ต้องอนุมัติใหม่' },
                { value: 'NEW_ITEM',     label: 'เพิ่มสินค้าใหม่',                sub: 'Import ตั้งราคา → CEO อนุมัติ' },
              ].map((opt) => (
                // eslint-disable-next-line jsx-a11y/label-has-associated-control -- label nests the radio control; its text is the dynamic opt.label
                <label key={opt.value} className="flex gap-[10px] items-start cursor-pointer text-[13px]">
                  <input type="radio" name="reviseScope" value={opt.value}
                    checked={reviseScope === opt.value}
                    onChange={() => setReviseScope(opt.value)}
                    className="mt-[3px] shrink-0" />
                  <span>
                    <strong>{opt.label}</strong>
                    <span className="block text-xs text-text-muted">{opt.sub}</span>
                  </span>
                </label>
              ))}
              <label className="text-[13px] font-semibold">
                เหตุผลการแก้ไข *
                <textarea rows={2} value={reviseReason} onChange={(e) => setReviseReason(e.target.value)}
                  placeholder="ระบุเหตุผล..." className="mt-1" />
              </label>
              <div className="flex gap-2">
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
        <div className="flex flex-col gap-4">
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
            <div className="panel-header py-[14px] px-[18px] border-b border-border flex justify-between items-center">
              <h2>รายการสินค้า ({editMode ? editDraft.length : items.length} รายการ)</h2>
            </div>

            {editMode ? (
              <div className="py-[14px] px-[18px]">
                {editDraft.map((item, index) => (
                  <div key={index} className="border border-border-subtle rounded-md py-3 px-[14px] mb-[10px] bg-surface-muted">
                    <div className="flex justify-between items-center mb-2">
                      <span className="text-xs font-bold text-text-muted">รายการที่ {index + 1}</span>
                      {editDraft.length > 1 && (
                        <button type="button" className="icon-button text-[#ef4444]" aria-label={`ลบรายการที่ ${index + 1}`}
                          onClick={() => setEditDraft((d) => d.filter((_, i) => i !== index))}>
                          <Icon name="close" size={14} />
                        </button>
                      )}
                    </div>
                    <div className="grid grid-cols-2 gap-2">
                      {[
                        { key: 'brand', label: 'ชื่อยี่ห้อ', placeholder: 'เช่น SCG, Cotto' },
                        { key: 'model', label: 'ชื่อรุ่น', placeholder: 'ชื่อรุ่น' },
                        { key: 'color', label: 'สี', placeholder: 'เช่น ขาว, เทา' },
                        { key: 'texture', label: 'เนื้อผิว', placeholder: 'เช่น ด้าน, มัน' },
                        { key: 'size', label: 'ขนาด', placeholder: 'เช่น 60x60 ซม.' },
                        { key: 'factory', label: 'โรงงาน', placeholder: 'เช่น SCG Ceramics' },
                      ].map(({ key, label, placeholder }) => (
                        <label key={key} className="m-0">
                          <span className="text-xs">{label}</span>
                          <input value={item[key] || ''} placeholder={placeholder}
                            onChange={(e) => setEditDraft((d) => d.map((r, i) => i === index ? { ...r, [key]: e.target.value } : r))} />
                        </label>
                      ))}
                      <label className="m-0">
                        <span className="text-xs">จำนวน</span>
                        <input type="number" min="1" value={item.qty || 1}
                          onChange={(e) => setEditDraft((d) => d.map((r, i) => i === index ? { ...r, qty: e.target.value } : r))} />
                      </label>
                      {ROLE_PERMISSIONS.canProposePrices.includes(role) && (
                        <label className="m-0 col-[1/-1]">
                          <span className="text-xs">ราคาที่เสนอ (บาท)</span>
                          <input type="number" min="0" step="0.01"
                            value={item.proposedPrice ?? ''}
                            placeholder="ราคา/หน่วย"
                            onChange={(e) => setEditDraft((d) => d.map((r, i) => i === index ? { ...r, proposedPrice: e.target.value === '' ? null : Number(e.target.value) } : r))} />
                        </label>
                      )}
                    </div>
                  </div>
                ))}
                <button type="button" className="secondary-button mb-3"
                  onClick={() => setEditDraft((d) => [...d, { brand: '', model: '', color: '', texture: '', size: '', qty: 1, proposedPrice: null }])}>
                  <Icon name="plus" size={14} /> เพิ่มรายการ
                </button>
                <label className="text-[13px] block mb-[10px]">
                  หมายเหตุการแก้ไข
                  <input value={editNote} onChange={(e) => setEditNote(e.target.value)} placeholder="ระบุสาเหตุที่แก้ไข (ถ้ามี)" className="mt-1" />
                </label>
                <div className="flex gap-2">
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
                        <div className="py-2 px-[18px] bg-surface-subtle text-xs font-bold text-[#1e3a5f] border-t border-border-subtle flex items-center gap-2 flex-wrap">
                          <Icon name="building" size={13} />
                          <span>{factory}</span>
                          <span className="font-normal text-text-muted">({groupItems.length} รายการ)</span>
                          {fc?.email && <span className="font-normal text-text-faint text-[11px]">· {fc.email}</span>}
                          {/* สกุลเงิน/หน่วย selector */}
                          <div className="flex items-center gap-1 ml-1">
                            <span className="font-normal text-text-muted text-[11px]">ราคาต่อ:</span>
                            <InfoTip
                              label="สกุลเงินและหน่วยนับ"
                              text="เลือกครั้งเดียวต่อโรงงาน และใช้กับทุกรายการของโรงงานนี้"
                            />
                            <select
                              value={draftFactoryCurr[factory]?.currency ?? 'THB'}
                              onChange={(e) => setDraftFactoryCurr((p) => ({ ...p, [factory]: { ...p[factory], currency: e.target.value } }))}
                              className="text-[11px] py-[2px] px-1 border border-[#cbd5e1] rounded-[4px] bg-surface cursor-pointer">
                              {['THB','EUR','USD','JPY','CNY','GBP'].map((c) => <option key={c}>{c}</option>)}
                            </select>
                            <select
                              value={draftFactoryCurr[factory]?.unit ?? 'piece'}
                              onChange={(e) => setDraftFactoryCurr((p) => ({ ...p, [factory]: { ...p[factory], unit: e.target.value } }))}
                              className="text-[11px] py-[2px] px-1 border border-[#cbd5e1] rounded-[4px] bg-surface cursor-pointer">
                              <option value="piece">แผ่น</option>
                              <option value="sqm">ตร.ม.</option>
                              <option value="box">กล่อง</option>
                            </select>
                          </div>
                          <button type="button" className="secondary-button ml-auto text-[11px] py-[2px] px-[10px]"
                            onClick={() => setEmailDraft(isDraftOpen ? null : buildEmailDraft(factory, groupItems))}>
                            <Icon name="fileText" size={12} />
                            {isDraftOpen ? 'ปิดร่างอีเมล' : 'ร่างอีเมล'}
                          </button>
                        </div>

                        {/* Email draft panel */}
                        {isDraftOpen && emailDraft && (
                          <div className="mx-[18px] mb-2 mt-0 p-3 border border-[#bfdbfe] rounded-md bg-info-row-active text-[13px]">
                            <p className="mt-0 mx-0 mb-2 font-bold text-xs text-info">ร่างอีเมลถึง {factory}</p>
                            <label className="block mb-[6px]">
                              <span className="text-[11px] text-text-muted">ถึง (To)</span>
                              <input value={emailDraft.to} onChange={(e) => setEmailDraft((d) => ({ ...d, to: e.target.value }))} className="mt-[2px]" />
                            </label>
                            <label className="block mb-[6px]">
                              <span className="text-[11px] text-text-muted">หัวข้อ (Subject)</span>
                              <input value={emailDraft.subject} onChange={(e) => setEmailDraft((d) => ({ ...d, subject: e.target.value }))} className="mt-[2px]" />
                            </label>
                            <label className="block mb-2">
                              <span className="text-[11px] text-text-muted">เนื้อหา</span>
                              <textarea rows={8} value={emailDraft.body} onChange={(e) => setEmailDraft((d) => ({ ...d, body: e.target.value }))} className="mt-[2px] font-mono text-xs" />
                            </label>
                            <div className="flex gap-2">
                              <button type="button" className="primary-button text-xs" disabled={emailSending || !emailDraft.to} onClick={sendFactoryEmail}>
                                {emailSending ? 'กำลังส่ง...' : 'ส่งอีเมล'}
                              </button>
                              <button type="button" className="secondary-button text-xs" onClick={() => setEmailDraft(null)}>ปิด</button>
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
                                {item.model && <small className="text-text-muted">{item.model}</small>}
                              </span>
                              <span className="flex flex-col gap-[2px]">
                                {item.color && <span>{item.color}</span>}
                                {item.texture && <small className="text-text-muted">{item.texture}</small>}
                                {item.size && <small className="text-text-faint">{item.size}</small>}
                              </span>
                              <span>
                                {item.qty} แผ่น
                                {item.qtySqm != null && <small className="block text-text-faint">{Number(item.qtySqm).toFixed(2)} ตร.ม.</small>}
                              </span>
                              <div className="flex items-center gap-1">
                                <input type="number" min="0" step="0.0001"
                                  value={draftRaw[item.id] ?? ''}
                                  onChange={(e) => setDraftRaw((prev) => ({ ...prev, [item.id]: e.target.value }))}
                                  placeholder={`ราคา/${unitLabel}`}
                                  title="ราคาต่อหน่วยของรายการนี้เท่านั้น (สกุลเงิน/หน่วยนับใช้ค่าที่ตั้งไว้ของโรงงาน)"
                                  className="w-[110px] py-1 px-2 border border-[#93c5fd] rounded-[4px] text-[13px]" />
                                <span className="text-[11px] text-indigo-ring whitespace-nowrap">{currLabel}/{unitLabel}</span>
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
                      {item.model && <small className="text-text-muted">{item.model}</small>}
                      {item.factory && <small className="text-text-faint text-[11px]">{item.factory}</small>}
                    </span>
                    <span className="flex flex-col gap-[2px]">
                      {item.color && <span>{item.color}</span>}
                      {item.texture && <small className="text-text-muted">{item.texture}</small>}
                      {item.size && <small className="text-text-faint">{item.size}</small>}
                    </span>
                    <span>
                      {item.qty} แผ่น
                      {item.qtySqm != null && <small className="block text-text-faint">{Number(item.qtySqm).toFixed(2)} ตร.ม.</small>}
                    </span>
                    {showCalcBreakdown ? (
                      <>
                        <span className="text-xs">
                          {item.rawPrice != null
                            ? <><strong>{Number(item.rawPrice).toLocaleString('th-TH', { minimumFractionDigits: 2 })}</strong><small className="text-text-faint"> {item.rawCurrency}/{item.rawUnit === 'sqm' ? 'ตร.ม.' : 'แผ่น'}</small></>
                            : <span className="text-text-faint">-</span>}
                          {item.calcConfigVersion && <small className="block text-text-faint text-[10px]">config v{item.calcConfigVersion}</small>}
                        </span>
                        <code className="text-[#0369a1]">{item.calcedCost != null ? formatMoney(item.calcedCost) : '—'}</code>
                        <code className="text-success font-bold">{item.calcedPrice != null ? formatMoney(item.calcedPrice) : '—'}</code>
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
                  <div className="py-3 px-[18px] flex flex-col gap-2 border-t border-border">
                    <label className="text-[13px]">
                      หมายเหตุราคา
                      <input value={proposeNote} onChange={(e) => setProposeNote(e.target.value)}
                        placeholder="ข้อมูลเพิ่มเติมเกี่ยวกับราคา (ถ้ามี)" className="mt-1" />
                    </label>
                    <div className="flex gap-2">
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
            <div className="panel-header flex justify-between items-center">
              <h2>ไฟล์แนบ (PO / ใบเซ็น)</h2>
              {!TERMINAL.includes(st) && (
                <label className="cursor-pointer">
                  <input type="file" className="hidden" onChange={handleUploadAttachment}
                    accept=".pdf,.doc,.docx,.xls,.xlsx,.png,.jpg,.jpeg" />
                  <span className="secondary-button text-xs py-1 px-[10px] inline-flex items-center gap-1">
                    <Icon name="upload" size={13} />
                    {uploadingFile ? 'กำลังอัปโหลด...' : 'แนบไฟล์'}
                  </span>
                </label>
              )}
            </div>
            {attachLoading ? (
              <div
                className="py-2 px-[18px] flex flex-col gap-[6px]"
                aria-busy="true"
                aria-label="กำลังโหลดไฟล์แนบ"
              >
                {[0, 1, 2].map((i) => (
                  <div key={i} className="flex items-center gap-2 py-[6px] px-[10px] bg-surface-muted rounded-[6px] border border-border-subtle">
                    <Skeleton width={13} height={13} radius="var(--radius-sm)" />
                    <Skeleton width="50%" height={13} />
                    <Skeleton width={40} height={16} radius="var(--radius-pill)" />
                  </div>
                ))}
              </div>
            ) : attachments.length === 0 ? (
              <p className="py-3 px-[18px] text-text-faint text-[13px]">ยังไม่มีไฟล์แนบ</p>
            ) : (
              <div className="py-2 px-[18px] flex flex-col gap-[6px]">
                {attachments.map((att) => (
                  <div key={att.id} className="flex items-center gap-2 py-[6px] px-[10px] bg-surface-muted rounded-[6px] border border-border-subtle">
                    <Icon name="paperclip" size={13} className="text-text-muted shrink-0" />
                    <span className="flex-1 text-[13px] text-text break-all">{att.fileName}</span>
                    <span className="text-[11px] text-text-faint whitespace-nowrap bg-surface-subtle py-[1px] px-[6px] rounded-full">
                      {att.attachType}
                    </span>
                    <a href={api.attachments.fileUrl(att.id)} target="_blank" rel="noreferrer"
                      className="text-xs text-indigo-ring no-underline whitespace-nowrap">
                      ดูไฟล์
                    </a>
                    {!TERMINAL.includes(st) && (
                      <button type="button" className="icon-button text-[#ef4444] shrink-0"
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
                <div key={q.id} className="py-[10px] px-[18px] border-b border-surface-subtle flex items-start gap-[10px]">
                  <div className="shrink-0 mt-[2px]">
                    <span className={cn(
                      'text-[11px] font-bold rounded-[4px] py-[2px] px-[7px]',
                      q.docStatus === 'SUPERSEDED' ? 'bg-surface-subtle text-text-faint'
                        : q.docStatus === 'ISSUED' ? 'bg-success-bg text-[#16a34a]'
                        : 'bg-info-row-active text-indigo-ring',
                    )}>Rev {q.quotationVersion}</span>
                  </div>
                  <div className="flex-1 min-w-0">
                    <div className="flex gap-2 items-center">
                      <span className="font-semibold text-[13px]">{q.number}</span>
                      <span className={cn(
                        'text-[10px] rounded-[3px] py-[1px] px-[5px] font-semibold',
                        q.docStatus === 'SUPERSEDED' ? 'bg-surface-subtle text-text-faint'
                          : q.docStatus === 'ISSUED' ? 'bg-success-bg text-[#16a34a]'
                          : 'bg-info-row-active text-[#3b82f6]',
                      )}>{q.docStatus}</span>
                    </div>
                    <div className="text-xs text-text-secondary mt-[2px]">
                      ยอดรวม {formatMoney(q.totalAmount)} · ออกโดย {q.issuedByName} · {formatThaiDate(q.issuedAt)}
                    </div>
                  </div>
                  <div className="flex gap-[6px] shrink-0">
                    <button type="button" className="secondary-button text-xs py-1 px-[10px]"
                      onClick={() => handleDownloadQuotation(q.id, q.number, 'xlsx')}>
                      <Icon name="fileText" size={12} /> Excel
                    </button>
                    <button type="button" className="secondary-button text-xs py-1 px-[10px]"
                      onClick={() => handleDownloadQuotation(q.id, q.number, 'pdf')}>
                      <Icon name="fileText" size={12} /> PDF
                    </button>
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
              <p className="text-text-faint text-[13px]">ยังไม่มีประวัติ</p>
            ) : [...events].reverse().map((event) => {
              let snapItems = null;
              if (event.kind === 'PRICE_PROPOSED' && event.itemSnapshot) {
                try { snapItems = JSON.parse(event.itemSnapshot); } catch { snapItems = null; }
              }
              return (
                <div key={event.id} className="ticket-event">
                  <span className={eventDotClass(event.kind)} />
                  <div className="flex-1 min-w-0">
                    <strong className="block text-[13px] text-text">
                      {EVENT_KIND_LABEL[event.kind] ?? event.kind}
                    </strong>
                    <span className="text-text-secondary text-xs">{event.actorName}</span>
                    {event.message && (
                      <p className="mt-1 mx-0 mb-0 text-xs text-text-muted bg-surface-muted rounded-[4px] py-1 px-2">
                        {event.message}
                      </p>
                    )}
                    {snapItems && snapItems.length > 0 && (
                      <div className="mt-[6px] mx-0 mb-0 text-[11px] text-text-secondary bg-surface-muted rounded-[4px] py-[6px] px-[10px] border-l-[3px] border-l-text-faint">
                        <div className="font-semibold mb-1 text-text-muted">รายการสินค้า ณ เวลาที่เสนอราคา</div>
                        {snapItems.map((it, i) => (
                          <div key={i} className="pb-[2px]">
                            {it.brand} {it.model} — {it.qty} ชิ้น
                            {it.rawPrice != null && (
                              <span className="text-text-faint ml-1">
                                @ {it.rawPrice} {it.rawCurrency}/{it.rawUnit}
                              </span>
                            )}
                          </div>
                        ))}
                      </div>
                    )}
                    <small className="text-text-faint text-[11px]">{formatThaiDate(event.createdAt)}</small>
                  </div>
                </div>
              );
            })}
          </div>

          {can.comment && (
            <div className="py-3 px-[18px] border-t border-border flex flex-col gap-2">
              <textarea
                rows={2}
                value={commentText}
                onChange={(e) => setCommentText(e.target.value)}
                placeholder="เพิ่มความคิดเห็น..."
                className="resize-y"
              />
              <button type="button" className="secondary-button self-end" onClick={handleComment} disabled={actionLoading || !commentText.trim()}>
                ส่งความคิดเห็น
              </button>
            </div>
          )}
        </section>
      </div>

      <ConfirmDialog
        open={confirm?.kind === 'deleteAttachment'}
        tone="danger"
        title="ลบไฟล์"
        message={`ลบไฟล์ "${confirm?.name}" ออก?`}
        busy={deletingAttachment}
        onCancel={() => setConfirm(null)}
        onConfirm={() => confirmDeleteAttachment(confirm?.id)}
      />

      <ConfirmDialog
        open={confirm?.kind === 'cancelTicket'}
        tone="danger"
        title="ยกเลิกใบขอราคา"
        message="ยืนยันการยกเลิกใบขอราคานี้?"
        busy={actionLoading}
        onCancel={() => setConfirm(null)}
        onConfirm={async () => {
          await doAction(() => api.tickets.cancel(ticketId), 'ยกเลิกใบขอราคาแล้ว');
          setConfirm(null);
        }}
      />
    </div>
  );
}
