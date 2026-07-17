import { useEffect, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, ROLE_PERMISSIONS } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { Breadcrumbs } from '../../components/common/Breadcrumbs.jsx';
import { ConfirmDialog } from '../../components/common/ConfirmDialog.jsx';
import { EmptyState } from '../../components/common/EmptyState.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { InfoTip } from '../../components/common/InfoTip.jsx';
import { Modal } from '../../components/common/Modal.jsx';
import { Skeleton, SkeletonText } from '../../components/common/Skeleton.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import {
  formatMoney,
  formatThaiDate,
  quotationRecipientLabel,
  quotationStatusLabel,
  ticketStatusLabel,
} from '../../utils/format.js';
import { downloadBlob } from '../../utils/download.js';
import { DealStagePanel } from './DealStagePanel.jsx';

const EVENT_KIND_LABEL = {
  CREATED:            'สร้างดีล',
  STAGE_CHANGED:      'เปลี่ยนสถานะดีล',
  MARKED_LOST:        'เสียงาน',
  REOPENED:           'เปิดดีลอีกครั้ง',
  ON_HOLD:            'พักดีลไว้',
  DORMANT:            'พัก dormant',
  RESUMED:            'ดำเนินการต่อ',
  POLICY_CHANGED:     'เปลี่ยนนโยบายดีล',
  SUBMITTED:          'ส่งเรื่องเข้าระบบ',
  PICKED_UP:          'รับมอบหมาย',
  PRICE_PROPOSED:     'เสนอราคาสินค้า',
  APPROVED:           'อนุมัติ',
  REJECTED:           'ปฏิเสธ',
  QUOTATION_ISSUED:   'ออกใบเสนอราคา',
  QUOTATION_SENT:     'ส่งใบเสนอราคา',
  QUOTATION_ACCEPTED: 'ลูกค้ารับใบเสนอราคา',
  QUOTATION_REJECTED: 'ลูกค้าปฏิเสธใบเสนอราคา',
  DOCUMENT_ISSUED:    'ออกใบแจ้งยอดมัดจำ',
  PRICE_REVISED:      'แก้ไขราคาที่เสนอ',
  REVISION_REQUESTED: 'ขอแก้ไข',
  CLOSED:             'ปิดเรื่อง',
  CANCELLED:          'ยกเลิก',
  EDITED:             'แก้ไขรายการสินค้า',
  COMMENTED:          'ความคิดเห็น',
  COMMENT:            'ความคิดเห็น',
  PRICE_OVERRIDDEN:   'แก้ไขราคาด้วยตนเอง (CEO)',
  // Dual-track post-quotation events (see backend TicketEventKind.java) — payment
  // and fulfillment labels mirror the DealStagePanel's การชำระเงิน/การนำเข้า
  // sub-status chips so an event and its chip read
  // as the same real-world action.
  CUSTOMER_CONFIRMED:     'ลูกค้ายืนยันคำสั่งซื้อ',
  DEPOSIT_NOTICE_ISSUED:  'ออกใบแจ้งมัดจำ',
  DEPOSIT_PAID:           'รับมัดจำแล้ว',
  IR_ISSUED:              'ออก Import Request (IR)',
  IR_SENT:                'ส่ง IR แล้ว',
  SHIPPING:               'สินค้าออกเดินทาง (Shipping)',
  GOODS_RECEIVED:         'รับสินค้าแล้ว',
  AWAITING_FINAL_PAYMENT: 'รอชำระส่วนที่เหลือ',
  FULLY_PAID:             'ชำระครบแล้ว',
};

// Payment-track events get a success-toned dot, fulfillment-track
// events get an info-toned dot — mirrors the two colour groups used by the
// sub-status chips in the DealStagePanel.
const PAYMENT_TRACK_KINDS = new Set([
  'CUSTOMER_CONFIRMED', 'DEPOSIT_NOTICE_ISSUED', 'DEPOSIT_PAID',
  'AWAITING_FINAL_PAYMENT', 'FULLY_PAID',
]);
const FULFILLMENT_TRACK_KINDS = new Set([
  'IR_ISSUED', 'IR_SENT', 'SHIPPING', 'GOODS_RECEIVED',
]);

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
  if (PAYMENT_TRACK_KINDS.has(kind)) return 'event-dot success';
  if (FULFILLMENT_TRACK_KINDS.has(kind)) return 'event-dot transition';
  return 'event-dot transition';
}

function InfoRow({ label, value }) {
  return (
    <div style={{ display: 'flex', gap: 8, padding: '6px 0', borderBottom: '1px solid var(--color-surface-subtle)', fontSize: 13 }}>
      <span style={{ color: 'var(--color-text-muted)', minWidth: 120 }}>{label}</span>
      <span style={{ fontWeight: 600, color: 'var(--color-text)' }}>{value || '-'}</span>
    </div>
  );
}

export function TicketDetailPage({ user, ticketId, onBack, onOpenDocument, showToast }) {
  const queryClient = useQueryClient();

  // Propose-price mode
  const [proposeMode, setProposeMode] = useState(false);
  const [draftRaw, setDraftRaw] = useState({});              // itemId → rawPrice (string)
  const [draftFactoryCurr, setDraftFactoryCurr] = useState({}); // factoryName → { currency, unit }
  const [proposeNote, setProposeNote] = useState('');

  // Email drafts (factoryConfigs itself is now a query — see below)
  const [emailDraft, setEmailDraft] = useState(null);         // { factory, to, subject, body } | null
  const [emailSending, setEmailSending] = useState(false);

  // Edit-items mode
  const [editMode, setEditMode] = useState(false);
  const [editDraft, setEditDraft] = useState([]);
  const [editNote, setEditNote] = useState('');

  // Reject form
  const [showRejectForm, setShowRejectForm] = useState(false);
  const [rejectReason, setRejectReason] = useState('');

  // D7/D9/D10: CEO price calculation + breakdown + override.
  // priceBreakdown has no GET endpoint — it only ever comes back as a mutation
  // result (calculatePrices), so it stays local state fed by that mutation's
  // onSuccess rather than a query (see handoff 63 for the reasoning).
  const [priceBreakdown, setPriceBreakdown] = useState([]); // PriceBreakdownItemDto[]
  const [showBreakdown, setShowBreakdown] = useState(false);
  const [overrideDraft, setOverrideDraft] = useState({}); // itemId → { price: string, reason: string }

  // Revision form
  const [showReviseForm, setShowReviseForm] = useState(false);
  const [reviseScope, setReviseScope] = useState('QTY_OR_NOTE');
  const [reviseReason, setReviseReason] = useState('');

  // Quotation recipient/terms form (Phase 2 recipient-scoped chains)
  const [quotationModal, setQuotationModal] = useState(null); // { defaultRecipientType, amendmentRequired } | null
  const [quotationDraft, setQuotationDraft] = useState({
    recipientType: 'BUYER',
    recipientLabel: '',
    paymentTerms: '',
    leadTime: '',
    deliveryTerms: '',
    validityDate: '',
    amendmentReason: '',
  });

  // Comment
  const [commentText, setCommentText] = useState('');

  // Confirmation dialogs (state-driven, replaces native browser confirm)
  const [confirm, setConfirm] = useState(null); // { kind: 'deleteAttachment', id, name } | { kind: 'cancelTicket' } | null

  // Download busy-state — keyed so each quotation/format pair (and the
  // remaining-invoice download) gets its own disabled+label state instead of
  // one shared flag that would disable every download button at once. Not
  // server-state mutations (no cache to invalidate), so left as local state.
  const [downloadingQuotationKey, setDownloadingQuotationKey] = useState(null); // `${quotationId}-${format}` | null
  const [downloadingInvoice, setDownloadingInvoice] = useState(false);

  const ticketQuery = useQuery({
    queryKey: queryKeys.ticketDetail(ticketId),
    queryFn: () => api.tickets.get(ticketId).then((r) => r.ticket),
    enabled: !!ticketId,
  });
  const ticket = ticketQuery.data ?? null;
  const actionsQuery = useQuery({
    queryKey: queryKeys.ticketActions(ticketId),
    queryFn: () => api.tickets.actions(ticketId),
    enabled: !!ticketId && !!ticket,
    // Keep the previous action list while a refetch is in flight — every doAction
    // invalidates this query, and without a placeholder the whole cockpit's buttons
    // vanish for a beat on each action (visible flicker; Opus review finding).
    placeholderData: (previous) => previous,
  });
  const availableActions = actionsQuery.data?.availableActions ?? [];
  const actionNames = new Set(availableActions.map((action) => action.action));
  const hasAction = (action) => actionNames.has(action);
  // isLoading-only (not isFetching): this gate swaps the ENTIRE page for a
  // full skeleton. Using isFetching too would flash that skeleton on every
  // quiet background refetch (window refocus, another tab's invalidate) —
  // same reasoning as TicketDashboard's loading gate in slice A (handoff 62).
  const loading = ticketQuery.isLoading;

  useEffect(() => {
    if (ticketQuery.error) showToast('error', ticketQuery.error.message || 'โหลดข้อมูลไม่สำเร็จ');
  }, [ticketQuery.error, showToast]);

  const attachmentsQuery = useQuery({
    queryKey: queryKeys.ticketAttachments(ticketId),
    queryFn: () => api.attachments.list(ticketId).then((r) => r.attachments ?? []),
    enabled: !!ticketId,
  });
  const attachments = attachmentsQuery.data ?? [];
  // Same isLoading-only reasoning as `loading` above — a re-upload/delete
  // shouldn't flash the attachment list back to its skeleton rows.
  const attachLoading = attachmentsQuery.isLoading;
  // Attachment load failures were silently swallowed before ("non-critical")
  // — preserved as-is, no error toast wired up here.

  // Factory configs used to be lazily fetched once per page instance inside
  // initPropose(); now a plain query keyed only by ['factoryConfigs'] (no
  // ticketId), so the whole app shares one fetch per session instead of one
  // per ticket-detail visit. staleTime: Infinity because this is config data
  // that doesn't change during a session — see handoff 63.
  const factoryConfigsQuery = useQuery({
    queryKey: ['factoryConfigs'],
    queryFn: () => api.factoryConfigs.list().then((res) => {
      const map = {};
      (res.factories ?? []).forEach((fc) => { map[fc.factoryName] = fc; });
      return map;
    }),
    staleTime: Infinity,
  });
  const factoryConfigs = factoryConfigsQuery.data ?? {};

  // Shared post-mutation side effects: the ticket-detail fast path (backend
  // returns the full ticket, so no refetch needed) plus the deliberate
  // staleness fix — list/dashboard/notifications went stale after every
  // action before this slice and nothing refreshed them.
  function applyTicketUpdate(updatedTicket) {
    queryClient.setQueryData(queryKeys.ticketDetail(ticketId), updatedTicket);
    queryClient.invalidateQueries({ queryKey: queryKeys.ticketActions(ticketId) });
    queryClient.invalidateQueries({ queryKey: ['tickets', 'list'] });
    queryClient.invalidateQueries({ queryKey: queryKeys.dashboardSummary() });
    queryClient.invalidateQueries({ queryKey: queryKeys.notifications() });
  }

  // Same UI-draft reset the old doAction ran on every successful action.
  function resetActionDrafts() {
    setProposeMode(false);
    setEditMode(false);
    setEditDraft([]);
    setEditNote('');
    setShowRejectForm(false);
    setRejectReason('');
    setShowReviseForm(false);
    setReviseReason('');
    setQuotationModal(null);
    setQuotationDraft({
      recipientType: 'BUYER',
      recipientLabel: '',
      paymentTerms: '',
      leadTime: '',
      deliveryTerms: '',
      validityDate: '',
      amendmentReason: '',
    });
    setCommentText('');
    setDraftRaw({});
    setDraftFactoryCurr({});
    setProposeNote('');
    setEmailDraft(null);
  }

  // Generic action mutation — a drop-in replacement for the old doAction(fn,
  // successMsg) helper. mutationFn/onSuccess receive the same (fn, successMsg)
  // pair as variables so every ~17 action call site below is unchanged.
  const actionMutation = useMutation({
    mutationFn: ({ fn }) => fn(),
    onSuccess: (response, { successMsg }) => {
      applyTicketUpdate(response.ticket);
      showToast('success', successMsg);
      resetActionDrafts();
    },
    onError: (error) => showToast('error', error.message || 'เกิดข้อผิดพลาด'),
  });
  const actionLoading = actionMutation.isPending;

  // doAction swallows its own rejection (mutateAsync always rejects even
  // after onError runs) so call sites keep firing it without a try/catch,
  // exactly like the old imperative doAction.
  async function doAction(fn, successMsg) {
    try {
      await actionMutation.mutateAsync({ fn, successMsg });
    } catch { /* onError above already toasted */ }
  }

  // D7: CEO price calculation. Kept as its own mutation (not routed through
  // doAction) because it never reset the propose/edit/reject/revise drafts —
  // it has its own success side effects (priceBreakdown + showBreakdown) and
  // its own spinner label ("กำลังคำนวณ...") independent of actionLoading.
  const calculatePricesMutation = useMutation({
    mutationFn: () => api.tickets.calculatePrices(ticketId),
    onSuccess: (response) => {
      applyTicketUpdate(response.ticket);
      setPriceBreakdown(response.breakdown ?? []);
      setShowBreakdown(true);
      showToast('success', 'คำนวณราคาเรียบร้อย — ตรวจสอบรายละเอียดสูตรด้านล่าง แล้วกดอนุมัติได้เลย');
    },
    onError: (error) => showToast('error', error.message || 'คำนวณราคาไม่สำเร็จ'),
  });
  const calcLoading = calculatePricesMutation.isPending;

  // D10: CEO per-item manual override. One mutation instance shared by every
  // item row; `overrideMutation.variables` (the last {itemId, payload} passed
  // to mutateAsync) lets each row derive its OWN pending flag instead of a
  // useState map — same per-item granularity as before, without juggling a
  // dynamic set of mutation hooks.
  const overrideMutation = useMutation({
    mutationFn: ({ itemId, payload }) => api.tickets.overrideItemPrice(ticketId, itemId, payload),
    onSuccess: (response, { itemId }) => {
      applyTicketUpdate(response.ticket);
      setOverrideDraft((p) => { const n = { ...p }; delete n[itemId]; return n; });
      showToast('success', 'บันทึกราคา override แล้ว');
    },
    onError: (err) => showToast('error', err.message || 'บันทึกไม่สำเร็จ'),
  });
  function isOverridingItem(itemId) {
    return overrideMutation.isPending && overrideMutation.variables?.itemId === itemId;
  }

  // R5: Attachments upload/delete — invalidate the attachments query instead
  // of manually reloading + setting local array state.
  const uploadAttachmentMutation = useMutation({
    mutationFn: ({ file, attachType }) => api.attachments.upload(ticketId, file, attachType),
    onSuccess: (_res, { file }) => {
      queryClient.invalidateQueries({ queryKey: queryKeys.ticketAttachments(ticketId) });
      showToast('success', `แนบไฟล์ ${file.name} แล้ว`);
    },
    onError: (err) => showToast('error', err.message || 'อัปโหลดไม่สำเร็จ'),
  });
  const uploadingFile = uploadAttachmentMutation.isPending;

  const deleteAttachmentMutation = useMutation({
    mutationFn: (id) => api.attachments.delete(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: queryKeys.ticketAttachments(ticketId) });
      showToast('success', 'ลบไฟล์แล้ว');
    },
    onError: (err) => showToast('error', err.message || 'ลบไม่สำเร็จ'),
  });
  const deletingAttachment = deleteAttachmentMutation.isPending;

  // Manual refresh — today's fix: refresh both the ticket AND its attachments
  // (the old button only ever reloaded the ticket).
  function refreshTicket() {
    queryClient.invalidateQueries({ queryKey: queryKeys.ticketDetail(ticketId) });
    queryClient.invalidateQueries({ queryKey: queryKeys.ticketAttachments(ticketId) });
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

  // Documents that already exist stay reachable from the deal-stage panel
  // through the later stages: the latest quotation file, and the issued
  // ใบแจ้งยอดมัดจำ (payment track past CUSTOMER_CONFIRMED means
  // DepositNoticeService.issue has run — the deposit page then shows/downloads it).
  const sortedQuotations = [...(quotations ?? [])].sort((a, b) => new Date(b.issuedAt ?? 0) - new Date(a.issuedAt ?? 0));
  const activeQuotations = sortedQuotations.filter((q) => !['SUPERSEDED', 'CANCELLED', 'REJECTED'].includes(q.docStatus));
  const stageRecipientPriority = summary.salesStage === 'QUOTE_BUYER'
    ? ['BUYER', 'OWNER', 'DESIGNER', 'UNSPECIFIED']
    : ['DESIGNER', 'OWNER', 'BUYER', 'UNSPECIFIED'];
  const latestQuotation = stageRecipientPriority
    .map((recipient) => activeQuotations.find((q) => (q.recipientType ?? 'UNSPECIFIED') === recipient))
    .find(Boolean) ?? activeQuotations[0] ?? sortedQuotations[0] ?? null;
  const quotationGroups = ['DESIGNER', 'OWNER', 'BUYER', 'UNSPECIFIED']
    .map((recipientType) => ({
      recipientType,
      label: quotationRecipientLabel(recipientType).label,
      quotations: sortedQuotations.filter((q) => (q.recipientType ?? 'UNSPECIFIED') === recipientType),
    }))
    .filter((group) => group.quotations.length > 0);
  const depositNoticeIssued = ps != null && ps !== 'CUSTOMER_CONFIRMED';

  // 'draft' included since V50: a lightweight lead-stage deal gets its product
  // items here, then submits into the price-request flow when it reaches the
  // quote stages.
  const EDITABLE_STATUSES = ['draft', 'submitted', 'in_review', 'price_proposed'];
  const can = {
    // Submit is only meaningful once the deal has product lines — the backend
    // 400s an item-less submit (mirrors TicketService.submit).
    submit:            hasAction('SUBMIT') && st === 'draft' && isOwner && ROLE_PERMISSIONS.canCreateTickets.includes(role) && items.length > 0,
    pickup:            hasAction('PICKUP') && st === 'submitted'       && ROLE_PERMISSIONS.canPickupTickets.includes(role),
    propose:           hasAction('PROPOSE_PRICE') && ['in_review', 'price_proposed', 'approved'].includes(st) && ROLE_PERMISSIONS.canProposePrices.includes(role),
    calculatePrices:   hasAction('CALCULATE_PRICES') && st === 'price_proposed'  && ROLE_PERMISSIONS.canApproveReject.includes(role),
    overridePrice:     hasAction('OVERRIDE_ITEM_PRICE') && st === 'price_proposed'  && ROLE_PERMISSIONS.canApproveReject.includes(role),
    approve:           hasAction('APPROVE') && st === 'price_proposed'  && ROLE_PERMISSIONS.canApproveReject.includes(role),
    reject:            hasAction('REJECT') && st === 'price_proposed'  && ROLE_PERMISSIONS.canApproveReject.includes(role),
    generateQuotation: hasAction('GENERATE_QUOTATION') && (st === 'approved' || st === 'quotation_issued') && ROLE_PERMISSIONS.canGenerateQuotation.includes(role) && isOwner,
    // Issuing the deposit-notice DOCUMENT is the payment-track step (mirrors
    // DepositNoticeService.issue): customer must have confirmed first, and the
    // former no-document "ออกใบแจ้งมัดจำ" action is gone.
    generateDocument:  hasAction('ISSUE_DEPOSIT_NOTICE') && st === 'quotation_issued' && ps === 'CUSTOMER_CONFIRMED' && ROLE_PERMISSIONS.canCreateTickets.includes(role) && isOwner,
    revise:            (st === 'approved' || st === 'quotation_issued' || st === 'document_issued') && ROLE_PERMISSIONS.canCreateTickets.includes(role) && isOwner,
    // Legacy document_issued close only for pre-dual-track tickets (ps never set)
    // or fully paid ones — mirrors TicketService.close().
    close:            hasAction('CLOSE') && ((st === 'document_issued' && (ps == null || ps === 'FULLY_PAID'))
                        || (st === 'quotation_issued' && dualTrackDone)) && ROLE_PERMISSIONS.canCreateTickets.includes(role) && isOwner,
    cancel:           hasAction('CANCEL') && !TERMINAL.includes(st)   && isOwner,
    comment:          !TERMINAL.includes(st),
    editItems: hasAction('EDIT_ITEMS') && EDITABLE_STATUSES.includes(st) && ROLE_PERMISSIONS.canCreateTickets.includes(role) && isOwner,
    // Dual-track (ข้อ 13)
    confirmCustomer:    hasAction('CONFIRM_CUSTOMER') && st === 'quotation_issued' && (ps == null || ps === 'CUSTOMER_CONFIRMED') && isSales,
    // Money receipts are confirmed by ฝ่ายบัญชี (account role, CEO fallback) —
    // mirrors TicketService.ACCOUNT_ROLES.
    confirmDepositPaid: hasAction('DEPOSIT_PAID') && st === 'quotation_issued' && ps === 'DEPOSIT_NOTICE_ISSUED' && isAccount,
    // DEPOSIT_PAID also qualifies: accounting may confirm the deposit before
    // import gets to the IR (mirrors TicketService.issueImportRequest).
    issueImportRequest: hasAction('ISSUE_IMPORT_REQUEST') && st === 'quotation_issued' && fs == null && isImport,
    markIrSent:         hasAction('IR_SENT') && st === 'quotation_issued' && fs === 'IR_ISSUED' && isImport,
    markShipping:       hasAction('SHIPPING') && st === 'quotation_issued' && fs === 'IR_SENT' && isImport,
    markGoodsReceived:  hasAction('GOODS_RECEIVED') && st === 'quotation_issued' && fs === 'SHIPPING' && isImport,
    confirmFinalPayment:hasAction('FINAL_PAYMENT') && st === 'quotation_issued' && ps === 'AWAITING_FINAL_PAYMENT' && isAccount,
    downloadRemainingInvoice: st === 'quotation_issued' && fs === 'GOODS_RECEIVED' && isSales,
  };

  // การดำเนินการอื่น ๆ now holds only the rare actions — everything workflow-
  // shaped (submit/pickup/propose/dual-track confirmations/close) moved into the
  // DealStagePanel cockpit, and docs live in its เอกสารของขั้นนี้ row. The
  // section hides entirely when none of the remaining actions apply.
  const hasActions = can.calculatePrices || can.revise || can.editItems || can.cancel
    || (st === 'draft' && isOwner && items.length === 0);

  const status = ticketStatusLabel(st);

  // Next-action summary for the current viewer only — derived strictly from the
  // `can` flags above (which already encode real status+role permission checks).
  // Verified against backend/mock transition handlers (see api.tickets.approve/
  // reject/pickup/... in src/api/mockApi.js) so the wording matches what the
  // button actually does. Never invents an owner or action the data can't support.
  const NEXT_ACTION_STEPS = [
    ['submit',           'ส่งขอราคา — รายการสินค้าพร้อมแล้ว ส่งให้ฝ่ายนำเข้าเสนอราคาได้เลย'],
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

  // The cockpit's primary action: the ONE workflow button for this viewer's
  // current sub-step (moved verbatim out of การดำเนินการอื่น ๆ). Doc-shaped next
  // steps (ออกใบแจ้งยอดมัดจำ, IR) are NOT repeated here — they already sit in the
  // stage panel's docs row and the guidance line points at them. CEO price
  // approve/reject keeps its dedicated decision panel below.
  const primaryAction = can.submit ? (
    <button type="button" className="primary-button" disabled={actionLoading}
      onClick={() => doAction(() => api.tickets.submit(ticketId), 'ส่งขอราคาแล้ว')}>
      <Icon name="check" size={14} />
      ส่งขอราคา (เริ่มเสนอราคา)
    </button>
  ) : can.pickup ? (
    <button type="button" className="primary-button" disabled={actionLoading}
      onClick={() => doAction(() => api.tickets.pickup(ticketId), 'รับมอบหมายแล้ว')}>
      <Icon name="check" size={14} />
      รับเรื่อง
    </button>
  ) : can.propose && !proposeMode ? (
    <button
      type="button"
      className={st === 'approved' ? 'secondary-button' : 'primary-button'}
      style={st === 'approved' ? { borderColor: 'var(--color-warning-border)', color: 'var(--color-warning-dark)', background: 'var(--color-warning-bg-soft)' } : {}}
      disabled={actionLoading}
      onClick={initPropose}
    >
      <Icon name="pencil" size={14} />
      {st === 'in_review' && 'เสนอราคาสินค้า'}
      {st === 'price_proposed' && 'แก้ไขราคาที่เสนอ'}
      {st === 'approved' && 'แก้ไขราคา (ต้องอนุมัติใหม่)'}
    </button>
  ) : can.confirmCustomer ? (
    <button type="button" className="primary-button" disabled={actionLoading}
      onClick={() => doAction(() => api.tickets.confirmCustomer(ticketId), 'ลูกค้ายืนยันแล้ว')}>
      ลูกค้ายืนยัน
    </button>
  ) : can.confirmDepositPaid ? (
    <button type="button" className="primary-button" disabled={actionLoading}
      onClick={() => doAction(() => api.tickets.confirmDepositPaid(ticketId), 'ยืนยันรับมัดจำแล้ว')}>
      ยืนยันรับมัดจำ
    </button>
  ) : can.markIrSent ? (
    <button type="button" className="primary-button" disabled={actionLoading}
      onClick={() => doAction(() => api.tickets.markIrSent(ticketId), 'ส่ง IR แล้ว')}>
      ส่ง IR แล้ว
    </button>
  ) : can.markShipping ? (
    <button type="button" className="primary-button" disabled={actionLoading}
      onClick={() => doAction(() => api.tickets.markShipping(ticketId), 'สินค้าอยู่ระหว่างขนส่ง')}>
      สินค้าออกเดินทาง (Shipping)
    </button>
  ) : can.markGoodsReceived ? (
    <button type="button" className="primary-button" disabled={actionLoading}
      onClick={() => doAction(() => api.tickets.markGoodsReceived(ticketId), 'รับสินค้าแล้ว')}>
      รับสินค้าแล้ว (Goods Received)
    </button>
  ) : can.confirmFinalPayment ? (
    <button type="button" className="primary-button" disabled={actionLoading}
      onClick={() => doAction(() => api.tickets.confirmFinalPayment(ticketId), 'ชำระครบแล้ว')}>
      ยืนยันชำระครบ (Final Payment)
    </button>
  ) : can.close ? (
    <button type="button" className="primary-button" disabled={actionLoading}
      onClick={() => doAction(() => api.tickets.close(ticketId), 'ปิดใบขอราคาแล้ว')}>
      <Icon name="check" size={14} />
      ปิดเรื่อง
    </button>
  ) : null;

  function initPropose() {
    // Factory configs are now a query (fetched once per session, see above);
    // use whatever's in cache — same fallback-to-empty-object behavior as the
    // old lazy fetch if it hasn't resolved yet.
    const fcMap = factoryConfigs;

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
    try {
      await calculatePricesMutation.mutateAsync();
    } catch { /* onError above already toasted */ }
  }

  async function handleOverridePrice(itemId) {
    const draft = overrideDraft[itemId];
    if (!draft?.price || isNaN(Number(draft.price)) || Number(draft.price) <= 0) {
      showToast('error', 'กรุณากรอกราคา override ที่ถูกต้อง');
      return;
    }
    try {
      await overrideMutation.mutateAsync({
        itemId,
        payload: { manualPrice: Number(draft.price), reason: draft.reason || null },
      });
    } catch { /* onError above already toasted */ }
  }

  async function handleUploadAttachment(e) {
    const file = e.target.files?.[0];
    if (!file) return;
    const attachType = file.name.toLowerCase().includes('po') ? 'PO' : 'OTHER';
    try {
      await uploadAttachmentMutation.mutateAsync({ file, attachType });
    } catch { /* onError above already toasted */ } finally {
      e.target.value = '';
    }
  }

  function handleDeleteAttachment(id, name) {
    setConfirm({ kind: 'deleteAttachment', id, name });
  }

  async function confirmDeleteAttachment(id) {
    try {
      await deleteAttachmentMutation.mutateAsync(id);
    } catch { /* onError above already toasted */ } finally {
      setConfirm(null);
    }
  }

  async function handleDownloadQuotation(quotationId, number, format) {
    const key = `${quotationId}-${format}`;
    setDownloadingQuotationKey(key);
    try {
      const blob = format === 'pdf'
        ? await api.tickets.downloadQuotationPdf(ticketId, quotationId)
        : await api.tickets.downloadQuotationXlsx(ticketId, quotationId);
      downloadBlob(blob, number ?? 'quotation', format);
    } catch (err) {
      showToast('error', err.message || 'ดาวน์โหลดไม่สำเร็จ');
    } finally {
      setDownloadingQuotationKey((current) => (current === key ? null : current));
    }
  }

  function openQuotationModal(recipientType = null) {
    const defaultRecipientType = recipientType
      ?? (summary.salesStage === 'QUOTE_BUYER' ? 'BUYER' : 'DESIGNER');
    const chainAccepted = (quotations ?? []).some((q) =>
      (q.recipientType ?? 'UNSPECIFIED') === defaultRecipientType && q.docStatus === 'ACCEPTED');
    const amendmentRequired = chainAccepted || summary.paymentStatus != null;
    setQuotationDraft({
      recipientType: defaultRecipientType,
      recipientLabel: '',
      paymentTerms: '',
      leadTime: '',
      deliveryTerms: '',
      validityDate: '',
      amendmentReason: '',
    });
    setQuotationModal({ amendmentRequired });
  }

  async function submitQuotation() {
    if (!quotationDraft.recipientType) {
      showToast('error', 'กรุณาเลือกผู้รับใบเสนอราคา');
      return;
    }
    if (quotationModal?.amendmentRequired && !quotationDraft.amendmentReason.trim()) {
      showToast('error', 'กรุณาระบุเหตุผลการแก้ไขใบเสนอราคา');
      return;
    }
    const payload = Object.fromEntries(Object.entries(quotationDraft).map(([key, value]) => [
      key,
      typeof value === 'string' && value.trim() === '' ? null : value,
    ]));
    await doAction(() => api.tickets.quotation(ticketId, payload), 'ออกใบเสนอราคาแล้ว');
  }

  async function markQuotation(q, action) {
    const fn = action === 'sent'
      ? () => api.tickets.markQuotationSent(ticketId, q.id, {})
      : action === 'accepted'
        ? () => api.tickets.markQuotationAccepted(ticketId, q.id, {})
        : () => api.tickets.markQuotationRejected(ticketId, q.id, {});
    const message = action === 'sent'
      ? 'บันทึกว่าส่งใบเสนอราคาแล้ว'
      : action === 'accepted'
        ? 'บันทึกลูกค้ารับใบเสนอราคาแล้ว'
        : 'บันทึกลูกค้าปฏิเสธใบเสนอราคาแล้ว';
    await doAction(fn, message);
  }

  async function handleDownloadRemainingInvoice() {
    setDownloadingInvoice(true);
    try {
      const blob = await api.tickets.downloadRemainingInvoice(ticketId);
      downloadBlob(blob, `remaining-invoice-${ticketId}`, 'xlsx');
    } catch (err) {
      showToast('error', err.message || 'ดาวน์โหลดไม่สำเร็จ');
    } finally {
      setDownloadingInvoice(false);
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
          <h1 style={{ margin: 0, fontSize: 22, fontWeight: 800, color: 'var(--color-text)' }}>{summary.customerName || summary.title}</h1>
          <div style={{ display: 'flex', gap: 8, alignItems: 'center', marginTop: 8, flexWrap: 'wrap' }}>
            <code style={{ fontSize: 13, background: 'var(--color-surface-subtle)', padding: '2px 8px', borderRadius: 4 }}>{summary.code}</code>
            <StatusBadge tone={status.tone}>{status.label}</StatusBadge>
            {summary.hasEdits && (
              <StatusBadge tone="warning">✎ มีการแก้ไข</StatusBadge>
            )}
          </div>
          {/* Summary: who created it, when, and who is currently handling it — all
              fields already fetched on `summary` (no new API calls / no invented owner). */}
          <div style={{ display: 'flex', flexWrap: 'wrap', gap: '4px 16px', marginTop: 10, fontSize: 13, color: 'var(--color-text-muted)' }}>
            <span>สร้างโดย <strong style={{ color: 'var(--color-text-secondary)' }}>{summary.createdByName || '-'}</strong> · {formatThaiDate(summary.createdAt)}</span>
            {summary.assignedToName && (
              <span>เจ้าหน้าที่นำเข้าที่ดูแล <strong style={{ color: 'var(--color-text-secondary)' }}>{summary.assignedToName}</strong></span>
            )}
          </div>
        </div>
        <button type="button" className="icon-button" onClick={refreshTicket} title="รีเฟรช" aria-label="รีเฟรช">
          <Icon name="refresh" />
        </button>
      </header>

      {/* Deal pipeline (V50): the 14-stage journey with stage-gated doc actions.
          Generation buttons reuse the exact handlers/permissions of the action
          row; once a document exists (quotation / ใบแจ้งยอดมัดจำ) it stays
          reachable from here through the later stages too. */}
      <DealStagePanel
        user={user}
        summary={summary}
        availableActions={availableActions}
        primaryAction={primaryAction}
        guidance={nextAction ?? waitingHint}
        actionLoading={actionLoading}
        onUpdateStage={(payload) => doAction(() => api.tickets.updateStage(ticketId, payload), 'อัปเดตสถานะดีลแล้ว')}
        onMarkLost={(payload) => doAction(() => api.tickets.markLost(ticketId, payload), 'บันทึกเสียงานแล้ว')}
        onReopen={() => doAction(() => api.tickets.reopen(ticketId, {}), 'เปิดดีลอีกครั้งแล้ว')}
        onHold={(payload) => doAction(() => api.tickets.hold(ticketId, payload), 'พักดีลไว้แล้ว')}
        onDormant={(payload) => doAction(() => api.tickets.dormant(ticketId, payload), 'พัก dormant แล้ว')}
        onResume={(payload) => doAction(() => api.tickets.resume(ticketId, payload), 'ดำเนินการต่อแล้ว')}
        onSetTenderRequirement={(payload) => doAction(() => api.tickets.setTenderRequirement(ticketId, payload), 'บันทึกสถานะประมูลแล้ว')}
        onSetDepositPolicy={(payload) => doAction(() => api.tickets.setDepositPolicy(ticketId, payload), 'บันทึกนโยบายมัดจำแล้ว')}
        docActions={(can.generateQuotation || can.generateDocument || can.issueImportRequest
          || can.downloadRemainingInvoice || latestQuotation || depositNoticeIssued) ? (
          <>
            {can.generateQuotation && (
              <button type="button" className="secondary-button" disabled={actionLoading}
                onClick={() => openQuotationModal()}>
                <Icon name="fileText" size={14} />
                {quotations && quotations.length > 0 ? 'ออกใบเสนอราคาใหม่ (Rev)' : 'ออกใบเสนอราคา'}
              </button>
            )}
            {latestQuotation && (
              <button type="button" className="secondary-button"
                disabled={downloadingQuotationKey === `${latestQuotation.id}-pdf`}
                onClick={() => handleDownloadQuotation(latestQuotation.id, latestQuotation.number, 'pdf')}>
                <Icon name="fileText" size={14} />
                {downloadingQuotationKey === `${latestQuotation.id}-pdf`
                  ? 'กำลังดาวน์โหลด...'
                  : `ใบเสนอราคา ${latestQuotation.number} (PDF)`}
              </button>
            )}
            {can.generateDocument && (
              <button type="button" className="primary-button" disabled={actionLoading}
                onClick={() => onOpenDocument && onOpenDocument(ticketId)}>
                <Icon name="fileText" size={14} />
                ออกใบแจ้งยอดมัดจำ
              </button>
            )}
            {depositNoticeIssued && (
              <button type="button" className="secondary-button"
                onClick={() => onOpenDocument && onOpenDocument(ticketId)}>
                <Icon name="fileText" size={14} />
                ดูใบแจ้งยอดมัดจำ
              </button>
            )}
            {can.issueImportRequest && (
              <button type="button" className="primary-button" disabled={actionLoading}
                onClick={() => doAction(() => api.tickets.issueImportRequest(ticketId), 'ออก IR แล้ว')}>
                ออก Import Request (IR)
              </button>
            )}
            {can.downloadRemainingInvoice && (
              <button type="button" className="secondary-button" disabled={downloadingInvoice}
                onClick={handleDownloadRemainingInvoice}>
                {downloadingInvoice ? 'กำลังดาวน์โหลด...' : 'ดาวน์โหลดใบแจ้งหนี้ส่วนที่เหลือ'}
              </button>
            )}
          </>
        ) : null}
      />

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
            <p style={{ margin: 0, fontSize: 13, color: 'var(--color-icon-muted)', lineHeight: 1.6 }}>
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
                    style={{ color: 'var(--color-danger)', borderColor: 'var(--color-danger-border)' }}>
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
                    style={{ color: 'var(--color-danger)', borderColor: 'var(--color-danger-border)' }}>
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
        <section className="panel" style={{ background: 'var(--color-surface-muted)' }}>
          <div className="panel-header">
            <h2>การดำเนินการอื่น ๆ</h2>
          </div>
          <div style={{ padding: '12px 18px', display: 'flex', flexWrap: 'wrap', gap: 10 }}>
            {st === 'draft' && isOwner && items.length === 0 && (
              <span className="rounded-lg border border-border bg-surface-subtle px-3 py-2 text-xs text-text-muted">
                ดีลนี้ยังไม่มีรายการสินค้า — กด “แก้ไขรายการสินค้า” เพื่อเพิ่มก่อนส่งขอราคา
              </span>
            )}
            {can.calculatePrices && (
              <button type="button" className="secondary-button" disabled={calcLoading || actionLoading}
                onClick={handleCalculatePrices}
                style={{ background: 'var(--color-info-row-active)', borderColor: 'var(--color-info-border-strong)', color: 'var(--color-info)' }}>
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

            {can.revise && !showReviseForm && (
              <button type="button" className="secondary-button" disabled={actionLoading}
                onClick={() => setShowReviseForm(true)}>
                <Icon name="pencil" size={14} />
                ขอแก้ไข (Revise)
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
                style={{ marginLeft: 'auto', color: 'var(--color-danger)', borderColor: 'var(--color-danger-border)' }}
                onClick={() => setConfirm({ kind: 'cancelTicket' })}>
                ยกเลิก
              </button>
            )}
          </div>

          {showReviseForm && (
            <div style={{ padding: '0 18px 14px', display: 'flex', flexDirection: 'column', gap: 10, borderTop: '1px solid var(--color-border)' }}>
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
                    style={{ marginTop: 2, flexShrink: 0, width: 16, height: 16, accentColor: 'var(--color-info-dot)', cursor: 'pointer' }} />
                  <span>
                    <strong>{opt.label}</strong>
                    <span style={{ display: 'block', fontSize: 12, color: 'var(--color-text-muted)' }}>{opt.sub}</span>
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
            <div className="panel-header" style={{ padding: '14px 18px', borderBottom: '1px solid var(--color-border)', display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
              <h2>รายการสินค้า ({editMode ? editDraft.length : items.length} รายการ)</h2>
            </div>

            {editMode ? (
              <div style={{ padding: '14px 18px' }}>
                {editDraft.map((item, index) => (
                  <div key={index} style={{ border: '1px solid var(--color-border-subtle)', borderRadius: 8, padding: '12px 14px', marginBottom: 10, background: 'var(--color-surface-muted)' }}>
                    <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 8 }}>
                      <span style={{ fontSize: 12, fontWeight: 700, color: 'var(--color-text-muted)' }}>รายการที่ {index + 1}</span>
                      {editDraft.length > 1 && (
                        <button type="button" className="icon-button" style={{ color: 'var(--color-danger)' }} aria-label={`ลบรายการที่ ${index + 1}`}
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
                                style={{ width: 16, height: 16, accentColor: 'var(--color-info-dot)', cursor: 'pointer' }} />
                              <strong>{opt.label}</strong>
                            </label>
                          ))}
                          {item.sqmPerPiece && (
                            <span style={{ fontSize: 11, color: 'var(--color-text-muted)' }}>· 1 แผ่น = {item.sqmPerPiece} ตร.ม.</span>
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
                            <div style={{ padding: '7px 10px', border: '1px solid var(--color-border-subtle)', borderRadius: 6, background: 'var(--color-surface-muted)', fontSize: 13, color: item.qtySqm ? 'var(--color-icon-muted)' : 'var(--color-text-faint)' }}>
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
                            <div style={{ padding: '7px 10px', border: '1px solid var(--color-border-subtle)', borderRadius: 6, background: 'var(--color-surface-muted)', fontSize: 13, color: item.qty ? 'var(--color-icon-muted)' : 'var(--color-text-faint)' }}>
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
                        <div style={{ padding: '8px 18px', background: 'var(--color-surface-subtle)', fontSize: 12, fontWeight: 700, color: 'var(--color-info-dot)', borderTop: '1px solid var(--color-border-subtle)', display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
                          <Icon name="building" size={13} />
                          <span>{factory}</span>
                          <span style={{ fontWeight: 400, color: 'var(--color-text-muted)' }}>({groupItems.length} รายการ)</span>
                          {fc?.email && <span style={{ fontWeight: 400, color: 'var(--color-text-muted)', fontSize: 11 }}>· {fc.email}</span>}
                          <button type="button" className="secondary-button"
                            style={{ marginLeft: 'auto', fontSize: 12, padding: '4px 12px' }}
                            onClick={() => setEmailDraft(isDraftOpen ? null : buildEmailDraft(factory, groupItems))}>
                            <Icon name="fileText" size={13} />
                            {isDraftOpen ? 'ปิดร่างอีเมล' : 'ร่างอีเมล'}
                          </button>
                        </div>

                        {/* Currency/unit settings bar */}
                        <div style={{ padding: '10px 18px', background: 'var(--color-surface-muted)', borderTop: '1px solid var(--color-border)', display: 'flex', gap: 20, alignItems: 'center', flexWrap: 'wrap' }}>
                          <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
                            <span style={{ fontSize: 13, color: 'var(--color-icon-muted)', fontWeight: 600, whiteSpace: 'nowrap' }}>สกุลเงิน</span>
                            <InfoTip label="สกุลเงินและหน่วยนับ" text="เลือกครั้งเดียวต่อโรงงาน ใช้กับทุกรายการของโรงงานนี้" />
                            <select
                              value={draftFactoryCurr[factory]?.currency ?? 'THB'}
                              onChange={(e) => setDraftFactoryCurr((p) => ({ ...p, [factory]: { ...p[factory], currency: e.target.value } }))}
                              style={{ fontSize: 14, padding: '5px 10px', border: '1px solid var(--color-border-muted)', borderRadius: 6, background: 'var(--color-surface)', cursor: 'pointer', fontWeight: 600, color: 'var(--color-info-dot)' }}>
                              {['THB','EUR','USD','JPY','CNY','GBP'].map((c) => <option key={c}>{c}</option>)}
                            </select>
                          </div>
                          <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
                            <span style={{ fontSize: 13, color: 'var(--color-icon-muted)', fontWeight: 600, whiteSpace: 'nowrap' }}>หน่วยราคา</span>
                            <select
                              value={draftFactoryCurr[factory]?.unit ?? 'piece'}
                              onChange={(e) => setDraftFactoryCurr((p) => ({ ...p, [factory]: { ...p[factory], unit: e.target.value } }))}
                              style={{ fontSize: 14, padding: '5px 10px', border: '1px solid var(--color-border-muted)', borderRadius: 6, background: 'var(--color-surface)', cursor: 'pointer', fontWeight: 600, color: 'var(--color-info-dot)' }}>
                              <option value="piece">/ แผ่น</option>
                              <option value="sqm">/ ตร.ม.</option>
                              <option value="box">/ กล่อง</option>
                            </select>
                          </div>
                        </div>

                        {/* Email draft panel */}
                        {isDraftOpen && emailDraft && (
                          <div style={{ margin: '0 18px 8px', padding: '12px', border: '1px solid var(--color-info-border)', borderRadius: 8, background: 'var(--color-info-row-active)', fontSize: 13 }}>
                            <p style={{ margin: '0 0 8px', fontWeight: 700, fontSize: 12, color: 'var(--color-info)' }}>ร่างอีเมลถึง {factory}</p>
                            <label style={{ display: 'block', marginBottom: 6 }}>
                              <span style={{ fontSize: 11, color: 'var(--color-text-muted)' }}>ถึง (To)</span>
                              <input value={emailDraft.to} onChange={(e) => setEmailDraft((d) => ({ ...d, to: e.target.value }))} style={{ marginTop: 2 }} />
                            </label>
                            <label style={{ display: 'block', marginBottom: 6 }}>
                              <span style={{ fontSize: 11, color: 'var(--color-text-muted)' }}>หัวข้อ (Subject)</span>
                              <input value={emailDraft.subject} onChange={(e) => setEmailDraft((d) => ({ ...d, subject: e.target.value }))} style={{ marginTop: 2 }} />
                            </label>
                            <label style={{ display: 'block', marginBottom: 8 }}>
                              <span style={{ fontSize: 11, color: 'var(--color-text-muted)' }}>เนื้อหา</span>
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
                                {item.model && <small style={{ color: 'var(--color-text-muted)' }}>{item.model}</small>}
                              </span>
                              <span data-label="สี / เนื้อผิว" style={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
                                {item.color && <span>{item.color}</span>}
                                {item.texture && <small style={{ color: 'var(--color-text-muted)' }}>{item.texture}</small>}
                                {item.size && <small style={{ color: 'var(--color-text-muted)' }}>{item.size}</small>}
                              </span>
                              <span data-label="จำนวน">
                                {item.unitBasis === 'SQM'
                                  ? <>{item.qtySqm != null ? `${Number(item.qtySqm).toFixed(2)} ตร.ม.` : '—'}<small style={{ display: 'block', color: 'var(--color-text-muted)' }}>{item.qty} แผ่น</small></>
                                  : <>{item.qty} แผ่น{item.qtySqm != null && <small style={{ display: 'block', color: 'var(--color-text-muted)' }}>{Number(item.qtySqm).toFixed(2)} ตร.ม.</small>}</>
                                }
                              </span>
                              <div data-label="ราคาที่เสนอ (แก้ไข)" style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
                                <input type="number" min="0" step="0.0001"
                                  value={draftRaw[item.id] ?? ''}
                                  onChange={(e) => setDraftRaw((prev) => ({ ...prev, [item.id]: e.target.value }))}
                                  placeholder={`ราคา/${unitLabel}`}
                                  title="ราคาต่อหน่วยของรายการนี้เท่านั้น (สกุลเงิน/หน่วยนับใช้ค่าที่ตั้งไว้ของโรงงาน)"
                                  style={{ width: 110, padding: '4px 8px', border: '1px solid var(--color-info-border-strong)', borderRadius: 4, fontSize: 13 }} />
                                <span style={{ fontSize: 11, color: 'var(--color-link)', whiteSpace: 'nowrap' }}>{currLabel}/{unitLabel}</span>
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
                      {item.model && <small style={{ color: 'var(--color-text-muted)' }}>{item.model}</small>}
                      {item.factory && <small style={{ color: 'var(--color-text-muted)', fontSize: 11 }}>{item.factory}</small>}
                    </span>
                    <span data-label="สี / เนื้อผิว" style={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
                      {item.color && <span>{item.color}</span>}
                      {item.texture && <small style={{ color: 'var(--color-text-muted)' }}>{item.texture}</small>}
                      {item.size && <small style={{ color: 'var(--color-text-muted)' }}>{item.size}</small>}
                    </span>
                    <span data-label="จำนวน">
                      {item.unitBasis === 'SQM'
                        ? <>{item.qtySqm != null ? `${Number(item.qtySqm).toFixed(2)} ตร.ม.` : '—'}<small style={{ display: 'block', color: 'var(--color-text-muted)' }}>{item.qty} แผ่น</small></>
                        : <>{item.qty} แผ่น{item.qtySqm != null && <small style={{ display: 'block', color: 'var(--color-text-muted)' }}>{Number(item.qtySqm).toFixed(2)} ตร.ม.</small>}</>
                      }
                    </span>
                    {showCalcBreakdown ? (
                      <>
                        <span data-label="ราคาโรงงาน" style={{ fontSize: 12 }}>
                          {item.rawPrice != null
                            ? <><strong>{Number(item.rawPrice).toLocaleString('th-TH', { minimumFractionDigits: 2 })}</strong><small style={{ color: 'var(--color-text-muted)' }}> {item.rawCurrency}/{item.rawUnit === 'sqm' ? 'ตร.ม.' : 'แผ่น'}</small></>
                            : <span style={{ color: 'var(--color-text-muted)' }}>-</span>}
                          {item.calcConfigVersion && <small style={{ display: 'block', color: 'var(--color-text-muted)', fontSize: 10 }}>config v{item.calcConfigVersion}</small>}
                        </span>
                        <code data-label="ต้นทุน (THB/ชิ้น)" style={{ color: 'var(--color-info)' }}>{item.calcedCost != null ? formatMoney(item.calcedCost) : '—'}</code>
                        <span data-label="ราคาขาย (THB/ชิ้น)">
                          <code style={{ color: item.manualPrice != null ? 'var(--color-override)' : 'var(--color-success)', fontWeight: 700 }}>
                            {item.manualPrice != null ? formatMoney(item.manualPrice) : item.calcedPrice != null ? formatMoney(item.calcedPrice) : '—'}
                          </code>
                          {item.manualPrice != null && <small style={{ display: 'block', color: 'var(--color-override)', fontSize: 10 }}>override</small>}
                          {can.overridePrice && (
                            overrideDraft[item.id] !== undefined ? (
                              <div style={{ display: 'flex', flexDirection: 'column', gap: 4, marginTop: 4 }}>
                                <input type="number" step="0.01" min="0"
                                  placeholder="ราคา override"
                                  value={overrideDraft[item.id]?.price ?? ''}
                                  onChange={(e) => setOverrideDraft((p) => ({ ...p, [item.id]: { ...p[item.id], price: e.target.value } }))}
                                  style={{ width: 90, padding: '2px 6px', fontSize: 12, border: '1px solid var(--color-override-border)', borderRadius: 4 }} />
                                <input type="text" placeholder="เหตุผล (ถ้ามี)"
                                  value={overrideDraft[item.id]?.reason ?? ''}
                                  onChange={(e) => setOverrideDraft((p) => ({ ...p, [item.id]: { ...p[item.id], reason: e.target.value } }))}
                                  style={{ width: 90, padding: '2px 6px', fontSize: 11, border: '1px solid var(--color-override-border)', borderRadius: 4 }} />
                                <div style={{ display: 'flex', gap: 4 }}>
                                  <button type="button" className="primary-button"
                                    style={{ fontSize: 10, padding: '2px 8px', background: 'var(--color-override)', borderColor: 'var(--color-override)' }}
                                    disabled={isOverridingItem(item.id)}
                                    onClick={() => handleOverridePrice(item.id)}>
                                    {isOverridingItem(item.id) ? '...' : 'บันทึก'}
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
                  <div style={{ padding: '12px 18px', display: 'flex', flexDirection: 'column', gap: 8, borderTop: '1px solid var(--color-border)' }}>
                    {st === 'approved' && (
                      <div style={{ padding: '10px 14px', borderRadius: 8, background: 'var(--color-warning-bg-soft)', border: '1px solid var(--color-warning-border)', fontSize: 13, color: 'var(--color-warning-dark)', display: 'flex', gap: 8, alignItems: 'flex-start' }}>
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
                  <div key={b.itemId} style={{ marginBottom: 16, padding: '12px 18px', borderBottom: '1px solid var(--color-surface-subtle)' }}>
                    <div style={{ fontWeight: 700, fontSize: 13, marginBottom: 8 }}>
                      {b.brand} {b.model && <span style={{ fontWeight: 400, color: 'var(--color-text-muted)' }}>({b.model})</span>}
                      {b.factory && <small style={{ color: 'var(--color-text-muted)', marginLeft: 8 }}>{b.factory}</small>}
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
                          <tr key={label} style={{ borderBottom: '1px solid var(--color-surface-muted)' }}>
                            <td style={{ padding: '3px 10px 3px 0', color: 'var(--color-icon-muted)', whiteSpace: 'nowrap' }}>{label}</td>
                            <td style={{ padding: '3px 0', fontWeight: 600, textAlign: 'right', minWidth: 80 }}>
                              {value != null ? Number(value).toLocaleString('th-TH', { minimumFractionDigits: 4 }) : ''}
                            </td>
                            <td style={{ padding: '3px 0 3px 10px', color: 'var(--color-text-muted)', fontSize: 10 }}>{note}</td>
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
                  <div key={i} style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '6px 10px', background: 'var(--color-surface-muted)', borderRadius: 6, border: '1px solid var(--color-border-subtle)' }}>
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
                  <div key={att.id} style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '6px 10px', background: 'var(--color-surface-muted)', borderRadius: 6, border: '1px solid var(--color-border-subtle)' }}>
                    <Icon name="paperclip" size={13} style={{ color: 'var(--color-text-muted)', flexShrink: 0 }} />
                    <span style={{ flex: 1, fontSize: 13, color: 'var(--color-text)', wordBreak: 'break-all' }}>{att.fileName}</span>
                    <span style={{ fontSize: 11, color: 'var(--color-text-muted)', whiteSpace: 'nowrap', background: 'var(--color-surface-subtle)', padding: '1px 6px', borderRadius: 99 }}>
                      {att.attachType}
                    </span>
                    <a href={api.attachments.fileUrl(att.id)} target="_blank" rel="noreferrer"
                      style={{ fontSize: 12, color: 'var(--color-link)', textDecoration: 'none', whiteSpace: 'nowrap' }}>
                      ดูไฟล์
                    </a>
                    {!TERMINAL.includes(st) && (
                      <button type="button" className="icon-button"
                        style={{ color: 'var(--color-danger)', flexShrink: 0 }}
                        onClick={() => handleDeleteAttachment(att.id, att.fileName)}>
                        <Icon name="close" size={13} />
                      </button>
                    )}
                  </div>
                ))}
              </div>
            )}
          </section>

          {quotationGroups.length > 0 && (
            <section className="panel">
              <div className="panel-header">
                <h2>ใบเสนอราคา</h2>
              </div>
              {quotationGroups.map((group) => (
                <div key={group.recipientType} style={{ borderTop: '1px solid var(--color-surface-subtle)' }}>
                  <div style={{ padding: '12px 18px 6px', display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 10 }}>
                    <h3 style={{ margin: 0, fontSize: 14 }}>{group.label}</h3>
                    {can.generateQuotation && (
                      <button type="button" className="secondary-button" style={{ fontSize: 12, padding: '4px 10px' }}
                        disabled={actionLoading}
                        onClick={() => openQuotationModal(group.recipientType)}>
                        <Icon name="fileText" size={12} /> Revise
                      </button>
                    )}
                  </div>
                  {group.quotations.map((q, index) => {
                    const status = quotationStatusLabel(q.docStatus);
                    const activeHead = index === 0 && !['SUPERSEDED', 'CANCELLED', 'REJECTED'].includes(q.docStatus);
                    const canMarkSent = activeHead && hasAction('MARK_QUOTATION_SENT') && q.docStatus === 'ISSUED';
                    const canMarkDecision = activeHead && ['ISSUED', 'SENT'].includes(q.docStatus);
                    return (
                      <div key={q.id} style={{ padding: '10px 18px', borderTop: '1px solid var(--color-surface-subtle)', display: 'flex', alignItems: 'flex-start', gap: 10 }}>
                        <div style={{ flexShrink: 0, marginTop: 2 }}>
                          <span style={{
                            fontSize: 11, fontWeight: 700, borderRadius: 4, padding: '2px 7px',
                            ...docStatusColors(q.docStatus),
                          }}>Rev {q.quotationVersion}</span>
                        </div>
                        <div style={{ flex: 1, minWidth: 0 }}>
                          <div style={{ display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap' }}>
                            <span style={{ fontWeight: 600, fontSize: 13 }}>{q.number}</span>
                            <StatusBadge tone={status.tone}>{status.label}</StatusBadge>
                            {q.recipientLabel && <span style={{ fontSize: 12, color: 'var(--color-text-muted)' }}>{q.recipientLabel}</span>}
                          </div>
                          <div style={{ fontSize: 12, color: 'var(--color-icon-muted)', marginTop: 2 }}>
                            ยอดรวม {formatMoney(q.totalAmount)} · ออกโดย {q.issuedByName} · ออก {formatThaiDate(q.issuedAt)}
                            {q.sentAt ? ` · ส่ง ${formatThaiDate(q.sentAt)}` : ''}
                            {q.acceptedAt ? ` · รับ ${formatThaiDate(q.acceptedAt)}` : ''}
                            {q.validityDate ? ` · ใช้ได้ถึง ${formatThaiDate(q.validityDate)}` : ''}
                          </div>
                          {(q.paymentTerms || q.leadTime || q.deliveryTerms) && (
                            <div style={{ fontSize: 12, color: 'var(--color-text-muted)', marginTop: 4 }}>
                              {[q.paymentTerms && `ชำระเงิน: ${q.paymentTerms}`, q.leadTime && `Lead time: ${q.leadTime}`, q.deliveryTerms && `ส่งมอบ: ${q.deliveryTerms}`].filter(Boolean).join(' · ')}
                            </div>
                          )}
                        </div>
                        <div style={{ display: 'flex', gap: 6, flexShrink: 0, flexWrap: 'wrap', justifyContent: 'flex-end' }}>
                          {canMarkSent && (
                            <button type="button" className="secondary-button" style={{ fontSize: 12, padding: '4px 10px' }}
                              disabled={actionLoading}
                              onClick={() => markQuotation(q, 'sent')}>
                              ส่งแล้ว
                            </button>
                          )}
                          {canMarkDecision && hasAction('MARK_QUOTATION_ACCEPTED') && (
                            <button type="button" className="secondary-button" style={{ fontSize: 12, padding: '4px 10px' }}
                              disabled={actionLoading}
                              onClick={() => markQuotation(q, 'accepted')}>
                              รับแล้ว
                            </button>
                          )}
                          {canMarkDecision && hasAction('MARK_QUOTATION_REJECTED') && (
                            <button type="button" className="secondary-button" style={{ fontSize: 12, padding: '4px 10px' }}
                              disabled={actionLoading}
                              onClick={() => markQuotation(q, 'rejected')}>
                              ปฏิเสธ
                            </button>
                          )}
                          <button type="button" className="secondary-button" style={{ fontSize: 12, padding: '4px 10px' }}
                            disabled={downloadingQuotationKey === `${q.id}-xlsx`}
                            onClick={() => handleDownloadQuotation(q.id, q.number, 'xlsx')}>
                            <Icon name="fileText" size={12} /> {downloadingQuotationKey === `${q.id}-xlsx` ? 'กำลังดาวน์โหลด...' : 'Excel'}
                          </button>
                          <button type="button" className="secondary-button" style={{ fontSize: 12, padding: '4px 10px' }}
                            disabled={downloadingQuotationKey === `${q.id}-pdf`}
                            onClick={() => handleDownloadQuotation(q.id, q.number, 'pdf')}>
                            <Icon name="fileText" size={12} /> {downloadingQuotationKey === `${q.id}-pdf` ? 'กำลังดาวน์โหลด...' : 'PDF'}
                          </button>
                        </div>
                      </div>
                    );
                  })}
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
                    <strong style={{ display: 'block', fontSize: 13, color: 'var(--color-text)' }}>
                      {EVENT_KIND_LABEL[event.kind] ?? event.kind}
                    </strong>
                    <span style={{ color: 'var(--color-icon-muted)', fontSize: 12 }}>{event.actorName}</span>
                    {event.message && (
                      <p style={{ margin: '4px 0 0', fontSize: 12, color: 'var(--color-text-muted)', background: 'var(--color-surface-muted)', borderRadius: 4, padding: '4px 8px' }}>
                        {event.message}
                      </p>
                    )}
                    {snapItems && snapItems.length > 0 && (
                      <div style={{ margin: '6px 0 0', fontSize: 11, color: 'var(--color-icon-muted)', background: 'var(--color-surface-muted)', borderRadius: 4, padding: '6px 10px' }}>
                        <div style={{ fontWeight: 600, marginBottom: 4, color: 'var(--color-text-muted)' }}>รายการสินค้า ณ เวลาที่เสนอราคา</div>
                        {snapItems.map((it, i) => (
                          <div key={i} style={{ paddingBottom: 2 }}>
                            {it.brand} {it.model} — {it.qty} ชิ้น
                            {it.rawPrice != null && (
                              <span style={{ color: 'var(--color-text-muted)', marginLeft: 4 }}>
                                @ {it.rawPrice} {it.rawCurrency}/{it.rawUnit}
                              </span>
                            )}
                          </div>
                        ))}
                      </div>
                    )}
                    <small style={{ color: 'var(--color-text-muted)', fontSize: 11 }}>{formatThaiDate(event.createdAt)}</small>
                  </div>
                </div>
              );
            })}
          </div>

          {can.comment && (
            <div style={{ padding: '12px 18px', borderTop: '1px solid var(--color-border)', display: 'flex', flexDirection: 'column', gap: 8 }}>
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

      {quotationModal && (
        <Modal
          title="ออกใบเสนอราคา"
          onClose={() => setQuotationModal(null)}
          footer={(
          <>
            <button type="button" className="secondary-button" onClick={() => setQuotationModal(null)}>
              ยกเลิก
            </button>
            <button type="button" className="primary-button" disabled={actionLoading} onClick={submitQuotation}>
              <Icon name="fileText" size={14} />
              {actionLoading ? 'กำลังบันทึก...' : 'ออกใบเสนอราคา'}
            </button>
          </>
          )}
        >
          <div style={{ display: 'grid', gap: 12 }}>
          <label style={{ display: 'grid', gap: 6, fontSize: 13, fontWeight: 600 }}>
            ผู้รับใบเสนอราคา
            <select
              value={quotationDraft.recipientType}
              onChange={(e) => {
                const recipientType = e.target.value;
                const chainAccepted = (quotations ?? []).some((q) =>
                  (q.recipientType ?? 'UNSPECIFIED') === recipientType && q.docStatus === 'ACCEPTED');
                setQuotationDraft((draft) => ({ ...draft, recipientType }));
                setQuotationModal((modal) => ({ ...(modal ?? {}), amendmentRequired: chainAccepted || summary.paymentStatus != null }));
              }}
            >
              <option value="DESIGNER">ผู้ออกแบบ</option>
              <option value="OWNER">เจ้าของ</option>
              <option value="BUYER">ผู้ซื้อ-ผู้รับเหมา</option>
            </select>
          </label>
          <label style={{ display: 'grid', gap: 6, fontSize: 13, fontWeight: 600 }}>
            ชื่อผู้รับ/บริษัท
            <input value={quotationDraft.recipientLabel}
              onChange={(e) => setQuotationDraft((draft) => ({ ...draft, recipientLabel: e.target.value }))} />
          </label>
          <label style={{ display: 'grid', gap: 6, fontSize: 13, fontWeight: 600 }}>
            เงื่อนไขชำระเงิน
            <textarea rows={2} value={quotationDraft.paymentTerms}
              onChange={(e) => setQuotationDraft((draft) => ({ ...draft, paymentTerms: e.target.value }))} />
          </label>
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(160px, 1fr))', gap: 10 }}>
            <label style={{ display: 'grid', gap: 6, fontSize: 13, fontWeight: 600 }}>
              Lead time
              <input value={quotationDraft.leadTime}
                onChange={(e) => setQuotationDraft((draft) => ({ ...draft, leadTime: e.target.value }))} />
            </label>
            <label style={{ display: 'grid', gap: 6, fontSize: 13, fontWeight: 600 }}>
              ใช้ได้ถึง
              <input type="date" value={quotationDraft.validityDate}
                onChange={(e) => setQuotationDraft((draft) => ({ ...draft, validityDate: e.target.value }))} />
            </label>
          </div>
          <label style={{ display: 'grid', gap: 6, fontSize: 13, fontWeight: 600 }}>
            เงื่อนไขส่งมอบ
            <textarea rows={2} value={quotationDraft.deliveryTerms}
              onChange={(e) => setQuotationDraft((draft) => ({ ...draft, deliveryTerms: e.target.value }))} />
          </label>
          {quotationModal?.amendmentRequired && (
            <label style={{ display: 'grid', gap: 6, fontSize: 13, fontWeight: 600 }}>
              เหตุผลการแก้ไข
              <textarea rows={3} value={quotationDraft.amendmentReason}
                onChange={(e) => setQuotationDraft((draft) => ({ ...draft, amendmentReason: e.target.value }))} />
            </label>
          )}
          </div>
        </Modal>
      )}

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
