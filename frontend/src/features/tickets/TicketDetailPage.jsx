import { useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { api, ROLE_PERMISSIONS } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { Breadcrumbs } from '../../components/common/Breadcrumbs.jsx';
import { Button } from '../../components/common/Button.jsx';
import { ConfirmDialog } from '../../components/common/ConfirmDialog.jsx';
import { EmptyState } from '../../components/common/EmptyState.jsx';
import { fieldErrorId } from '../../components/common/FormField.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { Panel } from '../../components/common/Layout.jsx';
import { Modal } from '../../components/common/Modal.jsx';
import { Skeleton, SkeletonText } from '../../components/common/Skeleton.jsx';
import { Tabs, TabPanel } from '../../components/common/Tabs.jsx';
import { cn } from '../../utils/cn.js';
import {
  dealStageLabel,
  formatMoney,
  formatThaiDate,
  quotationRecipientLabel,
} from '../../utils/format.js';
import { downloadBlob } from '../../utils/download.js';
import { convertToThb, formatThb } from './catalogPriceDisplay.js';
import { activePricingRequestsSummary } from '../pricingRequests/pricingRequestMeta.js';
import { PricingRequestPanel } from '../pricingRequests/PricingRequestPanel.jsx';
import { CancelDealModal } from './CancelDealModal.jsx';
import { hasActivitySince, isReadyToAdvance, lastStageChangeAt, STAGE_ADVANCE_GATE_HINT } from './dealTrackingMeta.js';
import { ContextSection, FieldRow } from './DealMetaFields.jsx';
import { DealAttachmentsPanel } from './DealAttachmentsPanel.jsx';
import { DealDepositPanel } from './DealDepositPanel.jsx';
import { DealDocumentRegister } from './DealDocumentRegister.jsx';
import { DealFulfilmentPanel } from './DealFulfilmentPanel.jsx';
import { DealHistoryPanel } from './DealHistoryPanel.jsx';
import { DealLegacyQuotations } from './DealLegacyQuotations.jsx';
import { DealMoneyTimeline } from './DealMoneyTimeline.jsx';
import { DealQuotationPanel } from './DealQuotationPanel.jsx';
import { DealStagePanel } from './DealStagePanel.jsx';
import { DealStateHeader } from './DealStateHeader.jsx';
import { DealTrackingPanel } from './DealTrackingPanel.jsx';
import { visibleSections } from './salesViewScope.js';
import {
  allowedTargetStages, canMarkLost, canSetStage, nextStage,
} from './stageMeta.js';
import {
  resolveTicketDetailTab, TICKET_DETAIL_TABS, visibleTicketDetailTabIds,
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
  // Slice C2b: DealTrackingPanel moved from the old `activity` tab into
  // `deal` (see ticketDetailTabs.js's own comment on that tab) — this jump
  // target moves with it, unchanged element id.
  'deal-tracking-panel': 'deal',
  'deal-fulfilment-panel': 'fulfilment',
  'deal-deposit-panel': 'money',
};

function scrollToSection(id) {
  const el = typeof document !== 'undefined' ? document.getElementById(id) : null;
  if (!el) return;
  // jsdom (Vitest) doesn't implement scrollIntoView — guarded so the same
  // jump helper used by the sticky primary CTA doesn't throw under test,
  // only under a real browser's absence of it.
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

// docStatusColors (quotation revision docStatus -> StatusBadge-matching
// tokens) moved into DealLegacyQuotations.jsx (ia-extract Slice C1) — it had
// exactly one caller and that caller moved with it.

// Slice A "chip diet" moved PaymentSubstepChips out of DealStagePanel (see
// its own doc comment) into the money tab's own payment section. Slice E
// moved it again, along with the rest of that section, into
// DealMoneyTimeline.jsx (see that file's own doc comment for why it stays
// separate from the merged timeline below it) — nothing here references it
// or depositBypassesNotice any more.

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
  // delivery modals, plus (5b) the revise form (a modal since Slice C2a) and
  // the edit-items quantities. One shared dict, keyed per-form/per-row so a stale error can
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

  // FX rates power the "≈ x บาท" companion figure next to a factory price quoted in a foreign
  // currency (the catalog is EUR + USD only). Plain currency conversion — no markup, no
  // multiplier: the ราคาตั้ง (ประมาณการ) estimate this page used to render was removed on the
  // owner's instruction (UAT feedback, 2026-08-10) along with dealEstimatePricing.js, because a
  // marked-up supplier cost shown next to real quoted prices read as a selling price it never was.
  // The fetch degrades silently by design — retry: false, no error toast — because this is
  // optional display enrichment, not critical page data (same non-critical pattern as
  // attachmentsQuery below). When a rate is missing the original currency stands alone; it is
  // never converted at an assumed 1:1.
  const fxRatesQuery = useQuery({
    queryKey: queryKeys.fxRates(),
    queryFn: () => api.fxRates.list().then((res) => res.fxRates ?? []),
    retry: false,
  });
  const fxRatesByCurrency = useMemo(() => {
    const map = { THB: 1 };
    for (const rate of fxRatesQuery.data ?? []) {
      if (rate?.currency) map[rate.currency] = Number(rate.rateToThb);
    }
    return map;
  }, [fxRatesQuery.data]);
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
      // `queryKeys.ticket` never existed — queryKeys.js defines ticketDetail/
      // ticketActions/ticketAttachments/… but no bare `ticket`. Calling it threw
      // `TypeError: queryKeys.ticket is not a function` HERE, as the third
      // statement: the two invalidations above had already run, so the file
      // list and the action list refreshed and the upload looked fine, but the
      // handler aborted before `showToast` below. react-query does not route an
      // onSuccess throw to onError, and handleUploadAttachment's `catch {}`
      // swallowed the rejected mutateAsync — so a successful upload reported
      // NOTHING to the user, and the ticket detail (whose summary drives the
      // invoiceOnFile close gate) was never refetched.
      queryClient.invalidateQueries({ queryKey: queryKeys.ticketDetail(ticketId) });
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
      // Same `queryKeys.ticket` TypeError as the upload handler above — see its
      // comment. Deleting a file silently reported nothing either.
      queryClient.invalidateQueries({ queryKey: queryKeys.ticketDetail(ticketId) });
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
      <div className="grid w-full grid-cols-1 gap-[18px] min-w-0 max-w-[1320px]" aria-busy="true" aria-label="กำลังโหลดข้อมูลดีล">
        <div className="flex items-start justify-between gap-4">
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
        <Panel>
          <Panel.Header>
            <Skeleton width="30%" height={16} />
          </Panel.Header>
          <div className="p-[14px_18px]">
            <SkeletonText lines={4} />
          </div>
        </Panel>
        {/* bordered/px-5 py-4 (vs. the legacy 16px-inline/0-vertical .table-panel >
            .panel-header) is the same small, deliberate shift the CommissionPage
            port (f7c2cace) already accepted for this exact class of header —
            Panel.Header's own doc comment calls the old borderless header a
            defect fixed by construction. */}
        <Panel flush>
          <Panel.Header bordered>
            <Skeleton width="30%" height={16} />
          </Panel.Header>
          <div className="p-[14px_18px]">
            <SkeletonText lines={5} />
          </div>
        </Panel>
      </div>
    );
  }

  if (!ticket) {
    return (
      <div className="grid w-full grid-cols-1 gap-[18px] min-w-0 max-w-[1320px]">
        <EmptyState icon="fileText" title="ไม่พบดีล" description="กลับไปหน้ารายการ" />
        <Button type="button" variant="secondary" onClick={onBack}>
          <Icon name="chevronLeft" />
          กลับ
        </Button>
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
  // The standalone ราคาตั้ง column (Slice F) was removed 2026-08-10 — see the fxRatesQuery comment
  // above. Its three fixed columns (ยี่ห้อ/รุ่น · สี/เนื้อผิว · จำนวน) are now followed directly by
  // whichever price columns the role is allowed to see, so each variant lost exactly one track.
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
  // Slice A "chip diet": DealStagePanel's own read-only "ส่งมอบ x/y" badge
  // (the only consumer of totalOrdered/totalDelivered) was removed as a
  // straight duplicate of DealFulfilmentPanel's own aggregate + progress bar,
  // which already computes the same totals from this same `items` prop — see
  // DealStagePanel.jsx's own doc comment.

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

  // Moved verbatim from the now-deleted TicketContextPanel.jsx (ticket-workspace
  // IA rebuild, Slice B): who the deal's pricing request is currently assigned
  // to, for the ภาพรวม tab's "ผู้เกี่ยวข้อง" section below. `canViewPricingRequests`
  // is the same role-scoped readout the panel used — a viewer without pricing-
  // request visibility gets 'ไม่แสดงในมุมมองนี้' rather than a widened peek at who
  // owns it.
  const pricingSummary = activePricingRequestsSummary(pricingRequests);
  const latestPr = pricingSummary ? pricingSummary.requests[pricingSummary.requests.length - 1] : null;
  const assignedImport = canViewPricingRequests
    ? latestPr
      ? latestPr.assignedImportName || 'ยังไม่มีผู้รับเรื่อง'
      : 'ยังไม่มีคำขอราคา'
    : 'ไม่แสดงในมุมมองนี้';

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

  // Slice C2b: the FIX 2 (Opus review) per-instance เอกสาร (attachments)
  // filter that used to live here — `tabItems`/`activeTab` are role-level
  // only, but the old `documents` (attachments) tab additionally needed
  // `canViewDocumentsTab`'s per-instance identity check — is gone. That
  // content now lives inside `history`, an unconditionally-visible tab, so
  // there is no longer a TAB to filter out or fall back from; the identity
  // check instead gates just the attachments panel as an inner render
  // condition (see the `history` TabPanel below). No tab in
  // ticketDetailTabs.js is per-instance gated any more, so `tabItems`/
  // `activeTab` are used directly, unfiltered.
  const visibleTabItems = tabItems;
  const visibleActiveTab = activeTab;

  // การดำเนินการอื่น ๆ (ticket-detail IA rebuild Phase 1's leftover grab-bag —
  // see workState.js's own doc comment for where its other siblings went)
  // is dissolved as of Slice C2b: `can.editItems` moves into the items
  // table's own panel header (it's an items action) and `can.revokeCloseConfirm`
  // moves into the money tab (it's a close-lifecycle action, unrelated to
  // items) — each rendered directly at its new site below rather than behind
  // one shared `hasActions` flag. The empty-draft hint travels with
  // `can.editItems` into the items panel.

  // Next-action summary for the current viewer only — derived strictly from the
  // `can` flags above (which already encode real status+role permission checks).
  // Verified against backend/mock transition handlers (see api.tickets.* in
  // src/api/mockApi.js) so the wording matches what the button actually does.
  // Never invents an owner or action the data can't support. 'revise' is
  // deliberately NOT in this cascade any more (Phase-1 audit finding #1): it
  // describes an OPTIONAL action, not something blocking the deal's progress,
  // so it no longer competes for the one sticky primary CTA slot — it is
  // still fully reachable, from the header overflow menu.
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
  // Paired with the "รอ<role>" waiting banner below, never alongside a live
  // primary CTA (a live primary action is never "blocked" by the same thing
  // the banner would name — pairing the two would read as a contradiction).
  // Kept to the fact only (design law: brief — who/what, not
  // what-to-do-about-it-by-when).
  const closeConfirmedAt = summary?.closeConfirmedAt ?? null;
  // !isAccount on the two payment-wait lines (P3, review round 2): account
  // is the role that CLEARS these two waits (confirmDeposit/
  // confirmFinalPayment — see nextAccountAction in accountActions.js) — for
  // account specifically, this line would otherwise read "รอชำระมัดจำ" right
  // next to their own resolver-derived "ยืนยันรับมัดจำ" primary CTA button,
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
    <Button type="button" variant="primary" disabled={actionLoading} data-testid="ticket-detail-confirm-customer"
      onClick={() => doAction(() => api.tickets.confirmCustomer(ticketId), 'ลูกค้ายืนยันแล้ว')}>
      ลูกค้ายืนยัน
    </Button>
  ) : can.confirmFinalPayment ? (
    <Button type="button" variant="primary" disabled={actionLoading} data-testid="ticket-detail-confirm-final"
      onClick={() => setConfirm({ kind: 'finalPayment' })}>
      ยืนยันชำระครบ (Final Payment)
    </Button>
  ) : can.confirmClose ? (
    <Button type="button" variant="primary" disabled={actionLoading} data-testid="ticket-detail-confirm-close"
      onClick={() => doAction(() => api.tickets.confirmCloseReady(ticketId),
        'ยืนยันพร้อมปิดงานแล้ว — รอ CEO ตรวจสอบ')}>
      <Icon name="check" size={14} />
      ยืนยันพร้อมปิดงาน
    </Button>
  ) : can.verifyClose ? (
    <Button type="button" variant="primary" disabled={actionLoading} data-testid="ticket-detail-verify-close"
      onClick={() => doAction(() => api.tickets.verifyClose(ticketId), 'ตรวจสอบและปิดงานแล้ว')}>
      <Icon name="check" size={14} />
      ตรวจสอบและปิดงาน
    </Button>
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
        <Button type="button" variant="primary" data-testid="ticket-primary-action" data-action={actionKey} onClick={() => navigate(to)}>
          {workStateAction.label}
          <Icon name="chevronRight" size={14} />
        </Button>
      );
      stickyPrimaryLabel = workStateAction.label;
    } else if (actionKey === 'create_pcr') {
      // Ticket-detail IA rebuild Phase 2: PricingRequestPanel now lives
      // inside the "สินค้าและราคา" tab (Slice C2b renamed/merged the old
      // "ราคา" tab into `items` — same panel, same mount point), mounted
      // only while that tab is active — `runOnTab` switches to it first,
      // then calls the ref (see this component's own doc comment on
      // `runOnTab`).
      stickyPrimaryAction = (
        <Button type="button" variant="primary" data-testid="ticket-primary-action" data-action={actionKey} onClick={() => runOnTab('items', () => pricingRequestPanelRef.current?.openCreate())}>
          <Icon name="plus" size={14} />
          {workStateAction.label}
        </Button>
      );
      stickyPrimaryLabel = workStateAction.label;
    } else if (actionKey === 'issue_quotation') {
      // DealQuotationPanel now lives inside the "เอกสาร" tab (Slice C2b
      // renamed the old "ใบเสนอราคา" tab id from `quotations` to `documents`
      // — same panel, same content, same gate).
      stickyPrimaryAction = (
        <Button type="button" variant="primary" data-testid="ticket-primary-action" data-action={actionKey} onClick={() => runOnTab('documents', () => dealQuotationPanelRef.current?.openIssueQuotation())}>
          {workStateAction.label}
        </Button>
      );
      stickyPrimaryLabel = workStateAction.label;
    } else if (actionKey === 'confirm_order') {
      // DealQuotationPanel now lives inside the "เอกสาร" tab (see comment above).
      stickyPrimaryAction = (
        <Button type="button" variant="primary" data-testid="ticket-primary-action" data-action={actionKey} onClick={() => runOnTab('documents', () => dealQuotationPanelRef.current?.openConfirmOrder())}>
          {workStateAction.label}
        </Button>
      );
      stickyPrimaryLabel = workStateAction.label;
    } else if (jumpId) {
      // Every IN_PAGE_JUMP_TARGET id now lives inside a tab — JUMP_TARGET_TAB
      // names which one, so runOnTab can switch there before scrolling.
      stickyPrimaryAction = (
        <Button type="button" variant="primary" data-testid="ticket-primary-action" data-action={actionKey} onClick={() => runOnTab(JUMP_TARGET_TAB[jumpId] ?? activeTab, () => scrollToSection(jumpId))}>
          {workStateAction.label}
          <Icon name="chevronRight" size={14} />
        </Button>
      );
      stickyPrimaryLabel = workStateAction.label;
    }
  }

  // The work-state banner line (IA region 4/6/7). The "ถึงคิวคุณ: <label>"
  // prefix is gone everywhere — it duplicated the CTA sitting right next to
  // it and read as distracting rather than informative. What replaces it
  // splits by WHERE the label came from:
  //
  //  - Resolver-derived CTAs (workStateAction, the branches above) render
  //    `workStateAction.label` verbatim on the button, so the line was that
  //    same string twice. Nulled — the button carries it alone.
  //  - The four `can.*` CTAs are different: `nextAction` is a descriptive
  //    SENTENCE ("ส่งมอบและรับเงินครบแล้ว — ยืนยันเพื่อส่งให้ CEO ตรวจสอบปิดงาน")
  //    naming the precondition and the consequence, while the button is a
  //    terse verb ("ยืนยันพร้อมปิดงาน"). Nulling those threw away real
  //    information, and — since the ticket-workspace IA rebuild Slice B
  //    retired the context rail that used to carry this line as a fallback —
  //    there is no other surface on the page left to say it. They keep
  //    their line, prefix-free.
  //
  // `primaryAction` (not stickyPrimaryLabel) is the discriminator: it is set
  // only by that four-branch cascade, and it always wins over the resolver.
  const bannerText = primaryAction
    ? nextAction
    : stickyPrimaryLabel
      ? null
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
  // ขอแก้ไข (Revise) opens as a modal (Slice C2a) — it no longer lives inside
  // a tab, so unlike handleOpenAdvance/handleOpenEditStage's siblings this
  // needs no runOnTab wrapper to switch tabs first; a plain flag flip is
  // enough regardless of which tab is active when the overflow item fires.
  function handleOpenRevise() { setShowReviseForm(true); clearFieldError('revise.reason'); }

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
    <div className={`grid w-full grid-cols-1 gap-[18px] min-w-0 max-w-[1320px] ${bannerText || stickyPrimaryAction || overflowItems.length > 0 ? 'mobile:pb-28' : ''}`}>
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
        <div className="overflow-hidden rounded-lg border border-border bg-surface mobile:overflow-visible mobile:border-0 mobile:bg-transparent">
          <DealStateHeader
            summary={summary}
            pricingRequests={pricingRequests}
            role={role}
            primaryAction={stickyPrimaryAction}
            bannerText={bannerText}
            overflowItems={overflowItems}
            onRefresh={refreshTicket}
            condensed={ticketChromeCondensed}
          />
          <div className="border-t border-border bg-surface px-4 sm:px-5">
            {/* onSurface: this strip is on a white card, unlike the two
                page-background call sites — see Tabs.jsx. */}
            <Tabs
              onSurface
              items={visibleTabItems}
              value={visibleActiveTab}
              onChange={setActiveTab}
              ariaLabel="รายละเอียดดีล"
              idPrefix="ticket-detail"
            />
          </div>
        </div>
      </div>

      {/* Ticket-workspace IA rebuild Slice B ("retire the context rail, one
          comment composer"): this was a two-column grid (`xl:grid-cols-[minmax(0,1fr)_20rem]`)
          with the sticky context rail as the second column — now a single
          full-width stack, since the rail is gone and its four sections were
          redistributed (see the overview TabPanel above and DealHistoryPanel
          below) rather than replaced with a second column of anything else. */}
      <div className="flex min-w-0 flex-col gap-4">

      {/* Deal pipeline (V50): the 14-stage journey with stage-gated doc actions.
          Generation buttons reuse the exact handlers/permissions of the action
          row; once a document exists (quotation / ใบแจ้งยอดมัดจำ) it stays
          reachable from here through the later stages too. */}
      <div className="min-w-0">
      <DealStagePanel
        ref={dealStagePanelRef}
        user={user}
        summary={summary}
        availableActions={availableActions}
        // primaryAction now lives solely in DealStateHeader above (Phase 2 Slice S2's
        // "one primary CTA" — see its own doc comment) — not passed here too, to avoid
        // rendering the exact same button twice on one page.
        advanceReady={readyToAdvance}
        actionLoading={actionLoading}
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
              <Button type="button" variant="secondary"
                disabled={downloadingQuotationKey === `${latestQuotation.id}-pdf`}
                onClick={() => handleDownloadQuotation(latestQuotation.id, latestQuotation.number, 'pdf')}>
                <Icon name="fileText" size={14} />
                {downloadingQuotationKey === `${latestQuotation.id}-pdf`
                  ? 'กำลังดาวน์โหลด…'
                  : `ใบเสนอราคา ${latestQuotation.number} (PDF)`}
              </Button>
            )}
            {can.downloadRemainingInvoice && (
              <Button type="button" variant="secondary" disabled={downloadingInvoice}
                onClick={handleDownloadRemainingInvoice}>
                {downloadingInvoice ? 'กำลังดาวน์โหลด…' : 'ดาวน์โหลดใบแจ้งหนี้ส่วนที่เหลือ'}
              </Button>
            )}
          </>
        ) : null}
      />
      </div>

      {/* Ticket-detail IA rebuild Phase 2 built the seven role-projected tabs
          from docs/ui-repair/02-information-architecture/TICKET_INFORMATION_ARCHITECTURE.md
          "Tabs (the deal's depth)", grouped by system object. Slice C2b
          ("the 7→6 tab restructure") regroups them into six tabs grouped by
          JOB instead — ดีล · สินค้าและราคา · เอกสาร · การเงิน · จัดซื้อ-ส่งมอบ ·
          ประวัติ — per the owner-approved IA. ticketDetailTabs.js still
          decides which ones this viewer's role may see at all (never a
          security boundary, same as salesViewScope.js it reads from), and
          Tabs.jsx keeps only the active one mounted.
          FIX 2 (Opus review) used to filter the `documents` (attachments)
          tab a second time here for its per-instance `canViewDocumentsTab`
          identity gate. That tab is gone — DealAttachmentsPanel now lives
          inside `history` (an unconditionally-visible tab) behind an INNER
          render condition instead, so no tab in ticketDetailTabs.js is
          per-instance gated any more and this second filter is no longer
          needed (`visibleTabItems`/`visibleActiveTab` above are now plain
          aliases of `tabItems`/`activeTab` — see that assignment's own
          comment). */}
      <div className="min-w-0">
      <TabPanel id="deal" idPrefix="ticket-detail" active={visibleActiveTab === 'deal'}>
          {/* Ticket-workspace IA rebuild Slice B ("retire the context rail, one
              comment composer"): วันสำคัญ / ผู้เกี่ยวข้อง moved here verbatim from
              the now-deleted TicketContextPanel.jsx sticky rail — same fields,
              same labels, same assignedImport role-scoped readout. */}
          <div className="grid gap-4 sm:grid-cols-2">
            <section className="rounded-lg border border-border bg-surface p-4">
              <ContextSection title="วันสำคัญ" helper="Key dates" icon="calendar">
                <dl className="m-0">
                  <FieldRow label="ติดตามครั้งถัดไป" value={formatThaiDate(summary.nextFollowUpAt)} />
                  <FieldRow label="ติดตามล่าสุด" value={formatThaiDate(summary.lastFollowUpAt)} />
                  <FieldRow label="วันวางบิล" value={formatThaiDate(summary.billingDate)} />
                  <FieldRow label="ครบกำหนดชำระ" value={formatThaiDate(summary.dueDate)} danger={summary.overdue} />
                  <FieldRow label="ใบเสนอราคาหมดอายุ" value={formatThaiDate(latestQuotation?.validityDate)} />
                </dl>
              </ContextSection>
            </section>
            <section className="rounded-lg border border-border bg-surface p-4">
              <ContextSection title="ผู้เกี่ยวข้อง" helper="ทีมที่เกี่ยวข้อง" icon="users">
                <dl className="m-0">
                  <FieldRow label="เจ้าของดีล" value={summary.createdByName} />
                  <FieldRow label="ผู้รับเรื่องคำขอราคา" value={assignedImport} />
                  <FieldRow label="บัญชี" value={summary.closeConfirmedByName || 'ยังไม่ระบุ'} />
                  <FieldRow label="ผู้ติดต่อ" value={summary.contactName} />
                </dl>
              </ContextSection>
            </section>
          </div>

          {/* Slice C2b: DealTrackingPanel moved in from the old "activity"
              tab — same `canViewDealTracking` gate, moved verbatim (see
              ticketDetailTabs.js's own comment on this tab and
              JUMP_TARGET_TAB's own doc comment above for the jump-target
              relocation). */}
          {canViewDealTracking ? (
            // id: the sticky bar's FOLLOW_UP/LOG_ACTIVITY jump target (see
            // IN_PAGE_JUMP_TARGET above) — scroll-mt so it doesn't tuck under
            // the sticky header, tabIndex so the jump can actually move focus
            // here.
            <div id="deal-tracking-panel" tabIndex={-1} className="scroll-mt-[300px] mobile:scroll-mt-[420px] outline-none">
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
      </TabPanel>

      <TabPanel id="items" idPrefix="ticket-detail" active={visibleActiveTab === 'items'}>
          {/* flush + title/actions picks up Panel's standard bordered-header inset
              (20px/16px, border-border-subtle) in place of the old inline 14px/18px
              padding and var(--color-border) rule — the same small, disclosed shift
              the CommissionPage port (f7c2cace) already made for this exact
              "table-topped panel with its own inline header padding" case. */}
          <Panel
            flush
            title={`รายการสินค้า (${editMode ? editDraft.length : items.length} รายการ)`}
            actions={can.editItems && !editMode ? (
              <Button type="button" variant="secondary" disabled={actionLoading}
                onClick={() => {
                  setEditDraft(items.map((item) => ({ ...item })));
                  setEditNote('');
                  setEditMode(true);
                  setFieldErrorsForPrefix('editItems.qty.', {});
                }}>
                <Icon name="pencil" size={14} />
                แก้ไขรายการสินค้า
              </Button>
            ) : null}
          >
            {editMode ? (
              <div className="p-[14px_18px]">
                {editDraft.map((item, index) => (
                  <div key={index} className="mb-2.5 rounded-md border border-border-subtle bg-surface-muted p-[12px_14px]">
                    <div className="mb-2 flex items-center justify-between">
                      <span className="text-xs font-bold text-text-muted">รายการที่ {index + 1}</span>
                      {editDraft.length > 1 && (
                        <Button type="button" variant="icon" className="text-danger" aria-label={`ลบรายการที่ ${index + 1}`}
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
                        </Button>
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
                            onChange={(e) => setEditDraft((d) => d.map((r, i) => {
                              if (i !== index) return r;
                              const next = { ...r, [key]: e.target.value };
                              // A hand-edit to a descriptive field invalidates a catalog link
                              // picked at deal-creation time — what's typed no longer
                              // necessarily matches what the link points at. Mirrors
                              // TicketCreateModal.jsx's updateItem/PricingRequestCreateModal's
                              // updateItem, same rule.
                              next.catalogPriceId = null;
                              next.catalogProductCode = '';
                              return next;
                            }))} />
                        </label>
                      ))}
                      {/* Unit basis toggle */}
                      <div className="col-span-full m-0">
                        <span className="mb-1 block text-xs">หน่วยที่ใช้สั่ง</span>
                        <div className="flex flex-wrap items-center gap-4">
                          {[{ value: 'PIECE', label: 'แผ่น' }, { value: 'SQM', label: 'ตร.ม.' }].map((opt) => (
                            <label key={opt.value} className="flex cursor-pointer items-center gap-1.5 text-sm">
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
                                className="h-4 w-4 cursor-pointer accent-info-dot" />
                              <strong>{opt.label}</strong>
                            </label>
                          ))}
                          {item.sqmPerPiece && (
                            <span className="text-2xs text-text-muted">· 1 แผ่น = {item.sqmPerPiece} ตร.ม.</span>
                          )}
                        </div>
                      </div>

                      {/* Qty inputs */}
                      {(item.unitBasis || 'PIECE') === 'PIECE' ? (
                        <>
                          <label className="m-0">
                            <span className="text-xs">จำนวน (แผ่น)</span>
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
                              <p id={fieldErrorId(`edit-item-qty-${index}`)} role="alert" className="mx-0 mb-0 mt-1 text-2xs font-bold text-danger">
                                {fieldErrors[`editItems.qty.${index}`]}
                              </p>
                            ) : null}
                          </label>
                          <div className="m-0">
                            <span className="mb-1 block text-xs">พื้นที่รวม (ตร.ม.)</span>
                            <div className={cn('rounded-[6px] border border-border-subtle bg-surface-muted px-2.5 py-[7px] text-sm', item.qtySqm ? 'text-icon-muted' : 'text-text-muted')}>
                              {item.qtySqm ? `${Number(item.qtySqm).toFixed(3)} ตร.ม.` : '—'}
                            </div>
                          </div>
                        </>
                      ) : (
                        <>
                          <label className="m-0">
                            <span className="text-xs">พื้นที่ (ตร.ม.)</span>
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
                              <p id={fieldErrorId(`edit-item-qtysqm-${index}`)} role="alert" className="mx-0 mb-0 mt-1 text-2xs font-bold text-danger">
                                {fieldErrors[`editItems.qty.${index}`]}
                              </p>
                            ) : null}
                          </label>
                          <div className="m-0">
                            <span className="mb-1 block text-xs">จำนวน (แผ่น)</span>
                            <div className={cn('rounded-[6px] border border-border-subtle bg-surface-muted px-2.5 py-[7px] text-sm', item.qty ? 'text-icon-muted' : 'text-text-muted')}>
                              {item.qty ? `${item.qty} แผ่น` : '—'}
                            </div>
                          </div>
                        </>
                      )}
                      {ROLE_PERMISSIONS.canProposePrices.includes(role) && (
                        <label className="col-span-full m-0">
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
                <Button type="button" variant="secondary"
                  onClick={() => setEditDraft((d) => [...d, { brand: '', model: '', color: '', texture: '', size: '', qty: 1, proposedPrice: null }])}
                  className="mb-3">
                  <Icon name="plus" size={14} /> เพิ่มรายการ
                </Button>
                <label className="mb-2.5 block text-sm">
                  หมายเหตุการแก้ไข
                  <input value={editNote} onChange={(e) => setEditNote(e.target.value)} placeholder="ระบุสาเหตุที่แก้ไข (ถ้ามี)" className="mt-1" />
                </label>
                <div className="flex gap-2">
                  <Button type="button" variant="primary" disabled={actionLoading}
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
                          // Round-trips the deal-creation catalog link (V110) — editDraft rows
                          // are seeded straight from the fetched `items` (which already carry
                          // it), and the row inputs above clear it on any descriptive hand-edit.
                          catalogPriceId: item.catalogPriceId ?? null,
                          catalogProductCode: item.catalogProductCode?.trim() || null,
                        })),
                        note: editNote.trim() || null,
                      }), 'บันทึกการแก้ไขแล้ว');
                    }}>
                    บันทึกการแก้ไข
                  </Button>
                  <Button type="button" variant="secondary" disabled={actionLoading}
                    onClick={() => { setEditMode(false); setEditDraft([]); setEditNote(''); setFieldErrorsForPrefix('editItems.qty.', {}); }}>
                    ยกเลิก
                  </Button>
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
                      {item.model && <small className="text-text-muted">{item.model}</small>}
                      {item.factory && <small className="text-2xs text-text-muted">{item.factory}</small>}
                    </span>
                    <span data-label="สี / เนื้อผิว" className="flex flex-col gap-0.5">
                      {item.color && <span>{item.color}</span>}
                      {item.texture && <small className="text-text-muted">{item.texture}</small>}
                      {item.size && <small className="text-text-muted">{item.size}</small>}
                    </span>
                    <span data-label="จำนวน">
                      {item.unitBasis === 'SQM'
                        ? <>{item.qtySqm != null ? `${Number(item.qtySqm).toFixed(2)} ตร.ม.` : '—'}<small className="block text-text-muted">{item.qty} แผ่น</small></>
                        : <>{item.qty} แผ่น{item.qtySqm != null && <small className="block text-text-muted">{Number(item.qtySqm).toFixed(2)} ตร.ม.</small>}</>
                      }
                    </span>
                    {showCalcBreakdown ? (
                      <>
                        <span data-label="ราคาโรงงาน" className="text-xs">
                          {item.rawPrice != null
                            ? (() => {
                              // Original currency is the figure of record; the baht line underneath is a
                              // straight FX conversion (no markup) and is omitted entirely when no rate
                              // exists for that currency, rather than implying a 1:1 rate.
                              const thb = convertToThb(item.rawPrice, item.rawCurrency, fxRatesByCurrency);
                              const unit = item.rawUnit === 'sqm' ? 'ตร.ม.' : 'แผ่น';
                              return (
                                <>
                                  <strong>{formatThb(item.rawPrice)}</strong>
                                  <small className="text-text-muted"> {item.rawCurrency}/{unit}</small>
                                  {thb != null && item.rawCurrency !== 'THB' ? (
                                    <small className="block text-text-muted">≈ {formatThb(thb)} บาท/{unit}</small>
                                  ) : null}
                                </>
                              );
                            })()
                            : <span className="text-text-muted">-</span>}
                          {item.calcConfigVersion && <small className="block text-2xs text-text-muted">สูตรราคาเวอร์ชัน {item.calcConfigVersion}</small>}
                        </span>
                        <code data-label="ต้นทุน (THB/ชิ้น)" className="text-info">{item.calcedCost != null ? formatMoney(item.calcedCost) : '—'}</code>
                        <span data-label="ราคาขาย (THB/ชิ้น)">
                          <code className={cn('font-bold', item.manualPrice != null ? 'text-override' : 'text-success')}>
                            {item.manualPrice != null ? formatMoney(item.manualPrice) : item.calcedPrice != null ? formatMoney(item.calcedPrice) : '—'}
                          </code>
                          {/* CEO manual-override entry (D10) was ticket-native and retired along
                              with calculatePrices/approve — this is now a read-only readout of
                              whatever the 3 stranded legacy tickets already carry. */}
                          {item.manualPrice != null && <small className="block text-2xs text-override">override</small>}
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

            {/* Slice C2b: this hint travels with can.editItems into the
                items panel — same condition, unchanged, moved verbatim from
                the old (now-dissolved) การดำเนินการอื่น ๆ grab-bag below the
                table. */}
            {st === 'draft' && isOwner && items.length === 0 && (
              <div className="p-[0_18px_16px]">
                <span className="rounded-lg border border-border bg-surface-subtle px-3 py-2 text-xs text-text-muted">
                  ดีลนี้ยังไม่มีรายการสินค้า — กด “แก้ไขรายการสินค้า” เพื่อเพิ่มก่อนส่งขอราคา
                </span>
              </div>
            )}
          </Panel>

          {/* id: the sticky bar's CREATE_PCR jump target (see
              IN_PAGE_JUMP_TARGET above). Slice C2b: PricingRequestPanel
              merges in here from the old whole-tab-gated "ราคา" tab — the
              old tab-level predicate
              (`Boolean(sections.pricingRequest) && canViewPricingRequests`,
              see ticketDetailTabs.js's own comment on the `items` tab)
              becomes this INNER render condition instead, reproduced
              exactly: `items` itself is unconditionally visible, so nothing
              upstream any longer enforces `sections.pricingRequest` for
              this panel. */}
          <div id="pricing-request-panel" tabIndex={-1} className="scroll-mt-[300px] mobile:scroll-mt-[420px] outline-none">
            {canViewPricingRequests && Boolean(sections.pricingRequest) ? (
              <PricingRequestPanel ref={pricingRequestPanelRef} ticketId={ticketId} deal={summary} ticketItems={items} user={user} />
            ) : null}
          </div>
      </TabPanel>

      <TabPanel id="documents" idPrefix="ticket-detail" active={visibleActiveTab === 'documents'}>
          {/* Slice D ("the เอกสาร document register"): one read-only roll-up of
              every document family — quotations (below, unchanged), the
              deposit notice + remaining invoice, and formal attachments —
              gated PER ROW FAMILY inside the component itself (this tab's own
              `isVisible` is now `() => true`, per ticketDetailTabs.js's own
              comment on this tab). `attachments`/`attachLoading` are the SAME
              query this page already runs for the ประวัติ tab's
              DealAttachmentsPanel (gated at that query's own `enabled` by
              `canViewDocumentsTab`, so it never fires an extra request here,
              and never fires at all for a viewer it would 403) — reused
              rather than re-fetched, so the two roll-ups of the same data
              can never drift. See DealDocumentRegister.jsx's own header
              comment for the exact three gates and their backend citations. */}
          <DealDocumentRegister
            ticketId={ticketId}
            user={user}
            summary={summary}
            sections={sections}
            canViewPricingRequests={canViewPricingRequests}
            canViewDocumentsTab={canViewDocumentsTab}
            pricingRequests={pricingRequests}
            legacyQuotations={sortedQuotations}
            attachments={attachments}
            attachLoading={attachLoading}
          />

          {/* "เอกสาร" (formerly "ใบเสนอราคา", tab id renamed `quotations` →
              `documents` in Slice C2b — same content, same gate; this id
              previously belonged to the attachments tab, whose content moved
              to `history` below, see ticketDetailTabs.js's own comment).
              Phase 2 Slice S2: the customer-facing tail of the
              PricingRequest chain (issue/outcome + confirm-order), pulled
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
            <div id="deal-quotation-panel" tabIndex={-1} className="scroll-mt-[300px] mobile:scroll-mt-[420px] outline-none">
              <DealQuotationPanel ref={dealQuotationPanelRef} ticketId={ticketId} pricingRequests={pricingRequests} user={user} showToast={showToast} />
            </div>
          ) : null}
          {/* Legacy ticket-native quotation rows (region 12's "docs") join the
              PCR-based quotation tail above, in this same tab — see
              TICKET_INFORMATION_ARCHITECTURE.md's ใบเสนอราคา row. Extracted to
              DealLegacyQuotations.jsx (ia-extract Slice C1); it returns null
              itself when quotationGroups is empty, matching the
              `quotationGroups.length > 0` half of this gate exactly. */}
          {sections.quotation ? (
            <DealLegacyQuotations
              quotationGroups={quotationGroups}
              downloadingQuotationKey={downloadingQuotationKey}
              handleDownloadQuotation={handleDownloadQuotation}
            />
          ) : null}
      </TabPanel>

      <TabPanel id="money" idPrefix="ticket-detail" active={visibleActiveTab === 'money'}>
          {sections.payment ? (
            <DealMoneyTimeline
              events={events}
              paymentReceipts={paymentReceipts}
              paymentsLoading={paymentsQuery.isLoading}
              summary={summary}
              canRecordPayment={can.recordPayment}
              canSetBilling={can.setBilling}
              actionLoading={actionLoading}
              onRecordPayment={openPaymentModal}
              onSetBilling={openBillingModal}
            />
          ) : null}

          {/* "มัดจำ" (Phase 3 Slice S3 — see
              docs/agent-handoffs/105_feat-deal-deposit-fulfilment-unify.md):
              the one deposit section — policy (account/CEO) → notice (sales)
              → payment confirmation (account) — replacing the deposit-policy
              control that used to live in DealStagePanel and the deposit
              doc/payment bits that used to live directly on this page.
              id: the sticky bar's account-role jump target (chaseOverdue/
              confirmDeposit/confirmFinalPayment/confirmCloseReady). */}
          <div id="deal-deposit-panel" tabIndex={-1} className="scroll-mt-[300px] mobile:scroll-mt-[420px] outline-none">
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

          {/* Slice C2b: relocated here from the dissolved การดำเนินการอื่น ๆ
              grab-bag — a close-lifecycle action, not an items action, so it
              belongs with การเงิน rather than สินค้าและราคา. Gate unchanged
              (`can.revokeCloseConfirm`). Rendered as its own section rather
              than nested inside the `sections.payment`-gated panel above,
              since the real server gate (`canRevokeCloseConfirmation`,
              account/ceo-only) doesn't itself depend on `sections.payment` —
              though for both those roles it is always true anyway (see
              ticketDetailTabs.js's own comment on this tab's gate), so the
              action can never end up stranded behind a hidden `money` tab. */}
          {can.revokeCloseConfirm && (
            <Panel className="bg-surface-muted">
              <div className="flex flex-wrap gap-2.5 p-[12px_18px]">
                <Button type="button" variant="secondary" disabled={actionLoading}
                  onClick={() => doAction(() => api.tickets.revokeCloseConfirmation(ticketId, {}),
                    'ยกเลิกการยืนยันปิดงานแล้ว')}>
                  ยกเลิกการยืนยันปิดงาน
                </Button>
              </div>
            </Panel>
          )}
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
          <div id="deal-fulfilment-panel" tabIndex={-1} className="scroll-mt-[300px] mobile:scroll-mt-[420px] outline-none">
            {sections.delivery ? (
              <DealFulfilmentPanel
                ticketId={ticketId}
                user={user}
                summary={summary}
                items={items}
                availableActions={availableActions}
                showToast={showToast}
              />
            ) : null}
          </div>
      </TabPanel>

      <TabPanel id="history" idPrefix="ticket-detail" active={visibleActiveTab === 'history'}>
          {/* "ประวัติดีล" (IA rebuild): merges the ticket's audit trail
              (ticket.events, formerly its own "ประวัติการดำเนินการ" panel) with
              the deal-tracking activity log (formerly DealTrackingPanel's own
              "ประวัติการติดตาม (Activity log)" list) into one chronological
              stream — regions 17+18 of TICKET_INFORMATION_ARCHITECTURE.md.
              FIX 1 (Opus review, owner decision): the audit trail (events)
              and the comment box (`can.comment`) are backed by
              `TicketService.projectForRole`/`comment`'s `requireViewAccess`,
              which every viewer of the deal passes — so they are NOT
              re-gated on `canViewDealTracking` here; only the follow-up feed
              (`activities`) and its add-activity form stay gated on
              `requireDealOwnership`, per TicketIaAuthzMatrixIntegrationTest's
              activities_* cases. DealTrackingPanel itself (win%/designer/
              owner/buyer/next-follow-up) is NOT part of this tab any more —
              Slice C2b moved it into `deal` above, unchanged gate — see
              ticketDetailTabs.js's own comment on this tab. */}
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

          {/* Slice C2b: DealAttachmentsPanel folds in here from the old
              `documents` (attachments) tab — that id now holds different
              content (see the `documents` TabPanel above and
              ticketDetailTabs.js's own comment on the id reuse). That old
              tab's own `() => true` role gate travels with it, but its
              per-instance `canViewDocumentsTab` check — formerly a TAB-level
              filter in `visibleTabItems`/`visibleActiveTab` (the old
              'documents' special-casing, now removed — see that
              assignment's own comment above) — is now this INNER render
              condition instead, since `history` itself is unconditionally
              visible and has no tab-level analogue left to filter. Anyone
              `canViewDocumentsTab` excludes still reaches this whole tab
              (the history stream above), just not this panel — see
              `canViewDocumentsTab`'s own doc comment near its declaration
              for the underlying predicate (unchanged: createdById/
              assignedToId participant OR
              ROLE_PERMISSIONS.canViewTicketDocuments).
              R5: Attachments. Extracted to DealAttachmentsPanel.jsx
              (ia-extract Slice C1) — the long "why no ใบกำกับภาษี control
              here" comment lives there now, verbatim, since it explains that
              panel's own JSX. `canUpload`/`notTerminal` are computed here
              (instead of handing the raw `st`/TERMINAL pair down) so the
              child only ever sees clean booleans; they reproduce the
              original `!TERMINAL.includes(st) && ...` guards exactly. */}
          {canViewDocumentsTab ? (
            <DealAttachmentsPanel
              attachments={attachments}
              attachLoading={attachLoading}
              canManageDocuments={canManageDocuments}
              uploadingFile={uploadingFile}
              onUploadAttachment={handleUploadAttachment}
              onDeleteAttachment={handleDeleteAttachment}
              canUpload={!TERMINAL.includes(st) && canManageDocuments}
              notTerminal={!TERMINAL.includes(st)}
              user={user}
            />
          ) : null}
      </TabPanel>
      </div>

      {/* "จัดการดีล" danger zone (ticket-detail IA rebuild Phase 1): เสียงาน /
          ยกเลิก moved off the working surface into a clearly-labelled,
          danger-toned section at the very bottom of the page — conventional
          danger-zone pattern, no new route. Both actions stay exactly as
          reachable as before (same canLostDeal/can.cancel gates, same
          modals), only demoted from sitting inline among the day-to-day
          pipeline controls. */}
      {(canLostDeal || can.cancel) ? (
        <section className="rounded-xl border border-danger-border bg-danger-bg p-4 sm:p-5" aria-labelledby="deal-danger-zone-heading">
          <h2 id="deal-danger-zone-heading" className="m-0 text-sm font-extrabold text-danger-dark">จัดการดีล</h2>
          <p className="mt-1 text-xs text-danger-dark">การดำเนินการเหล่านี้ส่งผลต่อทั้งดีล และบางรายการย้อนกลับไม่ได้ — ใช้เมื่อจำเป็นเท่านั้น</p>
          <div className="mt-3 flex flex-wrap gap-2">
            {canLostDeal ? (
              // Button's own `danger` variant (bg-surface text-danger border-danger-border) is
              // byte-identical to this hand-rolled secondary+override, so it replaces both here.
              <Button
                type="button"
                variant="danger"
                disabled={actionLoading}
                onClick={() => dealStagePanelRef.current?.openMarkLost()}
              >
                เสียงาน
              </Button>
            ) : null}
            {can.cancel ? (
              <Button
                type="button"
                variant="danger"
                disabled={actionLoading}
                onClick={() => setConfirm({ kind: 'cancelTicket' })}
              >
                ยกเลิก
              </Button>
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
              <Button type="button" variant="secondary" onClick={closePaymentModal}>ยกเลิก</Button>
              <Button type="button" variant="primary" disabled={actionLoading} onClick={handleRecordPayment}>
                บันทึก
              </Button>
            </>
          )}
        >
          <div className="grid gap-3">
            <label className="grid gap-1.5 text-sm font-semibold">
              ประเภท
              <select value={paymentDraft.kind} onChange={(e) => setPaymentDraft((draft) => ({ ...draft, kind: e.target.value }))}>
                <option value="DEPOSIT">มัดจำ</option>
                <option value="BALANCE">ส่วนที่เหลือ</option>
                <option value="ADJUSTMENT">ปรับปรุงยอด/คืนเงิน</option>
              </select>
            </label>
            <div className="grid grid-cols-[repeat(auto-fit,minmax(160px,1fr))] gap-2.5">
              <label className="grid gap-1.5 text-sm font-semibold">
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
                  <p id={fieldErrorId('payment-amount')} role="alert" className="m-0 text-2xs font-bold text-danger">
                    {fieldErrors['payment.amount']}
                  </p>
                ) : null}
              </label>
              <label className="grid gap-1.5 text-sm font-semibold">
                วันที่รับเงิน
                <input type="date" value={paymentDraft.receivedAt}
                  onChange={(e) => setPaymentDraft((draft) => ({ ...draft, receivedAt: e.target.value }))} />
              </label>
            </div>
            <label className="grid gap-1.5 text-sm font-semibold">
              เลขอ้างอิง
              <input value={paymentDraft.receiptRef}
                onChange={(e) => setPaymentDraft((draft) => ({ ...draft, receiptRef: e.target.value }))} />
            </label>
            <label className="grid gap-1.5 text-sm font-semibold">
              หมายเหตุ
              <textarea rows={3} value={paymentDraft.note}
                onChange={(e) => setPaymentDraft((draft) => ({ ...draft, note: e.target.value }))} />
            </label>
            <label className="flex items-center gap-2 text-sm">
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
              <Button type="button" variant="secondary" onClick={() => setBillingModal(false)}>ยกเลิก</Button>
              <Button type="button" variant="primary" disabled={actionLoading} onClick={handleSetBilling}>
                บันทึก
              </Button>
            </>
          )}
        >
          <div className="grid gap-3">
            <div className="grid grid-cols-[repeat(auto-fit,minmax(150px,1fr))] gap-2.5">
              <label className="grid gap-1.5 text-sm font-semibold">
                วันที่วางบิล
                <input type="date" value={billingDraft.billingDate}
                  onChange={(e) => setBillingDraft((draft) => ({ ...draft, billingDate: e.target.value }))} />
              </label>
              <label className="grid gap-1.5 text-sm font-semibold">
                วันครบกำหนด
                <input type="date" value={billingDraft.dueDate}
                  onChange={(e) => setBillingDraft((draft) => ({ ...draft, dueDate: e.target.value }))} />
              </label>
              <label className="grid gap-1.5 text-sm font-semibold">
                เครดิต (วัน)
                <input type="number" min="0" value={billingDraft.creditTermDays}
                  onChange={(e) => setBillingDraft((draft) => ({ ...draft, creditTermDays: e.target.value }))} />
              </label>
            </div>
            <div className="grid grid-cols-[repeat(auto-fit,minmax(150px,1fr))] gap-2.5">
              <label className="grid gap-1.5 text-sm font-semibold">
                ติดตามล่าสุด
                <input type="date" value={billingDraft.lastFollowUpAt}
                  onChange={(e) => setBillingDraft((draft) => ({ ...draft, lastFollowUpAt: e.target.value }))} />
              </label>
              <label className="grid gap-1.5 text-sm font-semibold">
                ติดตามครั้งถัดไป
                <input type="date" value={billingDraft.nextFollowUpAt}
                  onChange={(e) => setBillingDraft((draft) => ({ ...draft, nextFollowUpAt: e.target.value }))} />
              </label>
            </div>
          </div>
        </Modal>
      )}

      {/* ขอแก้ไข (Revise) — Slice C2a: converted from an inline "ภาพรวม" tab
          section into a modal, same pattern as paymentModal/billingModal
          above. The old inline version opened ~400px below its overflow-menu
          trigger and forced a tab switch (runOnTab('overview', …)) plus a
          scroll-into-view just to reveal itself — disorienting, and it also
          meant a menu item outside every tab silently changed which tab was
          active. A modal has no tab to switch to, so handleOpenRevise is now
          a plain flag flip (see its own doc comment). */}
      {can.revise && showReviseForm && (
        <Modal
          title="ขอแก้ไข"
          onClose={() => { setShowReviseForm(false); setReviseReason(''); clearFieldError('revise.reason'); }}
          footer={(
            <>
              <Button type="button" variant="secondary" disabled={actionLoading}
                onClick={() => { setShowReviseForm(false); setReviseReason(''); clearFieldError('revise.reason'); }}>
                ยกเลิก
              </Button>
              {/* This button is already disabled={!reviseReason.trim()} (unchanged
                  below), so the guard inside onClick is defensive/unreachable
                  through the UI — wired for consistency with the other 3 forms
                  in this slice, not because a user can trigger it here. */}
              <Button type="button" variant="primary" disabled={actionLoading || !reviseReason.trim()}
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
              </Button>
            </>
          )}
        >
          <div className="flex flex-col gap-2.5">
            <div className="text-sm font-semibold">ประเภทการแก้ไข</div>
            {[
              { value: 'QTY_OR_NOTE',  label: 'แก้จำนวน / หมายเหตุ / % มัดจำ', sub: 'ไม่ต้องอนุมัติใหม่ — ออกเอกสารรอบแก้ไขใหม่ได้เลย' },
              { value: 'PRICE_CHANGE', label: 'แก้ราคา / ส่วนลดต่อหน่วย',       sub: 'CEO ต้องอนุมัติใหม่' },
              { value: 'NEW_ITEM',     label: 'เพิ่มสินค้าใหม่',                sub: 'ฝ่ายนำเข้าตั้งราคา → CEO อนุมัติ' },
            ].map((opt) => (
              // eslint-disable-next-line jsx-a11y/label-has-associated-control -- label nests the radio control; its text is the dynamic opt.label
              <label key={opt.value} className="flex cursor-pointer items-start gap-2.5 text-sm">
                <input type="radio" name="reviseScope" value={opt.value}
                  checked={reviseScope === opt.value}
                  onChange={() => setReviseScope(opt.value)}
                  className="mt-0.5 h-4 w-4 shrink-0 cursor-pointer accent-info-dot" />
                <span>
                  <strong>{opt.label}</strong>
                  <span className="block text-xs text-text-muted">{opt.sub}</span>
                </span>
              </label>
            ))}
            <label className="text-sm font-semibold">
              เหตุผลการแก้ไข *
              <textarea rows={2}
                id="revise-reason"
                ref={(el) => { fieldRefs.current['revise.reason'] = el; }}
                value={reviseReason}
                onChange={(e) => { setReviseReason(e.target.value); clearFieldError('revise.reason'); }}
                placeholder="ระบุเหตุผล…" className="mt-1"
                aria-invalid={fieldErrors['revise.reason'] ? true : undefined}
                aria-describedby={fieldErrors['revise.reason'] ? fieldErrorId('revise-reason') : undefined}
              />
              {fieldErrors['revise.reason'] ? (
                <p id={fieldErrorId('revise-reason')} role="alert" className="mx-0 mb-0 mt-1 text-2xs font-bold text-danger">
                  {fieldErrors['revise.reason']}
                </p>
              ) : null}
            </label>
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
            <div className="flex flex-col gap-2">
              <p className="confirm-dialog-message text-text-secondary leading-normal m-0">
                {hasOutstanding
                  ? 'ระบบจะบันทึกรับชำระส่วนที่เหลือเต็มจำนวนเป็นรายการ BALANCE แล้วทำเครื่องหมายดีลนี้ว่าชำระครบแล้ว'
                  : 'ยอดคงเหลือเป็นศูนย์อยู่แล้ว ระบบจะทำเครื่องหมายดีลนี้ว่าชำระครบแล้วโดยไม่บันทึกรายการรับชำระใหม่'}
              </p>
              {hasOutstanding && (
                <div className="flex justify-between border-t border-border pt-2 text-md font-bold">
                  <span>ยอดที่จะบันทึกเป็นรับชำระ (คงเหลือ)</span>
                  <span className="font-mono">{formatMoney(outstanding)}</span>
                </div>
              )}
              <p className="m-0 text-xs text-icon-muted">
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
