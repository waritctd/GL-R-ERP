import { useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { Icon } from '../../components/common/Icon.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { formatMoney, formatThaiDate, pricingRequestStatusLabel } from '../../utils/format.js';
import { downloadBlob } from '../../utils/download.js';
import {
  canConfirmOrder, canCreateCustomerQuotation,
  canManageCustomerQuotation, canRecordCustomerQuotationOutcome, canViewCustomerQuotation,
  isCustomerQuotationEditable, pricingRequestRecipientLabel,
} from '../pricingRequests/pricingRequestMeta.js';

function generateClientRequestId() {
  return crypto.randomUUID?.()
    ?? '00000000-0000-4000-8000-' + String(Date.now()).slice(-12).padStart(12, '0');
}

// The customer-quotation tail only ever exists once a PricingRequest reaches
// APPROVED_FOR_QUOTATION (canCreateCustomerQuotation's own gate) — earlier
// statuses have nothing to surface here, only on PricingRequestDetailPage.
// Among the requests that DO qualify, the most recently created one is the
// one this deal is currently working — ties broken by id (creation order).
const QUOTATION_TAIL_STATUSES = new Set(['APPROVED_FOR_QUOTATION', 'QUOTATION_ISSUED', 'QUOTATION_ACCEPTED']);

function pickRelevantPricingRequest(pricingRequests = []) {
  return pricingRequests
    .filter((pr) => QUOTATION_TAIL_STATUSES.has(pr.status))
    .sort((a, b) => b.id - a.id)[0] ?? null;
}

/**
 * "ราคาและใบเสนอราคา" (Phase 2 Slice S2 — see
 * docs/agent-handoffs/104_feat-deal-workspace-unification.md): pulls the
 * customer-facing tail of the PricingRequest/CustomerQuotation chain (issue /
 * outcome) plus the order-confirm + deposit-notice bridge onto the deal
 * workspace, so a rep no longer has to leave the deal page to move a priced
 * PCR to a signed order. The factory/costing/CEO-price steps that precede
 * this stay on PricingRequestDetailPage — see the "ดูรายละเอียดเต็ม" link
 * below — this panel only covers Steps 4-6 of that chain
 * (CustomerQuotationService / OrderConfirmationService), reusing the exact
 * predicates and hrApi methods PricingRequestDetailPage itself uses.
 *
 * Renders nothing when the deal has no pricing request at or past
 * APPROVED_FOR_QUOTATION yet (there is nothing customer-facing to show) —
 * the caller should keep showing PricingRequestPanel above this for the
 * earlier-stage view.
 *
 * Terminal action is confirm-order only (Phase 3 Slice S4 de-duplication —
 * see docs/agent-handoffs/105_feat-deal-deposit-fulfilment-unify.md): the
 * create-deposit-notice-from-quotation control that used to live here too
 * (a real, documented overlap flagged in Slice S3's handoff) now lives
 * solely in DealDepositPanel's own "ใบแจ้งยอดมัดจำ" step — see this
 * component's git history for the removed `createDepositNotice`
 * mutation/`depositPercentInput` state if that overlap ever needs undoing.
 */
export function DealQuotationPanel({ ticketId, pricingRequests = [], user, showToast }) {
  const queryClient = useQueryClient();
  const [outcomeNote, setOutcomeNote] = useState('');
  const [downloadingFormat, setDownloadingFormat] = useState(null);

  const pr = useMemo(() => pickRelevantPricingRequest(pricingRequests), [pricingRequests]);
  const canView = pr != null && canViewCustomerQuotation(user, pr);

  const quotationsQuery = useQuery({
    queryKey: queryKeys.customerQuotations(pr?.id),
    queryFn: () => api.pricingRequests.listCustomerQuotations(pr.id).then((r) => r.items ?? []),
    enabled: canView,
  });
  const quotations = useMemo(
    () => [...(quotationsQuery.data ?? [])].sort((a, b) => a.quotationRevisionNo - b.quotationRevisionNo),
    [quotationsQuery.data],
  );
  const current = useMemo(
    () => quotations.find((q) => isCustomerQuotationEditable(q)) ?? [...quotations].reverse()[0] ?? null,
    [quotations],
  );

  function invalidate() {
    queryClient.invalidateQueries({ queryKey: queryKeys.customerQuotations(pr?.id) });
    queryClient.invalidateQueries({ queryKey: queryKeys.pricingRequestsByTicket(ticketId) });
    queryClient.invalidateQueries({ queryKey: queryKeys.pricingRequestDetail(pr?.id) });
    queryClient.invalidateQueries({ queryKey: queryKeys.ticketDetail(ticketId) });
  }

  const createQuotation = useMutation({
    mutationFn: () => api.pricingRequests.createCustomerQuotation(pr.id, { clientRequestId: generateClientRequestId() }),
    onSuccess: () => { showToast?.('success', 'สร้างร่างใบเสนอราคาลูกค้าแล้ว'); invalidate(); },
    onError: (err) => showToast?.('error', err.message || 'ดำเนินการไม่สำเร็จ'),
  });
  const issueQuotation = useMutation({
    mutationFn: () => api.pricingRequests.issueCustomerQuotation(current.id, { clientRequestId: generateClientRequestId() }),
    onSuccess: () => { showToast?.('success', 'ออกใบเสนอราคาลูกค้าแล้ว'); invalidate(); },
    onError: (err) => showToast?.('error', err.message || 'ดำเนินการไม่สำเร็จ'),
  });
  const recordOutcome = useMutation({
    mutationFn: (outcome) => api.pricingRequests.recordCustomerQuotationOutcome(current.id, {
      outcome, customerNote: outcomeNote || null, clientRequestId: generateClientRequestId(),
    }),
    onSuccess: () => { setOutcomeNote(''); showToast?.('success', 'บันทึกผลใบเสนอราคาแล้ว'); invalidate(); },
    onError: (err) => showToast?.('error', err.message || 'ดำเนินการไม่สำเร็จ'),
  });
  const confirmOrder = useMutation({
    mutationFn: () => api.pricingRequests.confirmOrder(pr.id, { clientRequestId: generateClientRequestId() }),
    onSuccess: () => { showToast?.('success', 'ยืนยันคำสั่งซื้อแล้ว'); invalidate(); },
    onError: (err) => showToast?.('error', err.message || 'ดำเนินการไม่สำเร็จ'),
  });

  async function handleDownload(format) {
    setDownloadingFormat(format);
    try {
      const blob = format === 'pdf'
        ? await api.pricingRequests.downloadCustomerQuotationPdf(current.id)
        : await api.pricingRequests.downloadCustomerQuotationXlsx(current.id);
      downloadBlob(blob, current.number ?? 'customer-quotation', format);
    } catch (err) {
      showToast?.('error', err.message || 'ดาวน์โหลดไม่สำเร็จ');
    } finally {
      setDownloadingFormat(null);
    }
  }

  if (!pr || !canView) return null;

  const status = pricingRequestStatusLabel(pr.status);

  return (
    <section className="table-panel" data-testid="deal-quotation-panel">
      <div className="panel-header" style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', flexWrap: 'wrap', gap: 8 }}>
        <h2>ราคาและใบเสนอราคา</h2>
        <div className="flex items-center gap-2">
          <StatusBadge tone={status.tone}>{status.label}</StatusBadge>
          <Link to={`/pricing-requests/${pr.id}`} className="text-xs font-bold text-link">
            ดูรายละเอียดเต็ม (ราคาโรงงาน/ต้นทุน/CEO) →
          </Link>
        </div>
      </div>

      <div className="flex flex-col gap-3 p-4">
        <div className="flex flex-wrap items-center gap-2 text-xs text-text-muted">
          <span>{pr.requestCode}</span>
          <span>·</span>
          <span>{pricingRequestRecipientLabel(pr.recipientType)}{pr.recipientLabel ? ` · ${pr.recipientLabel}` : ''}</span>
        </div>

        {quotationsQuery.isLoading ? (
          <p className="text-sm text-text-muted">กำลังโหลด...</p>
        ) : !current ? (
          canCreateCustomerQuotation(user, pr) ? (
            <div className="flex flex-col gap-2 rounded-md border border-border bg-surface p-3">
              <p className="text-sm text-text-muted">CEO อนุมัติราคาขายแล้ว — สร้างร่างใบเสนอราคาให้ลูกค้าได้เลย</p>
              <button type="button" className="primary-button self-start" disabled={createQuotation.isPending}
                onClick={() => createQuotation.mutate()} data-testid="deal-quotation-create">
                <Icon name="fileText" size={14} />
                สร้างร่างใบเสนอราคาลูกค้า
              </button>
            </div>
          ) : (
            <p className="text-sm text-text-muted">ยังไม่มีใบเสนอราคาลูกค้าสำหรับใบขอราคานี้</p>
          )
        ) : (
          <div className="flex flex-col gap-3 rounded-md border border-border bg-surface p-3">
            <div className="flex flex-wrap items-center gap-2">
              <span className="font-mono text-sm font-bold">{current.number ?? `rev ${current.quotationRevisionNo}`}</span>
              <StatusBadge tone={
                ['ISSUED', 'SENT', 'ACCEPTED'].includes(current.docStatus) ? 'success'
                  : ['REJECTED', 'CANCELLED', 'EXPIRED'].includes(current.docStatus) ? 'danger'
                    : current.docStatus === 'REVISION_REQUESTED' ? 'warning' : 'neutral'
              }>
                {current.docStatus}
              </StatusBadge>
              <span className="text-sm text-text-muted">รวมทั้งสิ้น {formatMoney(current.grandTotal)}</span>
              {current.validityDate ? (
                <span className="text-xs text-text-muted">ยืนราคาถึง {formatThaiDate(current.validityDate)}</span>
              ) : null}
            </div>

            <div className="flex flex-wrap gap-2">
              <button type="button" className="secondary-button" disabled={downloadingFormat === 'pdf'} onClick={() => handleDownload('pdf')}>
                <Icon name="fileText" size={12} /> {downloadingFormat === 'pdf' ? 'กำลังดาวน์โหลด...' : 'PDF'}
              </button>
              <button type="button" className="secondary-button" disabled={downloadingFormat === 'xlsx'} onClick={() => handleDownload('xlsx')}>
                <Icon name="fileText" size={12} /> {downloadingFormat === 'xlsx' ? 'กำลังดาวน์โหลด...' : 'Excel'}
              </button>
              {isCustomerQuotationEditable(current) && canManageCustomerQuotation(user, pr) ? (
                <>
                  <Link to={`/pricing-requests/${pr.id}`} className="secondary-button">
                    แก้ไขรายละเอียด/ส่วนลด →
                  </Link>
                  <button type="button" className="primary-button" disabled={issueQuotation.isPending}
                    onClick={() => issueQuotation.mutate()} data-testid="deal-quotation-issue">
                    ออกใบเสนอราคา
                  </button>
                </>
              ) : null}
            </div>

            {canRecordCustomerQuotationOutcome(user, pr, current) ? (
              <div className="flex flex-col gap-2 border-t border-border-subtle pt-3">
                <strong className="text-sm">บันทึกผลจากลูกค้า</strong>
                <textarea
                  className="rounded border border-border p-2 text-sm"
                  rows={2}
                  placeholder="หมายเหตุจากลูกค้า (ถ้ามี)"
                  value={outcomeNote}
                  onChange={(e) => setOutcomeNote(e.target.value)}
                />
                <div className="flex flex-wrap gap-2">
                  <button type="button" className="primary-button" disabled={recordOutcome.isPending}
                    onClick={() => recordOutcome.mutate('ACCEPTED')} data-testid="deal-quotation-accept">
                    ลูกค้ายอมรับ
                  </button>
                  <button type="button" className="secondary-button" style={{ color: 'var(--color-danger)', borderColor: 'var(--color-danger-border)' }}
                    disabled={recordOutcome.isPending} onClick={() => recordOutcome.mutate('REJECTED')} data-testid="deal-quotation-reject">
                    ลูกค้าปฏิเสธ
                  </button>
                  <button type="button" className="secondary-button" disabled={recordOutcome.isPending}
                    onClick={() => recordOutcome.mutate('REVISION_REQUESTED')} data-testid="deal-quotation-revision">
                    ลูกค้าขอแก้ไข
                  </button>
                </div>
              </div>
            ) : null}

            {['ACCEPTED', 'REJECTED', 'REVISION_REQUESTED', 'EXPIRED', 'SUPERSEDED'].includes(current.docStatus) ? (
              <p className="text-xs text-text-muted">
                ผลใบเสนอราคา: <strong>{current.docStatus}</strong>
                {current.outcomeNote ? ` — ${current.outcomeNote}` : ''}
              </p>
            ) : null}
          </div>
        )}

        {pr.status === 'QUOTATION_ACCEPTED' ? (
          <div className="flex flex-col gap-2 rounded-md border border-border bg-surface p-3">
            <strong className="text-sm">ยืนยันคำสั่งซื้อ</strong>
            {canConfirmOrder(user, pr) ? (
              <>
                <p className="text-sm text-text-muted">ลูกค้ายอมรับใบเสนอราคาแล้ว — ยืนยันคำสั่งซื้อเพื่อเริ่มขั้นตอนรับมัดจำและนำเข้าสินค้า</p>
                <button type="button" className="primary-button self-start" disabled={confirmOrder.isPending}
                  onClick={() => confirmOrder.mutate()} data-testid="deal-quotation-confirm-order">
                  ยืนยันคำสั่งซื้อ
                </button>
              </>
            ) : (
              <p className="text-sm text-text-muted">
                {pr.orderConfirmedAt
                  // Deposit-notice creation moved to DealDepositPanel's own
                  // "ใบแจ้งยอดมัดจำ" step (Phase 3 Slice S4 de-duplication —
                  // see docs/agent-handoffs/105_feat-deal-deposit-fulfilment-unify.md)
                  // — pointed at here rather than duplicated.
                  ? `ยืนยันคำสั่งซื้อแล้วเมื่อ ${formatThaiDate(pr.orderConfirmedAt)} — ออกใบแจ้งยอดเงินรับมัดจำได้ที่ส่วน "มัดจำ" ด้านล่าง`
                  : 'ยืนยันคำสั่งซื้อได้เฉพาะเจ้าของดีล (sales)'}
              </p>
            )}
          </div>
        ) : null}
      </div>
    </section>
  );
}
