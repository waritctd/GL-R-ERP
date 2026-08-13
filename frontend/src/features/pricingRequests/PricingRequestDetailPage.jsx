import { Fragment, useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { hasPermission } from '../../app/permissions.js';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { Icon } from '../../components/common/Icon.jsx';
import { Button } from '../../components/common/Button.jsx';
import { ConfirmDialog } from '../../components/common/ConfirmDialog.jsx';
import { FormField } from '../../components/common/FormField.jsx';
import { Panel } from '../../components/common/Layout.jsx';
import { PageHeader } from '../../components/common/PageHeader.jsx';
import { Skeleton, SkeletonText } from '../../components/common/Skeleton.jsx';
import { StatePanel } from '../../components/common/StatePanel.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import {
  factoryQuoteStatusLabel,
  formatMoney,
  formatThaiDate,
  pricingCostingStatusLabel,
  pricingDecisionStatusLabel,
  pricingRequestStatusLabel,
  quotationStatusLabel,
} from '../../utils/format.js';
import { downloadBlob } from '../../utils/download.js';
import { toUserErrorDescription, toUserErrorMessage } from '../../utils/userMessages.js';
import {
  canActOnPricingDecision,
  canConfirmOrder,
  canCreateCommercialOnlyRevision,
  canCreateCustomerQuotation,
  canCreateDepositNoticeFromQuotation,
  canManageCustomerQuotation,
  canRecordCustomerQuotationOutcome,
  canSeePricingDecisionSalesView,
  canSeeRawPricingDecision,
  canStartCeoReview,
  canViewCustomerQuotation,
  isCustomerQuotationEditable,
  isCustomerQuotationDiscountEditable,
  canTransition,
  pricingRequestRecipientLabel,
  unitBasisLabel,
} from './pricingRequestMeta.js';
import { PricingRequestCreateModal } from './PricingRequestCreateModal.jsx';
import { buttonVariants } from '../../components/common/Button.jsx';
import { cn } from '../../utils/cn.js';

function isImport(user) {
  return user?.role === 'import';
}

function isSales(user) {
  return user?.role === 'sales';
}

function canSeeRaw(user) {
  return user?.role === 'import' || user?.role === 'ceo';
}

const DISPATCH_STATUS_LABEL = {
  PENDING: 'รอส่ง',
  SENDING: 'กำลังส่ง',
  SENT: 'ส่งแล้ว',
  FAILED: 'ส่งไม่สำเร็จ',
};

function dispatchStatusBadge(quote) {
  const status = quote?.dispatchStatus;
  if (!status || status === 'SENT') return null;
  const tone = status === 'FAILED' ? 'danger' : 'warning';
  const attempt = quote.dispatchAttemptCount > 1 ? ` (ครั้งที่ ${quote.dispatchAttemptCount})` : '';
  return (
    <StatusBadge key={`dispatch-${quote.id}`} tone={tone}>
      {(DISPATCH_STATUS_LABEL[status] ?? status) + attempt}
    </StatusBadge>
  );
}

function PricingRequestDetailSkeleton() {
  return (
    <div className="grid w-[min(760px,100%)] gap-3" aria-hidden="true">
      <div className="grid gap-2 rounded-md border border-border bg-surface p-4">
        <Skeleton height={24} width="42%" />
        <Skeleton height={14} width="64%" />
        <div className="grid gap-2 pt-2 sm:grid-cols-2">
          <Skeleton height={16} />
          <Skeleton height={16} />
          <Skeleton height={16} />
          <Skeleton height={16} />
        </div>
      </div>
      <div className="grid gap-2 rounded-md border border-border bg-surface p-4">
        <Skeleton height={18} width="34%" />
        <Skeleton height={58} />
        <Skeleton height={58} />
      </div>
      <div className="grid gap-2 rounded-md border border-border bg-surface p-4">
        <Skeleton height={18} width="28%" />
        <SkeletonText lines={2} />
      </div>
    </div>
  );
}

function apiStatus(error) {
  return typeof error?.status === 'number' ? error.status : null;
}

/**
 * Seeds the "record the factory's answer" draft. Owner ruling 2026-08-11: Import types the PRICE
 * and nothing else — quantity, unit and currency all come from what Sales already requested, so
 * they are carried in this state (the backend still requires them) but never rendered as inputs.
 *
 * `requestItem` is the sales-side PricingRequestItem this quote line was generated from, looked up
 * by pricingRequestItemId. The currency fallback chain matters: `catalogCurrency` is the currency
 * of the catalog row Sales picked (EUR for the European factories), and it must win over the old
 * hardcoded 'THB'. That default was a real defect — CDE trades in EUR, so a price typed as 46 was
 * being stored as ฿46 instead of €46, a 38.5x error straight into the landed cost.
 */
function defaultResponseItems(quote, requestItemById = new Map()) {
  return (quote?.items ?? []).map((item) => {
    const requestItem = requestItemById.get(item.pricingRequestItemId) ?? {};
    const unit = item.unitBasis ?? item.quotedUnit ?? requestItem.requestedUnitBasis ?? 'PER_PIECE';
    return {
    pricingRequestItemId: item.pricingRequestItemId,
    supplierProductCode: item.supplierProductCode ?? '',
    supplierProductDescription: item.supplierProductDescription ?? '',
    quotedQuantity: item.quotedQuantity ?? requestItem.requestedQty ?? 1,
    quotedUnit: unit,
    unitBasis: unit,
    rawUnitPrice: item.rawUnitPrice ?? '',
    currency: item.currency ?? quote.defaultCurrency ?? requestItem.catalogCurrency ?? 'THB',
    minimumOrderQuantity: item.minimumOrderQuantity ?? '',
    sqmPerUnit: item.sqmPerUnit ?? '',
    piecesPerBox: item.piecesPerBox ?? '',
    leadTimeText: item.leadTimeText ?? '',
    availabilityNote: item.availabilityNote ?? '',
    lineNote: item.lineNote ?? '',
    };
  });
}

function cleanNumber(value) {
  if (value === '' || value == null) return null;
  return Number(value);
}

function generateClientRequestId() {
  return crypto.randomUUID?.()
    ?? '00000000-0000-4000-8000-' + String(Date.now()).slice(-12).padStart(12, '0');
}

function formatCurrency(value, currency = 'THB') {
  if (value == null || value === '') return '-';
  return currency === 'THB' ? formatMoney(value) : `${Number(value).toLocaleString('en-US')} ${currency}`;
}

function cleanResponsePayload(draft) {
  return {
    supplierQuoteRef: draft.supplierQuoteRef || null,
    // Falls back to the first line's currency (itself sourced from the catalog row Sales picked)
    // rather than a hardcoded 'THB' — see defaultResponseItems for why that default was a defect.
    defaultCurrency: draft.defaultCurrency || draft.items?.[0]?.currency || 'THB',
    paymentTerms: draft.paymentTerms || null,
    leadTimeText: draft.leadTimeText || null,
    revisionReason: draft.revisionReason || null,
    negotiationNote: draft.negotiationNote || null,
    items: draft.items.map((item) => ({
      ...item,
      rawUnitPrice: cleanNumber(item.rawUnitPrice),
      quotedQuantity: cleanNumber(item.quotedQuantity),
      minimumOrderQuantity: cleanNumber(item.minimumOrderQuantity),
      sqmPerUnit: cleanNumber(item.sqmPerUnit),
      piecesPerBox: cleanNumber(item.piecesPerBox),
    })),
  };
}

export function PricingRequestDetailPage({ user, showToast }) {
  const { id } = useParams();
  const pricingRequestId = Number(id);
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [responseDrafts, setResponseDrafts] = useState({});
  const [costingNote, setCostingNote] = useState('');
  const [costingClientRequestId] = useState(() => generateClientRequestId());
  const [emailDrafts, setEmailDrafts] = useState({});
  const [sendClientRequestIds, setSendClientRequestIds] = useState({});
  const [receiveClientRequestIds, setReceiveClientRequestIds] = useState({});
  const [confirmAction, setConfirmAction] = useState(null);
  // Review remediation (COMMIT 5, P1 finding 3): the customer-change revision UI now reuses
  // PricingRequestCreateModal in mode="revision" (seeded from the current request, full item
  // editing, catalog picker, unit select) instead of the old inline reason-only form that copied
  // every field verbatim via the now-deleted revisionPayload() helper.
  const [revisionModalOpen, setRevisionModalOpen] = useState(false);

  const detailQuery = useQuery({
    queryKey: queryKeys.pricingRequestDetail(pricingRequestId),
    queryFn: () => api.pricingRequests.get(pricingRequestId).then((r) => r.pricingRequest),
    enabled: Number.isFinite(pricingRequestId),
  });

  const factoryQuery = useQuery({
    queryKey: queryKeys.pricingRequestFactoryQuotes(pricingRequestId),
    queryFn: () => api.pricingRequests.listFactoryQuotes(pricingRequestId).then((r) => r.items ?? []),
    enabled: Number.isFinite(pricingRequestId) && canSeeRaw(user),
    // The outbox worker sends/finalizes a factory quote dispatch out-of-band (send() only
    // enqueues), so while any quote has one in flight, poll instead of leaving the UI stuck
    // showing a stale "PENDING"/"SENDING" badge until the next unrelated invalidate.
    refetchInterval: (query) => {
      const quotes = query.state.data ?? [];
      return quotes.some((q) => ['PENDING', 'SENDING'].includes(q.dispatchStatus)) ? 2000 : false;
    },
  });

  const costingQuery = useQuery({
    queryKey: queryKeys.pricingRequestCostings(pricingRequestId),
    queryFn: () => api.pricingRequests.listCostings(pricingRequestId).then((r) => r.items ?? []),
    enabled: Number.isFinite(pricingRequestId) && canSeeRaw(user),
  });

  // Step 3: CEO Selling Price Decision. Raw (cost/margin-bearing) history is import/ceo only
  // (design correction 2 — never leak cost to Sales); this query must never even fire for a
  // sales/sales_manager actor, not just be hidden in the DOM.
  const decisionsQuery = useQuery({
    queryKey: queryKeys.pricingDecisions(pricingRequestId),
    queryFn: () => api.pricingRequests.listPricingDecisions(pricingRequestId).then((r) => r.items ?? []),
    enabled: Number.isFinite(pricingRequestId) && canSeeRawPricingDecision(user),
  });

  // Sales-facing approved-price projection — a distinct query/DTO, not a client-side filter of
  // decisionsQuery above (which sales never even fetches).
  const decisionSalesViewQuery = useQuery({
    queryKey: queryKeys.pricingDecisionSalesView(pricingRequestId),
    queryFn: () => api.pricingRequests.getPricingDecisionSalesView(pricingRequestId).then((r) => r.decision),
    enabled: Number.isFinite(pricingRequestId) && !canSeeRawPricingDecision(user)
      && canSeePricingDecisionSalesView(user, detailQuery.data?.summary),
    retry: false,
  });

  // Step 4: Customer Quotation Generation and Issuance. Every viewer role canViewCustomerQuotation
  // allows may fetch the list (owner-scoped for sales, same as the sales-view decision query
  // above); account never fires this query, matching its total exclusion server-side.
  const customerQuotationsQuery = useQuery({
    queryKey: queryKeys.customerQuotations(pricingRequestId),
    queryFn: () => api.pricingRequests.listCustomerQuotations(pricingRequestId).then((r) => r.items ?? []),
    enabled: Number.isFinite(pricingRequestId) && canViewCustomerQuotation(user, detailQuery.data?.summary),
  });

  // Pricing Request attachments (V69, review remediation COMMIT 4): Sales-level supporting
  // attachments on the request itself — every viewer role can see the list (requireViewable's
  // usual scoping already applies server-side: a non-owner sales rep never even reaches this
  // page's detailQuery, so there is no separate check needed here).
  const attachmentsQuery = useQuery({
    queryKey: queryKeys.pricingRequestAttachments(pricingRequestId),
    queryFn: () => api.pricingRequests.listAttachments(pricingRequestId).then((r) => r.items ?? []),
    enabled: Number.isFinite(pricingRequestId),
  });

  function invalidate() {
    queryClient.invalidateQueries({ queryKey: queryKeys.pricingRequestDetail(pricingRequestId) });
    queryClient.invalidateQueries({ queryKey: queryKeys.pricingRequestFactoryQuotes(pricingRequestId) });
    queryClient.invalidateQueries({ queryKey: queryKeys.pricingRequestCostings(pricingRequestId) });
    queryClient.invalidateQueries({ queryKey: queryKeys.pricingRequestAttachments(pricingRequestId) });
    queryClient.invalidateQueries({ queryKey: queryKeys.pricingDecisions(pricingRequestId) });
    queryClient.invalidateQueries({ queryKey: queryKeys.pricingDecisionSalesView(pricingRequestId) });
    queryClient.invalidateQueries({ queryKey: queryKeys.customerQuotations(pricingRequestId) });
    queryClient.invalidateQueries({ queryKey: ['pricingRequests', 'queue'] });
  }

  const canReturnToPricingQueue = hasPermission(user?.role, 'canViewPricingRequestQueue');
  const returnToSafeList = () => {
    if (canReturnToPricingQueue) {
      navigate('/pricing-requests');
      return;
    }
    navigate(-1);
  };

  function useActionMutation(fn, successMessage) {
    return useMutation({
      mutationFn: fn,
      onSuccess: () => {
        showToast?.('success', successMessage);
        invalidate();
      },
      onError: (error) => showToast?.('error', error.message || 'ดำเนินการไม่สำเร็จ'),
    });
  }

  const generateDrafts = useActionMutation(() => api.pricingRequests.generateFactoryEmailDrafts(pricingRequestId), 'สร้างร่างอีเมลแล้ว');
  const updateQuote = useActionMutation(({ quote, draft }) => api.pricingRequests.updateFactoryQuote(quote.id, draft), 'บันทึกร่างอีเมลแล้ว');
  const sendQuote = useActionMutation(({ quote, draft }) => api.pricingRequests.sendFactoryQuote(quote.id, {
    emailTo: draft?.emailTo ?? quote.emailTo,
    emailSubject: draft?.emailSubject ?? quote.emailSubject,
    emailBody: draft?.emailBody ?? quote.emailBody,
    clientRequestId: sendClientRequestIds[quote.id] ?? generateClientRequestId(),
  }), 'ส่งคำขอโรงงานแล้ว');
  const receiveQuote = useMutation({
    mutationFn: ({ quote, draft, clientRequestId }) => api.pricingRequests.receiveFactoryQuote(quote.id, {
      ...cleanResponsePayload(draft),
      clientRequestId,
    }),
    onSuccess: (_, variables) => {
      // A successful submission consumes this idempotency key; a later distinct
      // response/revision for the same quote must mint a fresh one, not replay.
      setReceiveClientRequestIds((cur) => {
        const next = { ...cur };
        delete next[variables.quote.id];
        return next;
      });
      showToast?.('success', 'บันทึกราคาโรงงานแล้ว');
      invalidate();
    },
    onError: (error) => showToast?.('error', error.message || 'ดำเนินการไม่สำเร็จ'),
  });
  const negotiateQuote = useActionMutation((quote) => api.pricingRequests.startFactoryNegotiation(quote.id, { note: quote.negotiationNote || 'Negotiation in progress' }), 'เริ่มเจรจาแล้ว');
  /**
   * The whole Import -> CEO handoff as ONE action (owner ruling 2026-08-11: "import just have to
   * key in the price and submit to ceo"). Import no longer sees the costing aggregate at all; the
   * landed cost is still computed from the real freight/duty tables, just never surfaced here.
   *
   * ONE backend call. This used to chain four —
   * markFactoryQuoteReady -> createCosting -> recalculateCosting -> submitCosting — but V141
   * (PR #702) moved landed costing to the CEO and SEVERED the last three: PricingCostingService's
   * createDraft/recalculate/submit are @Deprecated shells whose entire body throws
   * 409 COSTING_MOVED_TO_CEO. Because the routes still exist, the contract guards counted them as
   * reached and nothing here noticed. The user-visible result was the worst possible shape: step 1
   * really did advance the request AND notify the CEO, then step 2's 409 fired the error toast and
   * skipped onSuccess, so invalidate() never ran and the page kept showing the stale status. See
   * issue #729.
   *
   * markFactoryQuoteReady is now the whole job: FactoryQuoteService.markReadyForCosting
   * (:612-660) marks the quote ready and, once LandedCostCalculator.isFullyResolvable agrees every
   * item's quote can be costed, auto-advances AWAITING_FACTORY_RESPONSE -> READY_FOR_CEO_REVIEW,
   * logs PRICING_COSTING_SUBMITTED and notifies the CEO itself.
   *
   * On a multi-factory request with a quote still outstanding the call still succeeds — it marks
   * THIS quote ready — and the request stays put until the last factory's quote is marked. That is
   * the backend's own semantics, so the success toast below is deliberately about the quote's
   * hand-off, not a claim that the CEO now has the whole request.
   */
  const submitToCeo = useMutation({
    mutationFn: (quote) => api.pricingRequests.markFactoryQuoteReady(quote.id),
    onSuccess: () => {
      showToast?.('success', 'ส่งราคาให้ CEO แล้ว');
      invalidate();
    },
    onError: (error) => showToast?.('error', error.message || 'ส่งให้ CEO ไม่สำเร็จ'),
  });

  const createCosting = useActionMutation(() => api.pricingRequests.createCosting(pricingRequestId, { note: costingNote || null, clientRequestId: costingClientRequestId }), 'สร้างร่างต้นทุนแล้ว');
  const recalculateCosting = useActionMutation((costing) => api.pricingRequests.recalculateCosting(costing.id, { note: costingNote || null }), 'คำนวณต้นทุนแล้ว');
  const submitCosting = useActionMutation((costing) => api.pricingRequests.submitCosting(costing.id, { note: costingNote || null }), 'ส่งให้ CEO แล้ว');
  const uploadQuoteAttachment = useActionMutation(({ quote, file }) => api.pricingRequests.uploadFactoryQuoteAttachment(quote.id, file), 'แนบไฟล์ราคาโรงงานแล้ว');
  const uploadPricingRequestAttachment = useActionMutation((file) => api.pricingRequests.uploadAttachment(pricingRequestId, file), 'แนบไฟล์แล้ว');
  const deletePricingRequestAttachment = useActionMutation((attachmentId) => api.pricingRequests.deleteAttachment(attachmentId), 'ลบไฟล์แนบแล้ว');
  const toggleAttachmentIncludeInFactoryEmail = useActionMutation(
    (attachment) => api.pricingRequests.setAttachmentIncludeInFactoryEmail(attachment.id, !attachment.includeInFactoryEmail),
    'อัปเดตไฟล์แนบแล้ว',
  );
  // Step 3: CEO Selling Price Decision.
  const [decisionDefaultMargin, setDecisionDefaultMargin] = useState('0.20');
  const [startReviewClientRequestId] = useState(() => generateClientRequestId());
  const [decisionItemDrafts, setDecisionItemDrafts] = useState({});
  const [approveClientRequestId, setApproveClientRequestId] = useState(() => generateClientRequestId());
  // Step 4: Customer Quotation Generation and Issuance.
  const [createQuotationClientRequestId, setCreateQuotationClientRequestId] = useState(() => generateClientRequestId());
  const [issueQuotationClientRequestId, setIssueQuotationClientRequestId] = useState(() => generateClientRequestId());
  const [revisionClientRequestId, setRevisionClientRequestId] = useState(() => generateClientRequestId());
  const [quotationHeaderDraft, setQuotationHeaderDraft] = useState({});
  const [quotationItemDrafts, setQuotationItemDrafts] = useState({});
  const [downloadingQuotationFormat, setDownloadingQuotationFormat] = useState(null);
  // Step 5: Customer Decision and Commercial Revisions.
  const [outcomeClientRequestId, setOutcomeClientRequestId] = useState(() => generateClientRequestId());
  const [outcomeNote, setOutcomeNote] = useState('');
  // Step 6: Deposit, Payment, and Order Confirmation.
  const [confirmOrderClientRequestId, setConfirmOrderClientRequestId] = useState(() => generateClientRequestId());
  const [depositPercentInput, setDepositPercentInput] = useState('0.5');
  const startCeoReview = useActionMutation(
    () => api.pricingRequests.startPricingDecision(pricingRequestId, {
      defaultMarginPct: cleanNumber(decisionDefaultMargin),
      clientRequestId: startReviewClientRequestId,
    }),
    'เริ่มพิจารณาราคาขายแล้ว',
  );
  const saveDecisionItems = useActionMutation(({ decision, items }) => api.pricingRequests.updatePricingDecision(decision.id, {
    items: items.map((item) => {
      const draft = decisionItemDrafts[item.id] ?? {};
      return {
        pricingDecisionItemId: item.id,
        marginPct: cleanNumber(draft.marginPct ?? item.proposedMarginPct),
        discountCeilingPct: cleanNumber(draft.discountCeilingPct ?? item.discountCeilingPct),
        minimumSellingPrice: cleanNumber(draft.minimumSellingPrice ?? item.minimumSellingPricePerRequestedUnit),
        decisionNote: draft.decisionNote ?? item.decisionNote ?? null,
      };
    }),
  }), 'บันทึกราคาขายที่เสนอแล้ว');
  const approveDecision = useMutation({
    mutationFn: (decision) => api.pricingRequests.approvePricingDecision(decision.id, {
      clientRequestId: approveClientRequestId,
    }),
    onSuccess: () => {
      setApproveClientRequestId(generateClientRequestId());
      showToast?.('success', 'อนุมัติราคาขายแล้ว');
      invalidate();
    },
    onError: (error) => showToast?.('error', error.message || 'ดำเนินการไม่สำเร็จ'),
  });
  const returnDecisionToImport = useActionMutation(
    ({ decision, reason }) => api.pricingRequests.returnPricingDecisionToImport(decision.id, { returnReason: reason }),
    'ตีกลับให้ฝ่ายนำเข้าแก้ไขต้นทุนแล้ว',
  );
  // Step 4: Customer Quotation Generation and Issuance.
  const createQuotation = useMutation({
    mutationFn: () => api.pricingRequests.createCustomerQuotation(pricingRequestId, {
      clientRequestId: createQuotationClientRequestId,
    }),
    onSuccess: () => {
      setCreateQuotationClientRequestId(generateClientRequestId());
      showToast?.('success', 'สร้างร่างใบเสนอราคาลูกค้าแล้ว');
      invalidate();
    },
    onError: (error) => showToast?.('error', error.message || 'ดำเนินการไม่สำเร็จ'),
  });
  const saveQuotation = useActionMutation((quotation) => api.pricingRequests.updateCustomerQuotation(quotation.id, {
    paymentTerms: quotationHeaderDraft.paymentTerms ?? quotation.paymentTerms,
    leadTime: quotationHeaderDraft.leadTime ?? quotation.leadTime,
    deliveryTerms: quotationHeaderDraft.deliveryTerms ?? quotation.deliveryTerms,
    validityDate: quotationHeaderDraft.validityDate ?? quotation.validityDate,
    customerNotes: quotationHeaderDraft.customerNotes ?? quotation.customerNotes,
    items: quotation.items.map((item) => {
      const draft = quotationItemDrafts[item.id] ?? {};
      return {
        quotationItemId: item.id,
        description: draft.description ?? item.description,
        itemNotes: draft.itemNotes ?? item.itemNotes,
        salesDiscount: cleanNumber(draft.salesDiscount ?? item.salesDiscount) ?? 0,
      };
    }),
  }), 'บันทึกใบเสนอราคาแล้ว');
  const issueQuotation = useMutation({
    mutationFn: (quotation) => api.pricingRequests.issueCustomerQuotation(quotation.id, {
      clientRequestId: issueQuotationClientRequestId,
    }),
    onSuccess: () => {
      setIssueQuotationClientRequestId(generateClientRequestId());
      showToast?.('success', 'ออกใบเสนอราคาลูกค้าแล้ว');
      invalidate();
    },
    onError: (error) => showToast?.('error', error.message || 'ดำเนินการไม่สำเร็จ'),
  });
  const cancelQuotation = useActionMutation(
    (quotation) => api.pricingRequests.cancelCustomerQuotation(quotation.id, {}),
    'ยกเลิกร่างใบเสนอราคาแล้ว',
  );
  const createQuotationRevision = useMutation({
    mutationFn: (quotation) => api.pricingRequests.createCustomerQuotationRevision(quotation.id, {
      clientRequestId: revisionClientRequestId,
    }),
    onSuccess: () => {
      setRevisionClientRequestId(generateClientRequestId());
      setQuotationItemDrafts({});
      setQuotationHeaderDraft({});
      showToast?.('success', 'สร้างรอบแก้ไขใหม่แล้ว');
      invalidate();
    },
    onError: (error) => showToast?.('error', error.message || 'ดำเนินการไม่สำเร็จ'),
  });
  // Step 5: Customer Decision and Commercial Revisions.
  const recordQuotationOutcome = useMutation({
    mutationFn: ({ quotation, outcome }) => api.pricingRequests.recordCustomerQuotationOutcome(quotation.id, {
      outcome,
      customerNote: outcomeNote || null,
      clientRequestId: outcomeClientRequestId,
    }),
    onSuccess: () => {
      setOutcomeClientRequestId(generateClientRequestId());
      setOutcomeNote('');
      showToast?.('success', 'บันทึกผลใบเสนอราคาแล้ว');
      invalidate();
    },
    onError: (error) => showToast?.('error', error.message || 'ดำเนินการไม่สำเร็จ'),
  });
  // Step 6: Deposit, Payment, and Order Confirmation.
  const confirmOrder = useMutation({
    mutationFn: () => api.pricingRequests.confirmOrder(pricingRequestId, {
      clientRequestId: confirmOrderClientRequestId,
    }),
    onSuccess: () => {
      setConfirmOrderClientRequestId(generateClientRequestId());
      showToast?.('success', 'ยืนยันคำสั่งซื้อแล้ว');
      invalidate();
    },
    onError: (error) => showToast?.('error', error.message || 'ดำเนินการไม่สำเร็จ'),
  });
  const createDepositNoticeFromQuotation = useMutation({
    mutationFn: () => api.pricingRequests.createDepositNoticeFromQuotation(pricingRequestId, {
      depositPercent: cleanNumber(depositPercentInput),
    }),
    onSuccess: () => {
      showToast?.('success', 'สร้างร่างใบแจ้งยอดเงินรับมัดจำแล้ว');
      // Reuse the existing (legacy) deposit-notice page as-is — it already loads/edits/issues a
      // DRAFT by ticketId; the draft this just created is exactly what it will find and show.
      navigate(`/tickets/${summary.ticketId}/deposit`);
    },
    onError: (error) => showToast?.('error', error.message || 'ดำเนินการไม่สำเร็จ'),
  });
  /**
   * Copies the generated factory message so Import can paste it into its own mail client.
   * `navigator.clipboard` is absent in jsdom and on non-secure origins, so the failure path
   * reports honestly rather than pretending the copy happened — a silent no-op here would have
   * Import paste stale content without knowing.
   */
  async function copyFactoryEmail(emailDraft) {
    const text = [
      emailDraft.emailTo ? `To: ${emailDraft.emailTo}` : null,
      emailDraft.emailSubject ? `Subject: ${emailDraft.emailSubject}` : null,
      '',
      emailDraft.emailBody ?? '',
    ].filter((line) => line !== null).join('\n');
    try {
      if (!navigator.clipboard?.writeText) throw new Error('clipboard unavailable');
      await navigator.clipboard.writeText(text);
      showToast?.('success', 'คัดลอกข้อความแล้ว');
    } catch {
      showToast?.('error', 'คัดลอกไม่สำเร็จ — กรุณาเลือกข้อความแล้วคัดลอกเอง');
    }
  }

  async function handleDownloadCustomerQuotation(quotation, format) {
    setDownloadingQuotationFormat(format);
    try {
      const blob = format === 'pdf'
        ? await api.pricingRequests.downloadCustomerQuotationPdf(quotation.id)
        : await api.pricingRequests.downloadCustomerQuotationXlsx(quotation.id);
      downloadBlob(blob, quotation.number ?? 'customer-quotation', format);
    } catch (err) {
      showToast?.('error', err.message || 'ดาวน์โหลดไม่สำเร็จ');
    } finally {
      setDownloadingQuotationFormat(null);
    }
  }
  const request = detailQuery.data;
  const summary = request?.summary;
  const status = pricingRequestStatusLabel(summary?.status);
  // pricingRequestItemId -> the sales-side item it came from. Feeds both defaultResponseItems'
  // autofill and the read-only "what Sales asked for" echo on each response row. Declared here
  // rather than beside the mutations above because it reads `request`, which is assigned just
  // above this line — and there are no early returns between, so hook order stays stable.
  const requestItemById = useMemo(
    () => new Map((request?.items ?? []).map((item) => [item.id, item])),
    [request],
  );
  const factoryQuotes = useMemo(() => factoryQuery.data ?? [], [factoryQuery.data]);
  const costings = useMemo(() => costingQuery.data ?? [], [costingQuery.data]);
  const latestOpenCosting = useMemo(
    () => [...costings].reverse().find((costing) => ['DRAFT', 'CALCULATED'].includes(costing.status)),
    [costings],
  );
  const pricingDecisions = useMemo(() => decisionsQuery.data ?? [], [decisionsQuery.data]);
  // The currently-relevant decision: the open DRAFT if one exists (the CEO's active review),
  // else the most recent one (so a just-approved or just-returned decision still renders).
  const currentDecision = useMemo(
    () => pricingDecisions.find((d) => d.status === 'DRAFT') ?? [...pricingDecisions].reverse()[0] ?? null,
    [pricingDecisions],
  );
  const decisionSalesView = decisionSalesViewQuery.data;
  // Step 4: newest revision last (creation order) — the OPEN draft/ready-to-issue revision if
  // one exists, else the most recent (so a just-issued or just-cancelled quotation still shows).
  const customerQuotations = useMemo(
    () => [...(customerQuotationsQuery.data ?? [])].sort((a, b) => a.quotationRevisionNo - b.quotationRevisionNo),
    [customerQuotationsQuery.data],
  );
  const currentCustomerQuotation = useMemo(
    () => customerQuotations.find((q) => isCustomerQuotationEditable(q)) ?? [...customerQuotations].reverse()[0] ?? null,
    [customerQuotations],
  );
  const canCreateCustomerRevision = isSales(user)
    && summary?.ticketCreatedById === user?.employeeId
    && !['DRAFT', 'CANCELLED', 'SUPERSEDED'].includes(summary?.status);
  const pricingRequestAttachments = attachmentsQuery.data ?? [];
  // Mirrors PricingRequestService.ATTACHMENT_EDITABLE_STATUSES: Sales may only upload/delete its
  // own Pricing Request attachments while the request is DRAFT, and only on the request it owns.
  // V140 narrowed that set from {DRAFT, MORE_INFO_REQUIRED} to {DRAFT} when the ขอข้อมูลเพิ่มเติม
  // round-trip left the product. Offering the controls on any wider set would just produce a 409
  // from uploadAttachment/deleteAttachment.
  const canEditPricingRequestAttachments = isSales(user)
    && summary?.ticketCreatedById === user?.employeeId
    && summary?.status === 'DRAFT';
  const detailErrorStatus = apiStatus(detailQuery.error);

  if (detailQuery.isLoading) {
    return (
      <div className="grid w-full grid-cols-1 gap-[18px] min-w-0 max-w-[1320px]">
        <StatePanel
          state="loading"
          title="กำลังโหลดคำขอราคา"
          description="กำลังดึงรายละเอียดสินค้า ผู้รับ และสถานะล่าสุด"
        >
          <PricingRequestDetailSkeleton />
        </StatePanel>
      </div>
    );
  }

  if (detailQuery.isError) {
    if (detailErrorStatus === 404) {
      return (
        <div className="grid w-full grid-cols-1 gap-[18px] min-w-0 max-w-[1320px]">
          <StatePanel
            state="notFound"
            title="ไม่พบคำขอราคานี้"
            description="ตรวจสอบลิงก์อีกครั้ง หรือกลับไปเปิดจากรายการที่คุณเข้าถึงได้"
            action={(
              <Button type="button" variant="primary" onClick={returnToSafeList}>
                {canReturnToPricingQueue ? 'กลับไปที่คิวขอราคา' : 'กลับ'}
              </Button>
            )}
          />
        </div>
      );
    }

    if (detailErrorStatus === 403) {
      return (
        <div className="grid w-full grid-cols-1 gap-[18px] min-w-0 max-w-[1320px]">
          <StatePanel
            state="denied"
            title="ยังเปิดคำขอราคานี้ไม่ได้"
            description="ระบบไม่เปิดเผยรายละเอียดของคำขอราคาที่คุณไม่มีสิทธิ์เข้าถึง"
            action={(
              <Button type="button" variant="primary" onClick={returnToSafeList}>
                {canReturnToPricingQueue ? 'กลับไปที่คิวขอราคา' : 'กลับ'}
              </Button>
            )}
          />
        </div>
      );
    }

    return (
      <div className="grid w-full grid-cols-1 gap-[18px] min-w-0 max-w-[1320px]">
        <StatePanel
          state="error"
          title={toUserErrorMessage(detailQuery.error, 'โหลดคำขอราคาไม่สำเร็จ')}
          description={toUserErrorDescription(detailQuery.error, 'ลองใหม่อีกครั้ง หรือกลับไปที่รายการคำขอ')}
          action={(
            <Button type="button" variant="secondary" onClick={() => detailQuery.refetch()}>
              <Icon name="refresh" size={14} />
              ลองใหม่
            </Button>
          )}
          secondaryAction={(
            <Button type="button" variant="primary" onClick={returnToSafeList}>
              {canReturnToPricingQueue ? 'กลับไปที่คิวขอราคา' : 'กลับ'}
            </Button>
          )}
        />
      </div>
    );
  }

  if (!summary) {
    return (
      <div className="grid w-full grid-cols-1 gap-[18px] min-w-0 max-w-[1320px]">
        <StatePanel
          state="notFound"
          title="ไม่พบคำขอราคานี้"
          description="ตรวจสอบลิงก์อีกครั้ง หรือกลับไปเปิดจากรายการที่คุณเข้าถึงได้"
          action={(
            <Button type="button" variant="primary" onClick={returnToSafeList}>
              {canReturnToPricingQueue ? 'กลับไปที่คิวขอราคา' : 'กลับ'}
            </Button>
          )}
        />
      </div>
    );
  }

  return (
    <div className="grid w-full grid-cols-1 gap-[18px] min-w-0 max-w-[1320px]">
      <PageHeader
        title={summary.requestCode}
        subtitle={`${summary.customerName ?? '-'}${summary.projectName ? ` · ${summary.projectName}` : ''}`}
        actions={(
          <Button type="button" variant="secondary" onClick={() => navigate(-1)}>
            <Icon name="chevronLeft" size={14} />
            กลับ
          </Button>
        )}
      />

      <Panel flush title="ภาพรวม" actions={<StatusBadge tone={status.tone}>{status.label}</StatusBadge>}>
        <div className="grid gap-3 p-4 md:grid-cols-2">
          <div className="text-sm"><strong>ดีล</strong> <Link to={`/tickets/${summary.ticketId}`} className="text-info underline">{summary.ticketCode}</Link></div>
          <div className="text-sm"><strong>ผู้รับ</strong> {pricingRequestRecipientLabel(summary.recipientType)}{summary.recipientLabel ? ` · ${summary.recipientLabel}` : ''}</div>
          <div className="text-sm"><strong>ต้องการภายใน</strong> {formatThaiDate(summary.requiredDate)}</div>
          <div className="text-sm"><strong>ฝ่ายนำเข้า</strong> ผู้รับเรื่องและประสานราคาโรงงาน</div>
        </div>
      </Panel>

      <Panel flush title="รายการสินค้าและราคาตั้งต้น">
        <div className="flex flex-col gap-2 p-4">
          {(request.items ?? []).map((item) => (
            <div key={item.id} className="rounded-md border border-border bg-surface p-3">
              <div className="flex flex-wrap items-center gap-2">
                <strong>{[item.catalogBrand ?? item.brand, item.catalogModel ?? item.model].filter(Boolean).join(' ') || item.productDescription || '-'}</strong>
                <span className="text-xs text-text-muted">{item.requestedQty} {item.requestedUnit}</span>
              </div>
              <div className="mt-1 flex flex-wrap gap-x-4 gap-y-1 text-xs text-text-muted">
                <span>Factory: {item.resolvedFactoryName ?? item.factory ?? '-'}</span>
                <span>Catalog: {item.catalogProductCode ?? '-'}</span>
                <span>Base: {item.catalogBasePrice != null ? `${formatCurrency(item.catalogBasePrice, item.catalogCurrency ?? 'THB')} (preliminary)` : '-'}</span>
              </div>
            </div>
          ))}
        </div>
      </Panel>

      <Panel
        flush
        title="ไฟล์แนบประกอบคำขอราคา"
        actions={canEditPricingRequestAttachments ? (
          // Left as a <label> wrapping the hidden file input, not <Button>: a
          // <button> cannot open the native file picker the way a <label>
          // wrapping its <input type="file"> does.
          <label className={cn(buttonVariants({ variant: 'secondary' }), 'cursor-pointer')}>
            <input type="file" className="hidden" onChange={(event) => {
              const file = event.target.files?.[0];
              if (file) uploadPricingRequestAttachment.mutate(file);
              event.target.value = '';
            }} />
            <Icon name="upload" size={13} />
            แนบไฟล์
          </label>
        ) : null}
      >
        <div className="flex flex-col gap-1 p-4 text-xs text-text-muted">
          {pricingRequestAttachments.map((attachment) => (
            <div key={attachment.id} className="flex flex-wrap items-center gap-2">
              <a className="text-info underline" href={api.pricingRequests.attachmentUrl(attachment.id)} target="_blank" rel="noreferrer">
                {attachment.fileName}
              </a>
              {isImport(user) ? (
                <label className="flex items-center gap-1">
                  <input
                    type="checkbox"
                    checked={Boolean(attachment.includeInFactoryEmail)}
                    disabled={toggleAttachmentIncludeInFactoryEmail.isPending}
                    onChange={() => toggleAttachmentIncludeInFactoryEmail.mutate(attachment)}
                  />
                  ส่งแนบไปกับอีเมลโรงงาน
                </label>
              ) : attachment.includeInFactoryEmail ? (
                <StatusBadge tone="neutral">แนบไปกับอีเมลโรงงาน</StatusBadge>
              ) : null}
              {canEditPricingRequestAttachments ? (
                <Button
                  type="button"
                  variant="icon"
                  aria-label={`ลบไฟล์แนบ ${attachment.fileName}`}
                  onClick={() => deletePricingRequestAttachment.mutate(attachment.id)}
                >
                  <Icon name="close" size={13} />
                </Button>
              ) : null}
            </div>
          ))}
          {pricingRequestAttachments.length === 0 ? <span>ยังไม่มีไฟล์แนบ</span> : null}
        </div>
      </Panel>

      {canCreateCustomerRevision ? (
        <Panel flush title="รอบแก้ไขตามการเปลี่ยนแปลงของลูกค้า">
          <div className="flex flex-wrap gap-2 p-4">
            <Button type="button" variant="secondary" onClick={() => setRevisionModalOpen(true)}>
              สร้างรอบแก้ไข
            </Button>
          </div>
        </Panel>
      ) : null}

      {canSeeRaw(user) ? (
        <Panel
          flush
          title="ราคาโรงงาน"
          actions={isImport(user) ? (
            <Button type="button" variant="primary" disabled={generateDrafts.isPending} onClick={() => generateDrafts.mutate()} data-testid="pcr-generate-drafts">
              สร้างร่างอีเมล
            </Button>
          ) : null}
        >
          <div className="flex flex-col gap-3 p-4">
            {factoryQuotes.map((quote) => {
              const quoteStatus = factoryQuoteStatusLabel(quote.status);
              const emailDraft = emailDrafts[quote.id] ?? {
                emailTo: quote.emailTo ?? '',
                emailSubject: quote.emailSubject ?? '',
                emailBody: quote.emailBody ?? '',
                note: quote.note ?? '',
              };
              const draft = responseDrafts[quote.id] ?? {
                supplierQuoteRef: quote.supplierQuoteRef ?? '',
                defaultCurrency: quote.defaultCurrency ?? 'THB',
                paymentTerms: quote.paymentTerms ?? '',
                leadTimeText: quote.leadTimeText ?? '',
                revisionReason: '',
                negotiationNote: quote.negotiationNote ?? '',
                items: defaultResponseItems(quote, requestItemById),
              };
              return (
                <div key={quote.id} className="rounded-md border border-border bg-surface p-3">
                  <div className="flex flex-wrap items-center gap-2">
                    <strong>{quote.factoryName}</strong>
                    <StatusBadge tone="neutral">ครั้งที่ {quote.revisionNo}</StatusBadge>
                    <StatusBadge tone={quote.current ? quoteStatus.tone : 'neutral'}>{quoteStatus.label}</StatusBadge>
                    {dispatchStatusBadge(quote)}
                    {/* Owner ruling 2026-08-11: the app does not send this mail. Import mails the
                        factory from its own client, so this generates text to copy and then
                        records that it went out. Two separate buttons on purpose — copying is not
                        sending, and the audit trail should only ever claim what a human confirmed.
                        The underlying sendFactoryQuote call is unchanged; it is what advances the
                        quote to REQUESTED and the request to เจรจาราคากับโรงงาน. */}
                    {isImport(user) && quote.status === 'DRAFT' && quote.dispatchStatus !== 'PENDING' && quote.dispatchStatus !== 'SENDING' ? (
                      <>
                        <Button type="button" variant="secondary" data-testid="pcr-copy-factory-email" onClick={() => copyFactoryEmail(emailDraft)}>
                          คัดลอกข้อความ
                        </Button>
                        <Button type="button" variant="secondary" data-testid="pcr-mark-factory-email-sent" onClick={() => {
                          // A FAILED dispatch has permanently exhausted its own clientRequestId (the
                          // backend's unique (created_by, client_request_id) index would just replay
                          // that same dead row), so a manual retry must mint a fresh idempotency key
                          // rather than reuse whatever was cached for this quote.
                          const clientRequestId = quote.dispatchStatus === 'FAILED'
                            ? generateClientRequestId()
                            : (sendClientRequestIds[quote.id] ?? generateClientRequestId());
                          setSendClientRequestIds((cur) => ({ ...cur, [quote.id]: clientRequestId }));
                          setConfirmAction({ type: 'sendQuote', quote, emailDraft });
                        }}>
                          {quote.dispatchStatus === 'FAILED' ? 'บันทึกว่าส่งแล้วอีกครั้ง' : 'ส่งแล้ว'}
                        </Button>
                      </>
                    ) : null}
                    {/* พร้อมคำนวณต้นทุน + สร้างร่างต้นทุน + คำนวณใหม่ + ส่งให้ CEO ตรวจ collapse into
                        this one button — see submitToCeo. เจรจา stays: re-quoting a factory is
                        real Import work, and it is what keeps the request in เจรจาราคากับโรงงาน.

                        READY_FOR_COSTING is deliberately NOT in this list (issue #729): the action
                        this button now performs is markFactoryQuoteReady, and a quote already in
                        READY_FOR_COSTING has had it performed. markReady's own UPDATE matches zero
                        rows there and the service 409s, so offering the button again could only
                        ever produce an error on work that was already done. If the request has not
                        advanced, it is because ANOTHER factory's quote is still outstanding — and
                        that quote carries its own button. */}
                    {isImport(user) && ['RESPONSE_RECEIVED', 'NEGOTIATING'].includes(quote.status) && quote.current ? (
                      <Button type="button" variant="primary" disabled={submitToCeo.isPending}
                        onClick={() => submitToCeo.mutate(quote)} data-testid="pcr-submit-to-ceo">
                        ส่งให้ CEO อนุมัติราคา
                      </Button>
                    ) : null}
                    {isImport(user) && quote.status === 'RESPONSE_RECEIVED' && quote.current ? <Button type="button" variant="secondary" onClick={() => negotiateQuote.mutate(quote)}>เจรจา</Button> : null}
                  </div>
                  <div className="mt-2 text-xs text-text-muted">{quote.emailTo ?? '-'} · {quote.supplierQuoteRef ?? '-'}</div>
                  {quote.dispatchStatus === 'FAILED' && quote.dispatchFailureMessage ? (
                    <div className="mt-1 text-xs text-danger">ส่งไม่สำเร็จ: {quote.dispatchFailureMessage}</div>
                  ) : null}
                  {isImport(user) && quote.status === 'DRAFT' ? (
                    <div className="mt-3 grid gap-2 border-t border-border-subtle pt-3">
                      {/* Real labels, not placeholders. A placeholder disappears
                          the moment the field has a value — which is the state
                          these fields spend their whole life in — so the only
                          thing naming them vanished exactly when a reader
                          needed it, and a screen reader had nothing to announce
                          at all. ids are keyed on quote.id because this block
                          renders once per factory quote. */}
                      <FormField label="อีเมลโรงงาน" htmlFor={`pcr-email-to-${quote.id}`}>
                        <input
                          id={`pcr-email-to-${quote.id}`}
                          type="email"
                          className="form-input"
                          value={emailDraft.emailTo}
                          onChange={(e) => setEmailDrafts({ ...emailDrafts, [quote.id]: { ...emailDraft, emailTo: e.target.value } })}
                        />
                      </FormField>
                      <FormField label="หัวข้ออีเมล" htmlFor={`pcr-email-subject-${quote.id}`}>
                        <input
                          id={`pcr-email-subject-${quote.id}`}
                          className="form-input"
                          value={emailDraft.emailSubject}
                          onChange={(e) => setEmailDrafts({ ...emailDrafts, [quote.id]: { ...emailDraft, emailSubject: e.target.value } })}
                        />
                      </FormField>
                      <FormField label="เนื้อหาอีเมล" htmlFor={`pcr-email-body-${quote.id}`}>
                        <textarea
                          id={`pcr-email-body-${quote.id}`}
                          className="form-input min-h-24"
                          value={emailDraft.emailBody}
                          onChange={(e) => setEmailDrafts({ ...emailDrafts, [quote.id]: { ...emailDraft, emailBody: e.target.value } })}
                        />
                      </FormField>
                      <Button type="button" variant="secondary" disabled={updateQuote.isPending} onClick={() => updateQuote.mutate({ quote, draft: emailDraft })}>
                        บันทึกร่างอีเมล
                      </Button>
                    </div>
                  ) : null}
                  {quote.items?.length ? (
                    <div className="mt-3 flex flex-col gap-1 border-t border-border-subtle pt-3 text-xs text-text-muted">
                      {quote.items.map((line, index) => (
                        <span key={line.id ?? `${line.pricingRequestItemId ?? 'item'}-${index}`}>
                          รายการ #{line.pricingRequestItemId} · ราคาโรงงาน {formatCurrency(line.rawUnitPrice, line.currency)} · {line.unitBasis ?? '-'} · {line.sqmPerUnit ? `${line.sqmPerUnit} ตร.ม./หน่วย` : '-'}
                        </span>
                      ))}
                    </div>
                  ) : null}
                  {(quote.attachments ?? []).length || isImport(user) ? (
                    <div className="mt-3 border-t border-border-subtle pt-3">
                      <div className="mb-2 flex flex-wrap items-center gap-2">
                        <span className="text-xs font-semibold text-text-muted">ไฟล์แนบ</span>
                        {isImport(user) ? (
                          // Left as a <label> wrapping the hidden file input, not <Button>: a
                          // <button> cannot open the native file picker the way a <label>
                          // wrapping its <input type="file"> does.
                          <label className={cn(buttonVariants({ variant: 'secondary' }), 'cursor-pointer')}>
                            <input type="file" className="hidden" onChange={(event) => {
                              const file = event.target.files?.[0];
                              if (file) uploadQuoteAttachment.mutate({ quote, file });
                              event.target.value = '';
                            }} />
                            <Icon name="upload" size={13} />
                            แนบไฟล์
                          </label>
                        ) : null}
                      </div>
                      <div className="flex flex-col gap-1 text-xs text-text-muted">
                        {(quote.attachments ?? []).map((attachment) => (
                          <a key={attachment.id} className="text-info underline" href={api.pricingRequests.factoryQuoteAttachmentUrl(attachment.id)} target="_blank" rel="noreferrer">
                            {attachment.fileName}
                          </a>
                        ))}
                        {(quote.attachments ?? []).length === 0 ? <span>-</span> : null}
                      </div>
                    </div>
                  ) : null}
                  {isImport(user) && quote.current && ['DRAFT', 'REQUESTED', 'RESPONSE_RECEIVED', 'NEGOTIATING', 'READY_FOR_COSTING'].includes(quote.status) ? (
                    <div className="mt-3 flex flex-col gap-2 border-t border-border-subtle pt-3">
                      {/* เลขอ้างอิงใบเสนอราคา / เงื่อนไขการชำระเงิน / ระยะเวลาผลิต-ส่งมอบ and the
                          quote-level สกุลเงิน were removed here (owner ruling 2026-08-11): Import
                          keys in the price and nothing else. All four are optional in
                          ReceiveFactoryQuoteRequest, so nothing is sent as null that the backend
                          required; currency now rides on each line, autofilled from the catalog
                          row Sales picked. */}
                      {/* One input per line: the factory's price. Everything else on the row is a
                          READ-ONLY echo of what Sales requested — quantity, unit and currency are
                          held in `draft.items` (the backend still requires all three) but are not
                          Import's to retype. Re-keying them was the single biggest source of
                          friction here, and the currency field in particular was a live defect:
                          it defaulted to THB against factories that trade in EUR. */}
                      {draft.items.map((line, index) => {
                        const itemRef = `รายการ #${line.pricingRequestItemId}`;
                        const requested = requestItemById.get(line.pricingRequestItemId);
                        const productName = [requested?.catalogBrand ?? requested?.brand, requested?.catalogModel ?? requested?.model]
                          .filter(Boolean).join(' ') || requested?.productDescription || itemRef;
                        return (
                          <div key={line.pricingRequestItemId} className="grid items-end gap-2 md:grid-cols-[1fr_auto]">
                            <div className="min-w-0">
                              <div className="truncate text-sm font-bold text-text">{productName}</div>
                              <div className="text-2xs text-text-muted">
                                ที่ Sales ขอ: {requested?.requestedQty ?? line.quotedQuantity} {requested?.requestedUnit ?? unitBasisLabel(line.unitBasis)}
                                {' · '}สกุลเงิน {line.currency}
                              </div>
                            </div>
                            <FormField label={`ราคาโรงงาน (${line.currency})`} htmlFor={`pcr-quote-price-${quote.id}-${line.pricingRequestItemId}`}>
                              <input
                                id={`pcr-quote-price-${quote.id}-${line.pricingRequestItemId}`}
                                className="form-input md:w-48"
                                type="number"
                                min="0"
                                step="0.0001"
                                inputMode="decimal"
                                aria-label={`ราคาโรงงาน ${itemRef}`}
                                value={line.rawUnitPrice}
                                onChange={(e) => {
                                  const items = [...draft.items];
                                  items[index] = { ...line, rawUnitPrice: e.target.value };
                                  setResponseDrafts({ ...responseDrafts, [quote.id]: { ...draft, items } });
                                }}
                              />
                            </FormField>
                          </div>
                        );
                      })}
                      <Button type="button" variant="secondary" disabled={receiveQuote.isPending} data-testid="pcr-quote-save-response" onClick={() => {
                        const clientRequestId = receiveClientRequestIds[quote.id] ?? generateClientRequestId();
                        setReceiveClientRequestIds((cur) => ({ ...cur, [quote.id]: clientRequestId }));
                        receiveQuote.mutate({ quote, draft, clientRequestId });
                      }}>
                        บันทึกคำตอบ/รอบแก้ไข
                      </Button>
                    </div>
                  ) : null}
                </div>
              );
            })}
            {factoryQuotes.length === 0 ? <p className="text-sm text-text-muted">ยังไม่มีราคาโรงงาน</p> : null}
          </div>
        </Panel>
      ) : null}

      {/* Import no longer sees the costing aggregate — submitToCeo runs it end to end. CEO keeps
          the full view (canSeeRaw is import+ceo; only the ceo half is wanted here). */}
      {canSeeRaw(user) && !isImport(user) ? (
        <Panel
          flush
          title="ต้นทุนนำเข้า"
          actions={isImport(user) ? <Button type="button" variant="primary" onClick={() => createCosting.mutate()} data-testid="pcr-costing-create">สร้างร่างต้นทุน</Button> : null}
        >
          <div className="flex flex-col gap-3 p-4">
            {isImport(user) ? (
              <FormField label="หมายเหตุต้นทุน" htmlFor="pcr-costing-note">
                <input
                  id="pcr-costing-note"
                  className="form-input"
                  value={costingNote}
                  onChange={(e) => setCostingNote(e.target.value)}
                />
              </FormField>
            ) : null}
            {costings.map((costing) => (
              <div key={costing.id} className="rounded-md border border-border bg-surface p-3">
                <div className="flex flex-wrap items-center gap-2">
                  <strong>{costing.costingCode}</strong>
                  <StatusBadge tone="neutral">เวอร์ชัน {costing.versionNo}</StatusBadge>
                  {(() => {
                    const status = pricingCostingStatusLabel(costing.status, { stale: costing.stale });
                    return <StatusBadge tone={status.tone}>{status.label}</StatusBadge>;
                  })()}
                  <span className="text-xs text-text-muted">{costing.totalLandedCostThb != null ? formatCurrency(costing.totalLandedCostThb, 'THB') : '-'}</span>
                  {isImport(user) && costing.id === latestOpenCosting?.id ? (
                    <Fragment key={`costing-actions-${costing.id}`}>
                      <Button type="button" variant="secondary" onClick={() => recalculateCosting.mutate(costing)} data-testid="pcr-costing-recalculate">คำนวณใหม่</Button>
                      <Button type="button" variant="secondary" disabled={costing.status !== 'CALCULATED' || costing.stale} onClick={() => setConfirmAction({ type: 'submitCosting', costing })} data-testid="pcr-costing-submit">ส่งให้ CEO ตรวจ</Button>
                    </Fragment>
                  ) : null}
                </div>
                {canSeeRaw(user) && costing.items?.length ? (
                  <div className="mt-2 flex flex-col gap-1 text-xs text-text-muted">
                    {costing.items.map((item, index) => (
                      <span key={item.id ?? `${item.factoryName ?? 'factory'}-${item.factoryQuoteRevisionNo ?? 'rev'}-${index}`}>{item.factoryName} · ครั้งที่ {item.factoryQuoteRevisionNo} · ราคาโรงงาน {formatCurrency(item.rawUnitPrice, item.rawCurrency)} · ต้นทุนนำเข้า {formatCurrency(item.landedCostPerUnitThb, 'THB')}</span>
                    ))}
                  </div>
                ) : null}
              </div>
            ))}
            {costings.length === 0 ? <p className="text-sm text-text-muted">ยังไม่มีต้นทุนนำเข้า</p> : null}
          </div>
        </Panel>
      ) : null}

      {/* Import's job ends at ส่งให้ CEO อนุมัติราคา (owner ruling 2026-08-11), so the
          CEO's own selling-price decision is no longer on Import's page — canSeeRawPricingDecision
          covers import+ceo, and only the CEO half is wanted here. The predicate itself is
          unchanged (it still governs cost/margin visibility elsewhere); this render site adds the
          role narrowing rather than editing the shared gate. */}
      {canSeeRawPricingDecision(user) && !isImport(user) ? (
        <Panel flush title="การพิจารณาราคาขายของ CEO">
          <div className="flex flex-col gap-3 p-4">
            {!currentDecision && canStartCeoReview(user, summary) ? (
              <div className="flex flex-wrap items-center gap-2">
                <label className="text-xs text-text-muted">
                  อัตรากำไรเริ่มต้น
                  <input
                    className="form-input ml-2 w-24"
                    value={decisionDefaultMargin}
                    onChange={(e) => setDecisionDefaultMargin(e.target.value)}
                    placeholder="0.20"
                  />
                </label>
                <Button type="button" variant="primary" disabled={startCeoReview.isPending} onClick={() => startCeoReview.mutate()} data-testid="pcr-ceo-start-review">
                  เริ่มพิจารณาราคาขาย
                </Button>
              </div>
            ) : null}
            {!currentDecision && !canStartCeoReview(user, summary) ? (
              <p className="text-sm text-text-muted">ยังไม่มีการพิจารณาราคาขาย</p>
            ) : null}
            {currentDecision ? (() => {
              const decision = currentDecision;
              const decisionStatus = pricingDecisionStatusLabel(decision.status);
              const isDraft = decision.status === 'DRAFT';
              const editable = isDraft && canActOnPricingDecision(user, summary);
              const missingBeforeApprove = decision.items.filter((item) => {
                const draft = decisionItemDrafts[item.id] ?? {};
                const margin = draft.marginPct ?? item.proposedMarginPct;
                const minPrice = draft.minimumSellingPrice ?? item.minimumSellingPricePerRequestedUnit;
                return margin == null || margin === '' || minPrice == null || minPrice === '';
              });
              return (
                <div key={decision.id} className="rounded-md border border-border bg-surface p-3">
                  <div className="flex flex-wrap items-center gap-2">
                    <strong>{decision.decisionCode}</strong>
                    <StatusBadge tone="neutral">เวอร์ชัน {decision.decisionVersionNo}</StatusBadge>
                    <StatusBadge tone={decisionStatus.tone}>{decisionStatus.label}</StatusBadge>
                    <span className="text-xs text-text-muted">
                      {decision.currency} · อัตราแลกเปลี่ยน {decision.fxRateUsed} ({decision.fxSource}, {decision.fxEffectiveDate})
                    </span>
                  </div>
                  <div className="mt-3 flex flex-col gap-2">
                    {decision.items.map((item) => {
                      const draft = decisionItemDrafts[item.id] ?? {};
                      const effectiveMargin = draft.marginPct ?? item.proposedMarginPct ?? '';
                      const effectiveMinimum = draft.minimumSellingPrice ?? item.minimumSellingPricePerRequestedUnit ?? '';
                      const effectiveCeiling = draft.discountCeilingPct ?? item.discountCeilingPct ?? '';
                      const belowMinimum = effectiveMinimum !== '' && item.proposedSellingPricePerRequestedUnit != null
                        && Number(item.proposedSellingPricePerRequestedUnit) < Number(effectiveMinimum);
                      return (
                        <div key={item.id} className="rounded-md border border-border-subtle p-2">
                          <div className="flex flex-wrap items-center gap-2 text-xs text-text-muted">
                            <strong className="text-text">{[item.brand, item.model].filter(Boolean).join(' ') || item.productDescription || '-'}</strong>
                            <span>{item.factoryName ?? '-'}</span>
                            <span>{item.requestedQuantity} ({item.requestedUnitBasis})</span>
                            <span>ต้นทุน/หน่วย: {formatCurrency(item.frozenLandedCostPerRequestedUnitThb, 'THB')}</span>
                            {belowMinimum ? <StatusBadge tone="danger">ต่ำกว่าราคาขั้นต่ำ</StatusBadge> : null}
                          </div>
                          {editable ? (
                            <div className="mt-2 grid gap-2 md:grid-cols-4">
                              <input
                                className="form-input"
                                value={effectiveMargin}
                                onChange={(e) => setDecisionItemDrafts((cur) => ({ ...cur, [item.id]: { ...draft, marginPct: e.target.value } }))}
                                placeholder="อัตรากำไร เช่น 0.20 = 20%"
                              />
                              <input
                                className="form-input"
                                value={effectiveMinimum}
                                onChange={(e) => setDecisionItemDrafts((cur) => ({ ...cur, [item.id]: { ...draft, minimumSellingPrice: e.target.value } }))}
                                placeholder="ราคาขั้นต่ำ"
                              />
                              <input
                                className="form-input"
                                value={effectiveCeiling}
                                onChange={(e) => setDecisionItemDrafts((cur) => ({ ...cur, [item.id]: { ...draft, discountCeilingPct: e.target.value } }))}
                                placeholder="ส่วนลดสูงสุด เช่น 0.10 = 10%"
                              />
                              <span className="self-center text-xs text-text-muted">
                                ราคาขายเสนอ: {formatCurrency(item.proposedSellingPricePerRequestedUnit, decision.currency)}
                              </span>
                            </div>
                          ) : (
                            <div className="mt-2 flex flex-wrap gap-x-4 gap-y-1 text-xs text-text-muted">
                              <span>อัตรากำไร: {item.approvedMarginPct ?? item.proposedMarginPct ?? '-'}</span>
                              <span>ราคาขาย: {formatCurrency(item.approvedSellingPricePerRequestedUnit ?? item.proposedSellingPricePerRequestedUnit, decision.currency)}</span>
                              <span>ราคาขั้นต่ำ: {item.minimumSellingPricePerRequestedUnit != null ? formatCurrency(item.minimumSellingPricePerRequestedUnit, decision.currency) : '-'}</span>
                            </div>
                          )}
                        </div>
                      );
                    })}
                  </div>
                  {editable ? (
                    <div className="mt-3 flex flex-wrap gap-2 border-t border-border-subtle pt-3">
                      <Button
                        type="button"
                        variant="secondary"
                        disabled={saveDecisionItems.isPending}
                        onClick={() => saveDecisionItems.mutate({ decision, items: decision.items })}
                        data-testid="pcr-ceo-save-decision-items"
                      >
                        บันทึกการเปลี่ยนแปลง
                      </Button>
                      <Button
                        type="button"
                        variant="primary"
                        disabled={approveDecision.isPending || missingBeforeApprove.length > 0}
                        onClick={() => setConfirmAction({ type: 'approveDecision', decision })}
                        data-testid="pcr-ceo-approve"
                      >
                        อนุมัติราคาขาย
                      </Button>
                      <Button
                        type="button"
                        variant="secondary"
                        disabled={returnDecisionToImport.isPending}
                        onClick={() => setConfirmAction({ type: 'returnDecision', decision })}
                      >
                        ตีกลับให้ฝ่ายนำเข้าแก้ไข
                      </Button>
                      {missingBeforeApprove.length > 0 ? (
                        <span className="self-center text-xs text-danger">ทุกรายการต้องมีอัตรากำไรและราคาขั้นต่ำก่อนอนุมัติ</span>
                      ) : null}
                    </div>
                  ) : null}
                </div>
              );
            })() : null}
            {pricingDecisions.length > 1 ? (
              <div className="text-xs text-text-muted">
                ประวัติ: {pricingDecisions.map((d) => {
                  const status = pricingDecisionStatusLabel(d.status);
                  return `เวอร์ชัน ${d.decisionVersionNo} (${status.label})`;
                }).join(' · ')}
              </div>
            ) : null}
          </div>
        </Panel>
      ) : null}

      {!canSeeRawPricingDecision(user) && canSeePricingDecisionSalesView(user, summary) && decisionSalesView ? (
        <Panel flush title="ราคาขายที่อนุมัติ">
          <div className="flex flex-col gap-2 p-4">
            {decisionSalesView.items.map((item) => (
              <div key={item.pricingRequestItemId} className="rounded-md border border-border bg-surface p-3 text-sm">
                <strong>{[item.brand, item.model].filter(Boolean).join(' ') || item.productDescription || '-'}</strong>
                <div className="mt-1 flex flex-wrap gap-x-4 gap-y-1 text-xs text-text-muted">
                  <span>{item.requestedQuantity} ({item.requestedUnitBasis})</span>
                  <span>ราคาขาย: {formatCurrency(item.approvedSellingPricePerRequestedUnit, decisionSalesView.currency)}</span>
                  {item.discountCeilingPct != null ? <span>ส่วนลดสูงสุด: {item.discountCeilingPct}</span> : null}
                </div>
              </div>
            ))}
          </div>
        </Panel>
      ) : null}

      {/* Same ruling: the customer-facing quotation is Sales' surface. canViewCustomerQuotation
          still grants import read access at the API (unchanged), but the panel is hidden here —
          Import was only ever shown an empty-state telling it to wait for APPROVED_FOR_QUOTATION. */}
      {canViewCustomerQuotation(user, summary) && !isImport(user) ? (
        <Panel
          flush
          title="ใบเสนอราคาลูกค้า"
          actions={currentCustomerQuotation ? (
            (() => {
              const status = quotationStatusLabel(currentCustomerQuotation.docStatus);
              return (
                <StatusBadge tone={status.tone}>
                  {status.label} · ครั้งที่ {currentCustomerQuotation.quotationRevisionNo}
                </StatusBadge>
              );
            })()
          ) : null}
        >
          <div className="flex flex-col gap-3 p-4">
            {!currentCustomerQuotation && canCreateCustomerQuotation(user, summary) ? (
              <Button type="button" variant="primary" className="self-start" onClick={() => createQuotation.mutate()} disabled={createQuotation.isPending}>
                สร้างร่างใบเสนอราคาลูกค้า
              </Button>
            ) : null}
            {!currentCustomerQuotation && !canCreateCustomerQuotation(user, summary) ? (
              <p className="text-sm text-text-muted">
                ยังไม่มีใบเสนอราคาลูกค้า — ต้องรออนุมัติราคาขาย (APPROVED_FOR_QUOTATION) ก่อนจึงจะสร้างได้
              </p>
            ) : null}

            {currentCustomerQuotation ? (() => {
              const quotation = currentCustomerQuotation;
              const quotationStatus = quotationStatusLabel(quotation.docStatus);
              const editable = isCustomerQuotationEditable(quotation) && canManageCustomerQuotation(user, summary);
              // Issue #733: a revision's discount is refused by CustomerQuotationService.update,
              // so the input must not be offered on one. Narrower than `editable` on purpose —
              // description/notes stay writable, only the money does not.
              const discountEditable = editable && isCustomerQuotationDiscountEditable(quotation);
              return (
                <div key={quotation.id} className="flex flex-col gap-3">
                  <div className="text-sm"><strong>เลขที่</strong> {quotation.number}</div>
                  {editable && !discountEditable ? (
                    <p className="rounded-md border border-warning-border bg-warning-bg p-3 text-xs text-warning-dark">
                      ใบเสนอราคาฉบับแก้ไขให้ส่วนลดไม่ได้ — ส่วนลดทุกรายการถูกตั้งเป็น 0 และแก้ไขไม่ได้
                      หากต้องเปลี่ยนราคาหรือจำนวนหลังออกใบเสนอราคาแล้ว ต้องสร้างรอบแก้ไขตามการเปลี่ยนแปลงของลูกค้า
                      (customer-change revision) เพื่อให้ CEO อนุมัติราคาใหม่
                    </p>
                  ) : null}
                  <div className="flex flex-col gap-2">
                    {quotation.items.map((item) => {
                      const draft = quotationItemDrafts[item.id] ?? {};
                      const discount = cleanNumber(draft.salesDiscount ?? item.salesDiscount) ?? 0;
                      const previewFinal = item.approvedUnitPrice - discount;
                      const belowMinimum = item.minimumSellingPricePerRequestedUnit != null
                        && previewFinal < item.minimumSellingPricePerRequestedUnit;
                      return (
                        <div key={item.id} className="rounded-md border border-border bg-surface p-3 text-sm">
                          {editable ? (
                            <input
                              className="w-full rounded border border-border p-1 text-sm"
                              value={draft.description ?? item.description ?? ''}
                              onChange={(e) => setQuotationItemDrafts((cur) => ({
                                ...cur, [item.id]: { ...cur[item.id], description: e.target.value },
                              }))}
                            />
                          ) : (
                            <strong>{item.description || '-'}</strong>
                          )}
                          <div className="mt-1 flex flex-wrap items-center gap-x-4 gap-y-1 text-xs text-text-muted">
                            <span>{item.requestedQuantity} ({item.requestedUnitBasis})</span>
                            <span>ราคาที่อนุมัติ: {formatCurrency(item.approvedUnitPrice, quotation.currency)}</span>
                            {discountEditable ? (
                              <label className="flex items-center gap-1">
                                ส่วนลด/หน่วย
                                <input
                                  type="number"
                                  step="0.01"
                                  className="w-24 rounded border border-border p-1 text-xs"
                                  value={draft.salesDiscount ?? item.salesDiscount ?? 0}
                                  onChange={(e) => setQuotationItemDrafts((cur) => ({
                                    ...cur, [item.id]: { ...cur[item.id], salesDiscount: e.target.value },
                                  }))}
                                />
                              </label>
                            ) : (
                              <span>ส่วนลด/หน่วย: {formatCurrency(item.salesDiscount, quotation.currency)}</span>
                            )}
                            <span>ราคาสุทธิ: {formatCurrency(discountEditable ? previewFinal : item.finalUnitPrice, quotation.currency)}</span>
                            <span>รวมรายการ: {formatCurrency(item.lineTotal, quotation.currency)}</span>
                          </div>
                          {belowMinimum ? (
                            <p className="mt-1 text-xs font-medium text-danger">
                              ⚠ ราคาต่ำกว่าราคาขั้นต่ำที่ CEO อนุมัติ ({formatCurrency(item.minimumSellingPricePerRequestedUnit, quotation.currency)}) — บันทึกไม่ได้
                            </p>
                          ) : null}
                          {editable ? (
                            <textarea
                              className="mt-2 w-full rounded border border-border p-1 text-xs"
                              placeholder="หมายเหตุรายการ"
                              value={draft.itemNotes ?? item.itemNotes ?? ''}
                              onChange={(e) => setQuotationItemDrafts((cur) => ({
                                ...cur, [item.id]: { ...cur[item.id], itemNotes: e.target.value },
                              }))}
                            />
                          ) : item.itemNotes ? <p className="mt-1 text-xs text-text-muted">{item.itemNotes}</p> : null}
                        </div>
                      );
                    })}
                  </div>

                  <div className="grid gap-2 text-sm md:grid-cols-3">
                    <div><strong>ยอดรวม (ก่อน VAT)</strong> {formatCurrency(quotation.subtotalAmount, quotation.currency)}</div>
                    <div><strong>VAT 7%</strong> {formatCurrency(quotation.vatAmount, quotation.currency)}</div>
                    <div><strong>รวมทั้งสิ้น</strong> {formatCurrency(quotation.grandTotal, quotation.currency)}</div>
                  </div>

                  {editable ? (
                    <div className="grid gap-2 md:grid-cols-2">
                      <input className="rounded border border-border p-2 text-sm" placeholder="เงื่อนไขการชำระเงิน"
                        value={quotationHeaderDraft.paymentTerms ?? quotation.paymentTerms ?? ''}
                        onChange={(e) => setQuotationHeaderDraft((cur) => ({ ...cur, paymentTerms: e.target.value }))} />
                      <input className="rounded border border-border p-2 text-sm" placeholder="ระยะเวลาส่งมอบ"
                        value={quotationHeaderDraft.leadTime ?? quotation.leadTime ?? ''}
                        onChange={(e) => setQuotationHeaderDraft((cur) => ({ ...cur, leadTime: e.target.value }))} />
                      <input className="rounded border border-border p-2 text-sm" placeholder="เงื่อนไขการจัดส่ง"
                        value={quotationHeaderDraft.deliveryTerms ?? quotation.deliveryTerms ?? ''}
                        onChange={(e) => setQuotationHeaderDraft((cur) => ({ ...cur, deliveryTerms: e.target.value }))} />
                      <input type="date" className="rounded border border-border p-2 text-sm"
                        value={quotationHeaderDraft.validityDate ?? quotation.validityDate ?? ''}
                        onChange={(e) => setQuotationHeaderDraft((cur) => ({ ...cur, validityDate: e.target.value }))} />
                      <textarea className="rounded border border-border p-2 text-sm md:col-span-2" placeholder="หมายเหตุถึงลูกค้า"
                        value={quotationHeaderDraft.customerNotes ?? quotation.customerNotes ?? ''}
                        onChange={(e) => setQuotationHeaderDraft((cur) => ({ ...cur, customerNotes: e.target.value }))} />
                    </div>
                  ) : (
                    <div className="grid gap-1 text-sm text-text-muted md:grid-cols-2">
                      <div>เงื่อนไขการชำระเงิน: {quotation.paymentTerms || '-'}</div>
                      <div>ระยะเวลาส่งมอบ: {quotation.leadTime || '-'}</div>
                      <div>เงื่อนไขการจัดส่ง: {quotation.deliveryTerms || '-'}</div>
                      <div>ยืนราคาถึง: {quotation.validityDate ? formatThaiDate(quotation.validityDate) : '-'}</div>
                      {quotation.customerNotes ? <div className="md:col-span-2">หมายเหตุ: {quotation.customerNotes}</div> : null}
                    </div>
                  )}

                  <div className="flex flex-wrap gap-2">
                    <Button type="button" variant="secondary" disabled={downloadingQuotationFormat === 'pdf'}
                      onClick={() => handleDownloadCustomerQuotation(quotation, 'pdf')}>
                      ดูตัวอย่าง PDF
                    </Button>
                    <Button type="button" variant="secondary" disabled={downloadingQuotationFormat === 'xlsx'}
                      onClick={() => handleDownloadCustomerQuotation(quotation, 'xlsx')}>
                      ดูตัวอย่าง XLSX
                    </Button>
                    {editable ? (
                      <Fragment key={`quotation-actions-${quotation.id}`}>
                        <Button type="button" variant="secondary" onClick={() => saveQuotation.mutate(quotation)} disabled={saveQuotation.isPending}>
                          บันทึก
                        </Button>
                        <Button type="button" variant="primary" disabled={issueQuotation.isPending}
                          onClick={() => setConfirmAction({ type: 'issueQuotation', quotation })}>
                          ออกใบเสนอราคา
                        </Button>
                        <Button type="button" variant="danger" disabled={cancelQuotation.isPending}
                          onClick={() => cancelQuotation.mutate(quotation)}>
                          ยกเลิกร่าง
                        </Button>
                      </Fragment>
                    ) : null}
                    {/* Widened per design correction 3: reachable once REVISION_REQUESTED too,
                        not only ISSUED — same guard the backend's createRevision now enforces. */}
                    {canCreateCommercialOnlyRevision(user, summary, quotation) ? (
                      <Button type="button" variant="secondary" disabled={createQuotationRevision.isPending}
                        onClick={() => createQuotationRevision.mutate(quotation)}>
                        {quotation.docStatus === 'REVISION_REQUESTED' ? 'สร้างรอบแก้ไขราคา/เงื่อนไข' : 'สร้างรอบแก้ไขใหม่'}
                      </Button>
                    ) : null}
                    {quotation.docStatus === 'REVISION_REQUESTED' && canManageCustomerQuotation(user, summary) ? (
                      <Button type="button" variant="secondary" onClick={() => setRevisionModalOpen(true)}>
                        สร้างรอบแก้ไขสินค้า/จำนวน/โรงงาน
                      </Button>
                    ) : null}
                  </div>

                  {/* Step 5: outcome-recording — Sales only, ISSUED only. */}
                  {canRecordCustomerQuotationOutcome(user, summary, quotation) ? (
                    <div className="flex flex-col gap-2 rounded-md border border-border bg-surface p-3">
                      <strong className="text-sm">บันทึกผลจากลูกค้า</strong>
                      <textarea
                        className="rounded border border-border p-2 text-sm"
                        placeholder="หมายเหตุจากลูกค้า (ถ้ามี)"
                        value={outcomeNote}
                        onChange={(e) => setOutcomeNote(e.target.value)}
                      />
                      <div className="flex flex-wrap gap-2">
                        <Button type="button" variant="primary" disabled={recordQuotationOutcome.isPending}
                          onClick={() => recordQuotationOutcome.mutate({ quotation, outcome: 'ACCEPTED' })}>
                          ลูกค้ายอมรับ
                        </Button>
                        <Button type="button" variant="danger" disabled={recordQuotationOutcome.isPending}
                          onClick={() => recordQuotationOutcome.mutate({ quotation, outcome: 'REJECTED' })}>
                          ลูกค้าปฏิเสธ
                        </Button>
                        <Button type="button" variant="secondary" disabled={recordQuotationOutcome.isPending}
                          onClick={() => recordQuotationOutcome.mutate({ quotation, outcome: 'REVISION_REQUESTED' })}>
                          ลูกค้าขอแก้ไข
                        </Button>
                      </div>
                    </div>
                  ) : null}

                  {/* Read-only outcome summary — visible to everyone (CEO/Import included), once
                      the customer's response has been recorded or the document has moved past
                      ISSUED for any other reason. */}
                  {['ACCEPTED', 'REJECTED', 'REVISION_REQUESTED', 'EXPIRED', 'SUPERSEDED'].includes(quotation.docStatus) ? (
                    <p className="text-sm text-text-muted">
                      ผลใบเสนอราคา: <strong>{quotationStatus.label}</strong>
                      {quotation.outcomeNote ? ` — ${quotation.outcomeNote}` : ''}
                      {quotation.docStatus === 'SUPERSEDED' ? ' (ถูกแทนที่ด้วยเวอร์ชันใหม่แล้ว)' : ''}
                    </p>
                  ) : null}

                  {customerQuotations.length > 1 ? (
                    <div className="mt-2 text-xs text-text-muted">
                      <strong>ประวัติรอบแก้ไข:</strong>{' '}
                      {customerQuotations.map((q) => {
                        const status = quotationStatusLabel(q.docStatus);
                        return `ครั้งที่ ${q.quotationRevisionNo} (${status.label})`;
                      }).join(' · ')}
                    </div>
                  ) : null}
                </div>
              );
            })() : null}
          </div>
        </Panel>
      ) : null}

      {/* Step 6: Deposit, Payment, and Order Confirmation — only once the customer has accepted
          the quotation (Step 5's terminal status). Bridges into the existing, already-tested
          dual-track payment pipeline (TicketService.confirmCustomer/DepositNoticeService) rather
          than inventing a new one — see OrderConfirmationService's own class Javadoc. */}
      {/* This block carried a status check and NO role gate, so Import saw Sales'
          order-confirm/deposit controls once the customer accepted. Same ruling as the two
          panels above — the actions were already server-gated to the ticket owner, so this
          only stops rendering controls Import could never successfully use. */}
      {summary.status === 'QUOTATION_ACCEPTED' && !isImport(user) ? (
        <Panel flush title="ยืนยันคำสั่งซื้อและออกใบแจ้งยอดเงินรับมัดจำ">
          <div className="flex flex-col gap-3 p-4">
            {canConfirmOrder(user, summary) ? (
              <div className="flex flex-col gap-2 rounded-md border border-border bg-surface p-3">
                <p className="text-sm text-text-muted">
                  ลูกค้ายอมรับใบเสนอราคาแล้ว — ยืนยันคำสั่งซื้อเพื่อเริ่มขั้นตอนรับมัดจำและนำเข้าสินค้า
                </p>
                <Button type="button" variant="primary" className="self-start" disabled={confirmOrder.isPending}
                  onClick={() => confirmOrder.mutate()}>
                  ยืนยันคำสั่งซื้อ
                </Button>
              </div>
            ) : null}
            {canCreateDepositNoticeFromQuotation(user, summary) ? (
              <div className="flex flex-col gap-2 rounded-md border border-border bg-surface p-3">
                <p className="text-sm text-text-muted">
                  ยืนยันคำสั่งซื้อแล้ว — สร้างใบแจ้งยอดเงินรับมัดจำจากใบเสนอราคาที่ลูกค้ายอมรับ (แก้ไข/ออกเอกสารในหน้าใบแจ้งยอดเงินรับมัดจำ)
                </p>
                <label className="flex items-center gap-2 text-sm">
                  % มัดจำ
                  <input type="number" min="0" max="1" step="0.05" className="w-24 rounded border border-border p-1 text-sm"
                    value={depositPercentInput} onChange={(e) => setDepositPercentInput(e.target.value)} />
                </label>
                <Button type="button" variant="primary" className="self-start" disabled={createDepositNoticeFromQuotation.isPending}
                  onClick={() => createDepositNoticeFromQuotation.mutate()}>
                  สร้างใบแจ้งยอดเงินรับมัดจำ
                </Button>
              </div>
            ) : null}
            {!canConfirmOrder(user, summary) && !canCreateDepositNoticeFromQuotation(user, summary) ? (
              <p className="text-sm text-text-muted">
                {summary.orderConfirmedAt
                  ? `ยืนยันคำสั่งซื้อแล้วเมื่อ ${formatThaiDate(summary.orderConfirmedAt)} — ดูใบแจ้งยอดเงินรับมัดจำได้ที่หน้าดีล`
                  : 'ยืนยันคำสั่งซื้อได้เฉพาะเจ้าของดีล (sales)'}
              </p>
            ) : null}
          </div>
        </Panel>
      ) : null}

      <ConfirmDialog
        open={Boolean(confirmAction)}
        title={confirmAction?.type === 'submitCosting' ? 'ส่งต้นทุนให้ CEO ตรวจ'
          : confirmAction?.type === 'approveDecision' ? 'อนุมัติราคาขาย'
          : confirmAction?.type === 'returnDecision' ? 'ตีกลับให้ฝ่ายนำเข้าแก้ไขต้นทุน'
          : confirmAction?.type === 'issueQuotation' ? 'ออกใบเสนอราคาลูกค้า'
          : 'ส่งอีเมลถึงโรงงาน'}
        message={confirmAction?.type === 'submitCosting'
          ? 'เมื่อส่งแล้ว เวอร์ชันต้นทุนนี้จะแก้ไขไม่ได้'
          : confirmAction?.type === 'approveDecision'
            ? 'เมื่ออนุมัติแล้ว ราคาขายจะถูกส่งให้ฝ่ายขายและไม่สามารถแก้ไขราคานี้ได้อีก'
            : confirmAction?.type === 'returnDecision'
              ? 'ระบุเหตุผลที่ตีกลับให้ฝ่ายนำเข้าคำนวณต้นทุนใหม่'
              : confirmAction?.type === 'issueQuotation'
                ? 'เมื่อออกใบเสนอราคาแล้ว จะแก้ไขไม่ได้ — การแก้ไขภายหลังต้องสร้างรอบแก้ไขใหม่'
                : 'ยืนยันการส่งคำขอราคาให้โรงงานด้วยรายละเอียดอีเมลนี้'}
        confirmLabel={confirmAction?.type === 'submitCosting' ? 'ส่งให้ CEO ตรวจ'
          : confirmAction?.type === 'approveDecision' ? 'อนุมัติ'
          : confirmAction?.type === 'returnDecision' ? 'ตีกลับ'
          : confirmAction?.type === 'issueQuotation' ? 'ออกใบเสนอราคา'
          : 'ส่งอีเมล'}
        tone={confirmAction?.type === 'returnDecision' ? 'danger' : 'default'}
        requireReason={confirmAction?.type === 'returnDecision'}
        reasonLabel="เหตุผลที่ตีกลับ"
        busy={sendQuote.isPending || submitCosting.isPending || approveDecision.isPending
          || returnDecisionToImport.isPending || issueQuotation.isPending}
        onCancel={() => setConfirmAction(null)}
        onConfirm={(reason) => {
          const action = confirmAction;
          setConfirmAction(null);
          if (action?.type === 'submitCosting') submitCosting.mutate(action.costing);
          if (action?.type === 'sendQuote') sendQuote.mutate({ quote: action.quote, draft: action.emailDraft });
          if (action?.type === 'approveDecision') approveDecision.mutate(action.decision);
          if (action?.type === 'returnDecision') returnDecisionToImport.mutate({ decision: action.decision, reason });
          if (action?.type === 'issueQuotation') issueQuotation.mutate(action.quotation);
        }}
      />

      {revisionModalOpen ? (
        <PricingRequestCreateModal
          mode="revision"
          initialValue={request}
          onClose={() => setRevisionModalOpen(false)}
          onCreated={(result) => {
            setRevisionModalOpen(false);
            invalidate();
            const newId = result?.pricingRequest?.summary?.id;
            if (newId) navigate(`/pricing-requests/${newId}`);
          }}
          createRevisionFn={(id, payload) => api.pricingRequests.createCustomerChangeRevision(id, payload)}
        />
      ) : null}
    </div>
  );
}
