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

const EVENT_KIND_LABEL = {
  CREATED:            'สร้างใบขอราคา',
  SUBMITTED:          'ส่งเรื่องเข้าระบบ',
  PICKED_UP:          'รับมอบหมาย',
  PRICE_PROPOSED:     'เสนอราคาสินค้า',
  APPROVED:           'อนุมัติ',
  REJECTED:           'ปฏิเสธ',
  DOCUMENT_ISSUED:    'ออกใบแจ้งยอดมัดจำ',
  PRICE_REVISED:      'แก้ไขราคาที่เสนอ',
  REVISION_REQUESTED: 'ขอแก้ไข',
  CLOSED:             'ปิดเรื่อง',
  CANCELLED:          'ยกเลิก',
  EDITED:             'แก้ไขรายการสินค้า',
  COMMENTED:          'ความคิดเห็น',
  COMMENT:            'ความคิดเห็น',
  PRICE_OVERRIDDEN:   'แก้ไขราคาด้วยตนเอง (CEO)',
};

const TERMINAL = ['closed', 'cancelled'];

// Quotation revision docStatus (DRAFT / ISSUED / SUPERSEDED) mapped onto the same
// success/info/neutral tokens StatusBadge uses, instead of one-off hex per state.
// Previously SUPERSEDED text used Ink Faint (#94a3b8), below the DESIGN.md Ink Muted
// contrast floor on a light background — this switches it to --color-icon-muted.
function docStatusColors(docStatus) {
  if (docStatus === 'SUPERSEDED') {
    return { background: 'var(--color-surface-subtle)', color: 'var(--color-icon-muted)' };
  }
  if (docStatus === 'ISSUED') {
    return { background: 'var(--color-success-bg)', color: 'var(--color-success-dark)' };
  }
  return { background: 'var(--color-info-bg)', color: 'var(--color-info)' };
}

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

  // D7/D9/D10: CEO price calculation + breakdown + override
  const [calcLoading, setCalcLoading] = useState(false);
  const [priceBreakdown, setPriceBreakdown] = useState([]); // PriceBreakdownItemDto[]
  const [showBreakdown, setShowBreakdown] = useState(false);
  const [overrideDraft, setOverrideDraft] = useState({}); // itemId → { price: string, reason: string }
  const [overrideLoading, setOverrideLoading] = useState({});

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
        <div style={{ display: 'flex', alignItems: 'flex-start', gap: 16, justifyContent: 'space-between' }}>
          <div style={{ flex: 1 }}>
            <Skeleton width={80} height={28} radius="var(--radius-md)" className="skeleton" />
            <div style={{ marginTop: 12 }}>
              <Skeleton width="40%" height={22} />
            </div>
            <div style={{ marginTop: 8 }}>
              <Skeleton width={220} height={16} />
            </div>
          </div>
        </div>
        <section className="panel">
          <div className="panel-header">
            <Skeleton width="30%" height={16} />
          </div>
          <div style={{ padding: '14px 18px' }}>
            <SkeletonText lines={4} />
          </div>
        </section>
        <section className="table-panel">
          <div className="panel-header">
            <Skeleton width="30%" height={16} />
          </div>
          <div style={{ padding: '14px 18px' }}>
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
  // ข้อ 10.1: Import sees only rawPrice + proposedPrice — NOT approvedPrice or CEO-set prices
  const showApproved = ROLE_PERMISSIONS.canApproveReject.includes(role) || ROLE_PERMISSIONS.canCreateTickets.includes(role);
  const showCalcBreakdown = ROLE_PERMISSIONS.canApproveReject.includes(role) && items.some((it) => it.calcedCost != null);
  const itemsGridCols = showCalcBreakdown
    ? 'minmax(0,1.4fr) minmax(0,1fr) minmax(0,0.5fr) minmax(0,0.9fr) minmax(0,0.9fr) minmax(0,0.9fr)'
    : showProposed
      ? 'minmax(0,1.8fr) minmax(0,1.2fr) minmax(0,0.6fr) minmax(0,1.1fr) minmax(0,1.1fr)'
      : 'minmax(0,1.8fr) minmax(0,1.2fr) minmax(0,0.6fr) minmax(0,1.1fr)';

  const ps = summary.paymentStatus;
  const fs = summary.fulfillmentStatus;
  const isSales   = ROLE_PERMISSIONS.canCreateTickets.includes(role);
  const isImport  = ROLE_PERMISSIONS.canPickupTickets.includes(role);
  const isAccount = ROLE_PERMISSIONS.canConfirmPayments.includes(role);
  const dualTrackDone = ps === 'FULLY_PAID' && fs === 'GOODS_RECEIVED';

  const EDITABLE_STATUSES = ['submitted', 'in_review', 'price_proposed'];
  const can = {
    pickup:            st === 'submitted'       && ROLE_PERMISSIONS.canPickupTickets.includes(role),
    propose:           ['in_review', 'price_proposed', 'approved'].includes(st) && ROLE_PERMISSIONS.canProposePrices.includes(role),
    calculatePrices:   st === 'price_proposed'  && ROLE_PERMISSIONS.canApproveReject.includes(role),
    overridePrice:     st === 'price_proposed'  && ROLE_PERMISSIONS.canApproveReject.includes(role),
    approve:           st === 'price_proposed'  && ROLE_PERMISSIONS.canApproveReject.includes(role),
    reject:            st === 'price_proposed'  && ROLE_PERMISSIONS.canApproveReject.includes(role),
    generateQuotation: (st === 'approved' || st === 'quotation_issued') && ROLE_PERMISSIONS.canGenerateQuotation.includes(role) && isOwner,
    // Issuing the deposit-notice DOCUMENT is the payment-track step (mirrors
    // DepositNoticeService.issue): customer must have confirmed first, and the
    // former no-document "ออกใบแจ้งมัดจำ" action is gone.
    generateDocument:  st === 'quotation_issued' && ps === 'CUSTOMER_CONFIRMED' && ROLE_PERMISSIONS.canCreateTickets.includes(role) && isOwner,
    revise:            (st === 'approved' || st === 'quotation_issued' || st === 'document_issued') && ROLE_PERMISSIONS.canCreateTickets.includes(role) && isOwner,
    // Legacy document_issued close only for pre-dual-track tickets (ps never set)
    // or fully paid ones — mirrors TicketService.close().
    close:            ((st === 'document_issued' && (ps == null || ps === 'FULLY_PAID'))
                        || (st === 'quotation_issued' && dualTrackDone)) && ROLE_PERMISSIONS.canCreateTickets.includes(role) && isOwner,
    cancel:           !TERMINAL.includes(st)   && isOwner,
    comment:          !TERMINAL.includes(st),
    editItems: EDITABLE_STATUSES.includes(st) && ROLE_PERMISSIONS.canCreateTickets.includes(role) && isOwner,
    // Dual-track (ข้อ 13)
    confirmCustomer:    st === 'quotation_issued' && ps == null && isSales,
    // Money receipts are confirmed by ฝ่ายบัญชี (account role, CEO fallback) —
    // mirrors TicketService.ACCOUNT_ROLES.
    confirmDepositPaid: st === 'quotation_issued' && ps === 'DEPOSIT_NOTICE_ISSUED' && isAccount,
    // DEPOSIT_PAID also qualifies: accounting may confirm the deposit before
    // import gets to the IR (mirrors TicketService.issueImportRequest).
    issueImportRequest: st === 'quotation_issued' && (ps === 'DEPOSIT_NOTICE_ISSUED' || ps === 'DEPOSIT_PAID') && fs == null && isImport,
    markIrSent:         st === 'quotation_issued' && fs === 'IR_ISSUED' && isImport,
    markShipping:       st === 'quotation_issued' && fs === 'IR_SENT' && isImport,
    markGoodsReceived:  st === 'quotation_issued' && fs === 'SHIPPING' && isImport,
    confirmFinalPayment:st === 'quotation_issued' && ps === 'AWAITING_FINAL_PAYMENT' && isAccount,
    downloadRemainingInvoice: st === 'quotation_issued' && fs === 'GOODS_RECEIVED' && isSales,
  };

  const hasActions = Object.values(can).some(Boolean);

  const status = ticketStatusLabel(st);

  // Next-action summary for the current viewer only — derived strictly from the
  // `can` flags above (which already encode real status+role permission checks).
  // Verified against backend/mock transition handlers (see api.tickets.approve/
  // reject/pickup/... in src/api/mockApi.js) so the wording matches what the
  // button actually does. Never invents an owner or action the data can't support.
  const NEXT_ACTION_STEPS = [
    ['reject',           st === 'price_proposed' ? 'ตรวจสอบราคาที่เสนอ แล้วอนุมัติหรือตีกลับให้ Import แก้ไข' : null],
    ['approve',          st === 'price_proposed' ? 'ตรวจสอบราคาที่เสนอ แล้วอนุมัติหรือตีกลับให้ Import แก้ไข' : null],
    ['pickup',           'รับมอบหมายใบขอราคานี้เพื่อเริ่มเสนอราคา'],
    ['propose',          st === 'approved' ? 'แก้ไขราคาที่เสนอ (ต้องอนุมัติใหม่)' : 'เสนอราคาสินค้าให้ลูกค้า'],
    // Dual-track steps come BEFORE generateQuotation: re-issuing a quotation is
    // always available at quotation_issued, so listing it first used to mask the
    // real next step (e.g. "ยืนยันลูกค้า") behind a generic re-issue suggestion.
    ['confirmCustomer',  'ยืนยันว่าลูกค้าตกลงคำสั่งซื้อแล้ว'],
    ['generateDocument', 'ออกใบแจ้งยอดมัดจำให้ลูกค้า (เริ่มขั้นตอนชำระเงิน)'],
    ['confirmDepositPaid','ยืนยันว่าลูกค้าชำระมัดจำแล้ว'],
    ['issueImportRequest','ออก Import Request (IR) ให้โรงงาน'],
    ['markIrSent',        'บันทึกว่าส่ง IR ให้โรงงานแล้ว'],
    ['markShipping',      'บันทึกว่าสินค้าออกเดินทางแล้ว'],
    ['markGoodsReceived', 'บันทึกว่ารับสินค้าแล้ว'],
    ['confirmFinalPayment','ยืนยันว่าลูกค้าชำระส่วนที่เหลือครบแล้ว'],
    ['generateQuotation','ออกใบเสนอราคาให้ลูกค้า'],
    ['revise',            'ขอแก้ไขรายละเอียดใบขอราคานี้ได้หากจำเป็น'],
    ['close',             'ปิดเรื่องนี้เมื่อดำเนินการครบถ้วนแล้ว'],
  ];
  const nextAction = NEXT_ACTION_STEPS.find(([key, text]) => can[key] && text)?.[1] ?? null;

  // Passive hint when the payment track waits on ฝ่ายบัญชี — money-receipt
  // confirmations belong to the account role (CEO fallback), so sales/import
  // would otherwise see a stalled payment stepper with no explanation. Shown
  // alongside the personal next-action callout, not instead of it.
  const waitingHint = (st === 'quotation_issued' && !isAccount)
    ? (ps === 'DEPOSIT_NOTICE_ISSUED' ? 'รอฝ่ายบัญชียืนยันรับยอดมัดจำ'
      : ps === 'AWAITING_FINAL_PAYMENT' ? 'รอฝ่ายบัญชียืนยันรับชำระส่วนที่เหลือ'
      : null)
    : null;

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
    const today = new Date().toLocaleDateString('th-TH', { year: 'numeric', month: 'long', day: 'numeric' });
    const itemLines = groupItems.map((item, i) => {
      const qtyDisplay = item.unitBasis === 'SQM' && item.qtySqm != null
        ? `${Number(item.qtySqm).toFixed(2)} ตร.ม.`
        : `${item.qty} แผ่น`;
      return `    ${i + 1}. ${item.brand} ${item.model} ${item.color} ${item.texture} ${item.size} — ${qtyDisplay}`;
    }).join('\n');
    return {
      factory,
      to: fc.email ?? '',
      subject: `ขอทราบราคาสินค้า — ${factory}`,
      body: `วันที่ ${today}\n\nเรียน ทีมขาย ${factory}\n\nด้วยบริษัท จี แอล แอนด์ อาร์ จำกัด มีความประสงค์จะขอทราบราคาสินค้าดังต่อไปนี้\n\nรายการสินค้า:\n${itemLines}\n\nกรุณาแจ้งราคาสินค้าเป็นสกุลเงิน ${currency} ต่อ ${unitLabel} พร้อมระยะเวลาจัดส่ง\n\nจึงเรียนมาเพื่อโปรดพิจารณา\n\nขอแสดงความนับถือ\nฝ่ายนำเข้า\nบริษัท จี แอล แอนด์ อาร์ จำกัด\nโทร. 02-xxx-xxxx`,
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
      setPriceBreakdown(response.breakdown ?? []);
      setShowBreakdown(true);
      showToast('success', 'คำนวณราคาเรียบร้อย — ตรวจสอบรายละเอียดสูตรด้านล่าง แล้วกดอนุมัติได้เลย');
    } catch (error) {
      showToast('error', error.message || 'คำนวณราคาไม่สำเร็จ');
    } finally {
      setCalcLoading(false);
    }
  }

  async function handleOverridePrice(itemId) {
    const draft = overrideDraft[itemId];
    if (!draft?.price || isNaN(Number(draft.price)) || Number(draft.price) <= 0) {
      showToast('error', 'กรุณากรอกราคา override ที่ถูกต้อง');
      return;
    }
    setOverrideLoading((p) => ({ ...p, [itemId]: true }));
    try {
      const res = await api.tickets.overrideItemPrice(ticketId, itemId, {
        manualPrice: Number(draft.price),
        reason: draft.reason || null,
      });
      setTicket(res.ticket);
      setOverrideDraft((p) => { const n = { ...p }; delete n[itemId]; return n; });
      showToast('success', 'บันทึกราคา override แล้ว');
    } catch (err) {
      showToast('error', err.message || 'บันทึกไม่สำเร็จ');
    } finally {
      setOverrideLoading((p) => ({ ...p, [itemId]: false }));
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
      // In mock mode the PDF handler returns text/html so browsers can render it
      const ext = blob.type.startsWith('text/html') ? 'html' : format;
      a.download = (number ?? 'quotation') + '.' + ext;
      a.click();
      URL.revokeObjectURL(url);
    } catch (err) {
      showToast('error', err.message || 'ดาวน์โหลดไม่สำเร็จ');
    }
  }

  async function handleDownloadRemainingInvoice() {
    try {
      const blob = await api.tickets.downloadRemainingInvoice(ticketId);
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `remaining-invoice-${ticketId}.xlsx`;
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
      <header style={{ display: 'flex', alignItems: 'flex-start', gap: 16, justifyContent: 'space-between' }}>
        <div style={{ minWidth: 0 }}>
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
          {/* Summary: who created it, when, and who is currently handling it — all
              fields already fetched on `summary` (no new API calls / no invented owner). */}
          <div style={{ display: 'flex', flexWrap: 'wrap', gap: '4px 16px', marginTop: 10, fontSize: 13, color: '#64748b' }}>
            <span>สร้างโดย <strong style={{ color: '#334155' }}>{summary.createdByName || '-'}</strong> · {formatThaiDate(summary.createdAt)}</span>
            {summary.assignedToName && (
              <span>เจ้าหน้าที่นำเข้าที่ดูแล <strong style={{ color: '#334155' }}>{summary.assignedToName}</strong></span>
            )}
          </div>
        </div>
        <button type="button" className="icon-button" onClick={loadTicket} title="รีเฟรช" aria-label="รีเฟรช">
          <Icon name="refresh" />
        </button>
      </header>

      {/* Next-action callout — only rendered when derivable from the real `can`
          permission flags above; otherwise omitted rather than guessed. */}
      {nextAction && (
        <div style={{
          display: 'flex', alignItems: 'flex-start', gap: 10, padding: '10px 14px',
          borderRadius: 8, border: '1px solid var(--color-info-border, #bfdbfe)',
          background: 'var(--color-info-bg)', color: 'var(--color-info)', fontSize: 13,
        }}>
          <Icon name="chevronRight" size={15} style={{ flexShrink: 0, marginTop: 1 }} />
          <span><strong>ขั้นตอนถัดไปสำหรับคุณ:</strong> {nextAction}</span>
        </div>
      )}

      {/* Passive waiting hint — the next step belongs to another team (ฝ่ายบัญชี /
          Import), so show who the ticket is waiting on instead of a dead end. */}
      {waitingHint && (
        <div style={{
          display: 'flex', alignItems: 'flex-start', gap: 10, padding: '10px 14px',
          borderRadius: 8, border: '1px solid var(--color-border)',
          background: 'var(--color-surface-muted, var(--color-info-bg))', color: 'var(--color-text-muted)', fontSize: 13,
        }}>
          <Icon name="clock" size={15} style={{ flexShrink: 0, marginTop: 1 }} />
          <span><strong>รอดำเนินการ:</strong> {waitingHint}</span>
        </div>
      )}

      {/* Contextual decision panel — approve/reject is the one action on this page
          with real financial and downstream consequences, so it gets its own
          visually separated, deliberate block instead of sitting in the general
          action row. Helper text below is quoted from api.tickets.approve/reject
          in src/api/mockApi.js (lines ~1218-1240): approve copies proposedPrice →
          approvedPrice and moves price_proposed → approved; reject moves the
          ticket back to in_review for Import to re-propose. */}
      {(can.approve || can.reject) && (
        // Warning token rather than a raw amber: DESIGN.md defines
        // --color-warning as the caution / needs-a-decision colour.
        <section className="panel" style={{ borderColor: 'var(--color-warning)', borderWidth: 1.5 }}>
          <div className="panel-header">
            <h2>การอนุมัติราคา</h2>
          </div>
          <div style={{ padding: '0 18px 16px', display: 'flex', flexDirection: 'column', gap: 10 }}>
            <p style={{ margin: 0, fontSize: 13, color: '#475569', lineHeight: 1.6 }}>
              ตรวจสอบราคาที่เสนอในรายการสินค้าด้านล่างก่อนตัดสินใจ
              {' — '}<strong>อนุมัติ</strong>จะยืนยันราคาที่เสนอเป็นราคาขายจริง และเปิดให้ฝ่ายขายออกใบเสนอราคาต่อได้
              {' '}<strong>ไม่อนุมัติ</strong>จะส่งใบขอราคากลับไปที่ฝ่าย Import เพื่อเสนอราคาใหม่
            </p>
            {!showRejectForm && (
              <div style={{ display: 'flex', flexWrap: 'wrap', gap: 10 }}>
                {can.approve && (
                  <button type="button" className="primary-button" disabled={actionLoading || calcLoading}
                    onClick={() => doAction(() => api.tickets.approve(ticketId), 'อนุมัติราคาแล้ว')}>
                    <Icon name="check" size={14} />
                    อนุมัติ
                  </button>
                )}
                {can.reject && (
                  <button type="button" className="secondary-button" disabled={actionLoading}
                    onClick={() => setShowRejectForm(true)}
                    style={{ color: '#dc2626', borderColor: '#fca5a5' }}>
                    <Icon name="close" size={14} />
                    ไม่อนุมัติ
                  </button>
                )}
              </div>
            )}
            {showRejectForm && (
              <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
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
          </div>
        </section>
      )}

      {hasActions && (
        <section className="panel" style={{ background: '#f8fafc' }}>
          <div className="panel-header">
            <h2>การดำเนินการอื่น ๆ</h2>
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
              <button
                type="button"
                className={st === 'approved' ? 'secondary-button' : 'primary-button'}
                style={st === 'approved' ? { borderColor: '#fbbf24', color: '#92400e', background: '#fffbeb' } : {}}
                disabled={actionLoading}
                onClick={initPropose}
              >
                <Icon name="pencil" size={14} />
                {st === 'in_review' && 'เสนอราคาสินค้า'}
                {st === 'price_proposed' && 'แก้ไขราคาที่เสนอ'}
                {st === 'approved' && 'แก้ไขราคา (ต้องอนุมัติใหม่)'}
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
            {priceBreakdown.length > 0 && (
              <button type="button" className="secondary-button"
                style={{ fontSize: 12 }}
                onClick={() => setShowBreakdown((v) => !v)}>
                {showBreakdown ? 'ซ่อนรายละเอียดสูตร' : 'ดูรายละเอียดสูตร'}
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

            {can.confirmCustomer && (
              <button type="button" className="primary-button" disabled={actionLoading}
                onClick={() => doAction(() => api.tickets.confirmCustomer(ticketId), 'ลูกค้ายืนยันแล้ว')}>
                ลูกค้ายืนยัน
              </button>
            )}
            {can.confirmDepositPaid && (
              <button type="button" className="primary-button" disabled={actionLoading}
                onClick={() => doAction(() => api.tickets.confirmDepositPaid(ticketId), 'ยืนยันรับมัดจำแล้ว')}>
                ยืนยันรับมัดจำ
              </button>
            )}
            {can.issueImportRequest && (
              <button type="button" className="primary-button" disabled={actionLoading}
                onClick={() => doAction(() => api.tickets.issueImportRequest(ticketId), 'ออก IR แล้ว')}>
                ออก Import Request (IR)
              </button>
            )}
            {can.markIrSent && (
              <button type="button" className="primary-button" disabled={actionLoading}
                onClick={() => doAction(() => api.tickets.markIrSent(ticketId), 'ส่ง IR แล้ว')}>
                ส่ง IR แล้ว
              </button>
            )}
            {can.markShipping && (
              <button type="button" className="primary-button" disabled={actionLoading}
                onClick={() => doAction(() => api.tickets.markShipping(ticketId), 'สินค้าอยู่ระหว่างขนส่ง')}>
                สินค้าออกเดินทาง (Shipping)
              </button>
            )}
            {can.markGoodsReceived && (
              <button type="button" className="primary-button" disabled={actionLoading}
                onClick={() => doAction(() => api.tickets.markGoodsReceived(ticketId), 'รับสินค้าแล้ว')}>
                รับสินค้าแล้ว (Goods Received)
              </button>
            )}
            {can.confirmFinalPayment && (
              <button type="button" className="primary-button" disabled={actionLoading}
                onClick={() => doAction(() => api.tickets.confirmFinalPayment(ticketId), 'ชำระครบแล้ว')}>
                ยืนยันชำระครบ (Final Payment)
              </button>
            )}
            {can.downloadRemainingInvoice && (
              <button type="button" className="secondary-button"
                onClick={handleDownloadRemainingInvoice}>
                ดาวน์โหลดใบแจ้งหนี้ส่วนที่เหลือ
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
                onClick={() => setConfirm({ kind: 'cancelTicket' })}>
                ยกเลิก
              </button>
            )}
          </div>

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
                    style={{ marginTop: 2, flexShrink: 0, width: 16, height: 16, accentColor: '#1e40af', cursor: 'pointer' }} />
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

      {st === 'quotation_issued' && (
        <section className="panel" style={{ marginBottom: 0 }}>
          <div className="panel-header"><h2>สถานะหลังออกใบเสนอราคา</h2></div>
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 16, padding: '12px 18px 18px' }}>
            <div>
              <div style={{ fontSize: 12, fontWeight: 600, color: '#64748b', marginBottom: 6 }}>Track P — การชำระเงิน</div>
              {[
                { key: 'CUSTOMER_CONFIRMED',    label: 'ลูกค้ายืนยัน' },
                { key: 'DEPOSIT_NOTICE_ISSUED', label: 'ออกใบแจ้งมัดจำ' },
                { key: 'DEPOSIT_PAID',          label: 'รับมัดจำแล้ว' },
                { key: 'AWAITING_FINAL_PAYMENT',label: 'รอชำระส่วนที่เหลือ' },
                { key: 'FULLY_PAID',            label: 'ชำระครบแล้ว' },
              ].map((step, idx, arr) => {
                const stepKeys = arr.map((s) => s.key);
                const curIdx = stepKeys.indexOf(ps);
                const done = curIdx >= idx;
                const active = curIdx === idx;
                return (
                  <div key={step.key} style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '4px 0' }}>
                    <span style={{ width: 18, height: 18, borderRadius: '50%', flexShrink: 0, display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: 10, fontWeight: 700,
                      background: done ? (active ? '#1e40af' : '#bfdbfe') : '#e2e8f0',
                      color: done ? (active ? '#fff' : '#1e40af') : '#64748b' }}>
                      {done && !active ? '✓' : idx + 1}
                    </span>
                    <span style={{ fontSize: 13, color: active ? '#1e40af' : done ? '#374151' : '#64748b', fontWeight: active ? 600 : 400 }}>{step.label}</span>
                  </div>
                );
              })}
            </div>
            <div>
              <div style={{ fontSize: 12, fontWeight: 600, color: '#64748b', marginBottom: 6 }}>Track F — การนำเข้า/จัดส่ง</div>
              {[
                { key: 'IR_ISSUED',      label: 'ออก Import Request' },
                { key: 'IR_SENT',        label: 'ส่ง IR แล้ว' },
                { key: 'SHIPPING',       label: 'สินค้าอยู่ระหว่างขนส่ง' },
                { key: 'GOODS_RECEIVED', label: 'รับสินค้าแล้ว' },
              ].map((step, idx, arr) => {
                const stepKeys = arr.map((s) => s.key);
                const curIdx = stepKeys.indexOf(fs);
                const done = curIdx >= idx;
                const active = curIdx === idx;
                return (
                  <div key={step.key} style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '4px 0' }}>
                    <span style={{ width: 18, height: 18, borderRadius: '50%', flexShrink: 0, display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: 10, fontWeight: 700,
                      background: done ? (active ? '#059669' : '#a7f3d0') : '#e2e8f0',
                      color: done ? (active ? '#fff' : '#059669') : '#64748b' }}>
                      {done && !active ? '✓' : idx + 1}
                    </span>
                    <span style={{ fontSize: 13, color: active ? '#059669' : done ? '#374151' : '#64748b', fontWeight: active ? 600 : 400 }}>{step.label}</span>
                  </div>
                );
              })}
            </div>
          </div>
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
                      {/* Unit basis toggle */}
                      <div style={{ margin: 0, gridColumn: '1 / -1' }}>
                        <span style={{ fontSize: 12, display: 'block', marginBottom: 4 }}>หน่วยที่ใช้สั่ง</span>
                        <div style={{ display: 'flex', gap: 16, alignItems: 'center', flexWrap: 'wrap' }}>
                          {[{ value: 'PIECE', label: 'แผ่น' }, { value: 'SQM', label: 'ตร.ม.' }].map((opt) => (
                            <label key={opt.value} style={{ display: 'flex', gap: 6, alignItems: 'center', cursor: 'pointer', fontSize: 13 }}>
                              <input type="radio" name={`editUnitBasis-${index}`} value={opt.value}
                                checked={(item.unitBasis || 'PIECE') === opt.value}
                                onChange={() => setEditDraft((d) => d.map((r, ri) => {
                                  if (ri !== index) return r;
                                  const u = { ...r, unitBasis: opt.value };
                                  if (r.sqmPerPiece) {
                                    if (opt.value === 'SQM' && r.qty) u.qtySqm = (Number(r.qty) * r.sqmPerPiece).toFixed(3);
                                    if (opt.value === 'PIECE' && r.qtySqm) u.qty = Math.ceil(Number(r.qtySqm) / r.sqmPerPiece);
                                  }
                                  return u;
                                }))}
                                style={{ width: 16, height: 16, accentColor: '#1e40af', cursor: 'pointer' }} />
                              <strong>{opt.label}</strong>
                            </label>
                          ))}
                          {item.sqmPerPiece && (
                            <span style={{ fontSize: 11, color: '#64748b' }}>· 1 แผ่น = {item.sqmPerPiece} ตร.ม.</span>
                          )}
                        </div>
                      </div>

                      {/* Qty inputs */}
                      {(item.unitBasis || 'PIECE') === 'PIECE' ? (
                        <>
                          <label style={{ margin: 0 }}>
                            <span style={{ fontSize: 12 }}>จำนวน (แผ่น)</span>
                            <input type="number" value={item.qty ?? ''} step="1"
                              onChange={(e) => setEditDraft((d) => d.map((r, ri) => {
                                if (ri !== index) return r;
                                const u = { ...r, qty: e.target.value };
                                if (r.sqmPerPiece && e.target.value) u.qtySqm = (Number(e.target.value) * r.sqmPerPiece).toFixed(3);
                                return u;
                              }))} />
                          </label>
                          <div style={{ margin: 0 }}>
                            <span style={{ fontSize: 12, display: 'block', marginBottom: 4 }}>พื้นที่รวม (ตร.ม.)</span>
                            <div style={{ padding: '7px 10px', border: '1px solid #e2e8f0', borderRadius: 6, background: '#f8fafc', fontSize: 13, color: item.qtySqm ? '#475569' : '#94a3b8' }}>
                              {item.qtySqm ? `${Number(item.qtySqm).toFixed(3)} ตร.ม.` : '—'}
                            </div>
                          </div>
                        </>
                      ) : (
                        <>
                          <label style={{ margin: 0 }}>
                            <span style={{ fontSize: 12 }}>พื้นที่ (ตร.ม.)</span>
                            <input type="number" value={item.qtySqm ?? ''} min="0" step="0.001"
                              onChange={(e) => setEditDraft((d) => d.map((r, ri) => {
                                if (ri !== index) return r;
                                const u = { ...r, qtySqm: e.target.value };
                                if (r.sqmPerPiece && e.target.value) u.qty = Math.ceil(Number(e.target.value) / r.sqmPerPiece);
                                return u;
                              }))} />
                          </label>
                          <div style={{ margin: 0 }}>
                            <span style={{ fontSize: 12, display: 'block', marginBottom: 4 }}>จำนวน (แผ่น)</span>
                            <div style={{ padding: '7px 10px', border: '1px solid #e2e8f0', borderRadius: 6, background: '#f8fafc', fontSize: 13, color: item.qty ? '#475569' : '#94a3b8' }}>
                              {item.qty ? `${item.qty} แผ่น` : '—'}
                            </div>
                          </div>
                        </>
                      )}
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
                    onClick={() => {
                      if (editDraft.some((item) => !item.qty || Number(item.qty) <= 0)) {
                        showToast('error', 'กรุณากรอกจำนวนสินค้าให้ครบทุกรายการ');
                        return;
                      }
                      doAction(() => api.tickets.editItems(ticketId, {
                        items: editDraft.map((item) => ({
                          brand: item.brand, model: item.model, color: item.color,
                          texture: item.texture, size: item.size,
                          factory: item.factory || null,
                          unitBasis: item.unitBasis || 'PIECE',
                          qty: Number(item.qty) || 0,
                          qtySqm: item.qtySqm != null && item.qtySqm !== '' ? Number(item.qtySqm) : null,
                          proposedPrice: item.proposedPrice != null && item.proposedPrice !== '' ? Number(item.proposedPrice) : null,
                          currency: item.currency ?? 'THB',
                        })),
                        note: editNote.trim() || null,
                      }), 'บันทึกการแก้ไขแล้ว');
                    }}>
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
                          {fc?.email && <span style={{ fontWeight: 400, color: '#64748b', fontSize: 11 }}>· {fc.email}</span>}
                          <button type="button" className="secondary-button"
                            style={{ marginLeft: 'auto', fontSize: 12, padding: '4px 12px' }}
                            onClick={() => setEmailDraft(isDraftOpen ? null : buildEmailDraft(factory, groupItems))}>
                            <Icon name="fileText" size={13} />
                            {isDraftOpen ? 'ปิดร่างอีเมล' : 'ร่างอีเมล'}
                          </button>
                        </div>

                        {/* Currency/unit settings bar */}
                        <div style={{ padding: '10px 18px', background: '#f8fafc', borderTop: '1px solid #e6eaf0', display: 'flex', gap: 20, alignItems: 'center', flexWrap: 'wrap' }}>
                          <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
                            <span style={{ fontSize: 13, color: '#475569', fontWeight: 600, whiteSpace: 'nowrap' }}>สกุลเงิน</span>
                            <InfoTip label="สกุลเงินและหน่วยนับ" text="เลือกครั้งเดียวต่อโรงงาน ใช้กับทุกรายการของโรงงานนี้" />
                            <select
                              value={draftFactoryCurr[factory]?.currency ?? 'THB'}
                              onChange={(e) => setDraftFactoryCurr((p) => ({ ...p, [factory]: { ...p[factory], currency: e.target.value } }))}
                              style={{ fontSize: 14, padding: '5px 10px', border: '1px solid #cbd5e1', borderRadius: 6, background: '#fff', cursor: 'pointer', fontWeight: 600, color: '#1e40af' }}>
                              {['THB','EUR','USD','JPY','CNY','GBP'].map((c) => <option key={c}>{c}</option>)}
                            </select>
                          </div>
                          <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
                            <span style={{ fontSize: 13, color: '#475569', fontWeight: 600, whiteSpace: 'nowrap' }}>หน่วยราคา</span>
                            <select
                              value={draftFactoryCurr[factory]?.unit ?? 'piece'}
                              onChange={(e) => setDraftFactoryCurr((p) => ({ ...p, [factory]: { ...p[factory], unit: e.target.value } }))}
                              style={{ fontSize: 14, padding: '5px 10px', border: '1px solid #cbd5e1', borderRadius: 6, background: '#fff', cursor: 'pointer', fontWeight: 600, color: '#1e40af' }}>
                              <option value="piece">/ แผ่น</option>
                              <option value="sqm">/ ตร.ม.</option>
                              <option value="box">/ กล่อง</option>
                            </select>
                          </div>
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
                            <div key={item.id ?? i} className="ticket-items-table data-row" style={{ gridTemplateColumns: itemsGridCols }}>
                              <span data-label="ยี่ห้อ / รุ่น">
                                <strong>{item.brand}</strong>
                                {item.model && <small style={{ color: '#64748b' }}>{item.model}</small>}
                              </span>
                              <span data-label="สี / เนื้อผิว" style={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
                                {item.color && <span>{item.color}</span>}
                                {item.texture && <small style={{ color: '#64748b' }}>{item.texture}</small>}
                                {item.size && <small style={{ color: '#64748b' }}>{item.size}</small>}
                              </span>
                              <span data-label="จำนวน">
                                {item.unitBasis === 'SQM'
                                  ? <>{item.qtySqm != null ? `${Number(item.qtySqm).toFixed(2)} ตร.ม.` : '—'}<small style={{ display: 'block', color: '#64748b' }}>{item.qty} แผ่น</small></>
                                  : <>{item.qty} แผ่น{item.qtySqm != null && <small style={{ display: 'block', color: '#64748b' }}>{Number(item.qtySqm).toFixed(2)} ตร.ม.</small>}</>
                                }
                              </span>
                              <div data-label="ราคาที่เสนอ (แก้ไข)" style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
                                <input type="number" min="0" step="0.0001"
                                  value={draftRaw[item.id] ?? ''}
                                  onChange={(e) => setDraftRaw((prev) => ({ ...prev, [item.id]: e.target.value }))}
                                  placeholder={`ราคา/${unitLabel}`}
                                  title="ราคาต่อหน่วยของรายการนี้เท่านั้น (สกุลเงิน/หน่วยนับใช้ค่าที่ตั้งไว้ของโรงงาน)"
                                  style={{ width: 110, padding: '4px 8px', border: '1px solid #93c5fd', borderRadius: 4, fontSize: 13 }} />
                                <span style={{ fontSize: 11, color: '#2563eb', whiteSpace: 'nowrap' }}>{currLabel}/{unitLabel}</span>
                              </div>
                              <code data-label="ราคาที่อนุมัติ">{formatMoney(item.approvedPrice)}</code>
                            </div>
                          );
                        })}
                      </div>
                    );
                  })
                ) : items.map((item, i) => (
                  <div key={item.id ?? i} className="ticket-items-table data-row" style={{ gridTemplateColumns: itemsGridCols }}>
                    <span data-label="ยี่ห้อ / รุ่น">
                      <strong>{item.brand}</strong>
                      {item.model && <small style={{ color: '#64748b' }}>{item.model}</small>}
                      {item.factory && <small style={{ color: '#64748b', fontSize: 11 }}>{item.factory}</small>}
                    </span>
                    <span data-label="สี / เนื้อผิว" style={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
                      {item.color && <span>{item.color}</span>}
                      {item.texture && <small style={{ color: '#64748b' }}>{item.texture}</small>}
                      {item.size && <small style={{ color: '#64748b' }}>{item.size}</small>}
                    </span>
                    <span data-label="จำนวน">
                      {item.unitBasis === 'SQM'
                        ? <>{item.qtySqm != null ? `${Number(item.qtySqm).toFixed(2)} ตร.ม.` : '—'}<small style={{ display: 'block', color: '#64748b' }}>{item.qty} แผ่น</small></>
                        : <>{item.qty} แผ่น{item.qtySqm != null && <small style={{ display: 'block', color: '#64748b' }}>{Number(item.qtySqm).toFixed(2)} ตร.ม.</small>}</>
                      }
                    </span>
                    {showCalcBreakdown ? (
                      <>
                        <span data-label="ราคาโรงงาน" style={{ fontSize: 12 }}>
                          {item.rawPrice != null
                            ? <><strong>{Number(item.rawPrice).toLocaleString('th-TH', { minimumFractionDigits: 2 })}</strong><small style={{ color: '#64748b' }}> {item.rawCurrency}/{item.rawUnit === 'sqm' ? 'ตร.ม.' : 'แผ่น'}</small></>
                            : <span style={{ color: '#64748b' }}>-</span>}
                          {item.calcConfigVersion && <small style={{ display: 'block', color: '#64748b', fontSize: 10 }}>config v{item.calcConfigVersion}</small>}
                        </span>
                        <code data-label="ต้นทุน (THB/ชิ้น)" style={{ color: '#0369a1' }}>{item.calcedCost != null ? formatMoney(item.calcedCost) : '—'}</code>
                        <span data-label="ราคาขาย (THB/ชิ้น)">
                          <code style={{ color: item.manualPrice != null ? '#7c3aed' : '#059669', fontWeight: 700 }}>
                            {item.manualPrice != null ? formatMoney(item.manualPrice) : item.calcedPrice != null ? formatMoney(item.calcedPrice) : '—'}
                          </code>
                          {item.manualPrice != null && <small style={{ display: 'block', color: '#7c3aed', fontSize: 10 }}>override</small>}
                          {can.overridePrice && (
                            overrideDraft[item.id] !== undefined ? (
                              <div style={{ display: 'flex', flexDirection: 'column', gap: 4, marginTop: 4 }}>
                                <input type="number" step="0.01" min="0"
                                  placeholder="ราคา override"
                                  value={overrideDraft[item.id]?.price ?? ''}
                                  onChange={(e) => setOverrideDraft((p) => ({ ...p, [item.id]: { ...p[item.id], price: e.target.value } }))}
                                  style={{ width: 90, padding: '2px 6px', fontSize: 12, border: '1px solid #a78bfa', borderRadius: 4 }} />
                                <input type="text" placeholder="เหตุผล (ถ้ามี)"
                                  value={overrideDraft[item.id]?.reason ?? ''}
                                  onChange={(e) => setOverrideDraft((p) => ({ ...p, [item.id]: { ...p[item.id], reason: e.target.value } }))}
                                  style={{ width: 90, padding: '2px 6px', fontSize: 11, border: '1px solid #d8b4fe', borderRadius: 4 }} />
                                <div style={{ display: 'flex', gap: 4 }}>
                                  <button type="button" className="primary-button"
                                    style={{ fontSize: 10, padding: '2px 8px', background: '#7c3aed', borderColor: '#7c3aed' }}
                                    disabled={overrideLoading[item.id]}
                                    onClick={() => handleOverridePrice(item.id)}>
                                    {overrideLoading[item.id] ? '...' : 'บันทึก'}
                                  </button>
                                  <button type="button" className="secondary-button"
                                    style={{ fontSize: 10, padding: '2px 6px' }}
                                    onClick={() => setOverrideDraft((p) => { const n = { ...p }; delete n[item.id]; return n; })}>
                                    ✕
                                  </button>
                                </div>
                              </div>
                            ) : (
                              <button type="button" className="secondary-button"
                                style={{ fontSize: 10, padding: '2px 8px', marginTop: 4, display: 'block' }}
                                onClick={() => setOverrideDraft((p) => ({ ...p, [item.id]: { price: item.calcedPrice != null ? String(item.calcedPrice) : '', reason: '' } }))}>
                                override
                              </button>
                            )
                          )}
                        </span>
                      </>
                    ) : (
                      <>
                        {showProposed && <code data-label={`ราคาที่เสนอ${proposeMode ? ' (แก้ไข)' : ''}`}>{formatMoney(item.proposedPrice)}</code>}
                        {showApproved && <code data-label="ราคาที่อนุมัติ">{formatMoney(item.approvedPrice)}</code>}
                      </>
                    )}
                  </div>
                ))}

                {proposeMode && (
                  <div style={{ padding: '12px 18px', display: 'flex', flexDirection: 'column', gap: 8, borderTop: '1px solid #e6eaf0' }}>
                    {st === 'approved' && (
                      <div style={{ padding: '10px 14px', borderRadius: 8, background: '#fffbeb', border: '1px solid #fbbf24', fontSize: 13, color: '#92400e', display: 'flex', gap: 8, alignItems: 'flex-start' }}>
                        <span style={{ fontWeight: 700, flexShrink: 0 }}>⚠</span>
                        <span>การแก้ไขราคาจะ<strong>ยกเลิกการอนุมัติ</strong> และสถานะจะย้อนกลับเป็น &ldquo;รอการอนุมัติ&rdquo; — CEO และ Sales จะได้รับแจ้งทันที</span>
                      </div>
                    )}
                    <label style={{ fontSize: 13 }}>
                      หมายเหตุราคา
                      <input value={proposeNote} onChange={(e) => setProposeNote(e.target.value)}
                        placeholder="ข้อมูลเพิ่มเติมเกี่ยวกับราคา (ถ้ามี)" style={{ marginTop: 4 }} />
                    </label>
                    <div style={{ display: 'flex', gap: 8 }}>
                      <button type="button" className="primary-button" onClick={handleProposePrice} disabled={actionLoading}>
                        {st === 'approved' ? 'ยืนยันแก้ไขราคา (รออนุมัติใหม่)' : 'ยืนยันราคาเสนอ'}
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

          {/* D9: Price formula breakdown */}
          {showBreakdown && priceBreakdown.length > 0 && (
            <section className="panel">
              <div className="panel-header" style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                <h2>รายละเอียดสูตรคำนวณราคา</h2>
                <button type="button" className="secondary-button" style={{ fontSize: 11, padding: '3px 8px' }}
                  onClick={() => setShowBreakdown(false)}>ซ่อน</button>
              </div>
              <div style={{ overflowX: 'auto', padding: '0 0 8px' }}>
                {priceBreakdown.map((b) => (
                  <div key={b.itemId} style={{ marginBottom: 16, padding: '12px 18px', borderBottom: '1px solid #f1f5f9' }}>
                    <div style={{ fontWeight: 700, fontSize: 13, marginBottom: 8 }}>
                      {b.brand} {b.model && <span style={{ fontWeight: 400, color: '#64748b' }}>({b.model})</span>}
                      {b.factory && <small style={{ color: '#64748b', marginLeft: 8 }}>{b.factory}</small>}
                    </div>
                    <table style={{ fontSize: 11, borderCollapse: 'collapse', width: '100%', maxWidth: 520 }}>
                      <tbody>
                        {[
                          ['ต้นทุนสินค้า (THB/ตร.ม.)', b.goodsCostPerSqm, `อัตราแลกเปลี่ยน ${b.rawCurrency}: ${b.fxRate}`],
                          ['+ ค่าเรือ (THB/ตร.ม.)', b.freightPerSqm, null],
                          ['+ ประกัน (THB/ตร.ม.)', b.insurancePerSqm, null],
                          ['= CIF (THB/ตร.ม.)', b.cifPerSqm, null],
                          ['+ ภาษีนำเข้า (THB/ตร.ม.)', b.importDutyPerSqm, null],
                          ['+ ขนส่งภายใน (THB/ตร.ม.)', b.inlandPerSqm, null],
                          ['= ต้นทุน Landed (THB/ตร.ม.)', b.landedCostPerSqm, null],
                          [`+ Margin ${b.marginPct != null ? `${(Number(b.marginPct) * 100).toFixed(1)}%` : ''}`, null, null],
                          ['= ราคาขาย (THB/ตร.ม.)', b.sellPricePerSqm, null],
                          ['sqm/แผ่น', b.sqmPerPiece, null],
                          ['ต้นทุน/แผ่น', b.calcedCostPerPiece, `config v${b.configVersion}`],
                          ['ราคาขาย/แผ่น', b.calcedPricePerPiece, null],
                        ].map(([label, value, note]) => (
                          <tr key={label} style={{ borderBottom: '1px solid #f8fafc' }}>
                            <td style={{ padding: '3px 10px 3px 0', color: '#475569', whiteSpace: 'nowrap' }}>{label}</td>
                            <td style={{ padding: '3px 0', fontWeight: 600, textAlign: 'right', minWidth: 80 }}>
                              {value != null ? Number(value).toLocaleString('th-TH', { minimumFractionDigits: 4 }) : ''}
                            </td>
                            <td style={{ padding: '3px 0 3px 10px', color: '#64748b', fontSize: 10 }}>{note}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                ))}
              </div>
            </section>
          )}

          {/* R5: Attachments */}
          <section className="panel">
            <div className="panel-header" style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
              <h2>ไฟล์แนบ (PO / ใบเซ็น)</h2>
              {!TERMINAL.includes(st) && (
                <label className="cursor-pointer max-[720px]:w-full" htmlFor="ticket-attachment-file">
                  <input
                    id="ticket-attachment-file"
                    type="file"
                    // See FileUploadField: styles.css now loads into @layer legacy
                    // (before Tailwind's utilities layer), so these utilities win
                    // over the legacy global `input` rules without `!` overrides.
                    className="sr-only h-px min-h-0 w-px border-0 p-0"
                    onChange={handleUploadAttachment}
                    accept=".pdf,.doc,.docx,.xls,.xlsx,.png,.jpg,.jpeg"
                  />
                  <span
                    className="secondary-button max-[720px]:min-h-11 max-[720px]:w-full"
                    style={{ fontSize: 12, padding: '4px 10px', display: 'inline-flex', alignItems: 'center', gap: 4 }}
                  >
                    <Icon name="upload" size={13} />
                    {uploadingFile ? 'กำลังอัปโหลด...' : 'แนบไฟล์ (PDF/JPG/PNG/Excel)'}
                  </span>
                </label>
              )}
            </div>
            {attachLoading ? (
              <div
                style={{ padding: '8px 18px', display: 'flex', flexDirection: 'column', gap: 6 }}
                aria-busy="true"
                aria-label="กำลังโหลดไฟล์แนบ"
              >
                {[0, 1, 2].map((i) => (
                  <div key={i} style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '6px 10px', background: '#f8fafc', borderRadius: 6, border: '1px solid #e2e8f0' }}>
                    <Skeleton width={13} height={13} radius="var(--radius-sm)" />
                    <Skeleton width="50%" height={13} />
                    <Skeleton width={40} height={16} radius="var(--radius-pill)" />
                  </div>
                ))}
              </div>
            ) : attachments.length === 0 ? (
              <div style={{ padding: '4px 18px 14px' }}>
                <EmptyState icon="paperclip" title="ยังไม่มีไฟล์แนบ" description="แนบ PO หรือใบเซ็นได้ด้วยปุ่มด้านบน" />
              </div>
            ) : (
              <div style={{ padding: '8px 18px', display: 'flex', flexDirection: 'column', gap: 6 }}>
                {attachments.map((att) => (
                  <div key={att.id} style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '6px 10px', background: '#f8fafc', borderRadius: 6, border: '1px solid #e2e8f0' }}>
                    <Icon name="paperclip" size={13} style={{ color: '#64748b', flexShrink: 0 }} />
                    <span style={{ flex: 1, fontSize: 13, color: '#0f172a', wordBreak: 'break-all' }}>{att.fileName}</span>
                    <span style={{ fontSize: 11, color: '#64748b', whiteSpace: 'nowrap', background: '#f1f5f9', padding: '1px 6px', borderRadius: 99 }}>
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
                      ...docStatusColors(q.docStatus),
                    }}>Rev {q.quotationVersion}</span>
                  </div>
                  <div style={{ flex: 1, minWidth: 0 }}>
                    <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
                      <span style={{ fontWeight: 600, fontSize: 13 }}>{q.number}</span>
                      <span style={{
                        fontSize: 10, borderRadius: 3, padding: '1px 5px', fontWeight: 600,
                        ...docStatusColors(q.docStatus),
                      }}>{q.docStatus}</span>
                    </div>
                    <div style={{ fontSize: 12, color: '#475569', marginTop: 2 }}>
                      ยอดรวม {formatMoney(q.totalAmount)} · ออกโดย {q.issuedByName} · {formatThaiDate(q.issuedAt)}
                    </div>
                  </div>
                  <div style={{ display: 'flex', gap: 6, flexShrink: 0 }}>
                    <button type="button" className="secondary-button" style={{ fontSize: 12, padding: '4px 10px' }}
                      onClick={() => handleDownloadQuotation(q.id, q.number, 'xlsx')}>
                      <Icon name="fileText" size={12} /> Excel
                    </button>
                    <button type="button" className="secondary-button" style={{ fontSize: 12, padding: '4px 10px' }}
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
              <EmptyState icon="clock" title="ยังไม่มีประวัติ" description="เหตุการณ์และความคิดเห็นจะปรากฏที่นี่" />
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
                      <div style={{ margin: '6px 0 0', fontSize: 11, color: '#475569', background: '#f8fafc', borderRadius: 4, padding: '6px 10px' }}>
                        <div style={{ fontWeight: 600, marginBottom: 4, color: '#64748b' }}>รายการสินค้า ณ เวลาที่เสนอราคา</div>
                        {snapItems.map((it, i) => (
                          <div key={i} style={{ paddingBottom: 2 }}>
                            {it.brand} {it.model} — {it.qty} ชิ้น
                            {it.rawPrice != null && (
                              <span style={{ color: '#64748b', marginLeft: 4 }}>
                                @ {it.rawPrice} {it.rawCurrency}/{it.rawUnit}
                              </span>
                            )}
                          </div>
                        ))}
                      </div>
                    )}
                    <small style={{ color: '#64748b', fontSize: 11 }}>{formatThaiDate(event.createdAt)}</small>
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
