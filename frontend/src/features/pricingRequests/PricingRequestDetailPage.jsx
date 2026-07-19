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
import { canRequestInformation, canRespondInformation, pricingRequestRecipientLabel, quantityTypeLabel } from './pricingRequestMeta.js';

function isImport(user) {
  return user?.role === 'import';
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
  const [confirmAction, setConfirmAction] = useState(null);

  const detailQuery = useQuery({
    queryKey: queryKeys.pricingRequestDetail(pricingRequestId),
    queryFn: () => api.pricingRequests.get(pricingRequestId).then((r) => r.pricingRequest),
    enabled: Number.isFinite(pricingRequestId),
  });

  const factoryQuery = useQuery({
    queryKey: queryKeys.pricingRequestFactoryQuotes(pricingRequestId),
    queryFn: () => api.pricingRequests.listFactoryQuotes(pricingRequestId).then((r) => r.items ?? []),
    enabled: Number.isFinite(pricingRequestId) && canSeeRaw(user),
  });

  const costingQuery = useQuery({
    queryKey: queryKeys.pricingRequestCostings(pricingRequestId),
    queryFn: () => api.pricingRequests.listCostings(pricingRequestId).then((r) => r.items ?? []),
    enabled: Number.isFinite(pricingRequestId) && canSeeRaw(user),
  });

  function invalidate() {
    queryClient.invalidateQueries({ queryKey: queryKeys.pricingRequestDetail(pricingRequestId) });
    queryClient.invalidateQueries({ queryKey: queryKeys.pricingRequestFactoryQuotes(pricingRequestId) });
    queryClient.invalidateQueries({ queryKey: queryKeys.pricingRequestCostings(pricingRequestId) });
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
  }), 'ส่งคำขอโรงงานแล้ว');
  const receiveQuote = useActionMutation(({ quote, draft }) => api.pricingRequests.receiveFactoryQuote(quote.id, cleanResponsePayload(draft)), 'บันทึกราคาโรงงานแล้ว');
  const negotiateQuote = useActionMutation((quote) => api.pricingRequests.startFactoryNegotiation(quote.id, { note: quote.negotiationNote || 'Negotiation in progress' }), 'เริ่มเจรจาแล้ว');
  const readyQuote = useActionMutation((quote) => api.pricingRequests.markFactoryQuoteReady(quote.id), 'พร้อมคำนวณต้นทุนแล้ว');
  const createCosting = useActionMutation(() => api.pricingRequests.createCosting(pricingRequestId, { note: costingNote || null, clientRequestId: costingClientRequestId }), 'สร้างร่างต้นทุนแล้ว');
  const recalculateCosting = useActionMutation((costing) => api.pricingRequests.recalculateCosting(costing.id, { note: costingNote || null }), 'คำนวณต้นทุนแล้ว');
  const submitCosting = useActionMutation((costing) => api.pricingRequests.submitCosting(costing.id, { note: costingNote || null }), 'ส่งให้ CEO แล้ว');
  const requestInfo = useActionMutation(() => api.pricingRequests.requestInformation(pricingRequestId, { message: infoMessage }), 'ส่งคำขอข้อมูลแล้ว');
  const respondInfo = useActionMutation(() => api.pricingRequests.respondInformation(pricingRequestId, { response: salesResponse }), 'ส่งข้อมูลเพิ่มเติมแล้ว');

  const request = detailQuery.data;
  const summary = request?.summary;
  const status = pricingRequestStatusLabel(summary?.status);
  const factoryQuotes = useMemo(() => factoryQuery.data ?? [], [factoryQuery.data]);
  const costings = useMemo(() => costingQuery.data ?? [], [costingQuery.data]);
  const latestOpenCosting = useMemo(
    () => [...costings].reverse().find((costing) => ['DRAFT', 'CALCULATED'].includes(costing.status)),
    [costings],
  );

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
                    {isImport(user) && quote.status === 'DRAFT' ? <button type="button" className="secondary-button" onClick={() => setConfirmAction({ type: 'sendQuote', quote, emailDraft })}>ส่ง</button> : null}
                    {isImport(user) && ['RESPONSE_RECEIVED', 'NEGOTIATING'].includes(quote.status) && quote.current ? <button type="button" className="secondary-button" onClick={() => readyQuote.mutate(quote)}>พร้อม costing</button> : null}
                    {isImport(user) && quote.status === 'RESPONSE_RECEIVED' && quote.current ? <button type="button" className="secondary-button" onClick={() => negotiateQuote.mutate(quote)}>เจรจา</button> : null}
                  </div>
                  <div className="mt-2 text-xs text-text-muted">{quote.emailTo ?? '-'} · {quote.supplierQuoteRef ?? '-'}</div>
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
                      <button type="button" className="secondary-button" disabled={receiveQuote.isPending} onClick={() => receiveQuote.mutate({ quote, draft })}>
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

      <ConfirmDialog
        open={Boolean(confirmAction)}
        title={confirmAction?.type === 'submitCosting' ? 'Submit costing to CEO' : 'ส่งอีเมลถึงโรงงาน'}
        message={confirmAction?.type === 'submitCosting'
          ? 'เมื่อ submit แล้ว costing version นี้จะแก้ไขไม่ได้'
          : 'ยืนยันการส่งคำขอราคาให้โรงงานด้วยรายละเอียดอีเมลนี้'}
        confirmLabel={confirmAction?.type === 'submitCosting' ? 'Submit to CEO' : 'ส่งอีเมล'}
        busy={sendQuote.isPending || submitCosting.isPending}
        onCancel={() => setConfirmAction(null)}
        onConfirm={() => {
          const action = confirmAction;
          setConfirmAction(null);
          if (action?.type === 'submitCosting') submitCosting.mutate(action.costing);
          if (action?.type === 'sendQuote') sendQuote.mutate({ quote: action.quote, draft: action.emailDraft });
        }}
      />
    </div>
  );
}
