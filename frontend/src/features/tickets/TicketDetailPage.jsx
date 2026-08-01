import { useEffect, useLayoutEffect, useRef, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useNavigate, useSearchParams } from 'react-router-dom';
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
import { Tabs, TabPanel } from '../../components/common/Tabs.jsx';
import {
  dealStageLabel,
  formatMoney,
  formatThaiDate,
  overdueBadgeLabel,
  paymentStageLabel,
  quotationRecipientLabel,
  quotationStatusLabel,
} from '../../utils/format.js';
import { downloadBlob } from '../../utils/download.js';
import { PricingRequestPanel } from '../pricingRequests/PricingRequestPanel.jsx';
import { CancelDealModal } from './CancelDealModal.jsx';
import { hasActivitySince, isReadyToAdvance, lastStageChangeAt, STAGE_ADVANCE_GATE_HINT } from './dealTrackingMeta.js';
import { DealDepositPanel } from './DealDepositPanel.jsx';
import { DealFulfilmentPanel } from './DealFulfilmentPanel.jsx';
import { DealHistoryPanel } from './DealHistoryPanel.jsx';
import { DealQuotationPanel } from './DealQuotationPanel.jsx';
import { DealStagePanel } from './DealStagePanel.jsx';
import { DealStateHeader } from './DealStateHeader.jsx';
import { DealTrackingPanel } from './DealTrackingPanel.jsx';
import { TicketContextPanel } from './TicketContextPanel.jsx';
import { visibleSections } from './salesViewScope.js';
import { allowedTargetStages, canMarkLost, canSetStage, nextStage } from './stageMeta.js';
import {
  DEFAULT_TICKET_DETAIL_TAB_ID, resolveTicketDetailTab, TICKET_DETAIL_TABS, visibleTicketDetailTabIds,
} from './ticketDetailTabs.js';
import { resolveWorkState } from './workState.js';

// Ticket-detail IA rebuild Phase 1 (see
// docs/ui-repair/02-information-architecture/TICKET_INFORMATION_ARCHITECTURE.md
// "Action bar (sticky)"): where the sticky primary button sends the viewer
// when workState.js hands back an action this page has no dedicated `can.*`
// button for (FOLLOW_UP/LOG_ACTIVITY for sales; the fulfilment chain for
// import; the deposit/final-payment/close-ready steps for account) — an
// in-page anchor id when the real control already lives further down THIS
// page, so the button scrolls to it instead of duplicating its logic (never
// a second copy of a mutation the real panel already owns and gates).
// Import's pickup step and account's commission step point at a different
// ROUTE entirely (nextImportAction/nextAccountAction's own `to`), handled
// separately below. CREATE_PCR/ISSUE_QUOTATION/CONFIRM_ORDER are NOT
// scroll targets — each opens its owning panel's mutation directly via a
// forwardRef instead (see pricingRequestPanelRef/dealQuotationPanelRef
// below and their own FIX 1/FIX 2 doc comments), so they are deliberately
// absent from this map.
const IN_PAGE_JUMP_TARGET = {
  // salesActions.js SALES_ACTION keys
  follow_up: 'deal-tracking-panel',
  log_activity: 'deal-tracking-panel',
  // importActions.js codes
  issueImportRequest: 'deal-fulfilment-panel',
  markIrSent: 'deal-fulfilment-panel',
  markShipping: 'deal-fulfilment-panel',
  markGoodsReceived: 'deal-fulfilment-panel',
  recordDelivery: 'deal-fulfilment-panel',
  // accountActions.js keys
  chaseOverdue: 'deal-deposit-panel',
  confirmDeposit: 'deal-deposit-panel',
  confirmFinalPayment: 'deal-deposit-panel',
  confirmCloseReady: 'deal-deposit-panel',
};

// Ticket-detail IA rebuild Phase 2: every one of the jump targets above (and
// the ref-opened CREATE_PCR/ISSUE_QUOTATION/CONFIRM_ORDER panels) now lives
// inside a conditionally-MOUNTED TabPanel (Tabs.jsx keeps only the active
// tab's children mounted — see its own doc comment). A trigger that lives
// OUTSIDE the tabs (the sticky header primary CTA, the header's "⋯" overflow
// menu) can be clicked while a DIFFERENT tab is active, in which case the
// target element/ref doesn't exist yet — `scrollToSection` would silently
// no-op and a forwardRef call would silently no-op, exactly the "control
// flips a flag inside an unmounted panel and vanishes" dead end this
// branch's brief calls out. `runOnTab` (defined inside the component, where
// `activeTab`/`setActiveTab` live) switches to the owning tab FIRST and only
// then performs the jump/ref-call, once that tab's own panel has mounted.
const JUMP_TARGET_TAB = {
  'deal-tracking-panel': 'activity',
  'deal-fulfilment-panel': 'fulfilment',
  'deal-deposit-panel': 'money',
};

function scrollToSection(id) {
  const el = typeof document !== 'undefined' ? document.getElementById(id) : null;
  if (!el) return;
  // jsdom (Vitest) doesn't implement scrollIntoView — guarded so the same
  // jump helper used by the revise-form effect and the sticky primary CTA
  // doesn't throw under test, only under a real browser's absence of it.
  el.scrollIntoView?.({ behavior: 'smooth', block: 'start' });
  el.focus?.({ preventScroll: true });
}

// Ticket-detail IA rebuild Phase 2: the "ประวัติการดำเนินการ" events panel that
// used to render inline here (with its own EVENT_KIND_LABEL/eventDotClass/
// PAYMENT_TRACK_KINDS/FULFILLMENT_TRACK_KINDS) is merged with the deal-
// tracking activity log into DealHistoryPanel.jsx (กิจกรรม tab) — that
// component owns its own identical copies of those constants now; nothing
// here references them any more.

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

function InfoRow({ label, value }) {
  return (
    <div style={{ display: 'flex', gap: 8, padding: '6px 0', borderBottom: '1px solid var(--color-surface-subtle)', fontSize: 13 }}>
      <span style={{ color: 'var(--color-text-muted)', minWidth: 120 }}>{label}</span>
      <span style={{ fontWeight: 600, color: 'var(--color-text)' }}>{value || '-'}</span>
    </div>
  );
}

export function TicketDetailPage({ user, ticketId, onBack, showToast }) {
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  // Ticket-detail IA rebuild Phase 2: the active tab lives in the URL, not
  // local state (same `useSearchParams` convention as TicketListPage.jsx's
  // phase/lifecycle/flag filters) — list → detail → back and a shared deep
  // link both keep it, and `{ replace: true }` keeps switching tabs out of
  // the back-button history stack (matches TicketListPage's own filter
  // writes). `role`/`sections` aren't known yet this early (they're derived
  // from `ticket.summary` below, past the loading/no-ticket early returns),
  // so the actual `activeTab` value is computed further down; the setter
  // only needs the param plumbing, which IS available this early.
  const [searchParams, setSearchParams] = useSearchParams();
  function setActiveTab(tabId) {
    setSearchParams((prev) => {
      const next = new URLSearchParams(prev);
      next.set('tab', tabId);
      return next;
    }, { replace: true });
  }
  // A cross-tab trigger (the sticky header primary CTA, the "⋯" overflow
  // menu's "ขอแก้ไข") queues its real action here and switches tabs; the
  // effect below (after `activeTab` is known) runs it once the destination
  // tab's TabPanel has actually mounted — see JUMP_TARGET_TAB's own doc
  // comment for why this can't just scroll/call-ref immediately. Declared
  // with useRef this early (hooks can't follow the loading/no-ticket early
  // returns below) but only ever populated by `runOnTab`, defined further
  // down once `activeTab` itself is known.
  const pendingTabActionRef = useRef(null);

  // `role`/`sections`/the tab derivation below only need `user`/`searchParams`
  // (never `ticket`), so — unlike `isOwner`/`summary`-derived values further
  // down — they can (and, per the rules of hooks, must) sit before the
  // loading/no-ticket early returns: the pending-tab-action effect that
  // reads `activeTab` cannot itself come after a conditional return.
  const role = user.role;
  // Role-scoped views, Phase A: which of the sections below this viewer's
  // role gets in full vs. hidden entirely. See salesViewScope.js —
  // presentation only, never a security boundary. Ticket-detail IA rebuild
  // Phase 2: these section ids now compose ticketDetailTabs.js's 7 tabs
  // (each tab owns one or more of them) rather than gating an inline
  // SectionPeek — a role-hidden section's TAB is simply absent from the
  // tablist, there is no more per-section peek fallback on the page itself.
  const sections = visibleSections(role);

  // Ticket-detail IA rebuild Phase 2: `?tab=` is the single source of truth
  // for which tab is open — an absent, unknown, or role-hidden value falls
  // back to `overview` (resolveTicketDetailTab), so a stale deep link (a
  // role change, a typo, hand-editing the URL) never renders a blank or
  // forbidden panel.
  const visibleTabIds = visibleTicketDetailTabIds(role);
  const activeTab = resolveTicketDetailTab(searchParams.get('tab'), role);
  const tabItems = TICKET_DETAIL_TABS
    .filter((tab) => visibleTabIds.includes(tab.id))
    .map((tab) => ({ id: tab.id, label: tab.label, helper: tab.helper }));

  // Runs a cross-tab trigger's real action (a scroll, or a ref-call into a
  // panel that lives in a different tab) — switching to that tab first if
  // we're not already on it. See JUMP_TARGET_TAB's own doc comment for why
  // this exists: every such panel is now conditionally MOUNTED (Tabs.jsx's
  // TabPanel), so calling straight through would silently no-op on any tab
  // but the target one.
  function runOnTab(tabId, run) {
    if (activeTab === tabId) { run(); return; }
    pendingTabActionRef.current = { tabId, run };
    setActiveTab(tabId);
  }
  // `activeTab` only changes (and this effect only re-fires) once the URL
  // update above has actually committed — by then the destination
  // TabPanel's children (and any ref inside them) have already mounted in
  // the SAME commit, so `pending.run()` here always finds a live target.
  // Intentionally keyed on `activeTab` alone: `pending` is read fresh from
  // the ref (never a dependency), so adding it would replay a stale queued
  // action on every unrelated render.
  useEffect(() => {
    const pending = pendingTabActionRef.current;
    if (pending && pending.tabId === activeTab) {
      pendingTabActionRef.current = null;
      pending.run();
    }
  }, [activeTab]);

  // The ticket workspace's persistent chrome (state header + detail tabs) is
  // content-dependent: primary actions, project names, and role-specific tabs
  // all change its height. Measure the rendered sticky region once and let
  // scroll-padding + the desktop context rail read the same value.
  const ticketStickyChromeRef = useRef(null);
  const [ticketChromeCondensed, setTicketChromeCondensed] = useState(false);

  // Imperative handle onto DealStagePanel (see its own doc comment): the
  // header overflow menu / bottom danger zone trigger its modals from here,
  // but DealStagePanel stays the sole owner of whether each is actually
  // available and of the modal/mutation itself.
  const dealStagePanelRef = useRef(null);
  // Imperative handle onto PricingRequestPanel (FIX 1, ticket-detail IA
  // rebuild Phase 1 clutter follow-up): the sticky header's CREATE_PCR
  // primary CTA opens this panel's own create modal directly instead of
  // duplicating a second "สร้างคำขอราคา" button — same convention as
  // dealStagePanelRef above.
  const pricingRequestPanelRef = useRef(null);
  // Imperative handle onto DealQuotationPanel (clutter follow-up round 2,
  // FIX 2): the sticky header's ISSUE_QUOTATION/CONFIRM_ORDER primary CTAs
  // trigger this panel's own mutations directly instead of scrolling to a
  // second copy of each button — same convention as the two refs above.
  const dealQuotationPanelRef = useRef(null);

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
  // never bleed into another: 'payment.amount' | 'revise.reason' |
  // 'editItems.qty.<rowIndex>' (per-row — see the edit-items save handler).
  // ('quotation.*'/'reject.reason'/'override.<itemId>' were retired along
  // with ticket-native pricing/quotation — Phase 2 Slice S1/S2; 'delivery.lines'
  // moved out along with the delivery/stock modals themselves — Phase 3 Slice
  // S4, see docs/agent-handoffs/105_feat-deal-deposit-fulfilment-unify.md —
  // DealFulfilmentPanel owns its own, simpler mutation-level error toasts
  // instead, matching DealDepositPanel/DealQuotationPanel's convention.)
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
  // Delivery/stock-reservation modal state moved into DealFulfilmentPanel
  // (Phase 3 Slice S4 — see docs/agent-handoffs/105_feat-deal-deposit-fulfilment-unify.md).

  // Comment
  const [commentText, setCommentText] = useState('');
  const [contextCommentText, setContextCommentText] = useState('');

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
  // FIX 2 (Opus review), rewritten for issue #389. Still an identity-aware gate rather than a
  // role-only one — the deal's participants (createdById/assignedToId) reach its documents
  // regardless of role — but the ROLE half is no longer this page's private invention: it mirrors
  // TicketAccessPolicy.canViewDocuments, which is `sales`- AND `import`-scoped to participation.
  //
  // What changed: `account` used to be filtered out here, faithfully mirroring a backend gate
  // that 403'd it — but that 403 was the bug. account is the role asked to confirm deposit and
  // final-payment receipts against exactly these files, so hiding the tab would have left #389's
  // fix invisible. A non-assignee `import` stays filtered out, unchanged: AttachType spans
  // SIGNED_QUOTATION/INVOICE, which carry the approved customer price that salesViewScope already
  // hides from import (quotation/dealQuotation/payment/depositNotice all false) and that the
  // backend refuses it on three other endpoints. `hr` needs no mention — route-gated off this
  // page entirely, and now refused by the backend too.
  //
  // Computed here (rather than after the loading/no-ticket early return below, where `isOwner`
  // already lives) so the attachments query never fires for a viewer the backend would 403 —
  // same reasoning as `activitiesQuery`'s own `enabled` gate for FIX 1. Presentation projection
  // of the backend gate, not a new authorization rule.
  const documentsTicketSummary = ticket?.summary ?? null;
  const canViewDocumentsTab = Boolean(documentsTicketSummary) && (
    user.id === documentsTicketSummary.createdById
    || user.id === documentsTicketSummary.assignedToId
    || ROLE_PERMISSIONS.canViewTicketDocuments.includes(role)
  );
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

  // Delivery history (ticketDeliveries) moved into DealFulfilmentPanel's own
  // query (Phase 3 Slice S4) — this page no longer reads it directly.

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

  // The revise form (see the "can.revise && showReviseForm" section further
  // down) opens ~400px below where its overflow-menu trigger lives — unlike
  // every other overflow item, which opens a modal in place. Without this,
  // opening it was a silent no-op from the viewer's vantage point (nothing
  // visibly happened until they scrolled to look for it), which is exactly
  // the "the control that matters is off-screen" problem this rebuild
  // exists to fix. Runs after the section actually mounts (the effect fires
  // on the render where showReviseForm just became true).
  useEffect(() => {
    if (showReviseForm) scrollToSection('revise-form');
  }, [showReviseForm]);

  const attachmentsQuery = useQuery({
    queryKey: queryKeys.ticketAttachments(ticketId),
    queryFn: () => api.attachments.list(ticketId).then((r) => r.attachments ?? []),
    // FIX 2: never fire this request for a viewer AttachmentController would
    // 403 — see `canViewDocumentsTab`'s own doc comment above.
    enabled: !!ticketId && canViewDocumentsTab,
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
    // UX-03: a successful action closes every modal below (payment among
    // them; delivery/stock moved to DealFulfilmentPanel, Phase 3 Slice S4) —
    // clear every field error along with them so a stale one can never greet
    // the user on the next open.
    setFieldErrors({});
    setEditMode(false);
    setEditDraft([]);
    setEditNote('');
    setShowReviseForm(false);
    setReviseReason('');
    setPaymentModal(false);
    setBillingModal(false);
    setCommentText('');
    setContextCommentText('');
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

  useLayoutEffect(() => {
    if (loading) return undefined;
    const chrome = ticketStickyChromeRef.current;
    if (!chrome || typeof window === 'undefined') return undefined;
    const scroller = chrome.closest('.content-scroll');
    if (!scroller) return undefined;
    const mobileQuery = typeof window.matchMedia === 'function'
      ? window.matchMedia('(max-width: 720px)')
      : null;
    const updateCondensed = () => {
      setTicketChromeCondensed(scroller.scrollTop > 8 && !mobileQuery?.matches);
    };

    updateCondensed();
    scroller.addEventListener('scroll', updateCondensed, { passive: true });
    mobileQuery?.addEventListener?.('change', updateCondensed);
    return () => {
      scroller.removeEventListener('scroll', updateCondensed);
      mobileQuery?.removeEventListener?.('change', updateCondensed);
    };
  }, [loading, ticketId]);

  useLayoutEffect(() => {
    if (loading) return undefined;
    const chrome = ticketStickyChromeRef.current;
    if (!chrome || typeof document === 'undefined') return undefined;
    const writeHeight = () => {
      const height = Math.ceil(chrome.getBoundingClientRect().height);
      if (height > 0) {
        document.documentElement.style.setProperty('--deal-header-h', `${height}px`);
      }
    };

    writeHeight();
    if (typeof ResizeObserver === 'undefined') {
      return () => document.documentElement.style.removeProperty('--deal-header-h');
    }

    const observer = new ResizeObserver(writeHeight);
    observer.observe(chrome);
    return () => {
      observer.disconnect();
      document.documentElement.style.removeProperty('--deal-header-h');
    };
  }, [loading, ticketId]);

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
  const isOwner = user.id === summary.createdById;
  // Issue #389: reading a deal's documents is now the same question as reading the deal (so
  // account/import reach the เอกสาร panel and hr no longer does), but WRITING one stays narrow —
  // TicketAccessPolicy.canManageDocuments is participant OR sales_manager/ceo. Mirrored here so
  // the panel does not offer account/import an upload the backend answers with a 403.
  const canManageDocuments = isOwner
    || (summary.assignedToId != null && user.id === summary.assignedToId)
    || ROLE_PERMISSIONS.canManageTicketDocuments.includes(role);

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
  const isAccount = ROLE_PERMISSIONS.canConfirmPayments.includes(role);
  // (deliveryDone / dualTrackDone removed with the single-step close: the close
  // gate is now the server's three-party sequence, surfaced via availableActions.)
  // Still needed for DealStagePanel's own read-only deliveryProgress prop
  // below — the percentage/per-item breakdown display itself moved into
  // DealFulfilmentPanel (Phase 3 Slice S4), which computes its own copy from
  // the same `items`.
  const totalOrdered = items.reduce((sum, item) => sum + Number(item.qty || 0), 0);
  const totalDelivered = items.reduce((sum, item) => sum + Number(item.qtyDelivered || 0), 0);

  // Documents that already exist stay reachable from the deal-stage panel
  // through the later stages: the latest quotation file. The deposit-notice
  // view/issue affordance moved into DealDepositPanel (Phase 3 Slice S3 — see
  // docs/agent-handoffs/105_feat-deal-deposit-fulfilment-unify.md).
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

  // 'draft' included since V50: a lightweight lead-stage deal gets its product
  // items here, then submits into the price-request flow when it reaches the
  // quote stages.
  const EDITABLE_STATUSES = ['draft', 'submitted', 'in_review', 'price_proposed'];
  const can = {
    // Ticket-level submit/pickup/propose-price/calculate-prices/override-item-price/approve/
    // reject/generate-quotation are retired (Phase 2 Slice S1/S2 "engine collapse" — see
    // docs/agent-handoffs/104_feat-deal-workspace-unification.md). Pricing now runs entirely on
    // the PricingRequest chain (PricingRequestPanel below the items table + DealQuotationPanel
    // for the customer-facing quotation tail + order-confirm). The deposit-notice
    // create/issue/view affordance (formerly generateDocument here) and the
    // deposit-payment confirmation (formerly confirmDepositPaid here) both moved
    // into DealDepositPanel (Phase 3 Slice S3), and the fulfilment chain
    // (issueImportRequest/markIrSent/markShipping/markGoodsReceived/
    // reserveStock/recordDelivery/completeDelivery, formerly defined here)
    // moved into DealFulfilmentPanel (Phase 3 Slice S4 — see
    // docs/agent-handoffs/105_feat-deal-deposit-fulfilment-unify.md), which
    // owns its own mutations against the same hrApi methods rather than
    // routing through this page's shared actionMutation.
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
    confirmFinalPayment:hasAction('FINAL_PAYMENT') && st === 'quotation_issued' && isAccount,
    recordPayment:      hasAction('RECORD_PAYMENT') && isAccount,
    setBilling:         hasAction('SET_BILLING') && isAccount,
    downloadRemainingInvoice: st === 'quotation_issued' && fs === 'GOODS_RECEIVED' && isSales,
  };

  // FIX 2 (Opus review): `tabItems`/`activeTab` above are role-level only
  // (computed before `ticket` loads at all) — เอกสาร additionally needs
  // `canViewDocumentsTab`'s per-instance identity check, computed once
  // `ticket` is available. Filtered here, right where isOwner/summary are
  // finally known, rather than widening `ticketDetailTabs.js`'s (role,
  // sections)-only `isVisible` signature for one tab.
  const visibleTabItems = tabItems.filter((tab) => tab.id !== 'documents' || canViewDocumentsTab);
  const visibleActiveTab = (activeTab === 'documents' && !canViewDocumentsTab) ? DEFAULT_TICKET_DETAIL_TAB_ID : activeTab;

  // การดำเนินการอื่น ๆ now holds only the rare actions — the workflow-shaped
  // dual-track confirmations/close live in the sticky header, docs live in
  // DealStagePanel's เอกสารของขั้นนี้ row, and แก้ไขสถานะ/พักดีลไว้/พัก dormant/
  // ขอแก้ไข/เสียงาน/ยกเลิก moved into the header overflow menu and the bottom
  // "จัดการดีล" danger zone (ticket-detail IA rebuild Phase 1 — see
  // workState.js's own doc comment). The section hides entirely when none of
  // the remaining actions apply.
  const hasActions = can.editItems || can.revokeCloseConfirm
    || (st === 'draft' && isOwner && items.length === 0);

  // Next-action summary for the current viewer only — derived strictly from the
  // `can` flags above (which already encode real status+role permission checks).
  // Verified against backend/mock transition handlers (see api.tickets.* in
  // src/api/mockApi.js) so the wording matches what the button actually does.
  // Never invents an owner or action the data can't support. 'revise' is
  // deliberately NOT in this cascade any more (Phase-1 audit finding #1): it
  // describes an OPTIONAL action, not something blocking the deal's progress,
  // so it no longer competes for the one "ถึงคิวคุณ" banner slot — it is still
  // fully reachable, from the header overflow menu.
  const NEXT_ACTION_STEPS = [
    // Dual-track steps: re-issuing/working the customer quotation lives in
    // DealQuotationPanel, the deposit-notice/deposit-payment steps live in
    // DealDepositPanel (Phase 3 Slice S3), and the fulfilment chain
    // (issueImportRequest/markIrSent/markShipping/markGoodsReceived) lives in
    // DealFulfilmentPanel (Phase 3 Slice S4) — so this list only covers the
    // operational chain this page's own primaryAction button still drives.
    ['confirmCustomer',  'ยืนยันว่าลูกค้าตกลงคำสั่งซื้อแล้ว'],
    ['confirmFinalPayment','ยืนยันว่าลูกค้าชำระส่วนที่เหลือครบแล้ว'],
    ['confirmClose',      'ส่งมอบและรับเงินครบแล้ว — ยืนยันเพื่อส่งให้ CEO ตรวจสอบปิดงาน'],
    ['verifyClose',       'ฝ่ายบัญชียืนยันแล้ว — ตรวจสอบและปิดงานได้เลย'],
  ];
  const nextAction = NEXT_ACTION_STEPS.find(([key, text]) => can[key] && text)?.[1] ?? null;

  // The blocker line (region 7 of the IA — "unmet precondition"): why the
  // deal isn't moving even though it might not be this viewer's turn to act.
  // Paired with the "รอ<role>" waiting banner below, never with an actionable
  // "ถึงคิวคุณ" line (a live primary action is never "blocked" by the same
  // thing the banner would name — pairing the two would read as a
  // contradiction). Kept to the fact only (design law: brief — who/what, not
  // what-to-do-about-it-by-when).
  const closeConfirmedAt = summary?.closeConfirmedAt ?? null;
  // !isAccount on the two payment-wait lines (P3, review round 2): account
  // is the role that CLEARS these two waits (confirmDeposit/
  // confirmFinalPayment — see nextAccountAction in accountActions.js) — for
  // account specifically, this line would otherwise read "รอชำระมัดจำ" right
  // next to their own resolver-derived "ถึงคิวคุณ: ยืนยันรับมัดจำ" primary,
  // stating the same fact twice (once as a call to action, once as a
  // blocker) instead of pairing the blocker with a role that ISN'T the one
  // who can act on it — exactly the contradiction this line's own doc
  // comment above warns against.
  const blocker = closeConfirmedAt && !can.verifyClose
    ? 'รอ CEO ตรวจสอบปิดงาน'
    : ps === 'DEPOSIT_NOTICE_ISSUED' && !isAccount ? 'รอชำระมัดจำ'
      : ps === 'AWAITING_FINAL_PAYMENT' && !isAccount ? 'รอชำระส่วนที่เหลือ'
        : null;

  // The cockpit's primary action: the ONE workflow button for this viewer's
  // current sub-step (moved verbatim out of การดำเนินการอื่น ๆ). The
  // customer-quotation issue/outcome/confirm-order actions live in
  // DealQuotationPanel, the deposit-notice/deposit-payment actions live in
  // DealDepositPanel (Phase 3 Slice S3), and the fulfilment chain
  // (issue IR/mark sent/shipping/goods received) lives in
  // DealFulfilmentPanel (Phase 3 Slice S4 — see
  // docs/agent-handoffs/105_feat-deal-deposit-fulfilment-unify.md) — all
  // three have their own primary buttons, so this page's own primaryAction
  // no longer drives that chain.
  const primaryAction = can.confirmCustomer ? (
    <button type="button" className="primary-button" disabled={actionLoading} data-testid="ticket-detail-confirm-customer"
      onClick={() => doAction(() => api.tickets.confirmCustomer(ticketId), 'ลูกค้ายืนยันแล้ว')}>
      ลูกค้ายืนยัน
    </button>
  ) : can.confirmFinalPayment ? (
    <button type="button" className="primary-button" disabled={actionLoading} data-testid="ticket-detail-confirm-final"
      onClick={() => setConfirm({ kind: 'finalPayment' })}>
      ยืนยันชำระครบ (Final Payment)
    </button>
  ) : can.confirmClose ? (
    <button type="button" className="primary-button" disabled={actionLoading} data-testid="ticket-detail-confirm-close"
      onClick={() => doAction(() => api.tickets.confirmCloseReady(ticketId),
        'ยืนยันพร้อมปิดงานแล้ว — รอ CEO ตรวจสอบ')}>
      <Icon name="check" size={14} />
      ยืนยันพร้อมปิดงาน
    </button>
  ) : can.verifyClose ? (
    <button type="button" className="primary-button" disabled={actionLoading} data-testid="ticket-detail-verify-close"
      onClick={() => doAction(() => api.tickets.verifyClose(ticketId), 'ตรวจสอบและปิดงานแล้ว')}>
      <Icon name="check" size={14} />
      ตรวจสอบและปิดงาน
    </button>
  ) : null;

  // Sticky action bar (ticket-detail IA rebuild Phase 1). `primaryAction`
  // above (confirmCustomer/finalPayment/close/verify) is a REAL, server-
  // gated button — when it exists it always wins, unchanged. workState.js's
  // resolveWorkState fills the gap for every earlier cascade step
  // (CREATE_PCR/ISSUE_QUOTATION/CONFIRM_ORDER/FOLLOW_UP/LOG_ACTIVITY for
  // sales; the fulfilment chain for import; deposit/final-payment/close-
  // ready for account) that this page has no dedicated `can.*` button for —
  // rendered as a "jump to the real control" convenience (in-page scroll,
  // or a real route when the control lives elsewhere), never a duplicate of
  // the mutation itself. See workState.js's own doc comment for why it can
  // also say "not your turn" (`waitingRoleLabel`), which nextSalesAction/
  // nextImportAction/nextAccountAction alone cannot express.
  //
  // CREATE_PCR/ISSUE_QUOTATION/CONFIRM_ORDER are the exceptions to "jump,
  // don't duplicate" (FIX 1 + FIX 2, Phase-1 clutter follow-up rounds 1/2):
  // each used to scroll to a panel that ALSO rendered its own button with
  // the exact same label — visible twice at once, since a sticky bar never
  // scrolls out of view. Each now triggers its owning panel's mutation
  // directly via a forwardRef (pricingRequestPanelRef / dealQuotationPanelRef),
  // and that panel no longer renders a trigger of its own — the sticky bar
  // is the only copy of each label on the page.
  const workState = resolveWorkState(user, summary, pricingRequests);
  const workStateAction = workState.action;
  let stickyPrimaryLabel = nextAction;
  let stickyPrimaryAction = primaryAction;
  if (!stickyPrimaryAction && workStateAction) {
    const actionKey = workStateAction.key ?? workStateAction.code;
    const jumpId = IN_PAGE_JUMP_TARGET[actionKey];
    const to = workStateAction.to;
    // data-testid="ticket-primary-action" + data-action={actionKey}: a
    // stable e2e hook for this slot (added after PR #343's CI break —
    // "ออกใบเสนอราคา"/"ยืนยันคำสั่งซื้อ" moved here from DealQuotationPanel's
    // own buttons, which took their old `deal-quotation-issue`/
    // `deal-quotation-confirm-order` testids with them when they were
    // deleted, leaving nothing for e2e/pcr-chain.spec.js and
    // e2e/deposit-fulfilment-close.spec.js to click). Deliberately generic
    // and reused across every branch below rather than a per-action id:
    // this bar is the ONE sticky primary slot on the page and its rendered
    // action changes with the deal's stage/role, so a spec should assert
    // "the primary slot is now offering X" (via data-action) rather than
    // hard-code a label- or action-specific testid that would need
    // updating every time the resolver's cascade changes. The real
    // `can.*`-gated buttons above (confirmCustomer/confirmFinalPayment/
    // confirmClose/verifyClose) keep their own long-lived
    // `ticket-detail-confirm-*`/`ticket-detail-verify-close` testids
    // unchanged — those are already stable, already covered by e2e specs,
    // and are a different kind of primary (server-gated, not
    // resolver-derived), so they are not folded into this generic id.
    if (to && to !== `/tickets/${ticketId}`) {
      stickyPrimaryAction = (
        <button type="button" className="primary-button" data-testid="ticket-primary-action" data-action={actionKey} onClick={() => navigate(to)}>
          {workStateAction.label}
          <Icon name="chevronRight" size={14} />
        </button>
      );
      stickyPrimaryLabel = workStateAction.label;
    } else if (actionKey === 'create_pcr') {
      // Ticket-detail IA rebuild Phase 2: PricingRequestPanel now lives
      // inside the "ราคา" tab, mounted only while that tab is active —
      // `runOnTab` switches to it first, then calls the ref (see this
      // component's own doc comment on `runOnTab`).
      stickyPrimaryAction = (
        <button type="button" className="primary-button" data-testid="ticket-primary-action" data-action={actionKey} onClick={() => runOnTab('pricing', () => pricingRequestPanelRef.current?.openCreate())}>
          <Icon name="plus" size={14} />
          {workStateAction.label}
        </button>
      );
      stickyPrimaryLabel = workStateAction.label;
    } else if (actionKey === 'issue_quotation') {
      // DealQuotationPanel now lives inside the "ใบเสนอราคา" tab.
      stickyPrimaryAction = (
        <button type="button" className="primary-button" data-testid="ticket-primary-action" data-action={actionKey} onClick={() => runOnTab('quotations', () => dealQuotationPanelRef.current?.openIssueQuotation())}>
          {workStateAction.label}
        </button>
      );
      stickyPrimaryLabel = workStateAction.label;
    } else if (actionKey === 'confirm_order') {
      // DealQuotationPanel now lives inside the "ใบเสนอราคา" tab.
      stickyPrimaryAction = (
        <button type="button" className="primary-button" data-testid="ticket-primary-action" data-action={actionKey} onClick={() => runOnTab('quotations', () => dealQuotationPanelRef.current?.openConfirmOrder())}>
          {workStateAction.label}
        </button>
      );
      stickyPrimaryLabel = workStateAction.label;
    } else if (jumpId) {
      // Every IN_PAGE_JUMP_TARGET id now lives inside a tab — JUMP_TARGET_TAB
      // names which one, so runOnTab can switch there before scrolling.
      stickyPrimaryAction = (
        <button type="button" className="primary-button" data-testid="ticket-primary-action" data-action={actionKey} onClick={() => runOnTab(JUMP_TARGET_TAB[jumpId] ?? activeTab, () => scrollToSection(jumpId))}>
          {workStateAction.label}
          <Icon name="chevronRight" size={14} />
        </button>
      );
      stickyPrimaryLabel = workStateAction.label;
    }
  }

  // The ONE work-state banner line (IA region 4/6/7 — replaces the old
  // duplicated "ถึงคิวคุณ" text that used to appear once in DealStateHeader
  // and again, unprefixed, in DealStagePanel's own guidance line — Phase-1
  // audit findings #1/#2).
  const bannerText = stickyPrimaryLabel
    ? `ถึงคิวคุณ: ${stickyPrimaryLabel}`
    : workState.waitingRoleLabel
      ? `รอ${workState.waitingRoleLabel}${blocker ? ` — ${blocker}` : ''}`
      : blocker;

  // Overflow-menu / danger-zone availability — mirrors DealStagePanel's own
  // canEditStage/canLost/canHold/canDormant gates byte-for-byte (same
  // hasAction/canSetStage/canMarkLost/allowedTargetStages calls on the same
  // data) so the header menu never offers something that panel itself
  // wouldn't also allow. DealStagePanel stays the single actual authority —
  // see its own doc comment on why the ref-exposed open functions re-check
  // these same conditions instead of trusting the caller.
  const lost = summary.lifecycle === 'CLOSED_LOST';
  // DealStagePanel only ever rendered its แก้ไขสถานะ…/เสียงาน/พักดีลไว้/พัก
  // dormant row inside its final "lifecycle is plain ACTIVE" JSX branch
  // (neither the ON_HOLD/DORMANT recovery banner nor the lost banner) — that
  // branch position, not just the four `can*` booleans, was what kept e.g.
  // "พัก dormant" from also appearing on an ON_HOLD deal (which legitimately
  // has MARK_DORMANT in availableActions, since ON_HOLD -> DORMANT is a real
  // transition — DealStagePanel's OWN ON_HOLD banner already offers that
  // exact button). Mirrored explicitly here so the header overflow menu
  // can't duplicate it.
  const isActiveLifecycle = (summary.lifecycle ?? 'ACTIVE') === 'ACTIVE';
  const canEditStage = isActiveLifecycle && hasAction('UPDATE_STAGE') && allowedTargetStages(user, summary).length > 0 && !lost;
  const canLostDeal = isActiveLifecycle && hasAction('MARK_LOST') && canMarkLost(user, summary) && !lost && summary.salesStage !== 'CLOSED_PAID';
  const canHoldDeal = isActiveLifecycle && hasAction('PLACE_ON_HOLD');
  const canDormantDeal = isActiveLifecycle && hasAction('MARK_DORMANT');
  // Mirrors DealStagePanel's own `next`/`canAdvance` byte-for-byte, PLUS the
  // explicit `isActiveLifecycle` guard DealStagePanel got "for free" from
  // which JSX branch rendered its old inline "เลื่อนไป" button (only the
  // plain-ACTIVE branch — never ON_HOLD/DORMANT/lost, which each render their
  // own dedicated recovery/reopen actions instead) — same class of implicit
  // gate this file already had to make explicit for canEditStage/
  // canHoldDeal/canDormantDeal above (ON_HOLD lifecycle regression, see that
  // describe block in TicketDetailPage.test.jsx).
  //
  // Deliberately NOT this file's own `hasAction(action)` above (single-arg,
  // "is this action name present at all") — DealStagePanel's own hasAction
  // takes a second `targetStage` argument and requires an exact match, so an
  // ADVANCE_STAGE entry for some OTHER target stage would false-positive
  // through the single-arg version. Checked directly against
  // `availableActions` here so this stays byte-identical to DealStagePanel's
  // gate, not just same-named.
  const next = isActiveLifecycle && !lost ? nextStage(summary.salesStage) : null;
  const hasAdvanceStageAction = Boolean(next) && availableActions.some(
    (item) => item.action === 'ADVANCE_STAGE' && item.targetStage === next.code,
  );
  const canAdvance = Boolean(next) && !next.auto && hasAdvanceStageAction && canSetStage(user, summary, next.code);

  // Stage-advance readiness (dealTrackingMeta.js's own gate, shared with
  // DealTrackingPanel's badge): lifted up here (Phase-1 audit finding #3,
  // "y=870") so it can sit right next to the "เลื่อนไป" action wherever that
  // action itself renders — first DealStagePanel's own inline button, now
  // (FIX 2, Phase-1 clutter follow-up) the header overflow menu item below.
  // Only ever consulted when canAdvance is also true, and canAdvance is only
  // ever true for a sales-gated next stage (every import/account-gated stage
  // in SALES_STAGES is `auto: true`), so `activities` being empty for
  // import/account viewers (canViewDealTracking below) never matters here.
  //
  // `activitiesQuery.isLoading` guards the initial fetch: `activities`
  // defaults to `[]` before that query resolves, which would otherwise read
  // as "not ready" for a beat on every load — a false negative that briefly
  // disables เลื่อนไป (and shows the gate hint) for a deal that may actually
  // already be ready, before flipping back. Treated as ready while loading
  // (matches the button's pre-Phase-1 behaviour, which never waited on this
  // query at all) rather than as not-ready.
  const readyToAdvance = activitiesQuery.isLoading
    || isReadyToAdvance(summary, hasActivitySince(activities, lastStageChangeAt(events, summary.createdAt)));

  // Named handlers (not inline closures in the array below) so each ref read
  // happens inside an ordinary event-handler function, not lexically inside
  // an object/array literal being built during render.
  function handleOpenEditStage() { dealStagePanelRef.current?.openEditStage(); }
  function handleOpenHold() { dealStagePanelRef.current?.openHold(); }
  function handleOpenDormant() { dealStagePanelRef.current?.openDormant(); }
  function handleOpenAdvance() { dealStagePanelRef.current?.openAdvance(); }
  // The revise-form section now lives inside the "ภาพรวม" tab (alongside the
  // items it revises) — this trigger fires from the header's "⋯" overflow
  // menu, which sits outside every tab, so it must switch there first (see
  // runOnTab's own doc comment) or the section it's trying to reveal may not
  // be mounted yet.
  function handleOpenRevise() { runOnTab('overview', () => { setShowReviseForm(true); clearFieldError('revise.reason'); }); }

  // react-hooks/refs (react-hooks v7's Compiler-oriented ruleset — see this
  // file's neighbouring `set-state-in-effect: 'off'` in eslint.config.js for
  // the same class of false positive) flags every named handler below as
  // "passing a ref to a function" because they end up as values inside this
  // array, which is itself passed to OverflowMenu as a prop. Each handler
  // only ever reads dealStagePanelRef.current from inside a real click
  // handler (OverflowMenu's own onClick, see OverflowMenu.jsx) — never
  // during render — so this is the same class of false positive, not a rule
  // violation.
  // eslint-disable-next-line react-hooks/refs
  const overflowItems = [
    // FIX 2 (Phase-1 clutter follow-up): "เลื่อนไป" moved out of DealStagePanel
    // into the overflow — the sticky header's own primary CTA is now the ONE
    // resolver-derived primary, so a second, filled-indigo "primary" button
    // sitting in the pipeline panel no longer competes with it for attention.
    // Its gate travels with it: shown only when canAdvance (the real
    // ADVANCE_STAGE/canSetStage gate — unchanged), and disabled-with-reason
    // (never silently hidden) when the readiness precondition isn't met yet.
    // FIX 3 (P2, clutter-follow-up review round 2): the old inline buttons
    // these overflow items replaced were each `disabled={actionLoading}` —
    // a mutation already in flight blocked a second click on the SAME
    // action. `disabled` here (rendered via OverflowMenu's aria-disabled,
    // see its own doc comment) has to carry that same condition now that
    // the click no longer runs through a native `disabled` button attribute
    // — without it, reopening the menu mid-mutation and clicking again fired
    // a second request (the second `updateStage` landing as a 409, "Deal is
    // already in stage X" — TicketService.java:1143). The re-check inside
    // each opener (DealStagePanel's openAdvance/openEditStage/openHold/
    // openDormant) is the actual guard; this is the visible half of the
    // same fix, so the item doesn't invite a click it's about to reject.
    canAdvance && {
      key: 'advanceStage',
      label: `เลื่อนไป: ${next ? dealStageLabel(next.code).label : ''}`,
      icon: 'chevronRight',
      disabled: !readyToAdvance || actionLoading,
      disabledReason: !readyToAdvance ? STAGE_ADVANCE_GATE_HINT : undefined,
      onSelect: handleOpenAdvance,
      testId: 'deal-stage-advance',
    },
    canEditStage && {
      key: 'editStage', label: 'แก้ไขสถานะ…', icon: 'pencil', disabled: actionLoading, onSelect: handleOpenEditStage,
    },
    canHoldDeal && { key: 'hold', label: 'พักดีลไว้', disabled: actionLoading, onSelect: handleOpenHold },
    canDormantDeal && { key: 'dormant', label: 'พัก dormant', disabled: actionLoading, onSelect: handleOpenDormant },
    can.revise && { key: 'revise', label: 'ขอแก้ไข', icon: 'pencil', onSelect: handleOpenRevise },
  ].filter(Boolean);

  async function handleUploadAttachment(e) {
    const file = e.target.files?.[0];
    if (!file) return;
    // INVOICE is never produced here — not inferred from the filename, and no
    // longer settable via an explicit type either. It gates the close and must
    // come from CommissionService.createFromDeal, which writes it alongside the
    // commission (see the "ไฟล์แนบ" panel comment below).
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

  async function handleContextComment() {
    if (!contextCommentText.trim()) return;
    await doAction(() => api.tickets.comment(ticketId, { message: contextCommentText.trim() }), 'เพิ่มความคิดเห็นแล้ว');
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

  // openDeliveryModal/closeDeliveryModal/openStockModal moved into
  // DealFulfilmentPanel (Phase 3 Slice S4).

  return (
    <div className={`page-stack ${bannerText || stickyPrimaryAction || overflowItems.length > 0 ? 'mobile:pb-28' : ''}`}>
      {/* F-14 (ticket-detail IA rebuild Phase 1): the breadcrumb is the single
          up-nav — a full-width "กลับ" bar underneath it just repeated the same
          affordance as page chrome. Verified safe to drop: every e2e "กลับ"
          locator is scoped to the create-deal modal, not this page bar (see
          frontend/e2e/) — onBack itself is unchanged, still reachable via the
          breadcrumb's "ดีล" crumb. */}
      <Breadcrumbs items={[{ label: 'ดีล', onClick: onBack }, { label: summary.code || summary.customerName || summary.title }]} />

      {/* Deal Workspace state header (Phase 2 Slice S2, folded into the
          ticket-detail IA rebuild Phase 1 — see DealStateHeader.jsx's own doc
          comment): deal code/title/customer + lifecycle × stage × PCR ×
          payment × fulfilment at a glance, the ONE work-state banner line,
          the sticky primary CTA, and the "⋯" overflow. Subsumes the old bare
          header (title/code/status/refresh). */}
      <div
        ref={ticketStickyChromeRef}
        data-testid="ticket-detail-sticky-chrome"
        className="sticky top-[calc(var(--deal-scroll-pad-y)*-1)] z-10 bg-surface pt-[var(--deal-scroll-pad-y)] mobile:static mobile:bg-transparent mobile:pt-0"
      >
        <div className="overflow-hidden rounded-lg border border-border bg-surface shadow-sm mobile:overflow-visible mobile:border-0 mobile:bg-transparent mobile:shadow-none">
          <DealStateHeader
            summary={summary}
            pricingRequests={pricingRequests}
            primaryAction={stickyPrimaryAction}
            bannerText={bannerText}
            overflowItems={overflowItems}
            onRefresh={refreshTicket}
            condensed={ticketChromeCondensed}
          />
          <div className="border-t border-border bg-surface px-4 sm:px-5">
            <Tabs
              items={visibleTabItems}
              value={visibleActiveTab}
              onChange={setActiveTab}
              ariaLabel="รายละเอียดดีล"
              idPrefix="ticket-detail"
            />
          </div>
        </div>
      </div>

      <div className="grid min-w-0 gap-4 xl:grid-cols-[minmax(0,1fr)_20rem] xl:items-start">

      {/* Deal pipeline (V50): the 14-stage journey with stage-gated doc actions.
          Generation buttons reuse the exact handlers/permissions of the action
          row; once a document exists (quotation / ใบแจ้งยอดมัดจำ) it stays
          reachable from here through the later stages too. */}
      <div className="min-w-0 xl:col-start-1">
      <DealStagePanel
        ref={dealStagePanelRef}
        user={user}
        summary={summary}
        availableActions={availableActions}
        pricingRequests={pricingRequests}
        // primaryAction now lives solely in DealStateHeader above (Phase 2 Slice S2's
        // "one primary CTA" — see its own doc comment) — not passed here too, to avoid
        // rendering the exact same button twice on one page.
        advanceReady={readyToAdvance}
        actionLoading={actionLoading}
        deliveryProgress={{ delivered: totalDelivered, ordered: totalOrdered }}
        onUpdateStage={(payload) => doAction(() => api.tickets.updateStage(ticketId, payload), 'อัปเดตสถานะดีลแล้ว')}
        onMarkLost={(payload) => doAction(() => api.tickets.markLost(ticketId, payload), 'บันทึกเสียงานแล้ว')}
        onReopen={() => doAction(() => api.tickets.reopen(ticketId, {}), 'เปิดดีลอีกครั้งแล้ว')}
        onHold={(payload) => doAction(() => api.tickets.hold(ticketId, payload), 'พักดีลไว้แล้ว')}
        onDormant={(payload) => doAction(() => api.tickets.dormant(ticketId, payload), 'พัก dormant แล้ว')}
        onResume={(payload) => doAction(() => api.tickets.resume(ticketId, payload), 'ดำเนินการต่อแล้ว')}
        onSetTenderRequirement={(payload) => doAction(() => api.tickets.setTenderRequirement(ticketId, payload), 'บันทึกสถานะประมูลแล้ว')}
        docActions={(can.downloadRemainingInvoice || (sections.quotation && latestQuotation)) ? (
          <>
            {/* Import/account (role-scoped views, Phase A): the view-only
                quotation download link isn't role-gated by `can.*` —
                sections.quotation hides it for the role that has no business
                in that document. The deposit-notice create/issue/view
                affordance moved to DealDepositPanel (Phase 3 Slice S3); the
                Import Request button moved to DealFulfilmentPanel (Phase 3
                Slice S4). */}
            {sections.quotation && latestQuotation && (
              <button type="button" className="secondary-button"
                disabled={downloadingQuotationKey === `${latestQuotation.id}-pdf`}
                onClick={() => handleDownloadQuotation(latestQuotation.id, latestQuotation.number, 'pdf')}>
                <Icon name="fileText" size={14} />
                {downloadingQuotationKey === `${latestQuotation.id}-pdf`
                  ? 'กำลังดาวน์โหลด…'
                  : `ใบเสนอราคา ${latestQuotation.number} (PDF)`}
              </button>
            )}
            {can.downloadRemainingInvoice && (
              <button type="button" className="secondary-button" disabled={downloadingInvoice}
                onClick={handleDownloadRemainingInvoice}>
                {downloadingInvoice ? 'กำลังดาวน์โหลด…' : 'ดาวน์โหลดใบแจ้งหนี้ส่วนที่เหลือ'}
              </button>
            )}
          </>
        ) : null}
      />
      </div>

      {/* Ticket-detail IA rebuild Phase 2 (see
          docs/ui-repair/02-information-architecture/TICKET_INFORMATION_ARCHITECTURE.md
          "Tabs (the deal's depth)"): the seven role-projected tabs —
          ticketDetailTabs.js decides which ones this viewer's role may see
          at all (never a security boundary, same as salesViewScope.js it
          reads from), and Tabs.jsx keeps only the active one mounted.
          FIX 2 (Opus review): `tabItems`'s role-level filter can't express
          เอกสาร's per-instance identity gate (it needs THIS ticket's
          createdById/assignedToId, not just the viewer's role — see
          `canViewDocumentsTab`'s own doc comment above), so it's filtered
          out here a second time; `visibleActiveTab` mirrors the same
          fallback `resolveTicketDetailTab` already uses for a role-hidden
          tab, for the one tab that predicate can't see: a stale/forbidden
          `?tab=documents` deep link (account, or an import rep who isn't
          this ticket's assignee) falls back to ภาพรวม instead of
          highlighting a tab with no button and rendering nothing. */}
      <div className="min-w-0 xl:col-start-1">
      <TabPanel id="overview" idPrefix="ticket-detail" active={visibleActiveTab === 'overview'}>
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
                            <div style={{ padding: '7px 10px', border: '1px solid var(--color-border-subtle)', borderRadius: 6, background: 'var(--color-surface-muted)', fontSize: 13, color: item.qtySqm ? 'var(--color-icon-muted)' : 'var(--color-text-muted)' }}>
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
                            <div style={{ padding: '7px 10px', border: '1px solid var(--color-border-subtle)', borderRadius: 6, background: 'var(--color-surface-muted)', fontSize: 13, color: item.qty ? 'var(--color-icon-muted)' : 'var(--color-text-muted)' }}>
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
                          {item.calcConfigVersion && <small style={{ display: 'block', color: 'var(--color-text-muted)', fontSize: 10 }}>สูตรราคาเวอร์ชัน {item.calcConfigVersion}</small>}
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
              </div>
            </section>
          )}

          {/* ขอแก้ไข (Revise) now triggers from the header overflow menu (see
              overflowItems above) rather than a button in the panel above — but
              the form itself still needs somewhere to render once opened, and
              hasActions no longer accounts for can.revise, so this stays its own
              independent block instead of nesting inside that section. */}
          {can.revise && showReviseForm && (
            <section id="revise-form" tabIndex={-1} className="panel scroll-mt-[300px] max-[720px]:scroll-mt-[420px] outline-none" style={{ background: 'var(--color-surface-muted)' }}>
              <div className="panel-header">
                <h2>ขอแก้ไข</h2>
              </div>
              <div style={{ padding: '14px 18px', display: 'flex', flexDirection: 'column', gap: 10 }}>
                <div style={{ fontSize: 13, fontWeight: 600, paddingTop: 12 }}>ประเภทการแก้ไข</div>
                  {[
                    { value: 'QTY_OR_NOTE',  label: 'แก้จำนวน / หมายเหตุ / % มัดจำ', sub: 'ไม่ต้องอนุมัติใหม่ — ออกเอกสารรอบแก้ไขใหม่ได้เลย' },
                    { value: 'PRICE_CHANGE', label: 'แก้ราคา / ส่วนลดต่อหน่วย',       sub: 'CEO ต้องอนุมัติใหม่' },
                    { value: 'NEW_ITEM',     label: 'เพิ่มสินค้าใหม่',                sub: 'ฝ่ายนำเข้าตั้งราคา → CEO อนุมัติ' },
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
                      placeholder="ระบุเหตุผล…" style={{ marginTop: 4 }}
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
            </section>
          )}
      </TabPanel>

      <TabPanel id="pricing" idPrefix="ticket-detail" active={visibleActiveTab === 'pricing'}>
          {/* id: the sticky bar's CREATE_PCR jump target (see
              IN_PAGE_JUMP_TARGET above). */}
          <div id="pricing-request-panel" tabIndex={-1} className="scroll-mt-[300px] max-[720px]:scroll-mt-[420px] outline-none">
            {canViewPricingRequests ? (
              <PricingRequestPanel ref={pricingRequestPanelRef} ticketId={ticketId} deal={summary} ticketItems={items} user={user} />
            ) : null}
          </div>
      </TabPanel>

      <TabPanel id="quotations" idPrefix="ticket-detail" active={visibleActiveTab === 'quotations'}>
          {/* "ราคาและใบเสนอราคา" (Phase 2 Slice S2): the customer-facing tail of
              the PricingRequest chain (issue/outcome + confirm-order), pulled
              onto the deal page. Renders nothing until a request reaches
              APPROVED_FOR_QUOTATION — see DealQuotationPanel's own doc
              comment. The factory/costing/CEO-price steps that precede that
              stay on PricingRequestDetailPage, linked from inside the panel.
              The sticky bar's ISSUE_QUOTATION/CONFIRM_ORDER primary CTAs no
              longer scroll here (FIX 2, clutter follow-up round 2) — they
              call dealQuotationPanelRef directly — but the id/scroll-mt
              wrapper stays for any other in-page anchor that might still
              want it. */}
          {sections.dealQuotation && canViewPricingRequests ? (
            <div id="deal-quotation-panel" tabIndex={-1} className="scroll-mt-[300px] max-[720px]:scroll-mt-[420px] outline-none">
              <DealQuotationPanel ref={dealQuotationPanelRef} ticketId={ticketId} pricingRequests={pricingRequests} user={user} showToast={showToast} />
            </div>
          ) : null}
          {/* Legacy ticket-native quotation rows (region 12's "docs") join the
              PCR-based quotation tail above, in this same tab — see
              TICKET_INFORMATION_ARCHITECTURE.md's ใบเสนอราคา row. */}
          {sections.quotation && quotationGroups.length > 0 ? (
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
                          }}>ครั้งที่ {q.quotationVersion}</span>
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
                              {[q.paymentTerms && `ชำระเงิน: ${q.paymentTerms}`, q.leadTime && `ระยะเวลาส่งมอบ: ${q.leadTime}`, q.deliveryTerms && `ส่งมอบ: ${q.deliveryTerms}`].filter(Boolean).join(' · ')}
                            </div>
                          )}
                        </div>
                        <div style={{ display: 'flex', gap: 6, flexShrink: 0, flexWrap: 'wrap', justifyContent: 'flex-end' }}>
                          <button type="button" className="secondary-button" style={{ fontSize: 12, padding: '4px 10px' }}
                            disabled={downloadingQuotationKey === `${q.id}-xlsx`}
                            onClick={() => handleDownloadQuotation(q.id, q.number, 'xlsx')}>
                            <Icon name="fileText" size={12} /> {downloadingQuotationKey === `${q.id}-xlsx` ? 'กำลังดาวน์โหลด…' : 'Excel'}
                          </button>
                          <button type="button" className="secondary-button" style={{ fontSize: 12, padding: '4px 10px' }}
                            disabled={downloadingQuotationKey === `${q.id}-pdf`}
                            onClick={() => handleDownloadQuotation(q.id, q.number, 'pdf')}>
                            <Icon name="fileText" size={12} /> {downloadingQuotationKey === `${q.id}-pdf` ? 'กำลังดาวน์โหลด…' : 'PDF'}
                          </button>
                        </div>
                      </div>
                    );
                  })}
                </div>
              ))}
            </section>
          ) : null}
      </TabPanel>

      <TabPanel id="money" idPrefix="ticket-detail" active={visibleActiveTab === 'money'}>
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
          ) : null}

          {/* "มัดจำ" (Phase 3 Slice S3 — see
              docs/agent-handoffs/105_feat-deal-deposit-fulfilment-unify.md):
              the one deposit section — policy (account/CEO) → notice (sales)
              → payment confirmation (account) — replacing the deposit-policy
              control that used to live in DealStagePanel and the deposit
              doc/payment bits that used to live directly on this page.
              id: the sticky bar's account-role jump target (chaseOverdue/
              confirmDeposit/confirmFinalPayment/confirmCloseReady). */}
          <div id="deal-deposit-panel" tabIndex={-1} className="scroll-mt-[300px] max-[720px]:scroll-mt-[420px] outline-none">
            {sections.depositNotice ? (
              <DealDepositPanel
                ticketId={ticketId}
                user={user}
                summary={summary}
                availableActions={availableActions}
                pricingRequests={pricingRequests}
                showToast={showToast}
              />
            ) : null}
          </div>
      </TabPanel>

      <TabPanel id="fulfilment" idPrefix="ticket-detail" active={visibleActiveTab === 'fulfilment'}>
          {/* "การส่งมอบ / นำเข้า" (Phase 3 Slice S4 — see
              docs/agent-handoffs/105_feat-deal-deposit-fulfilment-unify.md):
              the deal-level IR → shipping → goods-received → delivery chain
              (import/CEO act, sales/account/ceo see progress), plus an
              optional per-factory PO detail — replacing the "การส่งมอบสินค้า"
              panel and the IR button/delivery/stock modals that used to live
              directly on this page. Reuses the existing `delivery` section
              id (salesViewScope.js) rather than adding a parallel one.
              id (DOM): the sticky bar's import-role fulfilment jump target. */}
          <div id="deal-fulfilment-panel" tabIndex={-1} className="scroll-mt-[300px] max-[720px]:scroll-mt-[420px] outline-none">
            {sections.delivery ? (
              <DealFulfilmentPanel
                ticketId={ticketId}
                user={user}
                summary={summary}
                items={items}
                availableActions={availableActions}
                pricingRequests={pricingRequests}
                showToast={showToast}
              />
            ) : null}
          </div>
      </TabPanel>

      <TabPanel id="documents" idPrefix="ticket-detail" active={visibleActiveTab === 'documents'}>
          {/* R5: Attachments */}
          <section className="panel">
            <div className="panel-header" style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
              <h2>ไฟล์แนบ (PO / ใบเซ็น)</h2>
              {/* No แนบใบกำกับภาษี control here, deliberately (2026-07-30 owner
                  decision). The closing tax invoice is ฝ่ายบัญชี's to record, and
                  the ONLY supported path is CommissionService.createFromDeal
                  (POST /api/commissions/from-deal, CREATE_FROM_DEAL_ROLES =
                  account-only), reached from this page's own sticky CTA
                  "บันทึกใบกำกับ + ออกค่าคอม" -> /commissions?ticketId=NN
                  (accountActions.js). That one upload dual-writes the file as an
                  AttachType.INVOICE ticket attachment, so it satisfies the close
                  gate's invoiceOnFile check AND creates the deal owner's
                  commission in the same transaction.

                  A second invoice path here would satisfy the close gate WITHOUT
                  creating the commission — the sales rep would silently lose it.
                  That is why this is not simply re-gated to a role the backend
                  permits: the control that used to live here was gated isAccount
                  and 403'd for real, and only ever looked functional because
                  mockApi.js had no authz on attachments at all.

                  Issue #389 fixed the OTHER half of that gate — account can now
                  READ every deal document (it is asked to confirm money against
                  them) and hr can no longer read any — but the WRITE side was
                  left deliberately narrow for exactly the reason above:
                  TicketAccessPolicy.canManageDocuments is participant OR
                  sales_manager/ceo, never account. Pinned by
                  AttachmentTicketAccessIntegrationTest
                  .accountCannotUploadADocument_theTaxInvoiceKeepsExactlyOneEntryPoint.
                  Do not reintroduce it — the frontend regression guard is
                  TicketDetailPage.test.jsx, "offers NO ใบกำกับภาษี upload
                  control in เอกสาร". */}
              {!TERMINAL.includes(st) && canManageDocuments && (
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
                    {uploadingFile ? 'กำลังอัปโหลด…' : 'แนบไฟล์ (PDF/JPG/PNG/Excel)'}
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
                    {!TERMINAL.includes(st) && (canManageDocuments || att.uploadedBy === user.id) && (
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
      </TabPanel>

      <TabPanel id="activity" idPrefix="ticket-detail" active={visibleActiveTab === 'activity'}>
          {/* FIX 1 (Opus review, owner decision): this tab is now visible to
              every viewer of the deal (ticketDetailTabs.js's own doc comment
              on the "activity" entry), but DealTrackingPanel's win%/designer/
              owner/buyer/next-follow-up fields are a DIFFERENT capability
              from the two the review actually found lost (the audit trail
              and the comment box, both handled below) — the review named
              only those two, so this panel keeps its pre-existing
              dealTracking gate (sales-owner/sales_manager/ceo) rather than
              newly exposing a capability nobody asked to restore. */}
          {canViewDealTracking ? (
            // id: the sticky bar's FOLLOW_UP/LOG_ACTIVITY jump target (see
            // IN_PAGE_JUMP_TARGET above) — scroll-mt so it doesn't tuck under
            // the sticky header, tabIndex so the jump can actually move focus
            // here.
            <div id="deal-tracking-panel" tabIndex={-1} className="scroll-mt-[300px] max-[720px]:scroll-mt-[420px] outline-none">
              <DealTrackingPanel
                summary={summary}
                events={events}
                activities={activities}
                canEdit={isOwner || role === 'sales_manager' || role === 'ceo'}
                onUpdateTracking={(payload) => updateTrackingMutation.mutateAsync(payload)}
                updating={updateTrackingMutation.isPending}
              />
            </div>
          ) : null}

          {/* "ประวัติดีล" (IA rebuild): merges the ticket's audit trail
              (ticket.events, formerly its own "ประวัติการดำเนินการ" panel) with
              the deal-tracking activity log (formerly DealTrackingPanel's own
              "ประวัติการติดตาม (Activity log)" list, trimmed above) into one
              chronological stream — regions 17+18 of
              TICKET_INFORMATION_ARCHITECTURE.md.
              FIX 1 (Opus review, owner decision): the audit trail (events)
              and the comment box (`can.comment`) are backed by
              `TicketService.projectForRole`/`comment`'s `requireViewAccess`,
              which every viewer of the deal passes — so, unlike before, they
              are NOT re-gated on `canViewDealTracking` here; only the
              follow-up feed (`activities`) and its add-activity form stay
              gated on `requireDealOwnership`, per
              TicketIaAuthzMatrixIntegrationTest's activities_* cases. */}
          <DealHistoryPanel
            events={events}
            activities={activities}
            loading={activitiesQuery.isLoading}
            canViewActivityFeed={canViewDealTracking}
            canComment={can.comment}
            commentText={commentText}
            onCommentTextChange={setCommentText}
            onSubmitComment={handleComment}
            commentSubmitting={actionLoading}
            canAddActivity={canViewDealTracking && (isOwner || role === 'sales_manager' || role === 'ceo')}
            onAddActivity={(payload) => addActivityMutation.mutateAsync(payload)}
            addingActivity={addActivityMutation.isPending}
          />
      </TabPanel>
      </div>

      <div
        data-testid="ticket-context-rail"
        className="min-w-0 xl:sticky xl:top-[calc(var(--app-topbar-h)+var(--deal-header-h,18rem)+var(--space-4))] xl:col-start-2 xl:row-start-1 xl:row-span-4 xl:max-h-[calc(100vh-var(--app-topbar-h)-var(--deal-header-h,18rem)-var(--space-8)-var(--space-4))] xl:overflow-y-auto"
      >
        <TicketContextPanel
          summary={summary}
          pricingRequests={pricingRequests}
          latestQuotation={latestQuotation}
          events={events}
          bannerText={bannerText}
          canComment={can.comment}
          commentText={contextCommentText}
          onCommentTextChange={setContextCommentText}
          onSubmitComment={handleContextComment}
          commentSubmitting={actionLoading}
          showCommentForm={visibleActiveTab !== 'activity'}
          canViewPricingRequests={canViewPricingRequests}
        />
      </div>

      {/* "จัดการดีล" danger zone (ticket-detail IA rebuild Phase 1): เสียงาน /
          ยกเลิก moved off the working surface into a clearly-labelled,
          danger-toned section at the very bottom of the page — conventional
          danger-zone pattern, no new route. Both actions stay exactly as
          reachable as before (same canLostDeal/can.cancel gates, same
          modals), only demoted from sitting inline among the day-to-day
          pipeline controls. */}
      {(canLostDeal || can.cancel) ? (
        <section className="rounded-xl border border-danger-border bg-danger-bg p-4 sm:p-5 xl:col-start-1" aria-labelledby="deal-danger-zone-heading">
          <h2 id="deal-danger-zone-heading" className="m-0 text-sm font-extrabold text-danger-dark">จัดการดีล</h2>
          <p className="mt-1 text-xs text-danger-dark">การดำเนินการเหล่านี้ส่งผลต่อทั้งดีล และบางรายการย้อนกลับไม่ได้ — ใช้เมื่อจำเป็นเท่านั้น</p>
          <div className="mt-3 flex flex-wrap gap-2">
            {canLostDeal ? (
              <button
                type="button"
                className="secondary-button"
                style={{ color: 'var(--color-danger)', borderColor: 'var(--color-danger-border)' }}
                disabled={actionLoading}
                onClick={() => dealStagePanelRef.current?.openMarkLost()}
              >
                เสียงาน
              </button>
            ) : null}
            {can.cancel ? (
              <button
                type="button"
                className="secondary-button"
                style={{ color: 'var(--color-danger)', borderColor: 'var(--color-danger-border)' }}
                disabled={actionLoading}
                onClick={() => setConfirm({ kind: 'cancelTicket' })}
              >
                ยกเลิก
              </button>
            ) : null}
          </div>
        </section>
      ) : null}
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

      {/* Delivery/stock-reservation modals moved into DealFulfilmentPanel
          (Phase 3 Slice S4). */}

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
