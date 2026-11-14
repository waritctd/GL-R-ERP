/* Hallmark · genre: modern-minimal · macrostructure: Workbench · design-system: design.md · designed-as-app
 * Tokens only (no new colour/font/radius); radius on the DESIGN.md §8 scale, see also §20
 * (anti-patterns). The ใบเสนอราคา panel's route choice is two sibling header buttons (no
 * dialog, §16); ใบเสนอราคาตรง is hidden while the deal holds a live คำขอราคา.
 */
import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { api, ROLE_PERMISSIONS } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { Breadcrumbs } from '../../components/common/Breadcrumbs.jsx';
import { Button, buttonVariants } from '../../components/common/Button.jsx';
import { ConfirmDialog } from '../../components/common/ConfirmDialog.jsx';
import { ConfirmOrderDialog } from '../quotations/ConfirmOrderDialog.jsx';
import { EmptyState } from '../../components/common/EmptyState.jsx';
import { fieldErrorId } from '../../components/common/FormField.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { OverflowMenu } from '../../components/common/OverflowMenu.jsx';
import { Panel } from '../../components/common/Layout.jsx';
import { Modal } from '../../components/common/Modal.jsx';
import { Skeleton, SkeletonText } from '../../components/common/Skeleton.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
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
import { hasActivitySince, isReadyToAdvance, lastStageChangeAt, STAGE_ADVANCE_GATE_HINT, STAGE_ADVANCE_GATE_MESSAGE } from './dealTrackingMeta.js';
import { ContextSection, FieldRow } from './DealMetaFields.jsx';
import { DealAttachmentsPanel } from './DealAttachmentsPanel.jsx';
import { createDepositNoticeDraft, depositNoticesQueryOptions, pickDepositNotice } from '../deposits/createDepositNoticeDraft.js';
import { DealDocumentRegister, canReadStoredImportRequests } from './DealDocumentRegister.jsx';
import { ProcurementSourceDialog } from './ProcurementSourceDialog.jsx';
import { RemainingInvoiceDialog } from './RemainingInvoiceDialog.jsx';
import { DealFulfilmentPanel } from './DealFulfilmentPanel.jsx';
import { DealHistoryPanel } from './DealHistoryPanel.jsx';
import { DealLegacyQuotations } from './DealLegacyQuotations.jsx';
import { DealPaymentSection } from './DealPaymentSection.jsx';
import { DealQuotationPanel } from './DealQuotationPanel.jsx';
import {
  canReviseDealQuotation, orderConfirmationOf, canViewDealQuotation, newestQuotationByRequest,
  DEAD_PRICING_REQUEST_STATUSES, stageForQuotationRecipient,
} from '../quotations/quotationMeta.js';
import { DealStagePanel } from './DealStagePanel.jsx';
import { DealStateHeader } from './DealStateHeader.jsx';
import { DealTrackingPanel } from './DealTrackingPanel.jsx';
import { DealPartiesPanel } from './DealPartiesPanel.jsx';
import { BidOutcomeModal } from './BidOutcomeModal.jsx';
import {
  PARTY_ROLE_LABEL, buyerParties, isAwaitingBidOutcome, isBiddingParty,
} from './dealPartyMeta.js';
import { visibleSections } from './salesViewScope.js';
import { nextOnRoute, offRoutePartiesFor, stageIndexIn, useStageCatalog } from './stageCatalog.js';
import { PARTY_LABEL } from './salesActions.js';
import {
  resolveTicketDetailTab, TICKET_DETAIL_TABS, visibleTicketDetailTabIds,
} from './ticketDetailTabs.js';
import { isRemainingInvoiceReady, pickRemainingInvoice } from './remainingInvoiceReadiness.js';
import { isOwnerOrKeyer } from './dealOwnership.js';
import {
  applyCatalogPick,
  applyDescriptiveFieldEdit,
  hasRealTileCount,
  CatalogAutocompleteField,
  ITEM_FIELD_META,
  ItemField,
  ItemFieldLabel,
  isRequiredItemField,
  missingQtyMessage,
  REQUIRED_ITEM_FIELD_LABELS,
  requiredItemFieldErrors,
  requiredQtyField,
  searchCatalog,
} from './ticketItemFields.jsx';
import { manualMoveLabel } from './stageMeta.js';
import { quoteNextPartyBanner, resolveWorkState, secondaryWorkActions } from './workState.js';
import { ThaiDateInput } from '../../components/common/ThaiDateInput.jsx';

// Ticket-detail IA rebuild Phase 1 (see
// docs/ui-repair/02-information-architecture/TICKET_INFORMATION_ARCHITECTURE.md
// "Action bar (sticky)"): where the sticky primary button sends the viewer
// when workState.js hands back an action this page has no dedicated `can.*`
// button for (FOLLOW_UP/LOG_ACTIVITY/RECORD_DELIVERY for sales; the
// fulfilment chain for import; the deposit/final-payment/close-ready steps
// for account) — an
// in-page anchor id when the real control already lives further down THIS
// page, so the button scrolls to it instead of duplicating its logic (never
// a second copy of a mutation the real panel already owns and gates).
// Import's pickup step and account's commission step point at a different
// ROUTE entirely (nextImportAction/nextAccountAction's own `to`), handled
// separately below. CREATE_PCR/ISSUE_QUOTATION/CONFIRM_ORDER are NOT
// scroll targets — CREATE_PCR/CONFIRM_ORDER open their owning panel via a
// forwardRef (see pricingRequestPanelRef/dealQuotationPanelRef below) and
// ISSUE_QUOTATION writes its quotation on this page, so they are deliberately
// absent from this map.
const IN_PAGE_JUMP_TARGET = {
  // salesActions.js SALES_ACTION keys
  follow_up: 'deal-tracking-panel',
  log_activity: 'deal-tracking-panel',
  // Stages 13-14 (ส่งมอบสินค้า) belong to Sales too, additive to import/CEO: salesActions.js's
  // RECORD_DELIVERY bucket reuses importActions.js's nextFulfilmentActionCode (not
  // nextImportAction), so it needs its own entry. Import's camelCase `recordDelivery` below is
  // unreachable (nextImportAction no longer emits it) but kept: it maps to this same panel, so it is
  // already correct if import is ever re-prompted.
  record_delivery: 'deal-fulfilment-panel',
  // importActions.js codes
  issueImportRequest: 'deal-fulfilment-panel',
  markIrSent: 'deal-fulfilment-panel',
  markShipping: 'deal-fulfilment-panel',
  markGoodsReceived: 'deal-fulfilment-panel',
  recordDelivery: 'deal-fulfilment-panel',
  // accountActions.js keys (chaseOverdue/confirmDeposit/confirmFinalPayment/confirmCloseReady) are
  // deliberately absent: account works a deal on /finance/deals/:id, so the sticky bar navigates
  // to the resolver's `to` instead of scrolling to a control on this page.
};

// Every jump target above (and the ref-opened CREATE_PCR/CONFIRM_ORDER panels) lives inside a
// conditionally-MOUNTED TabPanel (Tabs.jsx keeps only the active tab's children mounted). A trigger
// OUTSIDE the tabs (the sticky header primary CTA, the "⋯" overflow menu) can be clicked while a
// DIFFERENT tab is active, when the target element/ref doesn't exist and `scrollToSection` or a
// forwardRef call would silently no-op. `runOnTab` (inside the component, where
// `activeTab`/`setActiveTab` live) switches to the owning tab FIRST, then performs the
// jump/ref-call once that tab's panel has mounted.
const JUMP_TARGET_TAB = {
  // DealTrackingPanel lives in the `deal` tab (see ticketDetailTabs.js).
  'deal-tracking-panel': 'deal',
  'deal-fulfilment-panel': 'fulfilment',
};

function scrollToSection(id) {
  const el = typeof document !== 'undefined' ? document.getElementById(id) : null;
  if (!el) return;
  // jsdom (Vitest) doesn't implement scrollIntoView — guarded so the same jump helper used by the sticky primary
  // CTA doesn't throw under test, only under a real browser's absence of it.
  el.scrollIntoView?.({ behavior: 'smooth', block: 'start' });
  el.focus?.({ preventScroll: true });
}

// The "ประวัติการดำเนินการ" events panel is merged with the deal-tracking activity log into
// DealHistoryPanel.jsx (กิจกรรม tab), which owns its own copies of the event constants.

const TERMINAL = ['closed', 'cancelled'];

// docStatusColors lives in DealLegacyQuotations.jsx, its only caller.

export function TicketDetailPage({ user, ticketId, onBack, showToast }) {
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  // Ticket-detail IA rebuild Phase 2: the active tab lives in the URL, not local state — list → detail → back
  // and a shared deep link both keep it, and `{ replace: true }` keeps switching tabs out of the back-button
  // history stack.
  const [searchParams, setSearchParams] = useSearchParams();
  function setActiveTab(tabId) {
    setSearchParams((prev) => {
      const next = new URLSearchParams(prev);
      next.set('tab', tabId);
      return next;
    }, { replace: true });
  }
  // A cross-tab trigger queues its real action here and switches tabs; the effect below runs it once the
  // destination tab's TabPanel has actually mounted — see JUMP_TARGET_TAB's own doc comment for why this can't
  // just scroll/call-ref immediately.
  const pendingTabActionRef = useRef(null);

  // `role`/`sections`/the tab derivation below only need `user`/`searchParams`, so — unlike
  // `isOwner`/`summary`-derived values further down — they can sit before the loading/no-ticket early returns:
  // the pending-tab-action effect that reads `activeTab` cannot itself come after a conditional return.
  const role = user.role;
  // Which sections this viewer's role gets in full vs. hidden entirely (salesViewScope.js;
  // presentation only, never a security boundary). These section ids compose
  // ticketDetailTabs.js's tabs; a role-hidden section's TAB is simply absent from the tablist.
  const sections = visibleSections(role);

  // Ticket-detail IA rebuild Phase 2: `?tab=` is the single source of truth for which tab is open — an absent,
  // unknown, or role-hidden value falls back to `overview` (resolveTicketDetailTab), so a stale deep link (a
  // role change, a typo, hand-editing the URL) never renders a blank or forbidden panel.
  const visibleTabIds = visibleTicketDetailTabIds(role);
  const activeTab = resolveTicketDetailTab(searchParams.get('tab'), role);
  const tabItems = TICKET_DETAIL_TABS
    .filter((tab) => visibleTabIds.includes(tab.id))
    .map((tab) => ({ id: tab.id, label: tab.label, helper: tab.helper }));

  // Runs a cross-tab trigger's real action (a scroll, or a ref-call into a panel in a different
  // tab), switching to that tab first if needed. See JUMP_TARGET_TAB's doc comment: every such
  // panel is conditionally MOUNTED (Tabs.jsx's TabPanel), so calling straight through would no-op
  // on any tab but the target one.
  function runOnTab(tabId, run) {
    if (activeTab === tabId) { run(); return; }
    pendingTabActionRef.current = { tabId, run };
    setActiveTab(tabId);
  }
  // `activeTab` only changes (and this effect only re-fires) once the URL update above has actually committed —
  // by then the destination TabPanel's children (and any ref inside them) have already mounted in the SAME
  // commit, so `pending.run()` here always finds a live target.
  useEffect(() => {
    const pending = pendingTabActionRef.current;
    if (pending && pending.tabId === activeTab) {
      pendingTabActionRef.current = null;
      pending.run();
    }
  }, [activeTab]);

  // The sticky deal chrome (state header + detail tabs) condenses as you scroll down.
  // Driven by TWO 0-size sentinels + IntersectionObserver (effect below) with hysteresis,
  // NOT by reading scroller.scrollTop:
  //   - condensing the header shrinks the page; a scrollTop threshold let that shrink feed
  //     back across the threshold and oscillate — the original "jitter / snaps to top" bug;
  //   - a single sentinel line still flickered on SLOW scroll parked right at the trigger.
  // Two sentinels give a dead-band: condense once scrolled past the LOWER sentinel, expand
  // only when the TOP sentinel is back in view. Slow-scrolling inside the band changes
  // nothing, so the collapse can't flip-flop. Sentinel visibility never depends on the
  // chrome's own height, so there is still no feedback loop. Plus "Gate A" (in the effect):
  // a page only slightly taller than the viewport never condenses at all, because condensing
  // it would shrink it below the condense line and clamp scrollTop back to the top — the
  // remaining slow-scroll indecision. Only genuinely tall pages condense, where it is stable.
  const expandSentinelRef = useRef(null);
  const condenseSentinelRef = useRef(null);
  // Separately, the chrome's measured height is published to `--deal-header-h` (second
  // effect below) so the content wrapper's descendant `scroll-mt-[calc(var(--deal-header-h,18rem) + …)]`
  // offsets focus and scrollIntoView targets below the sticky header (the shell deliberately has
  // no desktop scroll-padding-top — see AppShell.jsx). Measuring is orthogonal to the condense
  // trigger above and does not affect scrollTop/scrollHeight.
  const ticketStickyChromeRef = useRef(null);
  const [ticketChromeCondensed, setTicketChromeCondensed] = useState(false);
  // An open menu in the sticky header holds it as it is: condensing moves the header's buttons,
  // which remounts the menu and closes it. A condense that came while a menu was open is applied
  // when it closes.
  const headerMenuOpenRef = useRef(false);
  const heldCondensedRef = useRef(null);
  const onHeaderMenuOpenChange = useCallback((open) => {
    headerMenuOpenRef.current = open;
    if (!open && heldCondensedRef.current !== null) {
      setTicketChromeCondensed(heldCondensedRef.current);
      heldCondensedRef.current = null;
    }
  }, []);

  // Imperative handle onto DealStagePanel (see its doc comment): the header overflow menu / bottom
  // danger zone trigger its modals from here, but DealStagePanel stays the sole owner of whether
  // each is available and of the modal/mutation itself.
  const dealStagePanelRef = useRef(null);
  // Imperative handle onto PricingRequestPanel: the sticky header's CREATE_PCR primary CTA opens
  // its create modal directly instead of duplicating a second "สร้างคำขอราคา" button (same
  // convention as dealStagePanelRef above).
  const pricingRequestPanelRef = useRef(null);
  // Imperative handle onto DealQuotationPanel: the sticky header's CONFIRM_ORDER primary CTA
  // triggers its confirm directly instead of scrolling to a second copy of the button.
  const dealQuotationPanelRef = useRef(null);
  const fulfilmentPanelRef = useRef(null);

  // Edit-items mode
  const [editMode, setEditMode] = useState(false);
  const [editDraft, setEditDraft] = useState([]);
  const [editNote, setEditNote] = useState('');
  // Catalog autocomplete for the ยี่ห้อ/รุ่น fields in edit mode — same shape as TicketCreateModal's
  // catalogResults/catalogFocus (`{ index, field }`), kept as this page's own state because edit-items and
  // create are two separate mounted trees.
  const [editCatalogResults, setEditCatalogResults] = useState([]);
  const [editCatalogFocus, setEditCatalogFocus] = useState(null);
  // Mirrors TicketCreateModal's onCatalogInput/applyCatalogItem: brand/model typing searches the
  // catalog (debounced), a pick fills the row via the shared applyCatalogPick and clears that row's
  // required-field errors.
  //
  // Real keystroke: mutates the row AND searches. Kept separate from onEditCatalogFieldFocus below:
  // re-focusing an already-filled ยี่ห้อ/รุ่น box (no keystroke) through this mutating path would
  // re-run the brand→factory lockstep and catalog-link CLEAR, so tabbing into and out of a field on
  // a server-loaded row wiped catalogPriceId/catalogProductCode before save (data loss).
  function onEditCatalogInput(index, field, value) {
    setEditDraft((d) => d.map((r, i) => (i === index ? applyDescriptiveFieldEdit(r, field, value) : r)));
    clearFieldError(`editItems.${field}.${index}`);
    setEditCatalogFocus({ index, field });
    searchCatalog(value, setEditCatalogResults);
  }
  // Focus-only: search-only, no row mutation. Safe to call on every focus, including a re-focus of a field the
  // rep never actually retyped.
  function onEditCatalogFieldFocus(index, field, value) {
    setEditCatalogFocus({ index, field });
    if (value) searchCatalog(value, setEditCatalogResults);
  }
  function applyEditCatalogPick(index, cat) {
    setEditDraft((d) => d.map((r, i) => (i === index ? applyCatalogPick(r, cat) : r)));
    Object.keys(REQUIRED_ITEM_FIELD_LABELS).forEach((f) => clearFieldError(`editItems.${f}.${index}`));
    setEditCatalogResults([]);
    setEditCatalogFocus(null);
  }

  // Revision form
  const [showReviseForm, setShowReviseForm] = useState(false);
  const [reviseScope, setReviseScope] = useState('QTY_OR_NOTE');
  const [reviseReason, setReviseReason] = useState('');

  // Inline field-level validation for the payment / delivery modals, the revise form (a modal) and
  // the edit-items rows. One shared dict, keyed per-form/per-row so a stale error can't bleed into
  // another: 'payment.amount' | 'revise.reason' | 'editItems.<field>.<rowIndex>' (per-row,
  // per-field: brand/model/size/qty). DealFulfilmentPanel owns its own delivery error toasts.
  // fieldRefs holds the DOM node for each key so a failed submit can scroll/focus the first invalid
  // control (same shape as TicketCreateModal.jsx, CeoSettingsPage.jsx and DepositNoticePage.jsx).
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
  // Sets exactly one key. Used (instead of setFieldErrorsForPrefix) for the per-item override.<itemId> key:
  // itemIds are numeric, and setFieldErrorsForPrefix's startsWith match would treat 'override.1' as a prefix of
  // 'override.10' — silently wiping out a different item's error.
  function setFieldError(key, message) {
    setFieldErrors((prev) => ({ ...prev, [key]: message }));
  }
  // Replaces every fieldErrors key under `prefix` with `errors` in one go — used both to report a fresh
  // validation failure for one modal (errors non-empty) and to clear that modal's errors on open/close/success
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
  // Delivery/stock-reservation modal state lives in DealFulfilmentPanel.

  // Comment
  const [commentText, setCommentText] = useState('');

  // Confirmation dialogs (state-driven, replaces native browser confirm)
  const [confirm, setConfirm] = useState(null); // { kind: 'deleteAttachment', id, name } | { kind: 'cancelTicket' } | { kind: 'finalPayment' } | null

  // Download busy-state — keyed so each quotation/format pair (and the remaining-invoice download) gets its own
  // disabled+label state instead of one shared flag that would disable every download button at once. Not
  // server-state mutations (no cache to invalidate), so left as local state.
  const [downloadingQuotationKey, setDownloadingQuotationKey] = useState(null); // `${quotationId}-${format}` | null
  const [remainingInvoiceDialogOpen, setRemainingInvoiceDialogOpen] = useState(false);
  const [procurementDialogOpen, setProcurementDialogOpen] = useState(false);

  const ticketQuery = useQuery({
    queryKey: queryKeys.ticketDetail(ticketId),
    queryFn: () => api.tickets.get(ticketId).then((r) => r.ticket),
    enabled: !!ticketId,
  });
  const ticket = ticketQuery.data ?? null;

  // FX rates power the "≈ x บาท" companion figure next to a factory price quoted in a foreign
  // currency (the catalog is EUR + USD only). Plain currency conversion, no markup: a marked-up
  // supplier cost shown next to real quoted prices read as a selling price it never was. The fetch
  // degrades silently (retry: false, no error toast) because it is optional display enrichment (like
  // attachmentsQuery below). A missing rate leaves the original currency alone, never converted at
  // an assumed 1:1.
  // The deal's quotation rows (DEAL_DIRECT + PRICING_REQUEST origin), for the sticky "แก้ใบเสนอราคา"
  // CTA. Same key/fn as DealDocumentRegister so the two share one fetch.
  const dealQuotationsQuery = useQuery({
    queryKey: queryKeys.dealQuotationsByTicket(Number(ticketId)),
    queryFn: () => api.dealQuotations.listForTicket(Number(ticketId)).then((r) => r.items ?? []),
    enabled: Boolean(ticketId) && canViewDealQuotation(user),
  });
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
  // Identity-aware gate, not role-only: the deal's participants (createdById/assignedToId) reach
  // its documents regardless of role, and the ROLE half mirrors TicketAccessPolicy.canViewDocuments
  // (`sales`- AND `import`-scoped to participation).
  //
  // `account` is NOT filtered out: it confirms deposit and final-payment receipts against exactly
  // these files. A non-assignee `import` stays filtered out: AttachType spans
  // SIGNED_QUOTATION/INVOICE, which carry the approved customer price that salesViewScope hides
  // from import and the backend refuses it on three other endpoints. `hr` is route-gated off this
  // page entirely and refused by the backend.
  //
  // Computed here (before the loading/no-ticket early return, where `isOwner` lives) so the
  // attachments query never fires for a viewer the backend would 403, like `activitiesQuery`'s
  // `enabled` gate. A presentation projection of the backend gate, not a new authorization rule.
  const documentsTicketSummary = ticket?.summary ?? null;
  const canViewDocumentsTab = Boolean(documentsTicketSummary) && (
    isOwnerOrKeyer(documentsTicketSummary, user.id)
    || user.id === documentsTicketSummary.assignedToId
    || ROLE_PERMISSIONS.canViewTicketDocuments.includes(role)
  );
  // The pipeline's shape (stages, phases, gates, lost/cancel reason codes), fetched once from GET
  // /api/meta/deal-stages.
  const { catalog: stageCatalog } = useStageCatalog();
  const actionsQuery = useQuery({
    queryKey: queryKeys.ticketActions(ticketId),
    queryFn: () => api.tickets.actions(ticketId),
    enabled: !!ticketId && !!ticket,
    // Keep the previous action list while a refetch is in flight: every doAction invalidates this
    // query, and without a placeholder the cockpit's buttons vanish for a beat on each action.
    placeholderData: (previous) => previous,
  });
  const availableActions = actionsQuery.data?.availableActions ?? [];
  // Per-stage verdicts from TicketService.stageDecisions: which stages this user may move THIS deal
  // to, which need a written reason, and why each refused one is refused.
  const stageDecisions = actionsQuery.data?.stageDecisions ?? [];
  const actionNames = new Set(availableActions.map((action) => action.action));
  const hasAction = (action) => actionNames.has(action);
  // isLoading-only (not isFetching): this gate swaps the ENTIRE page for a skeleton, and
  // isFetching would flash it on every quiet background refetch (window refocus, another tab's
  // invalidate).
  const loading = ticketQuery.isLoading;
  const canViewPricingRequests = ['sales', 'import', 'ceo', 'sales_manager'].includes(user?.role);

  const paymentsQuery = useQuery({
    queryKey: queryKeys.ticketPayments(ticketId),
    queryFn: () => api.tickets.listPayments(ticketId).then((r) => r.items ?? []),
    enabled: !!ticketId && !!ticket,
  });
  const paymentReceipts = paymentsQuery.data ?? [];

  // Delivery history lives in DealFulfilmentPanel's own query.

  // Commit 6: the deal's own pricing requests — read here (not inside PricingRequestPanel) so DealStagePanel's
  // substep strip can also key off the most recent one, without a second fetch of the same list.
  const pricingRequestsQuery = useQuery({
    queryKey: queryKeys.pricingRequestsByTicket(ticketId),
    queryFn: () => api.pricingRequests.listForTicket(ticketId).then((r) => r.items ?? []),
    enabled: canViewPricingRequests && !!ticketId && !!ticket,
  });
  const pricingRequests = canViewPricingRequests ? (pricingRequestsQuery.data ?? []) : [];

  // Deal tracking: visible to the deal owner (sales), sales_manager, ceo (mirrors salesViewScope.js's
  // dealTracking section id and TicketService.requireDealOwnership; import/account have no business
  // in it).
  const canViewDealTracking = ['sales', 'sales_manager', 'ceo'].includes(user?.role);
  const activitiesQuery = useQuery({
    queryKey: queryKeys.ticketActivities(ticketId),
    queryFn: () => api.tickets.listActivities(ticketId).then((r) => r.items ?? []),
    enabled: canViewDealTracking && !!ticketId && !!ticket,
  });
  const activities = canViewDealTracking ? (activitiesQuery.data ?? []) : [];

  // Deal parties (deal-party-model.md): the contractors bidding this deal, its designer and owner.
  // Same audience as the whole-deal read (GET /tickets/{id}): sales, sales_manager, ceo.
  const partiesQuery = useQuery({
    queryKey: queryKeys.ticketParties(ticketId),
    queryFn: () => api.tickets.parties(ticketId).then((r) => r.parties ?? []),
    enabled: canViewDealTracking && !!ticketId && !!ticket,
  });
  const parties = canViewDealTracking ? (partiesQuery.data ?? []) : [];
  const [bidOutcomeOpen, setBidOutcomeOpen] = useState(false);

  // The main button creates the deposit notice / remaining invoice, so the sales rep's page reads
  // both. Same keys and shapes as DealPaymentSection's reads, so the cache is shared (one fetch).
  const readsDocs = role === 'sales' && isOwnerOrKeyer(ticket?.summary, user.id) && !!ticketId;
  const depositNoticesQuery = useQuery({ ...depositNoticesQueryOptions(ticketId), enabled: readsDocs });
  const remainingInvoicesQuery = useQuery({
    queryKey: queryKeys.storedRemainingInvoices(ticketId),
    queryFn: () => api.storedRemainingInvoices.listForTicket(ticketId).then((r) => r.remainingInvoices ?? []),
    enabled: readsDocs,
  });
  // Same key/fn as DealFulfilmentPanel's own read, so the two share one cache entry. Read for every
  // role the backend lets read stored IRs, since the header's การนำเข้า chip follows them on every tab.
  const storedIrQuery = useQuery({
    queryKey: queryKeys.storedImportRequests(ticketId),
    queryFn: () => api.storedImportRequests.listForTicket(ticketId).then((r) => r.importRequests ?? []),
    enabled: !!ticketId && canReadStoredImportRequests(user, ticket?.summary),
  });
  const importRequests = storedIrQuery.data;
  // The stage-10 "จัดซื้อสินค้า" decision: read for the owning rep and the CEO; the server says whether it is pending/editable.
  const procurementSourceQuery = useQuery({
    queryKey: queryKeys.procurementSource(ticketId),
    queryFn: () => api.tickets.getProcurementSource(ticketId),
    enabled: (readsDocs || role === 'ceo') && !!ticketId
      && ticket?.summary?.lifecycle === 'ACTIVE' && ticket?.summary?.fulfillmentStatus == null,
  });
  const procurementSource = procurementSourceQuery.data;
  const depositNotice = pickDepositNotice(depositNoticesQuery.data);
  // undefined until the list is read: nextSalesAction then never offers the invoice.
  const remainingInvoice = remainingInvoicesQuery.data === undefined ? undefined : pickRemainingInvoice(remainingInvoicesQuery.data);
  const createDepositNoticeMutation = useMutation({
    // Reads the list fresh (another tab may have made a draft): an existing DRAFT is only opened, never doubled.
    mutationFn: async () => {
      const docs = await queryClient.fetchQuery({ ...depositNoticesQueryOptions(ticketId), staleTime: 0 });
      return pickDepositNotice(docs)?.status === 'DRAFT' ? null : createDepositNoticeDraft(ticketId);
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: queryKeys.depositNotices(ticketId) });
      queryClient.invalidateQueries({ queryKey: queryKeys.ticketDetail(ticketId) });
      queryClient.invalidateQueries({ queryKey: queryKeys.ticketActions(ticketId) });
      navigate(`/tickets/${ticketId}/deposit`);
    },
    onError: (err) => showToast('error', err.message || 'สร้างเอกสารฉบับร่างไม่สำเร็จ'),
  });

  useEffect(() => {
    if (ticketQuery.error) showToast('error', ticketQuery.error.message || 'โหลดข้อมูลไม่สำเร็จ');
  }, [ticketQuery.error, showToast]);

  const attachmentsQuery = useQuery({
    queryKey: queryKeys.ticketAttachments(ticketId),
    queryFn: () => api.attachments.list(ticketId).then((r) => r.attachments ?? []),
    // Never fire this request for a viewer AttachmentController would 403: see
    // `canViewDocumentsTab` above.
    enabled: !!ticketId && canViewDocumentsTab,
  });
  const attachments = attachmentsQuery.data ?? [];
  // Same isLoading-only reasoning as `loading` above — a re-upload/delete shouldn't flash the attachment list
  // back to its skeleton rows.
  const attachLoading = attachmentsQuery.isLoading;
  // Attachment load failures were silently swallowed before ("non-critical") — preserved as-is, no error toast
  // wired up here.

  // No factoryConfigs fetch: the ticket-native propose-price flow is gone.

  // Shared post-mutation side effects: the ticket-detail fast path (the backend returns the full
  // ticket, so no refetch) plus refreshing list/dashboard/notifications, which would otherwise go
  // stale after every action.
  function applyTicketUpdate(updatedTicket) {
    if (updatedTicket) {
      queryClient.setQueryData(queryKeys.ticketDetail(ticketId), updatedTicket);
    } else {
      // The re-pointed money calls (recordPayment / confirmFinalPayment / confirmCloseReady) answer with the finance
      // view `{ deal }`, which carries no ticket — writing `undefined` into the cache would blank the page, so
      // re-read the ticket instead.
      queryClient.invalidateQueries({ queryKey: queryKeys.ticketDetail(ticketId) });
    }
    queryClient.invalidateQueries({ queryKey: queryKeys.ticketActions(ticketId) });
    queryClient.invalidateQueries({ queryKey: queryKeys.ticketPayments(ticketId) });
    queryClient.invalidateQueries({ queryKey: queryKeys.ticketDeliveries(ticketId) });
    queryClient.invalidateQueries({ queryKey: ['tickets', 'list'] });
    // The ceo can open the same deal on /finance/deals/:id; its cached finance view is money state too.
    queryClient.invalidateQueries({ queryKey: ['finance'] });
    queryClient.invalidateQueries({ queryKey: queryKeys.dashboardSummary() });
    queryClient.invalidateQueries({ queryKey: queryKeys.notifications() });
  }

  // The UI-draft reset every successful action runs.
  function resetActionDrafts() {
    // A successful action closes every modal below (payment among them), so clear every field
    // error with them: a stale one must not greet the user on the next open.
    setFieldErrors({});
    setEditMode(false);
    setEditDraft([]);
    setEditNote('');
    setShowReviseForm(false);
    setReviseReason('');
    setPaymentModal(false);
    setCommentText('');
  }

  // Generic action mutation: mutationFn/onSuccess receive the (fn, successMsg) pair as variables,
  // so every action call site below shares it.
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

  // doAction swallows its own rejection (mutateAsync always rejects, even after onError runs) so
  // call sites can fire it without a try/catch.
  async function doAction(fn, successMsg) {
    try {
      await actionMutation.mutateAsync({ fn, successMsg });
    } catch { /* onError above already toasted */ }
  }

  // "ยืนยันคำสั่งซื้อ" for an APPROVED direct quotation, from the sticky CTA. The same endpoint (and
  // confirm copy) as the quotation editor's own action (hrApi's dealQuotations.promoteToDeal). Not
  // routed through actionMutation: that expects a `{ ticket }` response, this endpoint answers
  // `{ result }`. Every refusal surfaces the server's own Thai message and re-reads the deal.
  const confirmOrderDirectMutation = useMutation({
    mutationFn: (quotationId) => api.dealQuotations.promoteToDeal(quotationId),
    onSuccess: () => {
      setConfirm(null);
      showToast('success', 'ยืนยันคำสั่งซื้อแล้ว');
      queryClient.invalidateQueries({ queryKey: ['tickets'] });
      queryClient.invalidateQueries({ queryKey: ['dealQuotations'] });
    },
    onError: (error) => {
      setConfirm(null);
      showToast('error', error.message || 'ยืนยันคำสั่งซื้อไม่สำเร็จ');
      queryClient.invalidateQueries({ queryKey: ['tickets'] });
    },
  });

  // Sticky "แก้ใบเสนอราคา": mint a revision of the target quotation and open it. The server
  // (DealQuotationService#createRevision) re-checks owner + status and refuses a second open
  // revision (409); its Thai message is surfaced as-is.
  const reviseQuotationMutation = useMutation({
    mutationFn: (quotationId) => api.dealQuotations.createRevision(quotationId),
    onSuccess: (res) => {
      queryClient.invalidateQueries({ queryKey: ['dealQuotations'] });
      queryClient.invalidateQueries({ queryKey: ['tickets'] });
      navigate(`/quotations/${res.quotation.id}`);
    },
    onError: (error) => {
      showToast('error', error.message || 'สร้างฉบับแก้ไขไม่สำเร็จ');
      queryClient.invalidateQueries({ queryKey: ['dealQuotations'] });
    },
  });

  // "เสนอใบราคา<party>": write the new-engine quotation from the approved request and open it — the
  // same call PricingRequestDetailPage makes. The server hands back the request's open draft instead
  // of minting a second one (DealQuotationService#createFromPricingRequest). The create moves the
  // deal's stage and lists, so those are re-read; the navigation belongs to the click (per-call
  // callbacks), so a rep who has already left the deal is not pulled back.
  const createQuotationFromRequestMutation = useMutation({
    mutationFn: (pricingRequestId) => api.dealQuotations.createFromPricingRequest(pricingRequestId),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['dealQuotations'] });
      queryClient.invalidateQueries({ queryKey: queryKeys.ticketDetail(ticketId) });
      queryClient.invalidateQueries({ queryKey: queryKeys.ticketActions(ticketId) });
      queryClient.invalidateQueries({ queryKey: queryKeys.pricingRequestsByTicket(ticketId) });
    },
    // A refusal (409) is the way to the request page (review V12), so it reads as information.
    onError: (error) => showToast(error?.status === 409 ? 'info' : 'error', error.message || 'ดำเนินการไม่สำเร็จ'),
  });
  function createQuotationFromRequest(pricingRequestId) {
    createQuotationFromRequestMutation.mutate(pricingRequestId, {
      onSuccess: (res) => { if (res?.quotation?.id) navigate(`/quotations/${res.quotation.id}`); },
      // A refusal (legacy price mode, an old-engine quotation, …): both engines' controls are on
      // the request page.
      onError: (error) => { if (error?.status === 409) navigate(`/pricing-requests/${pricingRequestId}`); },
    });
  }

  // Attachments upload/delete — invalidate the attachments query instead of manually reloading + setting local
  // array state.
  const uploadAttachmentMutation = useMutation({
    mutationFn: ({ file, attachType }) => api.attachments.upload(ticketId, file, attachType),
    onSuccess: (_res, { file }) => {
      queryClient.invalidateQueries({ queryKey: queryKeys.ticketAttachments(ticketId) });
      // An INVOICE upload unlocks ฝ่ายบัญชี's close confirmation, so the action list has to be re-read — otherwise
      // the button only appears after the user navigates away and back.
      queryClient.invalidateQueries({ queryKey: queryKeys.ticketActions(ticketId) });
      // Use queryKeys.ticketDetail (there is no bare `queryKeys.ticket`): a throw here, after the
      // two invalidations, would abort before `showToast`. react-query does not route an onSuccess
      // throw to onError and handleUploadAttachment's `catch {}` swallows the rejection, so a
      // successful upload would report nothing and the ticket detail (whose summary drives the
      // invoiceOnFile close gate) would never refetch.
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
      // Same `queryKeys.ticket` TypeError as the upload handler above — see its comment. Deleting a file silently
      // reported nothing either.
      queryClient.invalidateQueries({ queryKey: queryKeys.ticketDetail(ticketId) });
      showToast('success', 'ลบไฟล์แล้ว');
    },
    onError: (err) => showToast('error', err.message || 'ลบไม่สำเร็จ'),
  });
  const deletingAttachment = deleteAttachmentMutation.isPending;

  // Deal tracking: full-replace PUT; the response is the full ticket detail (same
  // applyTicketUpdate fast path as actionMutation above).
  const updateTrackingMutation = useMutation({
    mutationFn: (payload) => api.tickets.updateTracking(ticketId, payload),
    onSuccess: (response) => {
      applyTicketUpdate(response.ticket);
      showToast('success', 'บันทึกข้อมูลติดตามดีลแล้ว');
    },
    onError: (err) => showToast('error', err.message || 'บันทึกไม่สำเร็จ'),
  });

  // addActivity returns the bare DealActivityDto (not a ticket), so it invalidates rather than using
  // applyTicketUpdate's setQueryData fast path: the ticket detail needs a refetch because `stale`
  // and the stage-advance readiness depend on the activity just logged.
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

  // Manual refresh: refreshes both the ticket AND its attachments.
  function refreshTicket() {
    queryClient.invalidateQueries({ queryKey: queryKeys.ticketDetail(ticketId) });
    queryClient.invalidateQueries({ queryKey: queryKeys.ticketAttachments(ticketId) });
    queryClient.invalidateQueries({ queryKey: queryKeys.procurementSource(ticketId) });
  }

  // Condense the sticky chrome with hysteresis — see the ticketChromeCondensed declaration
  // for WHY two sentinels (dead-band, no scrollTop, no feedback loop, no slow-scroll flicker).
  useEffect(() => {
    if (loading) return undefined;
    const expandAt = expandSentinelRef.current;
    const condenseAt = condenseSentinelRef.current;
    if (!expandAt || !condenseAt || typeof IntersectionObserver === 'undefined') return undefined;
    const scroller = expandAt.closest('.content-scroll');
    const mobileQuery = typeof window !== 'undefined' && typeof window.matchMedia === 'function'
      ? window.matchMedia('(max-width: 720px)')
      : null;
    // Mobile renders the chrome static (never sticky, never condensed) — keep it expanded.
    // Gate A: only condense when the page overflows by MORE than the (expanded) chrome's own
    // height. Condensing shrinks the chrome — and therefore the page — by at most that height;
    // requiring the scroll overflow to exceed it guarantees the page stays scrolled past the
    // condense line afterwards, so scrollTop can't clamp back to the top and re-expand. That
    // clamp is what made a short/medium page (little taller than the viewport) flip "condense /
    // don't condense" on slow scroll and fight scrolling down. A page that short simply never
    // condenses — its full header is fine, there is barely anything to scroll. When the scroll
    // box can't be measured (no .content-scroll ancestor, e.g. unit tests), fall back to
    // condensing so the sentinel wiring stays observable.
    const hasRoomToCondense = () => {
      if (!scroller || scroller.clientHeight === 0) return true;
      const chromeHeight = ticketStickyChromeRef.current?.getBoundingClientRect().height ?? 0;
      return scroller.scrollHeight - scroller.clientHeight > chromeHeight + 80;
    };
    // While a header menu is open the change is held, and applied when it closes (review V10).
    const setCondensed = (value) => {
      if (headerMenuOpenRef.current) heldCondensedRef.current = value;
      else setTicketChromeCondensed(value);
    };
    const onIntersect = (entries) => {
      if (mobileQuery?.matches) { setTicketChromeCondensed(false); return; }
      for (const entry of entries) {
        // Back at the very top → expand. Scrolled past the lower sentinel → condense, but only
        // when there is room (Gate A). Anything in the band between the two sentinels leaves the
        // state unchanged (the dead-band / hysteresis).
        if (entry.target === expandAt && entry.isIntersecting) setCondensed(false);
        else if (entry.target === condenseAt && !entry.isIntersecting && hasRoomToCondense()) {
          setCondensed(true);
        }
      }
    };
    const onBreakpoint = () => { if (mobileQuery?.matches) setTicketChromeCondensed(false); };
    // The IntersectionObserver fires only as a sentinel crosses the scroll viewport; it does NOT
    // re-fire when content below grows/shrinks or the viewport resizes while the sentinels stay on
    // the same side, which can leave the condensed state stale (a page too short to condense that
    // grows tall stays expanded; a condensed page that shrinks stays condensed). Re-derive the
    // state synchronously from the sentinels' current positions on those events (same dead-band as
    // onIntersect). Needs real geometry, so it no-ops where the scroll box can't be measured (unit
    // tests); there the IO path drives.
    const reevaluate = () => {
      if (mobileQuery?.matches) { setTicketChromeCondensed(false); return; }
      if (!scroller || scroller.clientHeight === 0) return;
      if (!hasRoomToCondense()) { setCondensed(false); return; }
      const viewTop = scroller.getBoundingClientRect().top;
      if (expandAt.getBoundingClientRect().bottom >= viewTop) setCondensed(false);
      else if (condenseAt.getBoundingClientRect().bottom < viewTop) setCondensed(true);
      // else: between the two sentinels (the dead-band) — leave the state unchanged.
    };
    const observer = new IntersectionObserver(onIntersect, { root: scroller ?? null, threshold: 0 });
    observer.observe(expandAt);
    observer.observe(condenseAt);
    mobileQuery?.addEventListener?.('change', onBreakpoint);
    // Watch content growth (the content root's box) and viewport resize (the scroll box) so the
    // re-check above actually runs on those changes.
    const contentRoot = expandAt.parentElement;
    let resize;
    if (typeof ResizeObserver !== 'undefined') {
      resize = new ResizeObserver(reevaluate);
      if (contentRoot) resize.observe(contentRoot);
      if (scroller) resize.observe(scroller);
    }
    return () => {
      observer.disconnect();
      resize?.disconnect();
      mobileQuery?.removeEventListener?.('change', onBreakpoint);
    };
  }, [loading, ticketId]);

  // Publish the chrome's measured height to `--deal-header-h` so the content wrapper's
  // descendant `scroll-mt-[calc(var(--deal-header-h,18rem) + …)]` offsets focus and
  // scrollIntoView targets below the sticky header. This re-measures when the header
  // condenses/expands (ResizeObserver), which is fine: writing a scroll-margin custom property
  // never moves scrollTop/scrollHeight, so it cannot feed the condense loop. The write is coalesced per frame and skipped when the
  // value is unchanged (see below).
  useLayoutEffect(() => {
    if (loading) return undefined;
    const chrome = ticketStickyChromeRef.current;
    if (!chrome || typeof document === 'undefined') return undefined;
    let lastWritten = null;
    const writeHeight = () => {
      const height = Math.ceil(chrome.getBoundingClientRect().height);
      // Skip the write when the rounded height is unchanged: the condense/expand transition fires
      // the ResizeObserver on every intermediate frame, but most frames land on a value already
      // written.
      if (height > 0 && height !== lastWritten) {
        lastWritten = height;
        document.documentElement.style.setProperty('--deal-header-h', `${height}px`);
      }
    };
    // Coalesce the ResizeObserver burst during that animation to at most one measure+write per
    // frame instead of one per observer callback.
    const hasRaf = typeof requestAnimationFrame === 'function';
    let frame = 0;
    const schedule = hasRaf
      ? () => { if (!frame) frame = requestAnimationFrame(() => { frame = 0; writeHeight(); }); }
      : writeHeight;

    writeHeight();
    if (typeof ResizeObserver === 'undefined') {
      return () => document.documentElement.style.removeProperty('--deal-header-h');
    }

    const observer = new ResizeObserver(schedule);
    observer.observe(chrome);
    return () => {
      observer.disconnect();
      if (frame && hasRaf) cancelAnimationFrame(frame);
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
        {/* bordered/px-5 py-4 (vs. the legacy 16px-inline/0-vertical .table-panel > .panel-header)
            is a small deliberate shift, as in the CommissionPage port; Panel.Header's doc comment
            calls the old borderless header a defect. */}
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
  // The billing form is the only writer of lastFollowUpAt, so ติดตามล่าสุด is the later of it and the newest activity.
  const latestFollowUp = [summary.lastFollowUpAt, ...activities.map((a) => a.activityDate)].filter(Boolean).sort().pop() ?? null;
  // While sales.ticket_item is still empty (pre-order-confirmation), the items grid falls back to
  // the deal's live pricing-request/quotation line items instead of "0 รายการ" (see
  // TicketService#get for the pick rule). Those rows are NOT ticket_item, so they render
  // read-only: no edit affordance, no add-item control.
  const fromPricingChain = ticket.fromPricingChain ?? false;
  // Nothing on this page reads `quotationOnly`: a quotation-first deal is an ordinary deal
  // (IA §7, "Nothing hidden").
  const st = summary.status;
  const isOwner = isOwnerOrKeyer(summary, user.id);
  // Reading a deal's documents is the same question as reading the deal (account/import reach the
  // เอกสาร panel, hr does not), but WRITING stays narrow: TicketAccessPolicy.canManageDocuments is
  // participant OR sales_manager/ceo. Mirrored here so the panel does not offer account/import an
  // upload the backend answers with a 403.
  const canManageDocuments = isOwner
    || (summary.assignedToId != null && user.id === summary.assignedToId)
    || ROLE_PERMISSIONS.canManageTicketDocuments.includes(role);

  const showProposed = ROLE_PERMISSIONS.canProposePrices.includes(role) || ROLE_PERMISSIONS.canApproveReject.includes(role);
  // ข้อ 10.1: Import sees only rawPrice + proposedPrice — NOT approvedPrice or CEO-set prices
  const showApproved = ROLE_PERMISSIONS.canApproveReject.includes(role) || ROLE_PERMISSIONS.canCreateTickets.includes(role);
  const showCalcBreakdown = ROLE_PERMISSIONS.canApproveReject.includes(role) && items.some((it) => it.calcedCost != null);
  // There is no standalone ราคาตั้ง column (see the fxRatesQuery comment above): three fixed columns
  // (ยี่ห้อ/รุ่น · สี/เนื้อผิว · จำนวน) are followed by whichever price columns the role may see.
  const itemsGridCols = showCalcBreakdown
    ? 'minmax(0,1.4fr) minmax(0,1fr) minmax(0,0.5fr) minmax(0,0.9fr) minmax(0,0.9fr) minmax(0,0.9fr)'
    : showProposed
      ? 'minmax(0,1.8fr) minmax(0,1.2fr) minmax(0,0.6fr) minmax(0,1.1fr) minmax(0,1.1fr)'
      : 'minmax(0,1.8fr) minmax(0,1.2fr) minmax(0,0.6fr) minmax(0,1.1fr)';

  const ps = summary.paymentStatus;
  const fs = summary.fulfillmentStatus;
  const isSales   = ROLE_PERMISSIONS.canCreateTickets.includes(role);
  // Recording ANY payment (confirmFinalPayment/recordPayment alike) is account ONLY, no CEO
  // fallback; mirrors TicketService's PAYMENT_RECORD_ROLES. canConfirmPayments still includes ceo
  // and backs /finance visibility, so it is deliberately NOT used for the flags below.
  const isMoneyRecorder = role === 'account';
  // The close gate is the server's three-party sequence, surfaced via availableActions.
  // DealStagePanel has no "ส่งมอบ x/y" badge: it would duplicate DealFulfilmentPanel's aggregate +
  // progress bar, which computes the same totals from this same `items` prop.

  // Documents that already exist stay reachable from the deal-stage panel
  // through the later stages: the latest quotation file. The deposit-notice
  // view/issue affordance moved into DealPaymentSection (การเงิน tab).
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

  // Who the deal's pricing request is currently assigned to, for the ภาพรวม tab's "ผู้เกี่ยวข้อง"
  // section. `canViewPricingRequests` is the role-scoped readout: a viewer without pricing-request
  // visibility gets 'ไม่แสดงในมุมมองนี้' rather than a peek at who owns it.
  const pricingSummary = activePricingRequestsSummary(pricingRequests);
  const latestPr = pricingSummary ? pricingSummary.requests[pricingSummary.requests.length - 1] : null;
  const assignedImport = canViewPricingRequests
    ? latestPr
      ? latestPr.assignedImportName || 'ยังไม่มีผู้รับเรื่อง'
      : 'ยังไม่มีคำขอราคา'
    : 'ไม่แสดงในมุมมองนี้';

  // 'draft' included since V50: a lightweight lead-stage deal gets its product items here, then submits into the
  // price-request flow when it reaches the quote stages.
  const EDITABLE_STATUSES = ['draft', 'submitted', 'in_review', 'price_proposed'];
  const can = {
    // Pricing runs entirely on the PricingRequest chain (PricingRequestPanel below the items table
    // + DealQuotationPanel for the customer-facing quotation tail + order-confirm). The
    // deposit-notice and deposit-payment affordances live in DealPaymentSection, and the
    // fulfilment chain (issueImportRequest/markIrSent/markShipping/markGoodsReceived/reserveStock/
    // recordDelivery/completeDelivery) in DealFulfilmentPanel, which owns its own mutations against
    // the same hrApi methods rather than this page's shared actionMutation.
    // Not once the order is confirmed (ps set, ขั้น 10+): no new pricing round can start on the deal.
    revise:            (st === 'approved' || st === 'quotation_issued' || st === 'document_issued') && ps == null && ROLE_PERMISSIONS.canCreateTickets.includes(role) && isOwner,
    // Three-party close: ฝ่ายบัญชี confirms, then the CEO verifies. Sales is not part of the
    // sequence, so there is no owner/canCreateTickets gate: the server decides and we mirror its
    // availableActions.
    confirmClose:       hasAction('CONFIRM_CLOSE'),
    revokeCloseConfirm: hasAction('REVOKE_CLOSE_CONFIRM'),
    verifyClose:        hasAction('VERIFY_CLOSE'),
    cancel:           hasAction('CANCEL') && !TERMINAL.includes(st)   && isOwner,
    comment:          !TERMINAL.includes(st),
    // A fallback (fromPricingChain) grid shows borrowed quotation lines, not real ticket_item rows,
    // so there is nothing for editItems to save: the affordance (and the add-item control inside
    // edit mode) is hidden rather than left to fail server-side.
    editItems: hasAction('EDIT_ITEMS') && EDITABLE_STATUSES.includes(st) && ROLE_PERMISSIONS.canCreateTickets.includes(role) && isOwner && !fromPricingChain,
    // Dual-track (ข้อ 13)
    confirmCustomer:    hasAction('CONFIRM_CUSTOMER') && st === 'quotation_issued' && ps == null && isSales, // not at CUSTOMER_CONFIRMED: a re-confirm moves nothing and would hide สร้างใบแจ้งยอดเงินรับมัดจำ (2026-10-28)
    confirmFinalPayment:hasAction('FINAL_PAYMENT') && st === 'quotation_issued' && isMoneyRecorder,
    recordPayment:      hasAction('RECORD_PAYMENT') && isMoneyRecorder,
    downloadRemainingInvoice: isRemainingInvoiceReady({ status: st, fulfillmentStatus: fs }) && isSales,
  };

  // No tab is per-instance gated: `tabItems`/`activeTab` are role-level only and used directly,
  // unfiltered. The attachments panel's identity check (`canViewDocumentsTab`) is an inner render
  // condition inside the always-visible `history` tab (see its TabPanel below).
  const visibleTabItems = tabItems;
  const visibleActiveTab = activeTab;

  // `can.editItems` lives in the items table's panel header (it's an items action) and
  // `can.revokeCloseConfirm` in the money tab (a close-lifecycle action), each rendered at its own
  // site rather than behind one shared `hasActions` flag. The empty-draft hint travels with
  // `can.editItems`.

  // Next-action summary for the current viewer only, derived strictly from the `can` flags above
  // (which encode real status+role permission checks), so the wording matches what the button does
  // (see api.tickets.* in src/api/mockApi.js). Never invents an owner or action the data can't
  // support. 'revise' is deliberately NOT in this cascade: it is an OPTIONAL action, not a blocker,
  // so it must not compete for the one sticky primary CTA slot; it stays reachable from the header
  // overflow menu.
  const NEXT_ACTION_STEPS = [
    // Dual-track steps: the customer quotation lives in DealQuotationPanel, the deposit-notice /
    // deposit-payment steps in DealPaymentSection, and the fulfilment chain in DealFulfilmentPanel,
    // so this list covers only the operational chain this page's own primaryAction still drives.
    ['confirmCustomer',  'ยืนยันว่าลูกค้าตกลงคำสั่งซื้อแล้ว'],
    ['confirmFinalPayment','ยืนยันว่าลูกค้าชำระส่วนที่เหลือครบแล้ว'],
    ['confirmClose',      'ส่งมอบและรับเงินครบแล้ว — ยืนยันเพื่อส่งให้ CEO ตรวจสอบปิดงาน'],
    ['verifyClose',       'ฝ่ายบัญชียืนยันแล้ว — ตรวจสอบและปิดงานได้เลย'],
  ];
  const nextAction = NEXT_ACTION_STEPS.find(([key, text]) => can[key] && text)?.[1] ?? null;

  // The blocker line (region 7 of the IA — "unmet precondition"): why the deal isn't moving even though it might
  // not be this viewer's turn to act. Kept to the fact only (design law: brief — who/what, not
  // what-to-do-about-it-by-when).
  const closeConfirmedAt = summary?.closeConfirmedAt ?? null;
  // !isMoneyRecorder on the two payment-wait lines: account is the ONLY role that CLEARS these
  // waits (confirmDeposit/confirmFinalPayment, see nextAccountAction in accountActions.js), so for
  // account the blocker would read "รอชำระมัดจำ" beside its own "ยืนยันรับมัดจำ" primary CTA, stating
  // the same fact twice. The CEO is not a money recorder either (isAccount/canConfirmPayments still
  // includes ceo for SET_BILLING), so the CEO must see the same read-only status as sales/import.
  // The CEO can only verify an ACTIVE deal (canVerifyClose), so on any other lifecycle (COMPLETED
  // after the verify, or cancelled/lost/paused) there is no CEO step left to wait for.
  const blocker = closeConfirmedAt && !can.verifyClose && (summary?.lifecycle ?? 'ACTIVE') === 'ACTIVE'
    ? 'รอ CEO ตรวจสอบปิดงาน'
    : ps === 'DEPOSIT_NOTICE_ISSUED' && !isMoneyRecorder ? 'รอชำระมัดจำ'
      : ps === 'AWAITING_FINAL_PAYMENT' && !isMoneyRecorder ? 'รอชำระส่วนที่เหลือ'
        : null;

  // The cockpit's primary action: the ONE workflow button for this viewer's current sub-step. The
  // customer-quotation actions live in DealQuotationPanel, the deposit-notice/deposit-payment
  // actions in DealPaymentSection, and the fulfilment chain in DealFulfilmentPanel, each with its
  // own primary buttons, so this page's primaryAction does not drive them.
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
      onClick={() => setConfirm({ kind: 'verifyClose' })}>
      <Icon name="check" size={14} />
      ตรวจสอบและปิดงาน
    </Button>
  ) : null;

  // Sticky action bar. `primaryAction` above (confirmCustomer/finalPayment/close/verify) is a REAL,
  // server-gated button and always wins. workState.js's resolveWorkState fills the gap for every
  // earlier cascade step (CREATE_PCR/ISSUE_QUOTATION/CONFIRM_ORDER/FOLLOW_UP/LOG_ACTIVITY for
  // sales; the fulfilment chain for import; deposit/final-payment/close-ready for account) that
  // this page has no `can.*` button for, as a "jump to the real control" convenience (in-page
  // scroll, or a real route when the control lives elsewhere), never a duplicate of the mutation.
  //
  // CREATE_PCR/ISSUE_QUOTATION/CONFIRM_ORDER are the exceptions to "jump, don't duplicate": a
  // panel that also rendered its own same-label button would show it twice at once (a sticky bar
  // never scrolls away). CREATE_PCR/CONFIRM_ORDER trigger their owning panel via a forwardRef
  // (pricingRequestPanelRef / dealQuotationPanelRef); ISSUE_QUOTATION writes the quotation right
  // here. No panel renders its own trigger: the sticky bar is the only copy of each label.
  // The quotation "แก้ใบเสนอราคา" would revise: the newest row this viewer may revise per
  // quotationMeta.canReviseDealQuotation (the single rule; owner + status live there), plus any
  // open revision draft hanging off it. Same query key as DealDocumentRegister, so one fetch.
  const dealQuotationRows = dealQuotationsQuery.data ?? [];
  const confirmedRow = dealQuotationRows.find((q) => q.id === confirm?.quotationId);
  // What confirming from `quotationId` cancels (DealQuotationRepository#cancelOtherOpenDirectQuotations).
  const openDirectRowsOtherThan = (quotationId) => dealQuotationRows.filter(
    (q) => q.origin === 'DEAL_DIRECT' && q.id !== quotationId && (q.docStatus === 'DRAFT' || q.docStatus === 'PENDING_APPROVAL'),
  );
  const reviseCandidate = dealQuotationRows
    .filter((q) => canReviseDealQuotation(user, q, orderConfirmationOf(ticket?.events)))
    // The server refuses a revise on a SUPERSEDED/CANCELLED request's quotation (409).
    .filter((q) => !DEAD_PRICING_REQUEST_STATUSES.has(pricingRequests.find((pr) => pr.id === q.pricingRequestId)?.status))
    .reduce((best, q) => (best == null || q.id > best.id ? q : best), null);
  const openRevisionDraft = reviseCandidate
    ? dealQuotationRows.find((q) => q.docStatus === 'DRAFT' && q.parentQuotationId === reviseCandidate.id)
    : null;
  const reviseTarget = reviseCandidate
    ? { quotationId: reviseCandidate.id, number: reviseCandidate.number, openDraftId: openRevisionDraft?.id }
    : null;
  // For the sales route choice, the redo and the approval wait. Empty while the list loads:
  // requests then go by status.
  const quotationByRequest = newestQuotationByRequest(dealQuotationRows);
  // The deal's served route (catalog.routes for its entryChannel); null until the catalog loads.
  // Review V5: until the quotation list is read, a sent designer quotation may be being redone —
  // treated like an unknown route (no route choice; ⋯ still offers its outcome).
  // (Read once is enough: a failed refetch keeps the rows it read.)
  const offRouteParties = dealQuotationsQuery.data !== undefined ? offRoutePartiesFor(stageCatalog, summary.entryChannel) : null;
  const workState = resolveWorkState(user, summary, pricingRequests, { reviseTarget, offRouteParties, quotationByRequest, depositNotice, remainingInvoice, importRequests, items, procurementSource });
  // At the owner/buyer quote stages revise is an ADDITIONAL action (header "⋯" menu), never a
  // replacement for the primary บันทึกผลใบเสนอราคา: the client may simply accept the original.
  // The summary serves only the newest live direct quotation; an APPROVED one beside a newer owner/buyer
  // DRAFT/PENDING is read off the deal's rows so ⋯ can still confirm it (only the owner may; the confirm needs 'draft').
  const approvedDirect = isOwner && (summary.status ?? 'draft') === 'draft'
    ? dealQuotationRows.filter((q) => q.origin === 'DEAL_DIRECT' && q.docStatus === 'APPROVED')
      .reduce((newest, q) => (newest == null || q.id > newest.id ? q : newest), null)
    : null;
  const secondaryActions = secondaryWorkActions(user, summary, pricingRequests, {
    reviseTarget, offRouteParties, quotationByRequest, depositNotice, remainingInvoice, importRequests, items, procurementSource,
    approvedDirect: approvedDirect && { id: approvedDirect.id, number: approvedDirect.number }, primary: workState.action,
  });
  const outcomeSecondary = secondaryActions.find((action) => action.key === 'record_quotation_outcome') ?? null;
  const reviseSecondary = secondaryActions.find((action) => action.key === 'revise_quotation') ?? null;
  const confirmDirectSecondary = secondaryActions.find((action) => action.key === 'confirm_order_direct') ?? null;
  // The backend refuses a non-owner for both documents: no main button for them.
  const OWNER_ONLY_ACTIONS = ['create_deposit_notice', 'create_remaining_invoice', 'choose_procurement_source', 'create_import_request'];
  // Like the quotation list above: with the deal's request list unread, a DRAFT may be hiding, so no create_pcr.
  const requestListUnread = canViewPricingRequests && pricingRequestsQuery.data === undefined;
  const workStateAction = (OWNER_ONLY_ACTIONS.includes(workState.action?.key) && !isOwner)
    || (workState.action?.key === 'create_pcr' && requestListUnread) ? null : workState.action;
  const lost = summary.lifecycle === 'CLOSED_LOST';
  const isActiveLifecycle = (summary.lifecycle ?? 'ACTIVE') === 'ACTIVE';
  // ถัดไป names where the main button leads: ขั้น 10 for confirm-order, else the stage of the newest live
  // request's recipient when that is ahead of the deal (e.g. a buyer request opened by the skip).
  const newestLiveRequest = pricingRequests.filter((pr) => !DEAD_PRICING_REQUEST_STATUSES.has(pr.status))
    .reduce((newest, pr) => (newest == null || pr.id > newest.id ? pr : newest), null);
  const requestStage = stageForQuotationRecipient(newestLiveRequest?.recipientType);
  // The request's stage must also be on THIS deal's route (the server's verdict), else it is not where the deal goes.
  const requestStageOnRoute = stageDecisions.find((decision) => decision.stage === requestStage)?.onRoute !== false;
  const requestStageAhead = requestStage != null && requestStageOnRoute
    && stageIndexIn(stageCatalog, requestStage) > stageIndexIn(stageCatalog, summary.salesStage);
  const confirmOrderCta = ['confirm_order', 'confirm_order_direct'].includes(workStateAction?.key);
  const ctaStage = confirmOrderCta ? 'ORDER_RECEIVED'
    : requestStageAhead ? requestStage : null;
  // Mirrors DealStagePanel's own `next`/`canAdvance`, PLUS an explicit `isActiveLifecycle` guard:
  // DealStagePanel got it "for free" from which JSX branch rendered its inline "เลื่อนไป" button
  // (only the plain-ACTIVE branch, never ON_HOLD/DORMANT/lost, which render their own recovery
  // actions), the same implicit gate made explicit for canEditStage/canHoldDeal/canDormantDeal
  // above.
  //
  // Not this file's single-arg `hasAction(action)`: DealStagePanel's hasAction takes a second
  // `targetStage` argument and requires an exact match, so an ADVANCE_STAGE entry for some OTHER
  // target stage would false-positive through the single-arg version. Checked directly against
  // `availableActions` so it stays identical to DealStagePanel's gate.
  const next = isActiveLifecycle && !lost ? nextOnRoute(stageCatalog, summary.salesStage, stageDecisions) : null;
  const hasAdvanceStageAction = Boolean(next) && availableActions.some(
    (item) => item.action === 'ADVANCE_STAGE' && item.targetStage === next.code,
  );
  // hasAdvanceStageAction IS the server's verdict for this exact target (TicketService
  // .addStageActions derives ADVANCE_STAGE from stageDecisions, so it reflects the fact gates too).
  const canAdvance = Boolean(next) && !next.auto && hasAdvanceStageAction;
  // The one block the rep clears themselves (follow-up date + an activity since the last move),
  // read off the server's own reason, as DealStagePanel's `readinessBlocked` does.
  const nextDecision = next ? stageDecisions.find((decision) => decision.stage === next.code) : null;
  const advanceAwaitsReadiness = Boolean(next) && !next.auto && nextDecision?.blockedReason === STAGE_ADVANCE_GATE_MESSAGE;
  let advanceInHeader = false;
  let stickyPrimaryLabel = nextAction;
  let stickyPrimaryAction = primaryAction;
  // The เสนอราคา<party> line, minus a skip this viewer cannot take (set below).
  let quoteBannerText = workState.bannerText;
  // "บันทึกผลประมูล" (deal-party-model.md): offered to the deal's editors (owner/keyer sales,
  // sales_manager, ceo — DealPartyService re-checks) on an ACTIVE deal with at least one contractor
  // still bidding. It outranks the quote-stage actions below, never the money/close steps.
  const canEditParties = (role === 'sales' && isOwner) || role === 'sales_manager' || role === 'ceo';
  const bidOutcomeAvailable = canEditParties && isActiveLifecycle && parties.some(isBiddingParty);
  const awaitingBid = isAwaitingBidOutcome(parties);
  const bidOutcomeBanner = bidOutcomeAvailable && awaitingBid
    ? `รอผลประมูล · ผู้รับเหมา ${buyerParties(parties).filter(isBiddingParty).length} ราย เสนอราคาแล้ว · กด "บันทึกผลประมูล" เมื่อทราบผล เลือกผู้ชนะได้มากกว่า 1 ราย`
    : null;
  if (bidOutcomeAvailable && !can.confirmFinalPayment && !can.confirmClose && !can.verifyClose) {
    stickyPrimaryLabel = 'บันทึกผลประมูล';
    stickyPrimaryAction = (
      <Button type="button" variant="primary" data-testid="ticket-primary-action" data-action="record_bid_outcome"
        onClick={() => setBidOutcomeOpen(true)}>
        <Icon name="check" size={14} />
        บันทึกผลประมูล
      </Button>
    );
  }
  if (!stickyPrimaryAction && workStateAction) {
    const actionKey = workStateAction.key ?? workStateAction.code;
    const jumpId = IN_PAGE_JUMP_TARGET[actionKey];
    const to = workStateAction.to;
    // data-testid="ticket-primary-action" + data-action={actionKey}: a stable e2e hook for this
    // slot. It is generic and reused across every branch below because this bar is the ONE sticky
    // primary slot and its action changes with stage/role, so a spec asserts "the primary slot now
    // offers X" (via data-action) instead of a per-action testid. The server-gated `can.*` buttons
    // above (confirmCustomer/confirmFinalPayment/confirmClose/verifyClose) keep their own
    // `ticket-detail-confirm-*`/`ticket-detail-verify-close` testids.
    // `!jumpId` is load-bearing: an action with an in-page target on THIS page must scroll to it,
    // never navigate away, even when its resolver supplies a `to`.
    //
    // nextImportAction points the four fulfilment codes at /fulfilment; without `!jumpId` an import
    // user standing on the deal, one scroll from the real button on DealFulfilmentPanel, would be
    // sent to a list page, and four IN_PAGE_JUMP_TARGET entries would stop working while
    // recordDelivery (whose `to` is /tickets/:id) kept scrolling.
    //
    // Decided here rather than in nextImportAction because the resolver also serves the dashboard
    // and the workspace, where /fulfilment is correct. Only this page knows it contains the target.
    if (to && to !== `/tickets/${ticketId}` && !jumpId) {
      stickyPrimaryAction = (
        <Button type="button" variant="primary" data-testid="ticket-primary-action" data-action={actionKey} onClick={() => navigate(to)}>
          {workStateAction.label}
          <Icon name="chevronRight" size={14} />
        </Button>
      );
      stickyPrimaryLabel = workStateAction.label;
    } else if (actionKey === 'create_pcr') {
      // PricingRequestPanel lives in the "สินค้าและราคา" tab, mounted only while that tab is active:
      // `runOnTab` switches to it first, then calls the ref.
      stickyPrimaryAction = (
        <Button type="button" variant="primary" data-testid="ticket-primary-action" data-action={actionKey} onClick={() => runOnTab('items', () => pricingRequestPanelRef.current?.openCreate())}>
          <Icon name="plus" size={14} />
          {workStateAction.label}
        </Button>
      );
      stickyPrimaryLabel = workStateAction.label;
    } else if (actionKey === 'quote_next_party') {
      // "เสนอราคา<next party>" once a designer/owner quotation is sent. More than one party left: a
      // menu, later parties reading "ข้ามไปเสนอราคา<party>"; one left: a plain button. The menu
      // trigger reads the neutral "เสนอราคา". Each opens the price-request form with ผู้รับ pre-filled.
      const parties = workStateAction.parties;
      // A direct route choice opens the quotation editor addressed to the party instead.
      const openFor = (party) => (workStateAction.direct
        ? navigate(`/quotations/new?ticket=${ticketId}&recipient=${party}`)
        : runOnTab('items', () => pricingRequestPanelRef.current?.openCreate({ recipientType: party })));
      // An owner may buy directly: beside the contractor, "ไม่มีผู้รับเหมา" records the owner's answer (the
      // request page, or the confirm dialog for a direct quotation) so ยืนยันคำสั่งซื้อ follows.
      const skipBuyerItem = !workStateAction.skipBuyer ? null
        : workStateAction.direct
          ? isOwner && confirmDirectSecondary && {
            key: 'none', label: 'ไม่มีผู้รับเหมา — ยืนยันคำสั่งซื้อ', testId: 'quote-party-none',
            disabled: confirmOrderDirectMutation.isPending,
            onSelect: () => setConfirm({ kind: 'confirmOrderDirect', quotationId: confirmDirectSecondary.quotationId, number: confirmDirectSecondary.quotationNumber }),
          }
          : outcomeSecondary && {
            key: 'none', label: 'ไม่มีผู้รับเหมา — บันทึกผลใบเสนอราคา', testId: 'quote-party-none',
            onSelect: () => navigate(`/pricing-requests/${outcomeSecondary.pricingRequestId}`),
          };
      if (workStateAction.skipBuyer && !skipBuyerItem) quoteBannerText = quoteNextPartyBanner({ ...workStateAction, skipBuyer: false });
      stickyPrimaryAction = parties.length > 1 || skipBuyerItem ? (
        <OverflowMenu
          items={[...parties.map((party, index) => ({
            key: party,
            label: `${index > 0 ? 'ข้ามไป' : ''}เสนอราคา${PARTY_LABEL[party]}`,
            testId: `quote-party-${party}`,
            onSelect: () => openFor(party),
          })), skipBuyerItem].filter(Boolean)}
          label="เสนอราคา"
          triggerLabel={<>เสนอราคา<Icon name="chevronDown" size={14} /></>}
          triggerIcon={null}
          triggerClassName={buttonVariants({ variant: 'primary' })}
          triggerProps={{ 'data-testid': 'ticket-primary-action', 'data-action': actionKey }}
          align="end"
          mobilePlacement="up"
          onOpenChange={onHeaderMenuOpenChange}
        />
      ) : (
        <Button type="button" variant="primary" data-testid="ticket-primary-action" data-action={actionKey} onClick={() => openFor(parties[0])}>
          {workStateAction.label}
        </Button>
      );
      stickyPrimaryLabel = workStateAction.label;
    } else if (actionKey === 'submit_pricing_request') {
      // A later party's private DRAFT: sent to import through PricingRequestPanel's own ส่งให้ฝ่ายนำเข้า
      // (its ref, on the items tab) rather than a scroll jump the deal page pulls back to the top.
      stickyPrimaryAction = (
        <Button type="button" variant="primary" data-testid="ticket-primary-action" data-action={actionKey}
          onClick={() => runOnTab('items', () => pricingRequestPanelRef.current?.submit(workStateAction.pricingRequestId))}>
          {workStateAction.label}
        </Button>
      );
      stickyPrimaryLabel = workStateAction.label;
    } else if (actionKey === 'issue_quotation') {
      stickyPrimaryAction = (
        <Button type="button" variant="primary" data-testid="ticket-primary-action" data-action={actionKey}
          disabled={createQuotationFromRequestMutation.isPending}
          onClick={() => createQuotationFromRequest(workStateAction.pricingRequestId)}>
          {workStateAction.label}
        </Button>
      );
      stickyPrimaryLabel = workStateAction.label;
    } else if (actionKey === 'create_deposit_notice') {
      // DRAFT already made: just open it; else make it (same path as DepositNoticePage's สร้าง) and open it.
      stickyPrimaryAction = (
        <Button type="button" variant="primary" data-testid="ticket-primary-action" data-action={actionKey}
          disabled={createDepositNoticeMutation.isPending}
          onClick={() => createDepositNoticeMutation.mutate()}>
          {workStateAction.label}
        </Button>
      );
      stickyPrimaryLabel = workStateAction.label;
    } else if (actionKey === 'create_remaining_invoice') {
      stickyPrimaryAction = (
        <Button type="button" variant="primary" data-testid="ticket-primary-action" data-action={actionKey}
          onClick={() => setRemainingInvoiceDialogOpen(true)}>
          {workStateAction.label}
        </Button>
      );
      stickyPrimaryLabel = workStateAction.label;
    } else if (actionKey === 'choose_procurement_source') {
      stickyPrimaryAction = (
        <Button type="button" variant="primary" data-testid="ticket-primary-action" data-action={actionKey}
          onClick={() => setProcurementDialogOpen(true)}>
          {workStateAction.label}
        </Button>
      );
      stickyPrimaryLabel = workStateAction.label;
    } else if (actionKey === 'create_import_request') {
      // A DRAFT IR already exists (label says so): just show it; else run the panel's own create.
      const hasDraft = importRequests?.some((r) => r.status === 'DRAFT');
      stickyPrimaryAction = (
        <Button type="button" variant="primary" data-testid="ticket-primary-action" data-action={actionKey}
          disabled={importRequests === undefined}
          onClick={() => runOnTab('fulfilment', () => (hasDraft ? fulfilmentPanelRef.current?.showIrSection() : fulfilmentPanelRef.current?.createIrDrafts()))}>
          {workStateAction.label}
        </Button>
      );
      stickyPrimaryLabel = workStateAction.label;
    } else if (actionKey === 'confirm_order') {
      // DealQuotationPanel lives in the "เอกสาร" tab.
      stickyPrimaryAction = (
        <Button type="button" variant="primary" data-testid="ticket-primary-action" data-action={actionKey} onClick={() => runOnTab('documents', () => dealQuotationPanelRef.current?.openConfirmOrder())}>
          {workStateAction.label}
        </Button>
      );
      stickyPrimaryLabel = workStateAction.label;
    } else if (actionKey === 'record_quotation_outcome') {
      // salesActions.js's RECORD_QUOTATION_OUTCOME bucket (a QUOTATION_ISSUED PR whose customer
      // decision hasn't been recorded yet). The accept/reject/revision controls for both quotation
      // engines live on the pricing-request page, so this opens it; it never records anything itself.
      stickyPrimaryAction = (
        <Button type="button" variant="primary" data-testid="ticket-primary-action" data-action={actionKey} onClick={() => navigate(`/pricing-requests/${workStateAction.pricingRequestId}`)}>
          {workStateAction.label}
        </Button>
      );
      stickyPrimaryLabel = workStateAction.label;
    } else if (actionKey === 'revise_quotation') {
      // (An open revision draft carries `to`, which the navigate branch above already handled.)
      stickyPrimaryAction = (
        <Button
          type="button"
          variant="primary"
          data-testid="ticket-primary-action"
          data-action={actionKey}
          disabled={reviseQuotationMutation.isPending}
          onClick={() => reviseQuotationMutation.mutate(workStateAction.quotationId)}
        >
          {workStateAction.label}
        </Button>
      );
      stickyPrimaryLabel = workStateAction.label;
    } else if (actionKey === 'confirm_order_direct') {
      // An APPROVED direct quotation: the customer's order is confirmed right here, behind the same
      // confirm the quotation editor uses (consequential and irreversible, DESIGN.md principle 10).
      // SUBMIT and the approvers' action need no branch: both carry a `to` (/quotations/:id), which
      // the first branch above navigates.
      stickyPrimaryAction = (
        <Button
          type="button"
          variant="primary"
          data-testid="ticket-primary-action"
          data-action={actionKey}
          disabled={confirmOrderDirectMutation.isPending}
          onClick={() => setConfirm({
            kind: 'confirmOrderDirect', quotationId: workStateAction.quotationId, number: workStateAction.quotationNumber,
          })}
        >
          {workStateAction.label}
        </Button>
      );
      stickyPrimaryLabel = workStateAction.label;
    } else if (actionKey === 'advance_stage') {
      // ขั้น 1–2 are moved by hand. Offered only when the rep can move: DealStagePanel's startAdvance
      // moves at once when ready, else opens its follow-up + activity form.
      if (canAdvance || advanceAwaitsReadiness) {
        advanceInHeader = true;
        stickyPrimaryLabel = `เลื่อนไป: ${next.no}. ${dealStageLabel(next.code, summary.entryChannel).label}`;
        stickyPrimaryAction = (
          <Button type="button" variant="primary" data-testid="ticket-primary-action" data-action={actionKey} disabled={actionLoading} onClick={() => dealStagePanelRef.current?.startAdvance()}>
            {stickyPrimaryLabel}
            <Icon name="chevronRight" size={14} />
          </Button>
        );
      }
    } else if (jumpId) {
      // Every IN_PAGE_JUMP_TARGET id lives inside a tab; JUMP_TARGET_TAB names which, so runOnTab can
      // switch there before scrolling.
      stickyPrimaryAction = (
        <Button type="button" variant="primary" data-testid="ticket-primary-action" data-action={actionKey} onClick={() => runOnTab(JUMP_TARGET_TAB[jumpId] ?? activeTab, () => scrollToSection(jumpId))}>
          {workStateAction.label}
          <Icon name="chevronRight" size={14} />
        </Button>
      );
      stickyPrimaryLabel = workStateAction.label;
    }
  }

  // The work-state banner line. Resolver-derived CTAs (workStateAction, the branches above) render
  // `workStateAction.label` verbatim on the button, so the line would repeat it: null, the button
  // carries it alone. The four `can.*` CTAs differ: `nextAction` is a descriptive SENTENCE
  // ("ส่งมอบและรับเงินครบแล้ว — ยืนยันเพื่อส่งให้ CEO ตรวจสอบปิดงาน") naming the precondition and the
  // consequence, while the button is a terse verb ("ยืนยันพร้อมปิดงาน"), and no other surface says
  // it, so they keep their line, prefix-free.
  //
  // `primaryAction` (not stickyPrimaryLabel) is the discriminator: it is set only by that
  // four-branch cascade, and it always wins over the resolver.
  //
  // workState can hand back its own finished line (`workState.bannerText`: the rep's direct
  // quotation waiting on ผจก.ขาย/CEO), which reads before the blocker line. The เสนอราคา<party>
  // primary also carries one, shown beside its button.
  const bannerText = stickyPrimaryLabel === 'บันทึกผลประมูล'
    ? bidOutcomeBanner
    : primaryAction
    ? nextAction
    : stickyPrimaryLabel
      ? (quoteBannerText ?? null)
      : (quoteBannerText || blocker);

  // Overflow-menu / danger-zone availability mirrors DealStagePanel's canEditStage/canLost/canHold/
  // canDormant gates (same hasAction/stageDecisions reads on the same server payload) so the header
  // menu never offers something that panel wouldn't allow. DealStagePanel stays the single
  // authority (its ref-exposed open functions re-check these conditions instead of trusting the
  // caller). It rendered its แก้ไขสถานะ…/เสียงาน/พักดีลไว้/พัก dormant row only in its final "lifecycle
  // is plain ACTIVE" JSX branch, and that branch position kept "พัก dormant" off an ON_HOLD deal
  // (which legitimately has MARK_DORMANT in availableActions, and whose own banner already offers
  // that button). Mirrored explicitly here so the header menu can't duplicate it.
  const canEditStage = isActiveLifecycle && hasAction('UPDATE_STAGE')
    && stageDecisions.some((decision) => decision.allowed) && !lost;
  const canLostDeal = isActiveLifecycle && hasAction('MARK_LOST') && !lost && summary.salesStage !== 'CLOSED_PAID';
  const canHoldDeal = isActiveLifecycle && hasAction('PLACE_ON_HOLD');
  const canDormantDeal = isActiveLifecycle && hasAction('MARK_DORMANT');

  // Stage-advance readiness (dealTrackingMeta.js's gate, shared with DealTrackingPanel's badge),
  // lifted here so it sits next to the "เลื่อนไป" action in the header overflow menu. Consulted
  // only when canAdvance is true, which is only true for a sales-gated next stage (every
  // import/account-gated stage in SALES_STAGES is `auto: true`), so `activities` being empty for
  // import/account viewers never matters.
  //
  // `activitiesQuery.isLoading` guards the initial fetch: `activities` defaults to `[]`, which would
  // read as "not ready" for a beat on every load and briefly disable เลื่อนไป. Treated as ready
  // while loading.
  const readyToAdvance = activitiesQuery.isLoading
    || isReadyToAdvance(summary, hasActivitySince(activities, lastStageChangeAt(events, summary.createdAt)));

  // Named handlers (not inline closures in the array below) so each ref read happens inside an ordinary
  // event-handler function, not lexically inside an object/array literal being built during render.
  function handleOpenEditStage() { dealStagePanelRef.current?.openEditStage(); }
  function handleOpenHold() { dealStagePanelRef.current?.openHold(); }
  function handleOpenDormant() { dealStagePanelRef.current?.openDormant(); }
  function handleOpenAdvance() { dealStagePanelRef.current?.openAdvance(); }
  // ขอแก้ไข (Revise) opens as a modal, so it needs no runOnTab wrapper: a plain flag flip works
  // whichever tab is active.
  function handleOpenRevise() { setShowReviseForm(true); clearFieldError('revise.reason'); }

  // react-hooks/refs flags every named handler below as "passing a ref to a function" because they end up as
  // values inside this array, which is itself passed to OverflowMenu as a prop.
  // eslint-disable-next-line react-hooks/refs
  const overflowItems = [
    // "เลื่อนไป" lives in the overflow, not DealStagePanel: the sticky header's primary CTA is the
    // ONE resolver-derived primary, so a second filled primary in the pipeline panel would compete
    // with it. Shown only when canAdvance (the real ADVANCE_STAGE/canSetStage gate), and
    // disabled-with-reason (never silently hidden) when the readiness precondition isn't met.
    // `disabled={actionLoading}` has to carry the old inline buttons' in-flight condition (rendered
    // via OverflowMenu's aria-disabled, see its doc comment): without it, reopening the menu
    // mid-mutation and clicking again fires a second request (a 409 "Deal is already in stage X",
    // TicketService.java:1143). The re-check inside each opener (DealStagePanel's
    // openAdvance/openEditStage/openHold/openDormant) is the actual guard; this is the visible
    // half. While ยืนยันคำสั่งซื้อ is the primary, a second เลื่อนไป would compete with it.
    canAdvance && !confirmOrderCta && {
      key: 'advanceStage',
      label: summary.salesStage === 'PROCUREMENT' && next
        ? manualMoveLabel(summary.salesStage, next, '')
        : `เลื่อนไป: ${next ? dealStageLabel(next.code, summary.entryChannel).label : ''}`,
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
    // A sent designer/owner quotation's outcome is recorded on its request page, whatever the
    // primary is ("always in ⋯").
    outcomeSecondary && {
      key: 'recordQuotationOutcome',
      label: outcomeSecondary.label,
      onSelect: () => navigate(`/pricing-requests/${outcomeSecondary.pricingRequestId}`),
    },
    reviseSecondary && {
      key: 'reviseQuotation',
      label: reviseSecondary.label,
      icon: 'pencil',
      disabled: reviseQuotationMutation.isPending,
      onSelect: () => (reviseSecondary.to ? navigate(reviseSecondary.to) : reviseQuotationMutation.mutate(reviseSecondary.quotationId)),
      testId: 'deal-quotation-revise',
    },
    confirmDirectSecondary && {
      key: 'confirmOrderDirect',
      label: confirmDirectSecondary.label,
      disabled: confirmOrderDirectMutation.isPending,
      onSelect: () => setConfirm({
        kind: 'confirmOrderDirect', quotationId: confirmDirectSecondary.quotationId, number: confirmDirectSecondary.quotationNumber,
      }),
    },
    can.revise && { key: 'revise', label: 'ขอแก้ไข', icon: 'pencil', onSelect: handleOpenRevise },
  ].filter(Boolean);

  async function handleUploadAttachment(e) {
    const file = e.target.files?.[0];
    if (!file) return;
    // INVOICE is never produced here — not inferred from the filename, and no longer settable via an explicit type
    // either. It gates the close and must come from CommissionService.createFromDeal, which writes it alongside
    // the commission (see the "ไฟล์แนบ" panel comment below).
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

  // Final Payment is confirmed through a confirm-dialog + doAction (like cancelTicket below)
  // because it auto-records a full BALANCE receipt server-side (TicketService.confirmFinalPayment),
  // one of the most financially consequential actions. doAction swallows/toasts its own error, so
  // closing the dialog afterwards is unconditional.
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
    // Reset on open so a stale error never greets the user on reopen.
    setFieldErrorsForPrefix('payment.', {});
  }

  function closePaymentModal() {
    setPaymentModal(false);
    setFieldErrorsForPrefix('payment.', {});
  }

  // openDeliveryModal/closeDeliveryModal/openStockModal live in DealFulfilmentPanel.

  return (
    <div className={`relative grid w-full grid-cols-1 gap-[18px] min-w-0 max-w-[1320px] ${bannerText || stickyPrimaryAction || overflowItems.length > 0 ? 'mobile:pb-36' : ''}`}>
      {/* Hysteresis sentinels for the sticky-chrome condense IntersectionObserver (see the
          ticketChromeCondensed declaration). Absolutely positioned + 0-size so they add no
          layout/gap. The pair marks "the very top" and "scrolled past ~56px" independent of
          the chrome's own height, giving a dead-band so slow scrolling can't flip-flop the
          collapse — and no scroll-position feedback loop. */}
      <div ref={expandSentinelRef} data-testid="deal-chrome-expand-sentinel" aria-hidden="true" className="pointer-events-none absolute left-0 top-0 h-px w-px" />
      <div ref={condenseSentinelRef} data-testid="deal-chrome-condense-sentinel" aria-hidden="true" className="pointer-events-none absolute left-0 top-14 h-px w-px" />
      {/* The breadcrumb is the single up-nav (onBack is still reachable via its "ดีล" crumb). */}
      <Breadcrumbs items={[{ label: 'ดีล', onClick: onBack }, { label: summary.code || summary.customerName || summary.title }]} />

      {/* Deal Workspace state header (see DealStateHeader.jsx's doc comment): deal code/title/
          customer + lifecycle × stage × PCR × payment × fulfilment at a glance, the ONE work-state
          banner line, the sticky primary CTA, and the "⋯" overflow. */}
      <div
        ref={ticketStickyChromeRef}
        data-testid="ticket-detail-sticky-chrome"
        data-sticky-chrome=""
        className="sticky top-[calc(var(--deal-scroll-pad-y)*-1)] z-10 bg-surface pt-[var(--deal-scroll-pad-y)] mobile:static mobile:bg-transparent mobile:pt-0"
      >
        <div className="overflow-hidden rounded-md border border-border bg-surface mobile:overflow-visible mobile:border-0 mobile:bg-transparent">
          <DealStateHeader
            summary={summary}
            pricingRequests={pricingRequests}
            importRequests={importRequests}
            procurementSource={procurementSource}
            role={role}
            primaryAction={stickyPrimaryAction}
            bannerText={bannerText}
            overflowItems={overflowItems}
            onRefresh={refreshTicket}
            condensed={ticketChromeCondensed}
            onMenuOpenChange={onHeaderMenuOpenChange}
            awaitingBid={awaitingBid ? { projectName: summary.projectName, count: buyerParties(parties).length } : null}
          />
          <div className="border-t border-border bg-surface px-4 sm:px-5">
            {/* onSurface: this strip is on a white card, unlike the two page-background call
                sites (see Tabs.jsx). */}
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

      {/* A single full-width stack: the context rail is gone and its four sections live in the
          overview TabPanel above and DealHistoryPanel below. */}
      {/* min-[721px]:[&_*:not([role=dialog]_*)]:scroll-mt: desktop-only header clearance on the content's
          descendants, never on the sticky header's own controls (focusing those must not scroll the page)
          and not inside non-portalled Modal bodies (role="dialog").
          `mobile` is max-width:720px, so the two bands never overlap. */}
      <div className="flex min-w-0 flex-col gap-4 min-[721px]:[&_*:not([role=dialog]_*)]:scroll-mt-[calc(var(--deal-header-h,18rem)_+_var(--space-4))]">

      {/* Deal pipeline: the 14-stage journey with stage-gated doc actions. Generation buttons reuse
          the exact handlers/permissions of the action row; once a document exists (quotation /
          ใบแจ้งยอดมัดจำ) it stays reachable from here through the later stages. A quotation-first deal
          renders like any other. */}
      <div className="min-w-0">
      <DealStagePanel
        ref={dealStagePanelRef}
        summary={summary}
        availableActions={availableActions}
        stageDecisions={stageDecisions}
        catalog={stageCatalog}
        events={ticket.events}
        quoteParties={workStateAction?.key === 'quote_next_party' ? workStateAction.parties : null}
        ctaStage={ctaStage}
        // primaryAction lives solely in DealStateHeader above ("one primary CTA"); not passed here
        // too, to avoid rendering the same button twice.
        advanceReady={readyToAdvance}
        advanceInHeader={advanceInHeader}
        actionLoading={actionLoading}
        onUpdateStage={(payload) => doAction(() => api.tickets.updateStage(ticketId, payload), 'อัปเดตสถานะดีลแล้ว')}
        onMarkLost={(payload) => doAction(() => api.tickets.markLost(ticketId, payload), 'บันทึกเสียงานแล้ว')}
        onReopen={() => doAction(() => api.tickets.reopen(ticketId, {}), 'เปิดดีลอีกครั้งแล้ว')}
        onHold={(payload) => doAction(() => api.tickets.hold(ticketId, payload), 'พักดีลไว้แล้ว')}
        onDormant={(payload) => doAction(() => api.tickets.dormant(ticketId, payload), 'พัก dormant แล้ว')}
        onResume={(payload) => doAction(() => api.tickets.resume(ticketId, payload), 'ดำเนินการต่อแล้ว')}
        onSetTenderRequirement={(payload) => doAction(() => api.tickets.setTenderRequirement(ticketId, payload), 'บันทึกสถานะประมูลแล้ว')}
        onSetEntryChannel={(payload) => doAction(() => api.tickets.setEntryChannel(ticketId, payload), 'บันทึกช่องทางรับงานแล้ว')}
        docActions={(can.downloadRemainingInvoice || (sections.quotation && latestQuotation)) ? (
          <>
            {/* Import/account: the view-only quotation download link isn't role-gated by `can.*`;
                sections.quotation hides it for the role that has no business in that document. The
                deposit-notice affordance lives in DealPaymentSection and the Import Request button
                in DealFulfilmentPanel. */}
            {sections.quotation && latestQuotation && (
              <Button type="button" variant="text" size="sm" className="min-h-0 gap-1.5 text-sm font-bold"
                disabled={downloadingQuotationKey === `${latestQuotation.id}-pdf`}
                onClick={() => handleDownloadQuotation(latestQuotation.id, latestQuotation.number, 'pdf')}>
                <Icon name="fileText" size={14} />
                {downloadingQuotationKey === `${latestQuotation.id}-pdf`
                  ? 'กำลังดาวน์โหลด…'
                  : `ใบเสนอราคา ${latestQuotation.number} (PDF)`}
              </Button>
            )}
            {can.downloadRemainingInvoice && (
              <Button type="button" variant="text" size="sm" className="min-h-0 gap-1.5 text-sm font-bold" onClick={() => setRemainingInvoiceDialogOpen(true)}>
                <Icon name="fileText" size={14} />
                {remainingInvoice === undefined || remainingInvoice?.status === 'ISSUED' ? 'ดาวน์โหลดใบแจ้งหนี้ส่วนที่เหลือ' : 'สร้างใบแจ้งหนี้ส่วนที่เหลือ'}
              </Button>
            )}
          </>
        ) : null}
      />
      </div>

      {/* Six role-projected tabs grouped by JOB: ดีล · สินค้าและราคา · เอกสาร · การเงิน ·
          จัดซื้อ-ส่งมอบ · ประวัติ (see docs/ui-repair/02-information-architecture/
          TICKET_INFORMATION_ARCHITECTURE.md). ticketDetailTabs.js decides which ones this viewer's
          role may see at all (never a security boundary, like salesViewScope.js), and Tabs.jsx keeps
          only the active one mounted. No tab is per-instance gated: DealAttachmentsPanel lives inside
          `history` behind an INNER render condition, so `visibleTabItems`/`visibleActiveTab` above
          are plain aliases of `tabItems`/`activeTab`. */}
      <div className="min-w-0">
      <TabPanel id="deal" idPrefix="ticket-detail" active={visibleActiveTab === 'deal'}>
          {/* ผู้รับเหมาที่เสนอราคา: one row per bidding contractor (deal-party-model.md). */}
          {canViewDealTracking ? (
            <div className="mb-4">
              <DealPartiesPanel
                ticketId={ticketId}
                parties={parties}
                loading={partiesQuery.isLoading}
                canEdit={canEditParties && isActiveLifecycle}
                showToast={showToast}
              />
            </div>
          ) : null}
          {/* วันสำคัญ / ผู้เกี่ยวข้อง, with the assignedImport role-scoped readout. */}
          <div className="grid gap-4 sm:grid-cols-2">
            <section className="rounded-md border border-border bg-surface p-4">
              <ContextSection title="วันสำคัญ" helper="Key dates" icon="calendar">
                <dl className="m-0">
                  <FieldRow label="ติดตามครั้งถัดไป" value={formatThaiDate(summary.nextFollowUpAt)} />
                  <FieldRow label="ติดตามล่าสุด" value={formatThaiDate(latestFollowUp)} />
                  <FieldRow label="ใบเสนอราคาหมดอายุ" value={formatThaiDate(latestQuotation?.validityDate)} />
                </dl>
              </ContextSection>
            </section>
            <section className="rounded-md border border-border bg-surface p-4">
              <ContextSection title="ผู้เกี่ยวข้อง" helper="ทีมที่เกี่ยวข้อง" icon="users">
                <dl className="m-0">
                  <FieldRow label="เจ้าของดีล" value={summary.createdByName} />
                  {/* DESIGNER / OWNER parties when the deal has them; the old free-text names
                      otherwise (deal-party-model.md: parties replace them). */}
                  {['DESIGNER', 'OWNER'].map((partyRole) => {
                    const rows = parties.filter((p) => p.role === partyRole);
                    const fallback = partyRole === 'DESIGNER' ? summary.designerName : summary.ownerName;
                    if (rows.length === 0 && !fallback) return null;
                    return (
                      <FieldRow
                        key={partyRole}
                        label={<StatusBadge tone={partyRole === 'DESIGNER' ? 'info' : 'neutral'}>{PARTY_ROLE_LABEL[partyRole]}</StatusBadge>}
                        value={rows.length ? rows.map((p) => p.customerName).join(', ') : fallback}
                      />
                    );
                  })}
                  <FieldRow label="ผู้รับเรื่องคำขอราคา" value={assignedImport} />
                  <FieldRow label="บัญชี" value={summary.closeConfirmedByName || 'ยังไม่ระบุ'} />
                </dl>
              </ContextSection>
            </section>
          </div>

          {/* DealTrackingPanel, behind the `canViewDealTracking` gate (see ticketDetailTabs.js and
              JUMP_TARGET_TAB's doc comment). */}
          {canViewDealTracking ? (
            // id: the sticky bar's FOLLOW_UP/LOG_ACTIVITY jump target (see IN_PAGE_JUMP_TARGET
            // above); tabIndex so the jump can move focus here. On desktop the content wrapper's
            // descendant scroll-margin (measured header height) clears the sticky header for every
            // focus / scrollIntoView target; the jump lands just below the expanded header, and when
            // the header condenses the shell's overflow-anchor:none (see AppShell) keeps scrollTop so
            // the target ends up just below the condensed header. Mobile keeps its own 420px because
            // the chrome is static there, with a fixed bottom action bar.
            <div id="deal-tracking-panel" tabIndex={-1} className="mobile:scroll-mt-[420px] outline-none">
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
          {/* flush + title/actions picks up Panel's standard bordered-header inset (20px/16px,
              border-border-subtle), the same small shift as the CommissionPage port. */}
          <Panel
            flush
            title={`รายการสินค้า (${editMode ? editDraft.length : items.length} รายการ)`}
            actions={can.editItems && !editMode ? (
              <Button type="button" variant="secondary" disabled={actionLoading}
                onClick={() => {
                  setEditDraft(items.map((item) => ({ ...item })));
                  setEditNote('');
                  setEditMode(true);
                  setFieldErrorsForPrefix('editItems.', {});
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
                            // Removing a row shifts every later row's index down by one: reindex
                            // their qty errors so an error never ends up pinned to the wrong row.
                            setFieldErrors((prev) => {
                              let changed = false;
                              const next = {};
                              Object.entries(prev).forEach(([k, v]) => {
                                // Any editItems.<field>.<rowIndex> key (brand/model/size/qty), so a
                                // required-field error never ends up pinned to the wrong row after
                                // a delete.
                                const m = k.match(/^editItems\.([a-zA-Z]+)\.(\d+)$/);
                                if (!m) { next[k] = v; return; }
                                const [, field, idxStr] = m;
                                const idx = Number(idxStr);
                                if (idx === index) { changed = true; return; }
                                if (idx > index) { next[`editItems.${field}.${idx - 1}`] = v; changed = true; return; }
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
                      {/* Fields/labels/placeholders and the catalog autocomplete on ยี่ห้อ/รุ่น come
                          from ticketItemFields.jsx, shared with TicketCreateModal.jsx. ยี่ห้อ and
                          โรงงาน are one field, same as create (see applyDescriptiveFieldEdit's
                          brand branch). */}
                      <CatalogAutocompleteField
                        id={`edit-item-${index}-brand`}
                        label={ITEM_FIELD_META.brand.label}
                        placeholder={ITEM_FIELD_META.brand.placeholder}
                        required={isRequiredItemField('brand')}
                        value={item.brand || ''}
                        onInput={(value) => onEditCatalogInput(index, 'brand', value)}
                        onFocusSearch={() => onEditCatalogFieldFocus(index, 'brand', item.brand)}
                        onBlur={() => setTimeout(() => setEditCatalogFocus(null), 180)}
                        expanded={editCatalogFocus?.index === index && editCatalogFocus?.field === 'brand'}
                        results={editCatalogResults}
                        onPick={(cat) => applyEditCatalogPick(index, cat)}
                        error={fieldErrors[`editItems.brand.${index}`]}
                        inputRef={(el) => { fieldRefs.current[`editItems.brand.${index}`] = el; }}
                      />
                      <CatalogAutocompleteField
                        id={`edit-item-${index}-model`}
                        label={ITEM_FIELD_META.model.label}
                        placeholder={ITEM_FIELD_META.model.placeholder}
                        required={isRequiredItemField('model')}
                        value={item.model || ''}
                        onInput={(value) => onEditCatalogInput(index, 'model', value)}
                        onFocusSearch={() => onEditCatalogFieldFocus(index, 'model', item.model)}
                        onBlur={() => setTimeout(() => setEditCatalogFocus(null), 180)}
                        expanded={editCatalogFocus?.index === index && editCatalogFocus?.field === 'model'}
                        results={editCatalogResults}
                        onPick={(cat) => applyEditCatalogPick(index, cat)}
                        error={fieldErrors[`editItems.model.${index}`]}
                        inputRef={(el) => { fieldRefs.current[`editItems.model.${index}`] = el; }}
                      />
                      <ItemField
                        id={`edit-item-${index}-size`}
                        label={ITEM_FIELD_META.size.label}
                        required={isRequiredItemField('size')}
                        error={fieldErrors[`editItems.size.${index}`]}
                      >
                        <input
                          id={`edit-item-${index}-size`}
                          ref={(el) => { fieldRefs.current[`editItems.size.${index}`] = el; }}
                          value={item.size || ''}
                          placeholder={ITEM_FIELD_META.size.placeholder}
                          aria-required="true"
                          aria-invalid={fieldErrors[`editItems.size.${index}`] ? true : undefined}
                          aria-describedby={fieldErrors[`editItems.size.${index}`] ? fieldErrorId(`edit-item-${index}-size`) : undefined}
                          onChange={(e) => {
                            setEditDraft((d) => d.map((r, i) => (i === index ? applyDescriptiveFieldEdit(r, 'size', e.target.value) : r)));
                            clearFieldError(`editItems.size.${index}`);
                          }}
                        />
                      </ItemField>
                      <ItemField id={`edit-item-${index}-color`} label={ITEM_FIELD_META.color.label} hint={ITEM_FIELD_META.color.hint}>
                        <input
                          id={`edit-item-${index}-color`}
                          value={item.color || ''}
                          placeholder={ITEM_FIELD_META.color.placeholder}
                          onChange={(e) => setEditDraft((d) => d.map((r, i) => (i === index ? applyDescriptiveFieldEdit(r, 'color', e.target.value) : r)))}
                        />
                      </ItemField>
                      <ItemField id={`edit-item-${index}-texture`} label={ITEM_FIELD_META.texture.label} hint={ITEM_FIELD_META.texture.hint}>
                        <input
                          id={`edit-item-${index}-texture`}
                          value={item.texture || ''}
                          placeholder={ITEM_FIELD_META.texture.placeholder}
                          onChange={(e) => setEditDraft((d) => d.map((r, i) => (i === index ? applyDescriptiveFieldEdit(r, 'texture', e.target.value) : r)))}
                        />
                      </ItemField>
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
                          <label className="m-0 text-xs" htmlFor={`edit-item-qty-${index}`}>
                            <ItemFieldLabel label="จำนวน (แผ่น)" required={requiredQtyField(item.unitBasis) === 'qty'} />
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
                              aria-required={requiredQtyField(item.unitBasis) === 'qty' ? 'true' : undefined}
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
                            <div className={cn('rounded-md border border-border-subtle bg-surface-muted px-2.5 py-[7px] text-sm', item.qtySqm ? 'text-icon-muted' : 'text-text-muted')}>
                              {item.qtySqm ? `${Number(item.qtySqm).toFixed(3)} ตร.ม.` : '—'}
                            </div>
                          </div>
                        </>
                      ) : (
                        <>
                          <label className="m-0 text-xs" htmlFor={`edit-item-qtysqm-${index}`}>
                            <ItemFieldLabel label="พื้นที่ (ตร.ม.)" required={requiredQtyField(item.unitBasis) === 'qtySqm'} />
                            {/* Governs qty when this row is in SQM mode (qty is derived from qtySqm × sqmPerPiece below) — so the per-row
                             * qty error, if any, attaches here rather than to a non-editable derived display. */}
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
                              aria-required={requiredQtyField(item.unitBasis) === 'qtySqm' ? 'true' : undefined}
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
                            <div className={cn('rounded-md border border-border-subtle bg-surface-muted px-2.5 py-[7px] text-sm', hasRealTileCount(item) ? 'text-icon-muted' : 'text-text-muted')}>
                              {hasRealTileCount(item) ? `${item.qty} แผ่น` : '—'}
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
                      {/* "จากสต็อก" is only a flag (walk 4): the price is the CEO-approved one, so no price input. */}
                      <div className="col-span-full flex flex-col gap-1.5 rounded-md border border-border-input bg-surface-muted px-3 py-2.5">
                        <label className="m-0 flex cursor-pointer items-center gap-2 text-sm">
                          <input
                            type="checkbox"
                            className="h-4 w-4 cursor-pointer accent-info-dot"
                            checked={Boolean(item.sourcedFromStock)}
                            onChange={(e) => {
                              const checked = e.target.checked;
                              setEditDraft((d) => d.map((r, i) => i === index ? { ...r, sourcedFromStock: checked } : r));
                            }} />
                          <strong>จากสต็อก</strong>
                        </label>
                        <p className="m-0 pl-6 text-2xs font-semibold text-text-muted">
                          สินค้าที่มีอยู่ในคลัง — ราคาขายมาจากการอนุมัติของ CEO
                        </p>
                      </div>
                    </div>
                  </div>
                ))}
                <Button type="button" variant="secondary"
                  onClick={() => setEditDraft((d) => [...d, {
                    brand: '', model: '', color: '', texture: '', size: '', qty: 1, proposedPrice: null,
                    sourcedFromStock: false,
                  }])}
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
                      // Per-row required-field errors (brand, model, size, then qty/qtySqm), in the
                      // order focusFirstInvalid walks to land on the first invalid control
                      // top-to-bottom.
                      const rowErrors = {};
                      const order = [];
                      editDraft.forEach((item, i) => {
                        const itemErrors = requiredItemFieldErrors(item);
                        for (const field of ['brand', 'model', 'size']) {
                          if (itemErrors[field]) {
                            const key = `editItems.${field}.${i}`;
                            rowErrors[key] = itemErrors[field];
                            order.push(key);
                          }
                        }
                        // The qty rule matches create's basis-aware requiredQtyField. Always
                        // checking item.qty could deadlock the SQM branch: for a catalog row with NO
                        // ตร.ม./แผ่น factor, "จำนวน (แผ่น)" is a read-only derived display in SQM
                        // mode, so a rep who filled พื้นที่ (ตร.ม.) could never satisfy a check on a
                        // field they can't edit. Safe to align: create's zod schema never validates
                        // qty for SQM basis either, and the backend accepts that shape
                        // (TicketItemRequest's qty/qtySqm carry no @NotNull/@Positive,
                        // TicketService#mergeEditedItemsPreservingPricing passes both through, and
                        // sales.ticket_item.qty is NOT NULL with no CHECK > 0). Both payloads
                        // already coerce to `qty: Number(item.qty) || 0`.
                        const qtyField = requiredQtyField(item.unitBasis);
                        const qtyValue = item[qtyField];
                        if (!qtyValue || Number(qtyValue) <= 0) {
                          const key = `editItems.qty.${i}`;
                          rowErrors[key] = missingQtyMessage(item.unitBasis, i + 1);
                          order.push(key);
                        }
                      });
                      if (order.length > 0) {
                        setFieldErrorsForPrefix('editItems.', rowErrors);
                        focusFirstInvalid(order[0]);
                        return;
                      }
                      setFieldErrorsForPrefix('editItems.', {});
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
                          // Round-trips the deal-creation catalog link: editDraft rows are seeded
                          // from the fetched `items` (which carry it), and the row inputs above
                          // clear it on any descriptive hand-edit.
                          catalogPriceId: item.catalogPriceId ?? null,
                          catalogProductCode: item.catalogProductCode?.trim() || null,
                          // Sent unconditionally (true and false) so un-flagging a stock line reaches the
                          // backend as false, not as an absent field the merge reads as "inherit prior".
                          sourcedFromStock: Boolean(item.sourcedFromStock),
                        })),
                        note: editNote.trim() || null,
                      }), 'บันทึกการแก้ไขแล้ว');
                    }}>
                    บันทึกการแก้ไข
                  </Button>
                  <Button type="button" variant="secondary" disabled={actionLoading}
                    onClick={() => { setEditMode(false); setEditDraft([]); setEditNote(''); setFieldErrorsForPrefix('editItems.', {}); }}>
                    ยกเลิก
                  </Button>
                </div>
              </div>
            ) : (
              <>
                {fromPricingChain ? (
                  <div className="p-[14px_18px_0]">
                    <div className="flex items-start gap-2 rounded-md border border-info-border bg-info-bg px-3 py-2.5 text-xs text-info-dark" data-testid="items-pricing-chain-banner">
                      <Icon name="info" size={15} className="mt-0.5 shrink-0" />
                      <span>รายการจากใบเสนอราคา — จะยืนยันเป็นรายการจริงเมื่อยืนยันคำสั่งซื้อ (แก้ไขได้ที่คำขอราคา/ใบเสนอราคา)</span>
                    </div>
                  </div>
                ) : null}
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
                      {/* A saved จากสต็อก line is flagged here; its price is the CEO-approved one (walk 4). */}
                      {item.sourcedFromStock ? (
                        <span className="mt-0.5 block w-fit"><StatusBadge tone="warning">จากสต็อก</StatusBadge></span>
                      ) : null}
                    </span>
                    <span data-label="สี / เนื้อผิว" className="flex flex-col gap-0.5">
                      {item.color && <span>{item.color}</span>}
                      {item.texture && <small className="text-text-muted">{item.texture}</small>}
                      {item.size && <small className="text-text-muted">{item.size}</small>}
                    </span>
                    <span data-label="จำนวน">
                      {item.unitBasis === 'SQM'
                        ? <>{item.qtySqm != null ? `${Number(item.qtySqm).toFixed(2)} ตร.ม.` : '—'}{hasRealTileCount(item) && <small className="block text-text-muted">{item.qty} แผ่น</small>}</>
                        : <>{item.qty} แผ่น{item.qtySqm != null && <small className="block text-text-muted">{Number(item.qtySqm).toFixed(2)} ตร.ม.</small>}</>
                      }
                    </span>
                    {showCalcBreakdown ? (
                      <>
                        <span data-label="ราคาโรงงาน" className="text-xs">
                          {item.rawPrice != null
                            ? (() => {
                              // Original currency is the figure of record; the baht line underneath is a straight FX conversion (no markup)
                              // and is omitted entirely when no rate exists for that currency, rather than implying a 1:1 rate.
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
                          {/* A read-only readout of whatever the 3 stranded legacy tickets already
                              carry (the ticket-native CEO manual override is retired). */}
                          {item.manualPrice != null && <small className="block text-2xs text-override">override</small>}
                        </span>
                      </>
                    ) : (
                      <>
                        {showProposed && <code data-label="ราคาที่เสนอ">{formatMoney(item.proposedPrice)}</code>}
                        {showApproved && (
                          <span data-label="ราคาที่อนุมัติ">
                            <code>{formatMoney(item.approvedPrice)}</code>
                          </span>
                        )}
                      </>
                    )}
                  </div>
                ))}
              </>
            )}

            {/* The empty-draft hint travels with can.editItems into the items panel. */}
            {st === 'draft' && isOwner && items.length === 0 && (
              <div className="p-[0_18px_16px]">
                <span className="rounded-md border border-border bg-surface-subtle px-3 py-2 text-xs text-text-muted">
                  ดีลนี้ยังไม่มีรายการสินค้า — กด “แก้ไขรายการสินค้า” เพื่อเพิ่มก่อนส่งขอราคา
                </span>
              </div>
            )}
          </Panel>

          {/* id: the sticky bar's CREATE_PCR jump target (see IN_PAGE_JUMP_TARGET above). The
              panel's render condition is `Boolean(sections.pricingRequest) &&
              canViewPricingRequests` (see ticketDetailTabs.js's `items` tab): `items` itself is
              unconditionally visible, so nothing upstream enforces `sections.pricingRequest`. */}
          <div id="pricing-request-panel" tabIndex={-1} className="mobile:scroll-mt-[420px] outline-none">
            {canViewPricingRequests && Boolean(sections.pricingRequest) ? (
              <PricingRequestPanel ref={pricingRequestPanelRef} ticketId={ticketId} deal={summary} ticketItems={items} user={user} showToast={showToast} />
            ) : null}
          </div>
      </TabPanel>

      <TabPanel id="documents" idPrefix="ticket-detail" active={visibleActiveTab === 'documents'}>
          {/* The เอกสาร document register: one read-only roll-up of every document family
              (quotations below, the deposit notice + remaining invoice, formal attachments), gated
              PER ROW FAMILY inside the component (this tab's `isVisible` is `() => true`, see
              ticketDetailTabs.js). `attachments`/`attachLoading` are the SAME query the ประวัติ
              tab's DealAttachmentsPanel uses (gated at that query's `enabled` by
              `canViewDocumentsTab`, so it never fires an extra request, nor at all for a viewer it
              would 403), so the two roll-ups can't drift. See DealDocumentRegister.jsx's header
              comment for the three gates and their backend citations. */}
          <DealDocumentRegister
            depositCtaShown={workStateAction?.key === 'create_deposit_notice'}
            ticketId={ticketId}
            user={user}
            summary={summary}
            sections={sections}
            canViewPricingRequests={canViewPricingRequests}
            canViewDocumentsTab={canViewDocumentsTab}
            pricingRequests={pricingRequests}
            pricingRequestsLoading={canViewPricingRequests && pricingRequestsQuery.isLoading}
            legacyQuotations={sortedQuotations}
            attachments={attachments}
            attachLoading={attachLoading}
          />

          {/* "เอกสาร" tab: the customer-facing tail of the PricingRequest chain (issue/outcome +
              confirm-order) on the deal page. Renders nothing until a request reaches
              APPROVED_FOR_QUOTATION (see DealQuotationPanel's doc comment); the factory/costing/
              CEO-price steps before that stay on PricingRequestDetailPage, linked from inside the
              panel. The sticky bar's CONFIRM_ORDER CTA calls dealQuotationPanelRef directly, but the
              id/scroll-mt wrapper stays for any other in-page anchor. */}
          {sections.dealQuotation && canViewPricingRequests ? (
            <div id="deal-quotation-panel" tabIndex={-1} className="mobile:scroll-mt-[420px] outline-none">
              <DealQuotationPanel ref={dealQuotationPanelRef} ticketId={ticketId} pricingRequests={pricingRequests} quotationByRequest={quotationByRequest} user={user} showToast={showToast}
                depositMainButtonLabel={workStateAction?.key === 'create_deposit_notice' ? workStateAction.label : null}
                depositNoticeExists={depositNotice != null} />
            </div>
          ) : null}
          {/* Legacy ticket-native quotation rows join the PCR-based quotation tail above, in this
              tab (DealLegacyQuotations.jsx); it returns null itself when quotationGroups is empty,
              matching the `quotationGroups.length > 0` half of this gate. */}
          {sections.quotation ? (
            <DealLegacyQuotations
              quotationGroups={quotationGroups}
              downloadingQuotationKey={downloadingQuotationKey}
              handleDownloadQuotation={handleDownloadQuotation}
            />
          ) : null}
      </TabPanel>

      <TabPanel id="money" idPrefix="ticket-detail" active={visibleActiveTab === 'money'}>
          {/* The whole การเงิน tab is one "การชำระเงิน" panel (balance, the three document steps, the
              timeline), with the sections.payment audience. */}
          {sections.payment ? (
            <div>
              <DealPaymentSection
                ticketId={ticketId}
                user={user}
                summary={summary}
                events={events}
                paymentReceipts={paymentReceipts}
                paymentsLoading={paymentsQuery.isLoading}
                pricingRequests={pricingRequests}
                dealQuotations={dealQuotationRows}
                availableActions={availableActions}
                canReadDepositNotices={sections.depositNotice}
                canManageRemainingInvoice={can.downloadRemainingInvoice}
                onManageRemainingInvoice={() => setRemainingInvoiceDialogOpen(true)}
                mainButtonCreatesNotice={workStateAction?.key === 'create_deposit_notice'}
                onCreateDepositNotice={() => createDepositNoticeMutation.mutate()}
                canRecordPayment={can.recordPayment}
                actionLoading={actionLoading || createDepositNoticeMutation.isPending}
                onRecordPayment={openPaymentModal}
                showToast={showToast}
              />
            </div>
          ) : null}

          {/* A close-lifecycle action, so it belongs with การเงิน rather than สินค้าและราคา (gate:
              `can.revokeCloseConfirm`). Its own section, not nested in the `sections.payment`-gated
              panel: the real server gate (`canRevokeCloseConfirmation`, account/ceo-only) doesn't
              depend on `sections.payment`, though for both roles it is always true anyway (see
              ticketDetailTabs.js), so it can't be stranded behind a hidden `money` tab. */}
          {can.revokeCloseConfirm && (
            <Panel className="bg-surface-muted mt-4">
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
          {/* "การส่งมอบ / นำเข้า": the deal-level IR → shipping → goods-received → delivery chain
              (import/CEO act, sales/account/ceo see progress), plus an optional per-factory PO
              detail. Reuses the `delivery` section id (salesViewScope.js).
              id (DOM): the sticky bar's import-role fulfilment jump target. */}
          <div id="deal-fulfilment-panel" tabIndex={-1} className="mobile:scroll-mt-[420px] outline-none">
            {sections.delivery ? (
              <DealFulfilmentPanel
                ref={fulfilmentPanelRef}
                ticketId={ticketId}
                user={user}
                summary={summary}
                items={items}
                availableActions={availableActions}
                procurementSource={procurementSource}
                onChooseProcurementSource={() => setProcurementDialogOpen(true)}
                showToast={showToast}
              />
            ) : null}
          </div>
      </TabPanel>

      <TabPanel id="history" idPrefix="ticket-detail" active={visibleActiveTab === 'history'}>
          {/* "ประวัติดีล": merges the ticket's audit trail (ticket.events) with the deal-tracking
              activity log into one chronological stream (regions 17+18 of
              TICKET_INFORMATION_ARCHITECTURE.md). The audit trail and the comment box
              (`can.comment`) are backed by `TicketService.projectForRole`/`comment`'s
              `requireViewAccess`, which every viewer of the deal passes, so they are NOT re-gated on
              `canViewDealTracking`; only the follow-up feed (`activities`) and its add-activity form
              stay gated on `requireDealOwnership` (TicketIaAuthzMatrixIntegrationTest's activities_*
              cases). DealTrackingPanel is in the `deal` tab. */}
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

          {/* DealAttachmentsPanel lives in this unconditionally-visible tab behind an INNER render
              condition, `canViewDocumentsTab` (createdById/assignedToId participant OR
              ROLE_PERMISSIONS.canViewTicketDocuments; see its doc comment). Anyone it excludes
              still reaches the history stream above, just not this panel. `canUpload`/`notTerminal`
              are computed here so the child only sees clean booleans (they reproduce the original
              `!TERMINAL.includes(st) && ...` guards). */}
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

      {/* "จัดการดีล" danger zone (ticket-detail IA rebuild Phase 1): เสียงาน / ยกเลิก moved off the working surface
       * into a clearly-labelled, danger-toned section at the very bottom of the page — conventional danger-zone
       * pattern, no new route. */}
      {(canLostDeal || can.cancel) ? (
        <section className="rounded-md border border-danger-border bg-danger-bg p-4 sm:p-5" aria-labelledby="deal-danger-zone-heading">
          <h2 id="deal-danger-zone-heading" className="m-0 text-sm font-extrabold text-danger-dark">จัดการดีล</h2>
          <p className="mt-1 text-xs text-danger-dark">การดำเนินการเหล่านี้ส่งผลต่อทั้งดีล และบางรายการย้อนกลับไม่ได้ — ใช้เมื่อจำเป็นเท่านั้น</p>
          <div className="mt-3 flex flex-wrap gap-2">
            {canLostDeal ? (
              // Button's own `danger` variant (bg-surface text-danger border-danger-border) is byte-identical to this
              // hand-rolled secondary+override, so it replaces both here.
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
                <ThaiDateInput value={paymentDraft.receivedAt}
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

      {/* ขอแก้ไข (Revise) is a modal, like paymentModal above: an inline section opened ~400px below
          its overflow-menu trigger and forced a tab switch plus scroll-into-view. handleOpenRevise
          is a plain flag flip (see its doc comment). */}
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
              {/* This button is already disabled={!reviseReason.trim()}, so the guard inside onClick
                  is defensive and unreachable through the UI; it is wired for consistency with the
                  other forms. */}
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

      {/* Delivery/stock-reservation modals moved into DealFulfilmentPanel (Phase 3 Slice S4). */}

      {remainingInvoiceDialogOpen ? (
        // mirrors RemainingInvoiceService's own write gate — sales role + this deal's own owner, see
        // DealDocumentRegister.jsx's identical predicate.
        <RemainingInvoiceDialog ticketId={ticketId} canWrite={role === 'sales' && isOwner}
          onClose={() => setRemainingInvoiceDialogOpen(false)} />
      ) : null}

      {procurementDialogOpen ? (
        <ProcurementSourceDialog
          open
          ticketId={ticketId}
          items={items}
          procurementSource={procurementSource}
          onClose={() => setProcurementDialogOpen(false)}
          onDecided={(decided, { anyImport }) => {
            setProcurementDialogOpen(false);
            applyTicketUpdate(decided);
            // Decided now: flip the cached read first so the CTA never offers จัดซื้อสินค้า again during the refetch.
            queryClient.setQueryData(queryKeys.procurementSource(ticketId), (old) => (old ? { ...old, decidedAt: new Date().toISOString(), editable: false, pending: false } : old));
            queryClient.invalidateQueries({ queryKey: queryKeys.procurementSource(ticketId) });
            // The IR itself is made from the main button that follows, never from here.
            if (anyImport) setActiveTab('fulfilment');
          }}
        />
      ) : null}

      <ConfirmDialog
        open={confirm?.kind === 'deleteAttachment'}
        tone="danger"
        title="ลบไฟล์"
        message={`ลบไฟล์ "${confirm?.name}" ออก?`}
        busy={deletingAttachment}
        onCancel={() => setConfirm(null)}
        onConfirm={() => confirmDeleteAttachment(confirm?.id)}
      />

      {/* A bare yes/no confirm can't capture WHY, and cancel is irreversible — so this is the mark-lost modal's
       * reason picker, not a ConfirmDialog. */}
      {confirm?.kind === 'cancelTicket' && (
        <CancelDealModal
          catalog={stageCatalog}
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
          // Same summary.amountOutstanding the การชำระเงิน panel above renders as "คงเหลือ" (line ~1091) — reused as-is,
          // not recomputed, so this dialog can never disagree with the panel the user just looked at.
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

      {/* W4-10 (owner ruling 2026-10-30): the CEO's close cannot be undone, so it asks first. */}
      <ConfirmDialog
        open={confirm?.kind === 'verifyClose'}
        title="ยืนยันตรวจสอบและปิดงาน"
        message="เมื่อตรวจสอบและปิดงานแล้ว ดีลนี้จะปิดและย้อนกลับไม่ได้"
        confirmLabel="ตรวจสอบและปิดงาน"
        busy={actionLoading}
        // Close first: a second click on the open dialog would send twice.
        onConfirm={() => { setConfirm(null); doAction(() => api.tickets.verifyClose(ticketId), 'ตรวจสอบและปิดงานแล้ว'); }}
        onCancel={() => setConfirm(null)}
      />

      {/* Slice 2 §E — the sticky "ยืนยันคำสั่งซื้อ" of an APPROVED direct quotation. The copy is the quotation
       * editor's own (quotationMeta's CONFIRM_ORDER_FROM_QUOTATION_COPY), so the two doors to the same irreversible
       * act say the same thing. */}
      {bidOutcomeOpen ? (
        <BidOutcomeModal
          ticketId={ticketId}
          summary={summary}
          projectName={summary.projectName}
          parties={parties}
          quotations={dealQuotationRows}
          onClose={() => setBidOutcomeOpen(false)}
          showToast={showToast}
        />
      ) : null}

      <ConfirmOrderDialog
        open={confirm?.kind === 'confirmOrderDirect'}
        number={confirm?.number}
        depositPercent={confirmedRow?.depositPercent}
        cancelledNumbers={confirm?.kind === 'confirmOrderDirect' ? openDirectRowsOtherThan(confirm.quotationId).map((q) => q.number) : undefined}
        cancelLabel="ปิด"
        // Until the row is read the deposit wording (it may be 0%) is unknown: no confirm yet.
        busy={confirmOrderDirectMutation.isPending || (confirm?.kind === 'confirmOrderDirect' && !confirmedRow)}
        // Close first (as the pricing-request door does): a second click on the open dialog would send twice.
        onConfirm={() => { const { quotationId } = confirm; setConfirm(null); confirmOrderDirectMutation.mutate(quotationId); }}
        onCancel={() => setConfirm(null)}
      />
    </div>
  );
}
