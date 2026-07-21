import { useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { Icon } from '../../components/common/Icon.jsx';
import { ConfirmDialog } from '../../components/common/ConfirmDialog.jsx';
import { PageHeader } from '../../components/common/PageHeader.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { formatMoney, formatThaiDate, pricingRequestStatusLabel } from '../../utils/format.js';
import {
  canActOnPricingDecision,
  canRequestInformation,
  canRespondInformation,
  canSeePricingDecisionSalesView,
  canSeeRawPricingDecision,
  canStartCeoReview,
  pricingRequestRecipientLabel,
  quantityTypeLabel,
} from './pricingRequestMeta.js';
import { PricingRequestCreateModal } from './PricingRequestCreateModal.jsx';

function isImport(user) {
  return user?.role === 'import';
}

function isSales(user) {
  return user?.role === 'sales';
}

function canSeeRaw(user) {
  return user?.role === 'import' || user?.role === 'ceo';
}

const UNIT_OPTIONS = [
  { code: 'PER_SQM', label: 'per sqm' },
  { code: 'PER_PIECE', label: 'per piece' },
  { code: 'PER_BOX', label: 'per box' },
  { code: 'PER_LINEAR_M', label: 'per linear m' },
];

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
    <StatusBadge tone={tone}>
      {(DISPATCH_STATUS_LABEL[status] ?? status) + attempt}
    </StatusBadge>
  );
}

function defaultResponseItems(quote) {
  return (quote?.items ?? []).map((item) => ({
    pricingRequestItemId: item.pricingRequestItemId,
    supplierProductCode: item.supplierProductCode ?? '',
    supplierProductDescription: item.supplierProductDescription ?? '',
    quotedQuantity: item.quotedQuantity ?? 1,
    quotedUnit: item.quotedUnit ?? 'PER_PIECE',
    unitBasis: item.unitBasis ?? item.quotedUnit ?? 'PER_PIECE',
    rawUnitPrice: item.rawUnitPrice ?? '',
    currency: item.currency ?? quote.defaultCurrency ?? 'THB',
    minimumOrderQuantity: item.minimumOrderQuantity ?? '',
    sqmPerUnit: item.sqmPerUnit ?? '',
    piecesPerBox: item.piecesPerBox ?? '',
    leadTimeText: item.leadTimeText ?? '',
    availabilityNote: item.availabilityNote ?? '',
    lineNote: item.lineNote ?? '',
  }));
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
    defaultCurrency: draft.defaultCurrency || 'THB',
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
  const [infoMessage, setInfoMessage] = useState('');
  const [salesResponse, setSalesResponse] = useState('');
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
    queryClient.invalidateQueries({ queryKey: ['pricingRequests', 'queue'] });
  }

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
  const readyQuote = useActionMutation((quote) => api.pricingRequests.markFactoryQuoteReady(quote.id), 'พร้อมคำนวณต้นทุนแล้ว');
  const createCosting = useActionMutation(() => api.pricingRequests.createCosting(pricingRequestId, { note: costingNote || null, clientRequestId: costingClientRequestId }), 'สร้างร่างต้นทุนแล้ว');
  const recalculateCosting = useActionMutation((costing) => api.pricingRequests.recalculateCosting(costing.id, { note: costingNote || null }), 'คำนวณต้นทุนแล้ว');
  const submitCosting = useActionMutation((costing) => api.pricingRequests.submitCosting(costing.id, { note: costingNote || null }), 'ส่งให้ CEO แล้ว');
  const requestInfo = useActionMutation(() => api.pricingRequests.requestInformation(pricingRequestId, { message: infoMessage }), 'ส่งคำขอข้อมูลแล้ว');
  const respondInfo = useActionMutation(() => api.pricingRequests.respondInformation(pricingRequestId, { response: salesResponse }), 'ส่งข้อมูลเพิ่มเติมแล้ว');
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
    'ตีกลับให้ Import แก้ไขต้นทุนแล้ว',
  );
  const request = detailQuery.data;
  const summary = request?.summary;
  const status = pricingRequestStatusLabel(summary?.status);
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
  const canCreateCustomerRevision = isSales(user)
    && summary?.ticketCreatedById === user?.employeeId
    && !['DRAFT', 'CANCELLED', 'SUPERSEDED'].includes(summary?.status);
  const pricingRequestAttachments = attachmentsQuery.data ?? [];
  // Mirrors PricingRequestService.ATTACHMENT_EDITABLE_STATUSES: Sales may only upload/delete its
  // own Pricing Request attachments while the request is DRAFT or MORE_INFO_REQUIRED, and only
  // on the request it owns.
  const canEditPricingRequestAttachments = isSales(user)
    && summary?.ticketCreatedById === user?.employeeId
    && ['DRAFT', 'MORE_INFO_REQUIRED'].includes(summary?.status);

  if (detailQuery.isLoading) {
    return <div className="page-stack"><p className="text-sm text-text-muted">กำลังโหลด...</p></div>;
  }

  if (!summary) {
    return <div className="page-stack"><p className="text-sm text-text-muted">ไม่พบใบขอราคา</p></div>;
  }

  return (
    <div className="page-stack">
      <PageHeader
        title={summary.requestCode}
        subtitle={`${summary.customerName ?? '-'}${summary.projectName ? ` · ${summary.projectName}` : ''}`}
        actions={(
          <button type="button" className="secondary-button" onClick={() => navigate(-1)}>
            <Icon name="chevronLeft" size={14} />
            กลับ
          </button>
        )}
      />

      <section className="table-panel">
        <div className="panel-header">
          <h2>ภาพรวม</h2>
          <StatusBadge tone={status.tone}>{status.label}</StatusBadge>
        </div>
        <div className="grid gap-3 p-4 md:grid-cols-2">
          <div className="text-sm"><strong>ดีล</strong> <Link to={`/tickets/${summary.ticketId}`} className="text-info underline">{summary.ticketCode}</Link></div>
          <div className="text-sm"><strong>ผู้รับ</strong> {pricingRequestRecipientLabel(summary.recipientType)}{summary.recipientLabel ? ` · ${summary.recipientLabel}` : ''}</div>
          <div className="text-sm"><strong>ต้องการภายใน</strong> {formatThaiDate(summary.requiredDate)}</div>
          <div className="text-sm"><strong>Import</strong> ฝ่าย Import</div>
        </div>
      </section>

      <section className="table-panel">
        <div className="panel-header"><h2>รายการสินค้าและราคาตั้งต้น</h2></div>
        <div className="flex flex-col gap-2 p-3">
          {(request.items ?? []).map((item) => (
            <div key={item.id} className="rounded-md border border-border bg-surface p-3">
              <div className="flex flex-wrap items-center gap-2">
                <strong>{[item.catalogBrand ?? item.brand, item.catalogModel ?? item.model].filter(Boolean).join(' ') || item.productDescription || '-'}</strong>
                <StatusBadge tone="neutral">{quantityTypeLabel(item.quantityType)}</StatusBadge>
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
      </section>

      <section className="table-panel">
        <div className="panel-header">
          <h2>ไฟล์แนบประกอบคำขอราคา</h2>
          {canEditPricingRequestAttachments ? (
            <label className="secondary-button cursor-pointer">
              <input type="file" className="hidden" onChange={(event) => {
                const file = event.target.files?.[0];
                if (file) uploadPricingRequestAttachment.mutate(file);
                event.target.value = '';
              }} />
              <Icon name="upload" size={13} />
              แนบไฟล์
            </label>
          ) : null}
        </div>
        <div className="flex flex-col gap-1 p-3 text-xs text-text-muted">
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
                <button
                  type="button"
                  className="icon-button"
                  aria-label={`ลบไฟล์แนบ ${attachment.fileName}`}
                  onClick={() => deletePricingRequestAttachment.mutate(attachment.id)}
                >
                  <Icon name="close" size={13} />
                </button>
              ) : null}
            </div>
          ))}
          {pricingRequestAttachments.length === 0 ? <span>ยังไม่มีไฟล์แนบ</span> : null}
        </div>
      </section>

      {canRequestInformation(user, summary) ? (
        <section className="table-panel">
          <div className="panel-header"><h2>ขอข้อมูลจาก Sales</h2></div>
          <div className="flex flex-wrap gap-2 p-3">
            <input className="form-input min-w-64" value={infoMessage} onChange={(e) => setInfoMessage(e.target.value)} placeholder="ข้อความถึง Sales" />
            <button type="button" className="secondary-button" disabled={!infoMessage || requestInfo.isPending} onClick={() => requestInfo.mutate()}>
              ส่งคำขอ
            </button>
          </div>
        </section>
      ) : null}

      {canRespondInformation(user, summary) ? (
        <section className="table-panel">
          <div className="panel-header"><h2>ตอบข้อมูลเพิ่มเติม</h2></div>
          <div className="flex flex-wrap gap-2 p-3">
            <input className="form-input min-w-64" value={salesResponse} onChange={(e) => setSalesResponse(e.target.value)} placeholder="ข้อมูลเพิ่มเติม" />
            <button type="button" className="secondary-button" disabled={!salesResponse || respondInfo.isPending} onClick={() => respondInfo.mutate()}>
              ส่งข้อมูล
            </button>
          </div>
        </section>
      ) : null}

      {canCreateCustomerRevision ? (
        <section className="table-panel">
          <div className="panel-header"><h2>Customer Change Revision</h2></div>
          <div className="flex flex-wrap gap-2 p-3">
            <button type="button" className="secondary-button" onClick={() => setRevisionModalOpen(true)}>
              สร้าง revision
            </button>
          </div>
        </section>
      ) : null}

      {canSeeRaw(user) ? (
        <section className="table-panel">
          <div className="panel-header">
            <h2>Factory Quotes</h2>
            {isImport(user) ? (
              <button type="button" className="primary-button" disabled={generateDrafts.isPending} onClick={() => generateDrafts.mutate()}>
                สร้างร่างอีเมล
              </button>
            ) : null}
          </div>
          <div className="flex flex-col gap-3 p-3">
            {factoryQuotes.map((quote) => {
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
                items: defaultResponseItems(quote),
              };
              return (
                <div key={quote.id} className="rounded-md border border-border bg-surface p-3">
                  <div className="flex flex-wrap items-center gap-2">
                    <strong>{quote.factoryName}</strong>
                    <StatusBadge tone="neutral">Rev {quote.revisionNo}</StatusBadge>
                    <StatusBadge tone={quote.current ? 'success' : 'neutral'}>{quote.status}</StatusBadge>
                    {dispatchStatusBadge(quote)}
                    {isImport(user) && quote.status === 'DRAFT' && quote.dispatchStatus !== 'PENDING' && quote.dispatchStatus !== 'SENDING' ? (
                      <button type="button" className="secondary-button" onClick={() => {
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
                        {quote.dispatchStatus === 'FAILED' ? 'ส่งอีกครั้ง' : 'ส่ง'}
                      </button>
                    ) : null}
                    {isImport(user) && ['RESPONSE_RECEIVED', 'NEGOTIATING'].includes(quote.status) && quote.current ? <button type="button" className="secondary-button" onClick={() => readyQuote.mutate(quote)}>พร้อม costing</button> : null}
                    {isImport(user) && quote.status === 'RESPONSE_RECEIVED' && quote.current ? <button type="button" className="secondary-button" onClick={() => negotiateQuote.mutate(quote)}>เจรจา</button> : null}
                  </div>
                  <div className="mt-2 text-xs text-text-muted">{quote.emailTo ?? '-'} · {quote.supplierQuoteRef ?? '-'}</div>
                  {quote.dispatchStatus === 'FAILED' && quote.dispatchFailureMessage ? (
                    <div className="mt-1 text-xs text-danger">ส่งไม่สำเร็จ: {quote.dispatchFailureMessage}</div>
                  ) : null}
                  {isImport(user) && quote.status === 'DRAFT' ? (
                    <div className="mt-3 grid gap-2 border-t border-border-subtle pt-3">
                      <input className="form-input" value={emailDraft.emailTo} onChange={(e) => setEmailDrafts({ ...emailDrafts, [quote.id]: { ...emailDraft, emailTo: e.target.value } })} placeholder="Factory email recipient" />
                      <input className="form-input" value={emailDraft.emailSubject} onChange={(e) => setEmailDrafts({ ...emailDrafts, [quote.id]: { ...emailDraft, emailSubject: e.target.value } })} placeholder="Subject" />
                      <textarea className="form-input min-h-24" value={emailDraft.emailBody} onChange={(e) => setEmailDrafts({ ...emailDrafts, [quote.id]: { ...emailDraft, emailBody: e.target.value } })} placeholder="Email body" />
                      <button type="button" className="secondary-button" disabled={updateQuote.isPending} onClick={() => updateQuote.mutate({ quote, draft: emailDraft })}>
                        บันทึกร่างอีเมล
                      </button>
                    </div>
                  ) : null}
                  {quote.items?.length ? (
                    <div className="mt-3 flex flex-col gap-1 border-t border-border-subtle pt-3 text-xs text-text-muted">
                      {quote.items.map((line) => (
                        <span key={line.id}>
                          Item #{line.pricingRequestItemId} · raw {formatCurrency(line.rawUnitPrice, line.currency)} · {line.unitBasis ?? '-'} · {line.sqmPerUnit ? `${line.sqmPerUnit} sqm/unit` : '-'}
                        </span>
                      ))}
                    </div>
                  ) : null}
                  {(quote.attachments ?? []).length || isImport(user) ? (
                    <div className="mt-3 border-t border-border-subtle pt-3">
                      <div className="mb-2 flex flex-wrap items-center gap-2">
                        <span className="text-xs font-semibold uppercase text-text-muted">Attachments</span>
                        {isImport(user) ? (
                          <label className="secondary-button cursor-pointer">
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
                      <div className="grid gap-2 md:grid-cols-4">
                        <input className="form-input" value={draft.supplierQuoteRef} onChange={(e) => setResponseDrafts({ ...responseDrafts, [quote.id]: { ...draft, supplierQuoteRef: e.target.value } })} placeholder="Ref" />
                        <input className="form-input" value={draft.defaultCurrency} onChange={(e) => setResponseDrafts({ ...responseDrafts, [quote.id]: { ...draft, defaultCurrency: e.target.value } })} placeholder="Currency" />
                        <input className="form-input" value={draft.paymentTerms} onChange={(e) => setResponseDrafts({ ...responseDrafts, [quote.id]: { ...draft, paymentTerms: e.target.value } })} placeholder="Payment terms" />
                        <input className="form-input" value={draft.leadTimeText} onChange={(e) => setResponseDrafts({ ...responseDrafts, [quote.id]: { ...draft, leadTimeText: e.target.value } })} placeholder="Lead time" />
                      </div>
                      {draft.items.map((line, index) => (
                        <div key={line.pricingRequestItemId} className="grid gap-2 md:grid-cols-4">
                          <input className="form-input" value={line.rawUnitPrice} onChange={(e) => {
                            const items = [...draft.items];
                            items[index] = { ...line, rawUnitPrice: e.target.value };
                            setResponseDrafts({ ...responseDrafts, [quote.id]: { ...draft, items } });
                          }} placeholder="Raw price" />
                          <input className="form-input" value={line.currency} onChange={(e) => {
                            const items = [...draft.items];
                            items[index] = { ...line, currency: e.target.value };
                            setResponseDrafts({ ...responseDrafts, [quote.id]: { ...draft, items } });
                          }} placeholder="Currency" />
                          <select className="form-input" value={line.unitBasis} onChange={(e) => {
                            const items = [...draft.items];
                            items[index] = { ...line, quotedUnit: e.target.value, unitBasis: e.target.value };
                            setResponseDrafts({ ...responseDrafts, [quote.id]: { ...draft, items } });
                          }}>
                            {UNIT_OPTIONS.map((option) => <option key={option.code} value={option.code}>{option.label}</option>)}
                          </select>
                          <input className="form-input" value={line.sqmPerUnit} onChange={(e) => {
                            const items = [...draft.items];
                            items[index] = { ...line, sqmPerUnit: e.target.value };
                            setResponseDrafts({ ...responseDrafts, [quote.id]: { ...draft, items } });
                          }} placeholder="sqm/unit" />
                        </div>
                      ))}
                      <button type="button" className="secondary-button" disabled={receiveQuote.isPending} onClick={() => {
                        const clientRequestId = receiveClientRequestIds[quote.id] ?? generateClientRequestId();
                        setReceiveClientRequestIds((cur) => ({ ...cur, [quote.id]: clientRequestId }));
                        receiveQuote.mutate({ quote, draft, clientRequestId });
                      }}>
                        บันทึก response/revision
                      </button>
                    </div>
                  ) : null}
                </div>
              );
            })}
            {factoryQuotes.length === 0 ? <p className="text-sm text-text-muted">ยังไม่มี factory quote</p> : null}
          </div>
        </section>
      ) : null}

      {canSeeRaw(user) ? (
        <section className="table-panel">
          <div className="panel-header">
            <h2>Costing</h2>
            {isImport(user) ? <button type="button" className="primary-button" onClick={() => createCosting.mutate()}>สร้าง draft</button> : null}
          </div>
          <div className="flex flex-col gap-3 p-3">
            {isImport(user) ? <input className="form-input" value={costingNote} onChange={(e) => setCostingNote(e.target.value)} placeholder="Costing note" /> : null}
            {costings.map((costing) => (
              <div key={costing.id} className="rounded-md border border-border bg-surface p-3">
                <div className="flex flex-wrap items-center gap-2">
                  <strong>{costing.costingCode}</strong>
                  <StatusBadge tone="neutral">Version {costing.versionNo}</StatusBadge>
                  <StatusBadge tone={costing.status === 'SUBMITTED' ? 'success' : 'warning'}>{costing.status}{costing.stale ? ' · stale' : ''}</StatusBadge>
                  <span className="text-xs text-text-muted">{costing.totalLandedCostThb != null ? formatCurrency(costing.totalLandedCostThb, 'THB') : '-'}</span>
                  {isImport(user) && costing.id === latestOpenCosting?.id ? (
                    <>
                      <button type="button" className="secondary-button" onClick={() => recalculateCosting.mutate(costing)}>คำนวณใหม่</button>
                      <button type="button" className="secondary-button" disabled={costing.status !== 'CALCULATED' || costing.stale} onClick={() => setConfirmAction({ type: 'submitCosting', costing })}>Submit to CEO</button>
                    </>
                  ) : null}
                </div>
                {canSeeRaw(user) && costing.items?.length ? (
                  <div className="mt-2 flex flex-col gap-1 text-xs text-text-muted">
                    {costing.items.map((item) => (
                      <span key={item.id}>{item.factoryName} · Rev {item.factoryQuoteRevisionNo} · raw {formatCurrency(item.rawUnitPrice, item.rawCurrency)} · landed {formatCurrency(item.landedCostPerUnitThb, 'THB')}</span>
                    ))}
                  </div>
                ) : null}
              </div>
            ))}
            {costings.length === 0 ? <p className="text-sm text-text-muted">ยังไม่มี costing</p> : null}
          </div>
        </section>
      ) : null}

      {canSeeRawPricingDecision(user) ? (
        <section className="table-panel">
          <div className="panel-header">
            <h2>CEO Selling Price Decision</h2>
          </div>
          <div className="flex flex-col gap-3 p-3">
            {!currentDecision && canStartCeoReview(user, summary) ? (
              <div className="flex flex-wrap items-center gap-2">
                <label className="text-xs text-text-muted">
                  Default margin
                  <input
                    className="form-input ml-2 w-24"
                    value={decisionDefaultMargin}
                    onChange={(e) => setDecisionDefaultMargin(e.target.value)}
                    placeholder="0.20"
                  />
                </label>
                <button type="button" className="primary-button" disabled={startCeoReview.isPending} onClick={() => startCeoReview.mutate()}>
                  เริ่มพิจารณาราคาขาย
                </button>
              </div>
            ) : null}
            {!currentDecision && !canStartCeoReview(user, summary) ? (
              <p className="text-sm text-text-muted">ยังไม่มีการพิจารณาราคาขาย</p>
            ) : null}
            {currentDecision ? (() => {
              const decision = currentDecision;
              const isDraft = decision.status === 'DRAFT';
              const editable = isDraft && canActOnPricingDecision(user, summary);
              const missingBeforeApprove = decision.items.filter((item) => {
                const draft = decisionItemDrafts[item.id] ?? {};
                const margin = draft.marginPct ?? item.proposedMarginPct;
                const minPrice = draft.minimumSellingPrice ?? item.minimumSellingPricePerRequestedUnit;
                return margin == null || margin === '' || minPrice == null || minPrice === '';
              });
              return (
                <div className="rounded-md border border-border bg-surface p-3">
                  <div className="flex flex-wrap items-center gap-2">
                    <strong>{decision.decisionCode}</strong>
                    <StatusBadge tone="neutral">Version {decision.decisionVersionNo}</StatusBadge>
                    <StatusBadge tone={decision.status === 'APPROVED' ? 'success' : decision.status === 'RETURNED' ? 'danger' : 'warning'}>
                      {decision.status}
                    </StatusBadge>
                    <span className="text-xs text-text-muted">
                      {decision.currency} · FX {decision.fxRateUsed} ({decision.fxSource}, {decision.fxEffectiveDate})
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
                                placeholder="Margin (0.20 = 20%)"
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
                                placeholder="Discount ceiling (0.10 = 10%)"
                              />
                              <span className="self-center text-xs text-text-muted">
                                ราคาขายเสนอ: {formatCurrency(item.proposedSellingPricePerRequestedUnit, decision.currency)}
                              </span>
                            </div>
                          ) : (
                            <div className="mt-2 flex flex-wrap gap-x-4 gap-y-1 text-xs text-text-muted">
                              <span>Margin: {item.approvedMarginPct ?? item.proposedMarginPct ?? '-'}</span>
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
                      <button
                        type="button"
                        className="secondary-button"
                        disabled={saveDecisionItems.isPending}
                        onClick={() => saveDecisionItems.mutate({ decision, items: decision.items })}
                      >
                        บันทึกการเปลี่ยนแปลง
                      </button>
                      <button
                        type="button"
                        className="primary-button"
                        disabled={approveDecision.isPending || missingBeforeApprove.length > 0}
                        onClick={() => setConfirmAction({ type: 'approveDecision', decision })}
                      >
                        อนุมัติราคาขาย
                      </button>
                      <button
                        type="button"
                        className="secondary-button"
                        disabled={returnDecisionToImport.isPending}
                        onClick={() => setConfirmAction({ type: 'returnDecision', decision })}
                      >
                        ตีกลับให้ Import แก้ไข
                      </button>
                      {missingBeforeApprove.length > 0 ? (
                        <span className="self-center text-xs text-danger">ทุกรายการต้องมี margin และราคาขั้นต่ำก่อนอนุมัติ</span>
                      ) : null}
                    </div>
                  ) : null}
                </div>
              );
            })() : null}
            {pricingDecisions.length > 1 ? (
              <div className="text-xs text-text-muted">
                ประวัติ: {pricingDecisions.map((d) => `v${d.decisionVersionNo} (${d.status})`).join(' · ')}
              </div>
            ) : null}
          </div>
        </section>
      ) : null}

      {!canSeeRawPricingDecision(user) && canSeePricingDecisionSalesView(user, summary) && decisionSalesView ? (
        <section className="table-panel">
          <div className="panel-header"><h2>ราคาขายที่อนุมัติ</h2></div>
          <div className="flex flex-col gap-2 p-3">
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
        </section>
      ) : null}

      <ConfirmDialog
        open={Boolean(confirmAction)}
        title={confirmAction?.type === 'submitCosting' ? 'Submit costing to CEO'
          : confirmAction?.type === 'approveDecision' ? 'อนุมัติราคาขาย'
          : confirmAction?.type === 'returnDecision' ? 'ตีกลับให้ Import แก้ไขต้นทุน'
          : 'ส่งอีเมลถึงโรงงาน'}
        message={confirmAction?.type === 'submitCosting'
          ? 'เมื่อ submit แล้ว costing version นี้จะแก้ไขไม่ได้'
          : confirmAction?.type === 'approveDecision'
            ? 'เมื่ออนุมัติแล้ว ราคาขายจะถูกส่งให้ฝ่ายขายและไม่สามารถแก้ไขราคานี้ได้อีก'
            : confirmAction?.type === 'returnDecision'
              ? 'ระบุเหตุผลที่ตีกลับให้ Import คำนวณต้นทุนใหม่'
              : 'ยืนยันการส่งคำขอราคาให้โรงงานด้วยรายละเอียดอีเมลนี้'}
        confirmLabel={confirmAction?.type === 'submitCosting' ? 'Submit to CEO'
          : confirmAction?.type === 'approveDecision' ? 'อนุมัติ'
          : confirmAction?.type === 'returnDecision' ? 'ตีกลับ'
          : 'ส่งอีเมล'}
        tone={confirmAction?.type === 'returnDecision' ? 'danger' : 'default'}
        requireReason={confirmAction?.type === 'returnDecision'}
        reasonLabel="เหตุผลที่ตีกลับ"
        busy={sendQuote.isPending || submitCosting.isPending || approveDecision.isPending || returnDecisionToImport.isPending}
        onCancel={() => setConfirmAction(null)}
        onConfirm={(reason) => {
          const action = confirmAction;
          setConfirmAction(null);
          if (action?.type === 'submitCosting') submitCosting.mutate(action.costing);
          if (action?.type === 'sendQuote') sendQuote.mutate({ quote: action.quote, draft: action.emailDraft });
          if (action?.type === 'approveDecision') approveDecision.mutate(action.decision);
          if (action?.type === 'returnDecision') returnDecisionToImport.mutate({ decision: action.decision, reason });
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
