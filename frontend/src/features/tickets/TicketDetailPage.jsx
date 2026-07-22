import { useEffect, useRef, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, ROLE_PERMISSIONS } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { Breadcrumbs } from '../../components/common/Breadcrumbs.jsx';
import { ConfirmDialog } from '../../components/common/ConfirmDialog.jsx';
import { EmptyState } from '../../components/common/EmptyState.jsx';
import { fieldErrorId } from '../../components/common/FormField.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { Modal } from '../../components/common/Modal.jsx';
import { Skeleton, SkeletonText } from '../../components/common/Skeleton.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import {
  dealStageLabel,
  formatMoney,
  formatThaiDate,
  fulfilmentStatusLabel,
  overdueBadgeLabel,
  paymentStageLabel,
  quotationRecipientLabel,
  quotationStatusLabel,
  ticketStatusLabel,
} from '../../utils/format.js';
import { downloadBlob } from '../../utils/download.js';
import { PricingRequestPanel } from '../pricingRequests/PricingRequestPanel.jsx';
import { CancelDealModal } from './CancelDealModal.jsx';
import { DealQuotationPanel } from './DealQuotationPanel.jsx';
import { DealStagePanel } from './DealStagePanel.jsx';
import { DealStateHeader } from './DealStateHeader.jsx';
import { DealTrackingPanel } from './DealTrackingPanel.jsx';
import { visibleSections } from './salesViewScope.js';

const EVENT_KIND_LABEL = {
  CREATED:            'สร้างดีล',
  STAGE_CHANGED:      'เปลี่ยนสถานะดีล',
  MARKED_LOST:        'เสียงาน',
  REOPENED:           'เปิดดีลอีกครั้ง',
  ON_HOLD:            'พักดีลไว้',
  DORMANT:            'พัก dormant',
  RESUMED:            'ดำเนินการต่อ',
  POLICY_CHANGED:     'เปลี่ยนนโยบายดีล',
  PAYMENT_RECORDED:   'บันทึกรับชำระเงิน',
  BILLING_UPDATED:    'อัปเดตข้อมูลวางบิล',
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
  STOCK_RESERVED:         'จองสินค้าจากสต็อก',
  DELIVERY_RECORDED:      'บันทึกการส่งสินค้า',
  DELIVERY_COMPLETED:     'ส่งมอบครบแล้ว',
  AWAITING_FINAL_PAYMENT: 'รอชำระส่วนที่เหลือ',
  FULLY_PAID:             'ชำระครบแล้ว',
};

// Payment-track events get a success-toned dot, fulfillment-track
// events get an info-toned dot — mirrors the two colour groups used by the
// sub-status chips in the DealStagePanel.
const PAYMENT_TRACK_KINDS = new Set([
  'CUSTOMER_CONFIRMED', 'DEPOSIT_NOTICE_ISSUED', 'DEPOSIT_PAID',
  'AWAITING_FINAL_PAYMENT', 'FULLY_PAID', 'PAYMENT_RECORDED', 'BILLING_UPDATED',
]);
const FULFILLMENT_TRACK_KINDS = new Set([
  'IR_ISSUED', 'IR_SENT', 'SHIPPING', 'GOODS_RECEIVED',
  'STOCK_RESERVED', 'DELIVERY_RECORDED', 'DELIVERY_COMPLETED',
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

/**
 * Role-scoped views, Phase A (frontend only — see salesViewScope.js): what a
 * section collapses to when `visibleSections(role)` says this viewer's role
 * has no business in it. Never hides the deal itself, only the section's
 * detail — a one-line "customer · current stage" context row keeps the
 * cross-team peek the section would otherwise have offered.
 */
function SectionPeek({ title, summary }) {
  const stage = dealStageLabel(summary.salesStage);
  return (
    <div className="flex min-w-0 items-center justify-between gap-3 rounded-lg border border-dashed border-border bg-surface-subtle px-4 py-2.5">
      <span className="shrink-0 text-xs font-bold text-text-muted">{title}</span>
      <span className="min-w-0 truncate text-xs text-text-secondary">
        {summary.customerName || summary.title} · {stage.label}
      </span>
    </div>
  );
}

export function TicketDetailPage({ user, ticketId, onBack, onOpenDocument, showToast }) {
  const queryClient = useQueryClient();

  // Edit-items mode
  const [editMode, setEditMode] = useState(false);
  const [editDraft, setEditDraft] = useState([]);
  const [editNote, setEditNote] = useState('');

  // Revision form
  const [showReviseForm, setShowReviseForm] = useState(false);
  const [reviseScope, setReviseScope] = useState('QTY_OR_NOTE');
  const [reviseReason, setReviseReason] = useState('');

  // UX-03 (slice 5a + 5b): inline field-level validation for the payment /
  // delivery modals, plus (5b) the revise inline form and the edit-items
  // quantities. One shared dict, keyed per-form/per-row so a stale error can
  // never bleed into another: 'payment.amount' | 'delivery.lines' (a
  // group-level rule with no single owning field — same convention
  // DepositNoticePage.jsx uses for its 'items' key) | 'revise.reason' |
  // 'editItems.qty.<rowIndex>' (per-row — see the edit-items save handler).
  // ('quotation.*'/'reject.reason'/'override.<itemId>' were retired along
  // with ticket-native pricing/quotation — Phase 2 Slice S1/S2.)
  // fieldRefs holds the DOM node for each key so a failed submit can
  // scroll/focus the first invalid control. Same shape as the fieldErrors/
  // fieldRefs/clearFieldError pattern already used in TicketCreateModal.jsx,
  // CeoSettingsPage.jsx and DepositNoticePage.jsx on this branch.
  const [fieldErrors, setFieldErrors] = useState({});
  const fieldRefs = useRef({});
  function clearFieldError(key) {
    setFieldErrors((prev) => {
      if (!(key in prev)) return prev;
      const next = { ...prev };
      delete next[key];
      return next;
    });
  }
  // Sets exactly one key. Used (instead of setFieldErrorsForPrefix) for the
  // per-item override.<itemId> key: itemIds are numeric, and
  // setFieldErrorsForPrefix's startsWith match would treat 'override.1' as a
  // prefix of 'override.10' — silently wiping out a different item's error.
  // clearFieldError above is exact-match-safe already; this is its setter
  // counterpart.
  function setFieldError(key, message) {
    setFieldErrors((prev) => ({ ...prev, [key]: message }));
  }
  // Replaces every fieldErrors key under `prefix` with `errors` in one go —
  // used both to report a fresh validation failure for one modal (errors
  // non-empty) and to clear that modal's errors on open/close/success
  // (errors = {}), without touching any other modal's keys.
  function setFieldErrorsForPrefix(prefix, errors) {
    setFieldErrors((prev) => {
      const kept = Object.fromEntries(Object.entries(prev).filter(([k]) => !k.startsWith(prefix)));
      return { ...kept, ...errors };
    });
  }
  function focusFirstInvalid(key) {
    const node = fieldRefs.current[key];
    if (!node) return;
    if (typeof node.scrollIntoView === 'function') node.scrollIntoView({ behavior: 'smooth', block: 'center' });
    node.focus();
  }

  const [paymentModal, setPaymentModal] = useState(false);
  const [paymentDraft, setPaymentDraft] = useState({
    kind: 'DEPOSIT',
    amount: '',
    receivedAt: '',
    note: '',
    receiptRef: '',
    allowOverpayment: false,
  });
  const [billingModal, setBillingModal] = useState(false);
  const [billingDraft, setBillingDraft] = useState({
    billingDate: '',
    dueDate: '',
    creditTermDays: '',
    lastFollowUpAt: '',
    nextFollowUpAt: '',
  });
  const [deliveryModal, setDeliveryModal] = useState(false);
  const [deliveryDraft, setDeliveryDraft] = useState({ source: 'WAREHOUSE', note: '', lines: {} });
  const [stockModal, setStockModal] = useState(false);
  const [stockDraft, setStockDraft] = useState({ note: '', lines: {} });

  // Comment
  const [commentText, setCommentText] = useState('');

  // Confirmation dialogs (state-driven, replaces native browser confirm)
  const [confirm, setConfirm] = useState(null); // { kind: 'deleteAttachment', id, name } | { kind: 'cancelTicket' } | { kind: 'finalPayment' } | null

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
  const canViewPricingRequests = ['sales', 'import', 'ceo', 'sales_manager'].includes(user?.role);

  const paymentsQuery = useQuery({
    queryKey: queryKeys.ticketPayments(ticketId),
    queryFn: () => api.tickets.listPayments(ticketId).then((r) => r.items ?? []),
    enabled: !!ticketId && !!ticket,
  });
  const paymentReceipts = paymentsQuery.data ?? [];

  const deliveriesQuery = useQuery({
    queryKey: queryKeys.ticketDeliveries(ticketId),
    queryFn: () => api.tickets.listDeliveries(ticketId).then((r) => r.items ?? []),
    enabled: !!ticketId && !!ticket,
  });
  const deliveryRecords = deliveriesQuery.data ?? [];

  // Commit 6: the deal's own pricing requests — read here (not inside
  // PricingRequestPanel) so DealStagePanel's substep strip can also key off
  // the most recent one, without a second fetch of the same list.
  const pricingRequestsQuery = useQuery({
    queryKey: queryKeys.pricingRequestsByTicket(ticketId),
    queryFn: () => api.pricingRequests.listForTicket(ticketId).then((r) => r.items ?? []),
    enabled: canViewPricingRequests && !!ticketId && !!ticket,
  });
  const pricingRequests = canViewPricingRequests ? (pricingRequestsQuery.data ?? []) : [];

  // Deal tracking (V83, Slice B1/B2 "kill the weekly report" — handoff 103): visible to
  // the deal owner (sales), sales_manager, ceo — mirrors salesViewScope.js's dealTracking
  // section id (import/account have no business in it) and TicketService.requireDealOwnership.
  const canViewDealTracking = ['sales', 'sales_manager', 'ceo'].includes(user?.role);
  const activitiesQuery = useQuery({
    queryKey: queryKeys.ticketActivities(ticketId),
    queryFn: () => api.tickets.listActivities(ticketId).then((r) => r.items ?? []),
    enabled: canViewDealTracking && !!ticketId && !!ticket,
  });
  const activities = canViewDealTracking ? (activitiesQuery.data ?? []) : [];

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

  // Factory-configs email-draft flow lived here only to back the now-removed
  // propose-price UI (Phase 2 Slice S2 — see docs/agent-handoffs/104); that
  // whole aggregate is gone from the ticket-native flow, so there is nothing
  // left on this page to fetch factoryConfigs for.

  // Shared post-mutation side effects: the ticket-detail fast path (backend
  // returns the full ticket, so no refetch needed) plus the deliberate
  // staleness fix — list/dashboard/notifications went stale after every
  // action before this slice and nothing refreshed them.
  function applyTicketUpdate(updatedTicket) {
    queryClient.setQueryData(queryKeys.ticketDetail(ticketId), updatedTicket);
    queryClient.invalidateQueries({ queryKey: queryKeys.ticketActions(ticketId) });
    queryClient.invalidateQueries({ queryKey: queryKeys.ticketPayments(ticketId) });
    queryClient.invalidateQueries({ queryKey: queryKeys.ticketDeliveries(ticketId) });
    queryClient.invalidateQueries({ queryKey: ['tickets', 'list'] });
    queryClient.invalidateQueries({ queryKey: queryKeys.dashboardSummary() });
    queryClient.invalidateQueries({ queryKey: queryKeys.notifications() });
  }

  // Same UI-draft reset the old doAction ran on every successful action.
  function resetActionDrafts() {
    // UX-03: a successful action closes every modal below (payment/delivery
    // among them) — clear every field error along with them so a stale one
    // can never greet the user on the next open.
    setFieldErrors({});
    setEditMode(false);
    setEditDraft([]);
    setEditNote('');
    setShowReviseForm(false);
    setReviseReason('');
    setPaymentModal(false);
    setBillingModal(false);
    setDeliveryModal(false);
    setStockModal(false);
    setCommentText('');
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

  // R5: Attachments upload/delete — invalidate the attachments query instead
  // of manually reloading + setting local array state.
  const uploadAttachmentMutation = useMutation({
    mutationFn: ({ file, attachType }) => api.attachments.upload(ticketId, file, attachType),
    onSuccess: (_res, { file }) => {
      queryClient.invalidateQueries({ queryKey: queryKeys.ticketAttachments(ticketId) });
      // An INVOICE upload unlocks ฝ่ายบัญชี's close confirmation, so the action
      // list has to be re-read — otherwise the button only appears after the user
      // navigates away and back.
      queryClient.invalidateQueries({ queryKey: queryKeys.ticketActions(ticketId) });
      queryClient.invalidateQueries({ queryKey: queryKeys.ticket(ticketId) });
      showToast('success', `แนบไฟล์ ${file.name} แล้ว`);
    },
    onError: (err) => showToast('error', err.message || 'อัปโหลดไม่สำเร็จ'),
  });
  const uploadingFile = uploadAttachmentMutation.isPending;

  const deleteAttachmentMutation = useMutation({
    mutationFn: (id) => api.attachments.delete(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: queryKeys.ticketAttachments(ticketId) });
      // Deleting the invoice re-locks the close confirmation — same reason as upload.
      queryClient.invalidateQueries({ queryKey: queryKeys.ticketActions(ticketId) });
      queryClient.invalidateQueries({ queryKey: queryKeys.ticket(ticketId) });
      showToast('success', 'ลบไฟล์แล้ว');
    },
    onError: (err) => showToast('error', err.message || 'ลบไม่สำเร็จ'),
  });
  const deletingAttachment = deleteAttachmentMutation.isPending;

  // Deal tracking (V83, Slice B1/B2 — handoff 103): full-replace PUT, response is the
  // full ticket detail (same applyTicketUpdate fast path the actionMutation above uses).
  const updateTrackingMutation = useMutation({
    mutationFn: (payload) => api.tickets.updateTracking(ticketId, payload),
    onSuccess: (response) => {
      applyTicketUpdate(response.ticket);
      showToast('success', 'บันทึกข้อมูลติดตามดีลแล้ว');
    },
    onError: (err) => showToast('error', err.message || 'บันทึกไม่สำเร็จ'),
  });

  // addActivity returns the bare DealActivityDto (not a ticket), so it invalidates
  // rather than reusing applyTicketUpdate's setQueryData fast path — the ticket detail
  // still needs a refetch because `stale` and the stage-advance readiness both depend
  // on the activity that was just logged.
  const addActivityMutation = useMutation({
    mutationFn: (payload) => api.tickets.addActivity(ticketId, payload),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: queryKeys.ticketActivities(ticketId) });
      queryClient.invalidateQueries({ queryKey: queryKeys.ticketDetail(ticketId) });
      queryClient.invalidateQueries({ queryKey: ['tickets', 'list'] });
      showToast('success', 'บันทึกกิจกรรมแล้ว');
    },
    onError: (err) => showToast('error', err.message || 'บันทึกไม่สำเร็จ'),
  });

  // Manual refresh — today's fix: refresh both the ticket AND its attachments
  // (the old button only ever reloaded the ticket).
  function refreshTicket() {
    queryClient.invalidateQueries({ queryKey: queryKeys.ticketDetail(ticketId) });
    queryClient.invalidateQueries({ queryKey: queryKeys.ticketAttachments(ticketId) });
  }

  if (loading) {
    return (
      <div className="page-stack" aria-busy="true" aria-label="กำลังโหลดข้อมูลดีล">
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
        <EmptyState icon="fileText" title="ไม่พบดีล" description="กลับไปหน้ารายการ" />
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
  // Role-scoped views, Phase A: which of the sections below this viewer's
  // role gets in full vs. as a one-line SectionPeek. See salesViewScope.js —
  // presentation only, never a security boundary.
  const sections = visibleSections(role);
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
  const isFulfilment = isImport || role === 'ceo';
  const isAccount = ROLE_PERMISSIONS.canConfirmPayments.includes(role);
  // (deliveryDone / dualTrackDone removed with the single-step close: the close
  // gate is now the server's three-party sequence, surfaced via availableActions.)
  const totalOrdered = items.reduce((sum, item) => sum + Number(item.qty || 0), 0);
  const totalDelivered = items.reduce((sum, item) => sum + Number(item.qtyDelivered || 0), 0);
  const deliveryProgress = totalOrdered > 0 ? Math.min(100, Math.round((totalDelivered / totalOrdered) * 100)) : 0;

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
    // Ticket-level submit/pickup/propose-price/calculate-prices/override-item-price/approve/
    // reject/generate-quotation are retired (Phase 2 Slice S1/S2 "engine collapse" — see
    // docs/agent-handoffs/104_feat-deal-workspace-unification.md). Pricing now runs entirely on
    // the PricingRequest chain (PricingRequestPanel below the items table + DealQuotationPanel
    // for the customer-facing quotation tail + order-confirm).
    // Issuing the deposit-notice DOCUMENT is the payment-track step (mirrors
    // DepositNoticeService.issue): customer must have confirmed first, and the
    // former no-document "ออกใบแจ้งมัดจำ" action is gone.
    generateDocument:  hasAction('ISSUE_DEPOSIT_NOTICE') && st === 'quotation_issued' && ps === 'CUSTOMER_CONFIRMED' && ROLE_PERMISSIONS.canCreateTickets.includes(role) && isOwner,
    revise:            (st === 'approved' || st === 'quotation_issued' || st === 'document_issued') && ROLE_PERMISSIONS.canCreateTickets.includes(role) && isOwner,
    // Three-party close (V55): ฝ่ายบัญชี confirms, then the CEO verifies. Sales is
    // no longer part of the sequence, so there is no owner/canCreateTickets gate
    // here — the server decides and we mirror its availableActions.
    confirmClose:       hasAction('CONFIRM_CLOSE'),
    revokeCloseConfirm: hasAction('REVOKE_CLOSE_CONFIRM'),
    verifyClose:        hasAction('VERIFY_CLOSE'),
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
    issueImportRequest: hasAction('ISSUE_IMPORT_REQUEST') && st === 'quotation_issued' && fs == null && isFulfilment,
    markIrSent:         hasAction('IR_SENT') && st === 'quotation_issued' && fs === 'IR_ISSUED' && isFulfilment,
    markShipping:       hasAction('SHIPPING') && st === 'quotation_issued' && fs === 'IR_SENT' && isFulfilment,
    markGoodsReceived:  hasAction('GOODS_RECEIVED') && st === 'quotation_issued' && fs === 'SHIPPING' && isFulfilment,
    confirmFinalPayment:hasAction('FINAL_PAYMENT') && st === 'quotation_issued' && isAccount,
    recordPayment:      hasAction('RECORD_PAYMENT') && isAccount,
    setBilling:         hasAction('SET_BILLING') && isAccount,
    reserveStock:       hasAction('RESERVE_STOCK') && isFulfilment,
    recordDelivery:     hasAction('RECORD_PARTIAL_DELIVERY') && isFulfilment,
    completeDelivery:   hasAction('COMPLETE_DELIVERY') && isFulfilment,
    downloadRemainingInvoice: st === 'quotation_issued' && fs === 'GOODS_RECEIVED' && isSales,
  };

  // การดำเนินการอื่น ๆ now holds only the rare actions — everything workflow-
  // shaped (dual-track confirmations/close) moved into the DealStagePanel
  // cockpit, and docs live in its เอกสารของขั้นนี้ row. The section hides
  // entirely when none of the remaining actions apply.
  const hasActions = can.revise || can.editItems || can.cancel
    || can.revokeCloseConfirm
    || (st === 'draft' && isOwner && items.length === 0);

  const status = ticketStatusLabel(st);

  // Next-action summary for the current viewer only — derived strictly from the
  // `can` flags above (which already encode real status+role permission checks).
  // Verified against backend/mock transition handlers (see api.tickets.* in
  // src/api/mockApi.js) so the wording matches what the button actually does.
  // Never invents an owner or action the data can't support.
  const NEXT_ACTION_STEPS = [
    // Dual-track steps: re-issuing/working the customer quotation lives in
    // DealQuotationPanel now, so this list only covers the operational chain
    // this page's own primaryAction button drives.
    ['confirmCustomer',  'ยืนยันว่าลูกค้าตกลงคำสั่งซื้อแล้ว'],
    ['generateDocument', 'ออกใบแจ้งยอดมัดจำให้ลูกค้า (เริ่มขั้นตอนชำระเงิน)'],
    ['confirmDepositPaid','ยืนยันว่าลูกค้าชำระมัดจำแล้ว'],
    ['issueImportRequest','ออก Import Request (IR) ให้โรงงาน'],
    ['markIrSent',        'บันทึกว่าส่ง IR ให้โรงงานแล้ว'],
    ['markShipping',      'บันทึกว่าสินค้าออกเดินทางแล้ว'],
    ['markGoodsReceived', 'บันทึกว่ารับสินค้าแล้ว'],
    ['confirmFinalPayment','ยืนยันว่าลูกค้าชำระส่วนที่เหลือครบแล้ว'],
    ['revise',            'ขอแก้ไขรายละเอียดใบขอราคานี้ได้หากจำเป็น'],
    ['confirmClose',      'ส่งมอบและรับเงินครบแล้ว — ยืนยันเพื่อส่งให้ CEO ตรวจสอบปิดงาน'],
    ['verifyClose',       'ฝ่ายบัญชียืนยันแล้ว — ตรวจสอบและปิดงานได้เลย'],
  ];
  const nextAction = NEXT_ACTION_STEPS.find(([key, text]) => can[key] && text)?.[1] ?? null;

  // Passive hint when the payment track waits on ฝ่ายบัญชี — money-receipt
  // confirmations belong to the account role (CEO fallback), so sales/import
  // would otherwise see a stalled payment stepper with no explanation. Shown
  // alongside the personal next-action callout, not instead of it.
  // Awaiting the CEO's verification outranks the payment hints: it is the last
  // thing standing between this deal and closed, and it is invisible otherwise.
  const closeConfirmedAt = summary?.closeConfirmedAt ?? null;
  const waitingHint = closeConfirmedAt && !can.verifyClose
    ? `ฝ่ายบัญชียืนยันพร้อมปิดงานแล้ว${summary?.closeConfirmedByName ? ` (${summary.closeConfirmedByName})` : ''} — รอ CEO ตรวจสอบ`
    : (st === 'quotation_issued' && !isAccount)
      ? (ps === 'DEPOSIT_NOTICE_ISSUED' ? 'รอฝ่ายบัญชียืนยันรับยอดมัดจำ'
        : ps === 'AWAITING_FINAL_PAYMENT' ? 'รอฝ่ายบัญชียืนยันรับชำระส่วนที่เหลือ'
        : null)
      : null;

  // The cockpit's primary action: the ONE workflow button for this viewer's
  // current sub-step (moved verbatim out of การดำเนินการอื่น ๆ). Doc-shaped next
  // steps (ออกใบแจ้งยอดมัดจำ, IR) are NOT repeated here — they already sit in the
  // stage panel's docs row and the guidance line points at them. The
  // customer-quotation issue/outcome/confirm-order actions live in
  // DealQuotationPanel, which has its own primary buttons.
  const primaryAction = can.confirmCustomer ? (
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
      onClick={() => setConfirm({ kind: 'finalPayment' })}>
      ยืนยันชำระครบ (Final Payment)
    </button>
  ) : can.confirmClose ? (
    <button type="button" className="primary-button" disabled={actionLoading}
      onClick={() => doAction(() => api.tickets.confirmCloseReady(ticketId),
        'ยืนยันพร้อมปิดงานแล้ว — รอ CEO ตรวจสอบ')}>
      <Icon name="check" size={14} />
      ยืนยันพร้อมปิดงาน
    </button>
  ) : can.verifyClose ? (
    <button type="button" className="primary-button" disabled={actionLoading}
      onClick={() => doAction(() => api.tickets.verifyClose(ticketId), 'ตรวจสอบและปิดงานแล้ว')}>
      <Icon name="check" size={14} />
      ตรวจสอบและปิดงาน
    </button>
  ) : null;

  async function handleUploadAttachment(e, explicitType = null) {
    const file = e.target.files?.[0];
    if (!file) return;
    // INVOICE gates the close, so it is never inferred from the filename — ฝ่ายบัญชี
    // picks it deliberately via the dedicated upload control.
    const attachType = explicitType
      ?? (file.name.toLowerCase().includes('po') ? 'PO' : 'OTHER');
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

  // UX-34: Final Payment used to fire straight off the primary-action button
  // with no confirmation — the least-guarded action in the app despite being
  // one of the most financially consequential (auto-records a full BALANCE
  // receipt server-side, see TicketService.confirmFinalPayment). Routed
  // through the same confirm-dialog + doAction pattern as cancelTicket below:
  // doAction already swallows/toasts its own error, so unconditionally
  // closing the dialog afterwards matches that existing behavior.
  async function confirmFinalPaymentAction() {
    await doAction(() => api.tickets.confirmFinalPayment(ticketId), 'ชำระครบแล้ว');
    setConfirm(null);
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

  async function handleComment() {
    if (!commentText.trim()) return;
    await doAction(() => api.tickets.comment(ticketId, { message: commentText.trim() }), 'เพิ่มความคิดเห็นแล้ว');
  }

  async function handleRecordPayment() {
    const amount = Number(paymentDraft.amount);
    if (!amount || amount <= 0) {
      setFieldErrorsForPrefix('payment.', { 'payment.amount': 'กรุณากรอกยอดรับชำระ' });
      focusFirstInvalid('payment.amount');
      return;
    }
    setFieldErrorsForPrefix('payment.', {});
    const payload = {
      kind: paymentDraft.kind,
      amount,
      receivedAt: paymentDraft.receivedAt ? new Date(paymentDraft.receivedAt).toISOString() : null,
      note: paymentDraft.note.trim() || null,
      receiptRef: paymentDraft.receiptRef.trim() || null,
      allowOverpayment: paymentDraft.allowOverpayment,
    };
    await doAction(() => api.tickets.recordPayment(ticketId, payload), 'บันทึกรับชำระเงินแล้ว');
  }

  async function handleSetBilling() {
    const payload = {
      billingDate: billingDraft.billingDate || null,
      dueDate: billingDraft.dueDate || null,
      creditTermDays: billingDraft.creditTermDays === '' ? null : Number(billingDraft.creditTermDays),
      lastFollowUpAt: billingDraft.lastFollowUpAt || null,
      nextFollowUpAt: billingDraft.nextFollowUpAt || null,
    };
    await doAction(() => api.tickets.setBilling(ticketId, payload), 'บันทึกข้อมูลวางบิลแล้ว');
  }

  async function handleRecordDelivery() {
    const lines = items
      .map((item) => ({ itemId: item.id, qty: Number(deliveryDraft.lines[item.id] || 0) }))
      .filter((line) => line.qty > 0);
    if (lines.length === 0) {
      setFieldErrorsForPrefix('delivery.', { 'delivery.lines': 'กรุณาระบุจำนวนส่งมอบอย่างน้อย 1 รายการ' });
      focusFirstInvalid('delivery.lines');
      return;
    }
    setFieldErrorsForPrefix('delivery.', {});
    await doAction(() => api.tickets.recordDelivery(ticketId, {
      source: deliveryDraft.source,
      note: deliveryDraft.note.trim() || null,
      lines,
    }), 'บันทึกการส่งสินค้าแล้ว');
  }

  async function handleReserveStock() {
    const lines = items.map((item) => ({
      itemId: item.id,
      qtyFromStock: Number(stockDraft.lines[item.id] || 0),
      note: stockDraft.note.trim() || null,
    }));
    await doAction(() => api.tickets.reserveStock(ticketId, { lines }), 'บันทึกสินค้าจากสต็อกแล้ว');
  }

  function openBillingModal() {
    setBillingDraft({
      billingDate: summary.billingDate ?? '',
      dueDate: summary.dueDate ?? '',
      creditTermDays: summary.creditTermDays != null ? String(summary.creditTermDays) : '',
      lastFollowUpAt: summary.lastFollowUpAt ?? '',
      nextFollowUpAt: summary.nextFollowUpAt ?? '',
    });
    setBillingModal(true);
  }

  function openPaymentModal() {
    const suggestedKind = summary.amountPaid > 0 ? 'BALANCE' : 'DEPOSIT';
    setPaymentDraft({
      kind: suggestedKind,
      amount: summary.amountOutstanding != null && summary.amountOutstanding > 0 ? String(summary.amountOutstanding) : '',
      receivedAt: '',
      note: '',
      receiptRef: '',
      allowOverpayment: false,
    });
    setPaymentModal(true);
    // UX-03: reset on open so a stale error from a previous attempt never
    // greets the user on reopen.
    setFieldErrorsForPrefix('payment.', {});
  }

  function closePaymentModal() {
    setPaymentModal(false);
    setFieldErrorsForPrefix('payment.', {});
  }

  function openDeliveryModal() {
    const source = fs === 'FROM_STOCK' ? 'STOCK' : 'WAREHOUSE';
    const lines = {};
    items.forEach((item) => {
      lines[item.id] = String(Math.max(0, Number(item.qty || 0) - Number(item.qtyDelivered || 0)));
    });
    setDeliveryDraft({ source, note: '', lines });
    setDeliveryModal(true);
    // UX-03: reset on open so a stale error from a previous attempt never
    // greets the user on reopen.
    setFieldErrorsForPrefix('delivery.', {});
  }

  function closeDeliveryModal() {
    setDeliveryModal(false);
    setFieldErrorsForPrefix('delivery.', {});
  }

  function openStockModal() {
    const lines = {};
    items.forEach((item) => { lines[item.id] = String(item.qtyFromStock ?? 0); });
    setStockDraft({ note: '', lines });
    setStockModal(true);
  }

  return (
    <div className="page-stack">
      <Breadcrumbs items={[{ label: 'ดีล', onClick: onBack }, { label: summary.code || summary.customerName || summary.title }]} />
      <button type="button" className="secondary-button self-start" onClick={onBack}>
        <Icon name="chevronLeft" size={14} />
        กลับ
      </button>

      {/* Deal Workspace state header (Phase 2 Slice S2 — see
          docs/agent-handoffs/104_feat-deal-workspace-unification.md): deal
          code/title/customer + lifecycle × stage × PCR × payment × fulfilment
          at a glance, plus "ถึงคิวคุณ" and the one primary CTA that mirrors
          it. Subsumes the old bare header (title/code/status/refresh). */}
      <DealStateHeader
        summary={summary}
        pricingRequests={pricingRequests}
        primaryAction={primaryAction}
        nextAction={nextAction}
        waitingHint={waitingHint}
        onRefresh={refreshTicket}
      />
      <div className="flex flex-wrap items-center gap-2 text-xs text-text-muted sm:gap-4">
        <StatusBadge tone={status.tone}>{status.label}</StatusBadge>
        {summary.hasEdits && <StatusBadge tone="warning">✎ มีการแก้ไข</StatusBadge>}
        <span>สร้างโดย <strong className="text-text-secondary">{summary.createdByName || '-'}</strong> · {formatThaiDate(summary.createdAt)}</span>
        {summary.assignedToName && (
          <span>เจ้าหน้าที่นำเข้าที่ดูแล <strong className="text-text-secondary">{summary.assignedToName}</strong></span>
        )}
      </div>

      {/* Deal pipeline (V50): the 14-stage journey with stage-gated doc actions.
          Generation buttons reuse the exact handlers/permissions of the action
          row; once a document exists (quotation / ใบแจ้งยอดมัดจำ) it stays
          reachable from here through the later stages too. */}
      <DealStagePanel
        user={user}
        summary={summary}
        availableActions={availableActions}
        pricingRequests={pricingRequests}
        // primaryAction now lives solely in DealStateHeader above (Phase 2 Slice S2's
        // "one primary CTA" — see its own doc comment) — not passed here too, to avoid
        // rendering the exact same button twice on one page.
        guidance={nextAction ?? waitingHint}
        actionLoading={actionLoading}
        deliveryProgress={{ delivered: totalDelivered, ordered: totalOrdered }}
        onUpdateStage={(payload) => doAction(() => api.tickets.updateStage(ticketId, payload), 'อัปเดตสถานะดีลแล้ว')}
        onMarkLost={(payload) => doAction(() => api.tickets.markLost(ticketId, payload), 'บันทึกเสียงานแล้ว')}
        onReopen={() => doAction(() => api.tickets.reopen(ticketId, {}), 'เปิดดีลอีกครั้งแล้ว')}
        onHold={(payload) => doAction(() => api.tickets.hold(ticketId, payload), 'พักดีลไว้แล้ว')}
        onDormant={(payload) => doAction(() => api.tickets.dormant(ticketId, payload), 'พัก dormant แล้ว')}
        onResume={(payload) => doAction(() => api.tickets.resume(ticketId, payload), 'ดำเนินการต่อแล้ว')}
        onSetTenderRequirement={(payload) => doAction(() => api.tickets.setTenderRequirement(ticketId, payload), 'บันทึกสถานะประมูลแล้ว')}
        onSetDepositPolicy={(payload) => doAction(() => api.tickets.setDepositPolicy(ticketId, payload), 'บันทึกนโยบายมัดจำแล้ว')}
        docActions={(can.generateDocument || can.issueImportRequest
          || can.downloadRemainingInvoice || (sections.quotation && latestQuotation)
          || (sections.depositNotice && depositNoticeIssued)) ? (
          <>
            {/* Import/account (role-scoped views, Phase A): the view-only
                quotation/deposit-notice download links aren't role-gated by
                `can.*` (unlike generate/issue, which already require
                isOwner) — sections.quotation/depositNotice hide them for the
                role that has no business in that document. */}
            {sections.quotation && latestQuotation && (
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
            {sections.depositNotice && depositNoticeIssued && (
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

      {/* Deal tracking (V83, Slice B1/B2 "kill the weekly report" — handoff 103): the
          weekly-report replacement. Owner/sales_manager/ceo only — see salesViewScope.js's
          dealTracking section id. Import/account get a one-line SectionPeek like every
          other role-scoped section on this page instead of the full panel. */}
      {sections.dealTracking ? (
        <DealTrackingPanel
          summary={summary}
          events={events}
          activities={activities}
          activitiesLoading={activitiesQuery.isLoading}
          canEdit={canViewDealTracking && (isOwner || role === 'sales_manager' || role === 'ceo')}
          onUpdateTracking={(payload) => updateTrackingMutation.mutateAsync(payload)}
          onAddActivity={(payload) => addActivityMutation.mutateAsync(payload)}
          updating={updateTrackingMutation.isPending}
          addingActivity={addActivityMutation.isPending}
        />
      ) : (
        <SectionPeek title="การติดตามดีล" summary={summary} />
      )}

      {sections.payment ? (
      <section className="panel">
        <div className="panel-header" style={{ alignItems: 'center' }}>
          <h2>การชำระเงิน</h2>
          <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
            <StatusBadge tone={paymentStageLabel(summary.paymentStage).tone}>
              {paymentStageLabel(summary.paymentStage).label}
            </StatusBadge>
            {summary.overdue && (
              <StatusBadge tone={overdueBadgeLabel(true).tone}>{overdueBadgeLabel(true).label}</StatusBadge>
            )}
          </div>
        </div>
        <div style={{ padding: '0 18px 16px', display: 'flex', flexDirection: 'column', gap: 14 }}>
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(150px, 1fr))', gap: 10 }}>
            {[
              ['ยอดที่ต้องชำระ', summary.amountPayable],
              ['ชำระแล้ว', summary.amountPaid],
              ['คงเหลือ', summary.amountOutstanding],
            ].map(([label, value]) => (
              <div key={label} style={{ border: '1px solid var(--color-border-subtle)', borderRadius: 8, padding: '10px 12px', background: 'var(--color-surface-muted)' }}>
                <div style={{ fontSize: 12, color: 'var(--color-text-muted)', marginBottom: 4 }}>{label}</div>
                <strong style={{ fontSize: 18, color: 'var(--color-text)' }}>{formatMoney(value ?? 0)}</strong>
              </div>
            ))}
          </div>
          <div style={{ display: 'flex', flexWrap: 'wrap', gap: '8px 18px', fontSize: 13, color: 'var(--color-text-muted)' }}>
            <span>วันวางบิล <strong style={{ color: 'var(--color-text-secondary)' }}>{formatThaiDate(summary.billingDate)}</strong></span>
            <span>ครบกำหนด <strong style={{ color: summary.overdue ? 'var(--color-danger)' : 'var(--color-text-secondary)' }}>{formatThaiDate(summary.dueDate)}</strong></span>
            {summary.nextFollowUpAt && <span>ติดตามครั้งถัดไป <strong style={{ color: 'var(--color-text-secondary)' }}>{formatThaiDate(summary.nextFollowUpAt)}</strong></span>}
          </div>
          <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
            {can.recordPayment && (
              <button type="button" className="primary-button" disabled={actionLoading} onClick={openPaymentModal}>
                บันทึกรับชำระเงิน
              </button>
            )}
            {can.setBilling && (
              <button type="button" className="secondary-button" disabled={actionLoading} onClick={openBillingModal}>
                ตั้งค่าการวางบิล
              </button>
            )}
          </div>
          <div style={{ borderTop: '1px solid var(--color-border)', paddingTop: 10 }}>
            <h3 style={{ margin: '0 0 8px', fontSize: 13, fontWeight: 700 }}>ประวัติรับชำระ</h3>
            {paymentsQuery.isLoading ? (
              <SkeletonText lines={2} />
            ) : paymentReceipts.length === 0 ? (
              <p style={{ margin: 0, fontSize: 13, color: 'var(--color-text-muted)' }}>ยังไม่มีรายการรับชำระ</p>
            ) : (
              <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
                {paymentReceipts.map((receipt) => (
                  <div key={receipt.receiptId} style={{ display: 'grid', gridTemplateColumns: '110px 90px 1fr', gap: 10, alignItems: 'start', fontSize: 13 }}>
                    <span style={{ color: 'var(--color-text-muted)' }}>{formatThaiDate(receipt.receivedAt)}</span>
                    <strong>{receipt.kind}</strong>
                    <span>
                      {formatMoney(receipt.amount)}
                      <small style={{ display: 'block', color: 'var(--color-text-muted)' }}>
                        {receipt.recordedByName || '-'}{receipt.note ? ` · ${receipt.note}` : ''}
                      </small>
                    </span>
                  </div>
                ))}
              </div>
            )}
          </div>
        </div>
      </section>
      ) : (
        <SectionPeek title="การชำระเงิน" summary={summary} />
      )}

      {sections.delivery ? (
      <section className="panel">
        <div className="panel-header" style={{ alignItems: 'center' }}>
          <h2>การส่งมอบสินค้า</h2>
          <StatusBadge tone={fulfilmentStatusLabel(fs).tone}>
            {fulfilmentStatusLabel(fs).label}
          </StatusBadge>
        </div>
        <div style={{ padding: '0 18px 16px', display: 'flex', flexDirection: 'column', gap: 14 }}>
          <div style={{ display: 'grid', gridTemplateColumns: 'minmax(0, 1fr) auto', gap: 12, alignItems: 'center' }}>
            <div>
              <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: 13, marginBottom: 6 }}>
                <strong>{totalDelivered.toLocaleString('en-US')} / {totalOrdered.toLocaleString('en-US')}</strong>
                <span style={{ color: 'var(--color-text-muted)' }}>{deliveryProgress}%</span>
              </div>
              <div style={{ height: 8, borderRadius: 999, background: 'var(--color-surface-subtle)', overflow: 'hidden' }}>
                <div style={{ width: `${deliveryProgress}%`, height: '100%', background: 'var(--color-success)' }} />
              </div>
            </div>
            <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', justifyContent: 'flex-end' }}>
              {can.reserveStock && (
                <button type="button" className="secondary-button" disabled={actionLoading} onClick={openStockModal}>
                  จองสินค้าจากสต็อก
                </button>
              )}
              {can.recordDelivery && (
                <button type="button" className="primary-button" disabled={actionLoading} onClick={openDeliveryModal}>
                  บันทึกการส่งสินค้า
                </button>
              )}
              {can.completeDelivery && (
                <button type="button" className="secondary-button" disabled={actionLoading}
                  onClick={() => doAction(() => api.tickets.completeDelivery(ticketId, { note: 'ส่งมอบครบจากหน้าดีล' }), 'ส่งมอบครบแล้ว')}>
                  ส่งมอบครบ
                </button>
              )}
            </div>
          </div>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
            {items.map((item) => {
              const ordered = Number(item.qty || 0);
              const delivered = Number(item.qtyDelivered || 0);
              const remaining = Math.max(0, ordered - delivered);
              return (
                <div key={item.id} style={{ border: '1px solid var(--color-border-subtle)', borderRadius: 8, padding: '10px 12px', display: 'grid', gridTemplateColumns: 'minmax(0, 1fr) auto', gap: 10 }}>
                  <div>
                    <strong>{item.brand} {item.model || ''}</strong>
                    <small style={{ display: 'block', color: 'var(--color-text-muted)' }}>
                      จากสต็อก {Number(item.qtyFromStock || 0).toLocaleString('en-US')} · คงเหลือ {remaining.toLocaleString('en-US')}
                    </small>
                  </div>
                  <strong>{delivered.toLocaleString('en-US')} / {ordered.toLocaleString('en-US')}</strong>
                </div>
              );
            })}
          </div>
          <div style={{ borderTop: '1px solid var(--color-border)', paddingTop: 10 }}>
            <h3 style={{ margin: '0 0 8px', fontSize: 13, fontWeight: 700 }}>ประวัติส่งมอบ</h3>
            {deliveriesQuery.isLoading ? (
              <SkeletonText lines={2} />
            ) : deliveryRecords.length === 0 ? (
              <p style={{ margin: 0, fontSize: 13, color: 'var(--color-text-muted)' }}>ยังไม่มีรายการส่งมอบ</p>
            ) : (
              <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
                {deliveryRecords.map((record) => (
                  <div key={record.deliveryId} style={{ display: 'grid', gridTemplateColumns: '110px 90px 1fr', gap: 10, alignItems: 'start', fontSize: 13 }}>
                    <span style={{ color: 'var(--color-text-muted)' }}>{formatThaiDate(record.deliveredAt)}</span>
                    <strong>{record.source}</strong>
                    <span>
                      {(record.items ?? []).map((line) => `${line.itemId}: ${Number(line.qty).toLocaleString('en-US')}`).join(', ')}
                      <small style={{ display: 'block', color: 'var(--color-text-muted)' }}>
                        {record.deliveredByName || '-'}{record.note ? ` · ${record.note}` : ''}
                      </small>
                    </span>
                  </div>
                ))}
              </div>
            )}
          </div>
        </div>
      </section>
      ) : (
        <SectionPeek title="การส่งมอบสินค้า" summary={summary} />
      )}

      {/* The CEO price-approval decision panel (approve/reject a Import-proposed
          price) is gone along with ticket-native pricing (Phase 2 Slice S1/S2 —
          see docs/agent-handoffs/104): CEO price decisions now happen on the
          PricingRequest chain, in PricingRequestDetailPage's Step 3 panel. */}

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
            {can.revise && !showReviseForm && (
              <button type="button" className="secondary-button" disabled={actionLoading}
                onClick={() => { setShowReviseForm(true); clearFieldError('revise.reason'); }}>
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
                  setFieldErrorsForPrefix('editItems.qty.', {});
                }}>
                <Icon name="pencil" size={14} />
                แก้ไขรายการสินค้า
              </button>
            )}

            {can.revokeCloseConfirm && (
              <button type="button" className="secondary-button" disabled={actionLoading}
                onClick={() => doAction(() => api.tickets.revokeCloseConfirmation(ticketId, {}),
                  'ยกเลิกการยืนยันปิดงานแล้ว')}>
                ยกเลิกการยืนยันปิดงาน
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
                <textarea rows={2}
                  id="revise-reason"
                  ref={(el) => { fieldRefs.current['revise.reason'] = el; }}
                  value={reviseReason}
                  onChange={(e) => { setReviseReason(e.target.value); clearFieldError('revise.reason'); }}
                  placeholder="ระบุเหตุผล..." style={{ marginTop: 4 }}
                  aria-invalid={fieldErrors['revise.reason'] ? true : undefined}
                  aria-describedby={fieldErrors['revise.reason'] ? fieldErrorId('revise-reason') : undefined}
                />
                {fieldErrors['revise.reason'] ? (
                  <p id={fieldErrorId('revise-reason')} role="alert" style={{ margin: '4px 0 0', fontSize: 11, fontWeight: 700, color: 'var(--color-danger)' }}>
                    {fieldErrors['revise.reason']}
                  </p>
                ) : null}
              </label>
              <div style={{ display: 'flex', gap: 8 }}>
                {/* This button is already disabled={!reviseReason.trim()} (unchanged
                    below), so the guard inside onClick is defensive/unreachable
                    through the UI — wired for consistency with the other 3 forms
                    in this slice, not because a user can trigger it here. */}
                <button type="button" className="primary-button" disabled={actionLoading || !reviseReason.trim()}
                  onClick={() => {
                    if (!reviseReason.trim()) {
                      setFieldError('revise.reason', 'กรุณาระบุเหตุผล');
                      focusFirstInvalid('revise.reason');
                      return;
                    }
                    clearFieldError('revise.reason');
                    doAction(() => api.tickets.revision(ticketId, { scope: reviseScope, reason: reviseReason.trim() }), 'ส่งคำขอแก้ไขแล้ว');
                  }}>
                  ยืนยันขอแก้ไข
                </button>
                <button type="button" className="secondary-button" disabled={actionLoading}
                  onClick={() => { setShowReviseForm(false); setReviseReason(''); clearFieldError('revise.reason'); }}>
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
                          onClick={() => {
                            setEditDraft((d) => d.filter((_, i) => i !== index));
                            // Removing a row shifts every later row's index down by
                            // one — reindex their qty errors along with it so an
                            // error never ends up pinned to the wrong (now
                            // different) row.
                            setFieldErrors((prev) => {
                              let changed = false;
                              const next = {};
                              Object.entries(prev).forEach(([k, v]) => {
                                const m = k.match(/^editItems\.qty\.(\d+)$/);
                                if (!m) { next[k] = v; return; }
                                const idx = Number(m[1]);
                                if (idx === index) { changed = true; return; }
                                if (idx > index) { next[`editItems.qty.${idx - 1}`] = v; changed = true; return; }
                                next[k] = v;
                              });
                              return changed ? next : prev;
                            });
                          }}>
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
                              id={`edit-item-qty-${index}`}
                              ref={(el) => { fieldRefs.current[`editItems.qty.${index}`] = el; }}
                              onChange={(e) => {
                                setEditDraft((d) => d.map((r, ri) => {
                                  if (ri !== index) return r;
                                  const u = { ...r, qty: e.target.value };
                                  if (r.sqmPerPiece && e.target.value) u.qtySqm = (Number(e.target.value) * r.sqmPerPiece).toFixed(3);
                                  return u;
                                }));
                                clearFieldError(`editItems.qty.${index}`);
                              }}
                              aria-invalid={fieldErrors[`editItems.qty.${index}`] ? true : undefined}
                              aria-describedby={fieldErrors[`editItems.qty.${index}`] ? fieldErrorId(`edit-item-qty-${index}`) : undefined}
                            />
                            {fieldErrors[`editItems.qty.${index}`] ? (
                              <p id={fieldErrorId(`edit-item-qty-${index}`)} role="alert" style={{ margin: '4px 0 0', fontSize: 11, fontWeight: 700, color: 'var(--color-danger)' }}>
                                {fieldErrors[`editItems.qty.${index}`]}
                              </p>
                            ) : null}
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
                            {/* Governs qty when this row is in SQM mode (qty is
                                derived from qtySqm × sqmPerPiece below) — so the
                                per-row qty error, if any, attaches here rather
                                than to a non-editable derived display. */}
                            <input type="number" value={item.qtySqm ?? ''} min="0" step="0.001"
                              id={`edit-item-qtysqm-${index}`}
                              ref={(el) => { fieldRefs.current[`editItems.qty.${index}`] = el; }}
                              onChange={(e) => {
                                setEditDraft((d) => d.map((r, ri) => {
                                  if (ri !== index) return r;
                                  const u = { ...r, qtySqm: e.target.value };
                                  if (r.sqmPerPiece && e.target.value) u.qty = Math.ceil(Number(e.target.value) / r.sqmPerPiece);
                                  return u;
                                }));
                                clearFieldError(`editItems.qty.${index}`);
                              }}
                              aria-invalid={fieldErrors[`editItems.qty.${index}`] ? true : undefined}
                              aria-describedby={fieldErrors[`editItems.qty.${index}`] ? fieldErrorId(`edit-item-qtysqm-${index}`) : undefined}
                            />
                            {fieldErrors[`editItems.qty.${index}`] ? (
                              <p id={fieldErrorId(`edit-item-qtysqm-${index}`)} role="alert" style={{ margin: '4px 0 0', fontSize: 11, fontWeight: 700, color: 'var(--color-danger)' }}>
                                {fieldErrors[`editItems.qty.${index}`]}
                              </p>
                            ) : null}
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
                      // UX-03 (slice 5b): this used to be one toast covering every
                      // row ("กรุณากรอกจำนวนสินค้าให้ครบทุกรายการ") — the exact
                      // "one message for many fields" defect the finding names.
                      // Flag each offending row's own qty input instead, keyed by
                      // row index, so the user sees exactly which rows are wrong.
                      const qtyErrors = {};
                      editDraft.forEach((item, i) => {
                        if (!item.qty || Number(item.qty) <= 0) {
                          qtyErrors[`editItems.qty.${i}`] = 'กรุณากรอกจำนวนสินค้าของรายการนี้ให้ถูกต้อง';
                        }
                      });
                      const order = Object.keys(qtyErrors);
                      if (order.length > 0) {
                        setFieldErrorsForPrefix('editItems.qty.', qtyErrors);
                        focusFirstInvalid(order[0]);
                        return;
                      }
                      setFieldErrorsForPrefix('editItems.qty.', {});
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
                    onClick={() => { setEditMode(false); setEditDraft([]); setEditNote(''); setFieldErrorsForPrefix('editItems.qty.', {}); }}>
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
                      <span>ราคาที่เสนอ</span>
                      <span>ราคาที่อนุมัติ</span>
                    </>
                  ) : (
                    <span>ราคาที่อนุมัติ</span>
                  )}
                </div>
                {items.length === 0 ? (
                  <EmptyState title="ไม่มีรายการสินค้า" />
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
                          {/* CEO manual-override entry (D10) was ticket-native and retired along
                              with calculatePrices/approve — this is now a read-only readout of
                              whatever the 3 stranded legacy tickets already carry. */}
                          {item.manualPrice != null && <small style={{ display: 'block', color: 'var(--color-override)', fontSize: 10 }}>override</small>}
                        </span>
                      </>
                    ) : (
                      <>
                        {showProposed && <code data-label="ราคาที่เสนอ">{formatMoney(item.proposedPrice)}</code>}
                        {showApproved && <code data-label="ราคาที่อนุมัติ">{formatMoney(item.approvedPrice)}</code>}
                      </>
                    )}
                  </div>
                ))}
              </>
            )}
          </section>

          {sections.pricingRequest ? (
            canViewPricingRequests ? (
              <PricingRequestPanel ticketId={ticketId} deal={summary} ticketItems={items} user={user} />
            ) : null
          ) : (
            <SectionPeek title="ใบขอราคา (Pricing Request)" summary={summary} />
          )}

          {/* "ราคาและใบเสนอราคา" (Phase 2 Slice S2): the customer-facing tail of
              the PricingRequest chain (issue/outcome + confirm-order), pulled
              onto the deal page. Renders nothing until a request reaches
              APPROVED_FOR_QUOTATION — see DealQuotationPanel's own doc
              comment. The factory/costing/CEO-price steps that precede that
              stay on PricingRequestDetailPage, linked from inside the panel. */}
          {sections.dealQuotation && canViewPricingRequests ? (
            <DealQuotationPanel ticketId={ticketId} pricingRequests={pricingRequests} user={user} showToast={showToast} />
          ) : null}

          {/* R5: Attachments */}
          <section className="panel">
            <div className="panel-header" style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
              <h2>ไฟล์แนบ (PO / ใบเซ็น / ใบกำกับภาษี)</h2>
              {/* ใบกำกับภาษี is issued by an external system and uploaded here; its
                  presence is a prerequisite for ฝ่ายบัญชี to confirm the close. */}
              {!TERMINAL.includes(st) && isAccount && (
                <label className="cursor-pointer max-[720px]:w-full" htmlFor="ticket-invoice-file">
                  <input
                    id="ticket-invoice-file"
                    type="file"
                    className="sr-only h-px min-h-0 w-px border-0 p-0"
                    onChange={(e) => handleUploadAttachment(e, 'INVOICE')}
                    accept=".pdf,.doc,.docx,.xls,.xlsx,.png,.jpg,.jpeg"
                  />
                  <span
                    className="secondary-button max-[720px]:min-h-11 max-[720px]:w-full"
                    style={{ fontSize: 12, padding: '4px 10px', display: 'inline-flex', alignItems: 'center', gap: 4 }}
                  >
                    <Icon name="upload" size={13} />
                    {uploadingFile ? 'กำลังอัปโหลด...' : 'แนบใบกำกับภาษี'}
                  </span>
                </label>
              )}
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

          {sections.quotation ? (
            quotationGroups.length > 0 && (
            <section className="panel">
              <div className="panel-header">
                <h2>ใบเสนอราคา (เอกสารเดิม)</h2>
              </div>
              {/* Ticket-native quotation generate/mark-sent/accepted/rejected is retired
                  (Phase 2 Slice S1/S2 — see docs/agent-handoffs/104): these rows predate the
                  PricingRequest/CustomerQuotation redesign (pricing_request_id IS NULL) and
                  stay visible read-only/download-only so the 3 legacy deals' history isn't
                  stranded. New quotations live in DealQuotationPanel above. */}
              {quotationGroups.map((group) => (
                <div key={group.recipientType} style={{ borderTop: '1px solid var(--color-surface-subtle)' }}>
                  <div style={{ padding: '12px 18px 6px', display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 10 }}>
                    <h3 style={{ margin: 0, fontSize: 14 }}>{group.label}</h3>
                  </div>
                  {group.quotations.map((q) => {
                    const status = quotationStatusLabel(q.docStatus);
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
            )
          ) : (
            <SectionPeek title="ใบเสนอราคา" summary={summary} />
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

      {paymentModal && (
        <Modal
          title="บันทึกรับชำระเงิน"
          onClose={closePaymentModal}
          footer={(
            <>
              <button type="button" className="secondary-button" onClick={closePaymentModal}>ยกเลิก</button>
              <button type="button" className="primary-button" disabled={actionLoading} onClick={handleRecordPayment}>
                บันทึก
              </button>
            </>
          )}
        >
          <div style={{ display: 'grid', gap: 12 }}>
            <label style={{ display: 'grid', gap: 6, fontSize: 13, fontWeight: 600 }}>
              ประเภท
              <select value={paymentDraft.kind} onChange={(e) => setPaymentDraft((draft) => ({ ...draft, kind: e.target.value }))}>
                <option value="DEPOSIT">มัดจำ</option>
                <option value="BALANCE">ส่วนที่เหลือ</option>
                <option value="ADJUSTMENT">ปรับปรุงยอด/คืนเงิน</option>
              </select>
            </label>
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(160px, 1fr))', gap: 10 }}>
              <label style={{ display: 'grid', gap: 6, fontSize: 13, fontWeight: 600 }}>
                จำนวนเงิน
                <input type="number" min="0" step="0.01"
                  id="payment-amount"
                  ref={(el) => { fieldRefs.current['payment.amount'] = el; }}
                  value={paymentDraft.amount}
                  onChange={(e) => {
                    setPaymentDraft((draft) => ({ ...draft, amount: e.target.value }));
                    clearFieldError('payment.amount');
                  }}
                  aria-invalid={fieldErrors['payment.amount'] ? true : undefined}
                  aria-describedby={fieldErrors['payment.amount'] ? fieldErrorId('payment-amount') : undefined}
                />
                {fieldErrors['payment.amount'] ? (
                  <p id={fieldErrorId('payment-amount')} role="alert" style={{ margin: 0, fontSize: 11, fontWeight: 700, color: 'var(--color-danger)' }}>
                    {fieldErrors['payment.amount']}
                  </p>
                ) : null}
              </label>
              <label style={{ display: 'grid', gap: 6, fontSize: 13, fontWeight: 600 }}>
                วันที่รับเงิน
                <input type="date" value={paymentDraft.receivedAt}
                  onChange={(e) => setPaymentDraft((draft) => ({ ...draft, receivedAt: e.target.value }))} />
              </label>
            </div>
            <label style={{ display: 'grid', gap: 6, fontSize: 13, fontWeight: 600 }}>
              เลขอ้างอิง
              <input value={paymentDraft.receiptRef}
                onChange={(e) => setPaymentDraft((draft) => ({ ...draft, receiptRef: e.target.value }))} />
            </label>
            <label style={{ display: 'grid', gap: 6, fontSize: 13, fontWeight: 600 }}>
              หมายเหตุ
              <textarea rows={3} value={paymentDraft.note}
                onChange={(e) => setPaymentDraft((draft) => ({ ...draft, note: e.target.value }))} />
            </label>
            <label style={{ display: 'flex', gap: 8, alignItems: 'center', fontSize: 13 }}>
              <input type="checkbox" checked={paymentDraft.allowOverpayment}
                onChange={(e) => setPaymentDraft((draft) => ({ ...draft, allowOverpayment: e.target.checked }))} />
              ยืนยันรับชำระเกินยอด
            </label>
          </div>
        </Modal>
      )}

      {billingModal && (
        <Modal
          title="ตั้งค่าการวางบิล"
          onClose={() => setBillingModal(false)}
          footer={(
            <>
              <button type="button" className="secondary-button" onClick={() => setBillingModal(false)}>ยกเลิก</button>
              <button type="button" className="primary-button" disabled={actionLoading} onClick={handleSetBilling}>
                บันทึก
              </button>
            </>
          )}
        >
          <div style={{ display: 'grid', gap: 12 }}>
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(150px, 1fr))', gap: 10 }}>
              <label style={{ display: 'grid', gap: 6, fontSize: 13, fontWeight: 600 }}>
                วันที่วางบิล
                <input type="date" value={billingDraft.billingDate}
                  onChange={(e) => setBillingDraft((draft) => ({ ...draft, billingDate: e.target.value }))} />
              </label>
              <label style={{ display: 'grid', gap: 6, fontSize: 13, fontWeight: 600 }}>
                วันครบกำหนด
                <input type="date" value={billingDraft.dueDate}
                  onChange={(e) => setBillingDraft((draft) => ({ ...draft, dueDate: e.target.value }))} />
              </label>
              <label style={{ display: 'grid', gap: 6, fontSize: 13, fontWeight: 600 }}>
                เครดิต (วัน)
                <input type="number" min="0" value={billingDraft.creditTermDays}
                  onChange={(e) => setBillingDraft((draft) => ({ ...draft, creditTermDays: e.target.value }))} />
              </label>
            </div>
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(150px, 1fr))', gap: 10 }}>
              <label style={{ display: 'grid', gap: 6, fontSize: 13, fontWeight: 600 }}>
                ติดตามล่าสุด
                <input type="date" value={billingDraft.lastFollowUpAt}
                  onChange={(e) => setBillingDraft((draft) => ({ ...draft, lastFollowUpAt: e.target.value }))} />
              </label>
              <label style={{ display: 'grid', gap: 6, fontSize: 13, fontWeight: 600 }}>
                ติดตามครั้งถัดไป
                <input type="date" value={billingDraft.nextFollowUpAt}
                  onChange={(e) => setBillingDraft((draft) => ({ ...draft, nextFollowUpAt: e.target.value }))} />
              </label>
            </div>
          </div>
        </Modal>
      )}

      {deliveryModal && (
        <Modal
          title="บันทึกการส่งสินค้า"
          onClose={closeDeliveryModal}
          footer={(
            <>
              <button type="button" className="secondary-button" onClick={closeDeliveryModal}>ยกเลิก</button>
              <button type="button" className="primary-button" disabled={actionLoading} onClick={handleRecordDelivery}>
                บันทึก
              </button>
            </>
          )}
        >
          <div style={{ display: 'grid', gap: 12 }}>
            <label style={{ display: 'grid', gap: 6, fontSize: 13, fontWeight: 600 }}>
              แหล่งสินค้า
              <select value={deliveryDraft.source}
                onChange={(e) => setDeliveryDraft((draft) => ({ ...draft, source: e.target.value }))}>
                <option value="WAREHOUSE">WAREHOUSE</option>
                <option value="STOCK">STOCK</option>
              </select>
            </label>
            {/*
              UX-03: "at least 1 line item with qty > 0" is a group-level rule
              with no single owning field — like DepositNoticePage's
              doc-items-panel, the wrapper itself is the scroll/focus target,
              carrying aria-invalid/aria-describedby and a tabIndex so a plain
              div is focusable.
            */}
            <div
              id="delivery-lines-panel"
              ref={(el) => { fieldRefs.current['delivery.lines'] = el; }}
              tabIndex={-1}
              aria-invalid={fieldErrors['delivery.lines'] ? true : undefined}
              aria-describedby={fieldErrors['delivery.lines'] ? fieldErrorId('delivery-lines-panel') : undefined}
              style={{ display: 'grid', gap: 8 }}
            >
              {items.map((item) => {
                const remaining = Math.max(0, Number(item.qty || 0) - Number(item.qtyDelivered || 0));
                return (
	                  <label key={item.id} style={{ display: 'grid', gridTemplateColumns: 'minmax(0, 1fr) 120px', gap: 10, alignItems: 'center', fontSize: 13 }}>
	                    <span>
                        <span className="sr-only">จำนวนส่งมอบ</span>
	                      <strong>{item.brand} {item.model || ''}</strong>
                      <small style={{ display: 'block', color: 'var(--color-text-muted)' }}>
                        คงเหลือ {remaining.toLocaleString('en-US')} · ส่งแล้ว {Number(item.qtyDelivered || 0).toLocaleString('en-US')}
                      </small>
                    </span>
	                    <input type="number" min="0" max={remaining} step="0.01"
                        aria-label={`จำนวนส่งมอบ ${item.brand} ${item.model || ''}`}
	                      value={deliveryDraft.lines[item.id] ?? ''}
                      onChange={(e) => {
                        const { value } = e.target;
                        setDeliveryDraft((draft) => ({
                          ...draft,
                          lines: { ...draft.lines, [item.id]: value },
                        }));
                        // The group rule is "at least one line qty > 0" — the
                        // instant THIS line goes positive that rule is
                        // satisfied regardless of the other lines' values
                        // (they were all <=0 for the error to have fired).
                        if (Number(value) > 0) clearFieldError('delivery.lines');
                      }} />
                  </label>
                );
              })}
              {fieldErrors['delivery.lines'] ? (
                <p id={fieldErrorId('delivery-lines-panel')} role="alert" style={{ margin: 0, fontSize: 12, fontWeight: 700, color: 'var(--color-danger)' }}>
                  {fieldErrors['delivery.lines']}
                </p>
              ) : null}
            </div>
            <label style={{ display: 'grid', gap: 6, fontSize: 13, fontWeight: 600 }}>
              หมายเหตุ
              <textarea rows={3} value={deliveryDraft.note}
                onChange={(e) => setDeliveryDraft((draft) => ({ ...draft, note: e.target.value }))} />
            </label>
          </div>
        </Modal>
      )}

      {stockModal && (
        <Modal
          title="จองสินค้าจากสต็อก"
          onClose={() => setStockModal(false)}
          footer={(
            <>
              <button type="button" className="secondary-button" onClick={() => setStockModal(false)}>ยกเลิก</button>
              <button type="button" className="primary-button" disabled={actionLoading} onClick={handleReserveStock}>
                บันทึก
              </button>
            </>
          )}
        >
          <div style={{ display: 'grid', gap: 12 }}>
            {items.map((item) => (
	              <label key={item.id} style={{ display: 'grid', gridTemplateColumns: 'minmax(0, 1fr) 120px', gap: 10, alignItems: 'center', fontSize: 13 }}>
	                <span>
                    <span className="sr-only">จำนวนจากสต็อก</span>
	                  <strong>{item.brand} {item.model || ''}</strong>
                  <small style={{ display: 'block', color: 'var(--color-text-muted)' }}>
                    สั่ง {Number(item.qty || 0).toLocaleString('en-US')} · จากสต็อกเดิม {Number(item.qtyFromStock || 0).toLocaleString('en-US')}
                  </small>
                </span>
	                <input type="number" min="0" max={Number(item.qty || 0)} step="0.01"
                    aria-label={`จำนวนจากสต็อก ${item.brand} ${item.model || ''}`}
	                  value={stockDraft.lines[item.id] ?? ''}
                  onChange={(e) => setStockDraft((draft) => ({
                    ...draft,
                    lines: { ...draft.lines, [item.id]: e.target.value },
                  }))} />
              </label>
            ))}
            <label style={{ display: 'grid', gap: 6, fontSize: 13, fontWeight: 600 }}>
              เหตุผล / หมายเหตุ
              <textarea rows={3} value={stockDraft.note}
                onChange={(e) => setStockDraft((draft) => ({ ...draft, note: e.target.value }))} />
            </label>
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

      {/* A bare yes/no confirm can't capture WHY, and cancel is irreversible —
          so this is the mark-lost modal's reason picker, not a ConfirmDialog. */}
      {confirm?.kind === 'cancelTicket' && (
        <CancelDealModal
          submitting={actionLoading}
          onClose={() => setConfirm(null)}
          onSubmit={async (payload) => {
            await doAction(() => api.tickets.cancel(ticketId, payload), 'ยกเลิกดีลแล้ว');
            setConfirm(null);
          }}
        />
      )}

      <ConfirmDialog
        open={confirm?.kind === 'finalPayment'}
        tone="danger"
        title="ยืนยันการรับชำระครบถ้วน"
        message={(() => {
          // Same summary.amountOutstanding the การชำระเงิน panel above renders
          // as "คงเหลือ" (line ~1091) — reused as-is, not recomputed, so this
          // dialog can never disagree with the panel the user just looked at.
          const outstanding = Number(summary.amountOutstanding ?? 0);
          const hasOutstanding = outstanding > 0;
          return (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
              <p className="confirm-dialog-message" style={{ margin: 0 }}>
                {hasOutstanding
                  ? 'ระบบจะบันทึกรับชำระส่วนที่เหลือเต็มจำนวนเป็นรายการ BALANCE แล้วทำเครื่องหมายดีลนี้ว่าชำระครบแล้ว'
                  : 'ยอดคงเหลือเป็นศูนย์อยู่แล้ว ระบบจะทำเครื่องหมายดีลนี้ว่าชำระครบแล้วโดยไม่บันทึกรายการรับชำระใหม่'}
              </p>
              {hasOutstanding && (
                <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: 14, fontWeight: 700, borderTop: '1px solid var(--color-border)', paddingTop: 8 }}>
                  <span>ยอดที่จะบันทึกเป็นรับชำระ (คงเหลือ)</span>
                  <span className="font-mono">{formatMoney(outstanding)}</span>
                </div>
              )}
              <p style={{ margin: 0, fontSize: 12, color: 'var(--color-icon-muted)' }}>
                สถานะการชำระเงินจะเปลี่ยนเป็น &quot;ชำระครบแล้ว&quot; และไม่สามารถย้อนกลับได้
              </p>
            </div>
          );
        })()}
        confirmLabel="ยืนยันชำระครบ"
        busy={actionLoading}
        onConfirm={confirmFinalPaymentAction}
        onCancel={() => setConfirm(null)}
      />
    </div>
  );
}
