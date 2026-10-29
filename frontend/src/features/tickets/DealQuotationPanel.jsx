import {
  forwardRef, useImperativeHandle, useMemo, useState,
} from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { Button } from '../../components/common/Button.jsx';
import { ConfirmDialog } from '../../components/common/ConfirmDialog.jsx';
import { ConfirmOrderDialog } from '../quotations/ConfirmOrderDialog.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { Panel } from '../../components/common/Layout.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import {
  formatMoney,
  formatThaiDate,
  pricingRequestStatusLabel,
  quotationStatusLabel,
} from '../../utils/format.js';
import { downloadBlob } from '../../utils/download.js';
import {
  canConfirmOrder, confirmOrderBlockedReason, canCreateCustomerQuotation,
  canManageCustomerQuotation, canRecordCustomerQuotationOutcome, canViewCustomerQuotation,
  isCustomerQuotationEditable, pricingRequestRecipientLabel,
} from '../pricingRequests/pricingRequestMeta.js';
import { acceptOutcomeConfirmCopy, dealQuotationStatusLabel } from '../quotations/quotationMeta.js';
import { buttonVariants } from '../../components/common/Button.jsx';

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
  const tail = pricingRequests
    .filter((pr) => QUOTATION_TAIL_STATUSES.has(pr.status))
    .sort((a, b) => b.id - a.id);
  // The deal's order lives on its accepted request (one per deal, R8): the panel shows and confirms
  // that one, not a later party's newer request (review V2).
  return tail.find((pr) => pr.status === 'QUOTATION_ACCEPTED') ?? tail[0] ?? null;
}

// An old-engine quotation that no longer stands (CustomerQuotationRepository#hasLiveQuotation's
// non-live set): it does not hide the request's new-engine quotation (review V6).
const DEAD_CUSTOMER_QUOTATION_STATUSES = new Set(['CANCELLED', 'SUPERSEDED', 'EXPIRED', 'REJECTED']);

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
 *
 * Owner ruling R2 (2026-10-18/22): the sticky "เสนอใบราคา<party>" writes the NEW-engine quotation
 * (TicketDetailPage, api.dealQuotations.createFromPricingRequest); this panel no longer creates or
 * issues the old-engine customer quotation from the deal page. It shows that new-engine quotation
 * (`quotationByRequest`, the deal's quotation list) with a link to it.
 *
 * Ticket-detail IA rebuild Phase 1 clutter follow-up round 2 (FIX 2): this
 * panel no longer renders its own "ออกใบเสนอราคา" / "ยืนยันคำสั่งซื้อ"
 * buttons — the sticky header's primary CTA (TicketDetailPage's
 * `workState`-derived ISSUE_QUOTATION/CONFIRM_ORDER action) owns both
 * outright now; CONFIRM_ORDER triggers the confirm here via the forwardRef below (same
 * "parent triggers, panel stays the sole gate + mutation owner" convention
 * DealStagePanel/PricingRequestPanel already use for their own openers).
 * Before this, "ออกใบเสนอราคา" and "ยืนยันคำสั่งซื้อ" each rendered twice on
 * one page (the sticky bar's own scroll-to-here copy, plus this panel's own
 * button) — since the sticky bar is `sticky top-0`, both copies of each
 * label were visible on screen simultaneously.
 */
export const DealQuotationPanel = forwardRef(function DealQuotationPanel({
  ticketId, pricingRequests = [], quotationByRequest = null, user, showToast, depositMainButtonLabel = null,
}, ref) {
  const queryClient = useQueryClient();
  const [outcomeNote, setOutcomeNote] = useState('');
  const [downloadingFormat, setDownloadingFormat] = useState(null);
  const [confirmingAccept, setConfirmingAccept] = useState(false);
  // SK-02: the order confirmation is irreversible (the deal can no longer go below ขั้น 10), so every door to it
  // (sticky primary, ⋯, this panel) opens this dialog first.
  const [confirmingOrder, setConfirmingOrder] = useState(false);

  const pr = useMemo(() => pickRelevantPricingRequest(pricingRequests), [pricingRequests]);
  const prQuotation = quotationByRequest?.get(pr?.id);
  const prQuotationStatus = prQuotation && dealQuotationStatusLabel(prQuotation.docStatus);
  const acceptConfirm = acceptOutcomeConfirmCopy(pr?.recipientType);
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

  // Defensive re-check before acting (same convention as DealStagePanel's openers): a stale caller
  // cannot force a mutation past the real gate.
  useImperativeHandle(ref, () => ({
    openConfirmOrder: () => {
      if (confirmOrder.isPending) return;
      if (pr && canConfirmOrder(user, pr)) {
        setConfirmingOrder(true);
      } else {
        showToast?.('error', confirmOrderBlockedReason(pr)
          ?? 'ยังยืนยันคำสั่งซื้อไม่ได้ — ตรวจสอบสถานะคำขอราคาในส่วน "ราคาและใบเสนอราคา" ด้านล่าง');
      }
    },
  }));

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
    <Panel flush data-testid="deal-quotation-panel">
      {/* Explicit Panel.Header, not title/actions: this header's gap is 8px,
          not Panel's default 14px, and title/actions has no way to pass a
          className through to the header row it builds. */}
      <Panel.Header bordered className="gap-2">
        <h2 className="m-0 min-w-0 text-lg break-words">ราคาและใบเสนอราคา</h2>
        <div className="flex items-center gap-2">
          <StatusBadge tone={status.tone}>{status.label}</StatusBadge>
          <Link to={`/pricing-requests/${pr.id}`} className="text-xs font-bold text-link">
            ดูรายละเอียดเต็ม (ราคาโรงงาน/ต้นทุน/CEO) →
          </Link>
        </div>
      </Panel.Header>

      <div className="flex flex-col gap-3 p-4">
        <div className="flex flex-wrap items-center gap-2 text-xs text-text-muted">
          <span>{pr.requestCode}</span>
          <span>·</span>
          <span>{pricingRequestRecipientLabel(pr.recipientType)}{pr.recipientLabel ? ` · ${pr.recipientLabel}` : ''}</span>
        </div>

        {quotationsQuery.isLoading ? (
          <p className="text-sm text-text-muted">กำลังโหลดใบเสนอราคาลูกค้า…</p>
        ) : prQuotation && !(current && !DEAD_CUSTOMER_QUOTATION_STATUSES.has(current.docStatus)) ? (
          <div className="flex flex-wrap items-center gap-2 rounded-md border border-border bg-surface p-3">
            <span className="font-mono text-sm font-bold">{prQuotation.number}</span>
            <StatusBadge tone={prQuotationStatus.tone}>
              {prQuotationStatus.label}
            </StatusBadge>
            <Link to={`/quotations/${prQuotation.id}`} className="text-sm font-bold text-link">เปิดใบเสนอราคา</Link>
          </div>
        ) : !current ? (
          canCreateCustomerQuotation(user, pr) ? (
            <p className="text-sm text-text-muted">
              CEO อนุมัติราคาขายแล้ว — กดปุ่ม “เสนอใบราคา” บนแถบด้านบนเพื่อเขียนใบเสนอราคา
            </p>
          ) : (
            <p className="text-sm text-text-muted">ยังไม่มีใบเสนอราคาลูกค้าสำหรับคำขอราคานี้</p>
          )
        ) : (
          <div className="flex flex-col gap-3 rounded-md border border-border bg-surface p-3">
            <div className="flex flex-wrap items-center gap-2">
              <span className="font-mono text-sm font-bold">{current.number ?? `ครั้งที่ ${current.quotationRevisionNo}`}</span>
              <StatusBadge tone={
                ['ISSUED', 'SENT', 'ACCEPTED'].includes(current.docStatus) ? 'success'
                  : ['REJECTED', 'CANCELLED', 'EXPIRED'].includes(current.docStatus) ? 'danger'
                    : current.docStatus === 'REVISION_REQUESTED' ? 'warning' : 'neutral'
              }>
                {quotationStatusLabel(current.docStatus).label}
              </StatusBadge>
              <span className="text-sm text-text-muted">รวมทั้งสิ้น {formatMoney(current.grandTotal)}</span>
              {current.validityDate ? (
                <span className="text-xs text-text-muted">ยืนราคาถึง {formatThaiDate(current.validityDate)}</span>
              ) : null}
            </div>

            <div className="flex flex-wrap gap-2">
              <Button type="button" variant="secondary" disabled={downloadingFormat === 'pdf'} onClick={() => handleDownload('pdf')}>
                <Icon name="fileText" size={12} /> {downloadingFormat === 'pdf' ? 'กำลังดาวน์โหลด…' : 'PDF'}
              </Button>
              <Button type="button" variant="secondary" disabled={downloadingFormat === 'xlsx'} onClick={() => handleDownload('xlsx')}>
                <Icon name="fileText" size={12} /> {downloadingFormat === 'xlsx' ? 'กำลังดาวน์โหลด…' : 'Excel'}
              </Button>
              {isCustomerQuotationEditable(current) && canManageCustomerQuotation(user, pr) ? (
                // Not a <Button>: react-router's <Link> renders an <a> for
                // client-side navigation, which Button (a <button>) can't do.
                <Link to={`/pricing-requests/${pr.id}`} className={buttonVariants({ variant: 'secondary' })}>
                  แก้ไขรายละเอียด/ส่วนลด →
                </Link>
              ) : null}
            </div>

            {canRecordCustomerQuotationOutcome(user, pr, current) ? (
              <div data-testid="deal-quotation-outcome-controls" className="flex flex-col gap-2 border-t border-border-subtle pt-3">
                <strong className="text-sm">บันทึกผลจากลูกค้า</strong>
                <textarea
                  className="rounded border border-border p-2 text-sm"
                  rows={2}
                  placeholder="หมายเหตุจากลูกค้า (ถ้ามี)"
                  value={outcomeNote}
                  onChange={(e) => setOutcomeNote(e.target.value)}
                />
                <div className="flex flex-wrap gap-2">
                  <Button type="button" variant="primary" disabled={recordOutcome.isPending}
                    onClick={() => (acceptConfirm ? setConfirmingAccept(true) : recordOutcome.mutate('ACCEPTED'))}
                    data-testid="deal-quotation-accept">
                    ลูกค้ายอมรับ
                  </Button>
                  <Button type="button" variant="secondary" style={{ color: 'var(--color-danger)', borderColor: 'var(--color-danger-border)' }}
                    disabled={recordOutcome.isPending} onClick={() => recordOutcome.mutate('REJECTED')} data-testid="deal-quotation-reject">
                    ลูกค้าปฏิเสธ
                  </Button>
                  <Button type="button" variant="secondary" disabled={recordOutcome.isPending}
                    onClick={() => recordOutcome.mutate('REVISION_REQUESTED')} data-testid="deal-quotation-revision">
                    ลูกค้าขอแก้ไข
                  </Button>
                </div>
              </div>
            ) : null}

            {['ACCEPTED', 'REJECTED', 'REVISION_REQUESTED', 'EXPIRED', 'SUPERSEDED'].includes(current.docStatus) ? (
              <p className="text-xs text-text-muted">
                ผลใบเสนอราคา: <strong>{quotationStatusLabel(current.docStatus).label}</strong>
                {current.outcomeNote ? ` — ${current.outcomeNote}` : ''}
              </p>
            ) : null}
          </div>
        )}

        {pr.status === 'QUOTATION_ACCEPTED' ? (
          <div className="flex flex-col gap-2 rounded-md border border-border bg-surface p-3">
            <strong className="text-sm">ยืนยันคำสั่งซื้อ</strong>
            {canConfirmOrder(user, pr) ? (
              // The "ยืนยันคำสั่งซื้อ" button itself lives solely on the
              // sticky header now (FIX 2, see this component's own doc
              // comment) — this explains the section instead of duplicating
              // the CTA.
              <p className="text-sm text-text-muted">
                ลูกค้ายอมรับใบเสนอราคาแล้ว — กดปุ่ม “ยืนยันคำสั่งซื้อ” บนแถบด้านบนของหน้าเพื่อเริ่มขั้นตอนรับมัดจำและนำเข้าสินค้า
              </p>
            ) : (
              <p className="text-sm text-text-muted">
                {confirmOrderBlockedReason(pr) ?? (pr.orderConfirmedAt
                  // Owner rulings 2026-10-28: the main button creates the deposit notice (only while it offers
                  // it); otherwise the notice lives on the การเงิน tab.
                  ? `ยืนยันคำสั่งซื้อแล้วเมื่อ ${formatThaiDate(pr.orderConfirmedAt)} — ${depositMainButtonLabel
                    ? `ขั้นต่อไป: กดปุ่ม "${depositMainButtonLabel}" ด้านบน`
                    : 'ใบแจ้งยอดเงินรับมัดจำอยู่ที่แท็บ "การเงิน"'}`
                  : 'ยืนยันคำสั่งซื้อได้เฉพาะเจ้าของดีล (sales)')}
              </p>
            )}
          </div>
        ) : null}
      </div>

      <ConfirmOrderDialog
        open={confirmingOrder}
        number={current?.number}
        busy={confirmOrder.isPending}
        onCancel={() => setConfirmingOrder(false)}
        onConfirm={() => { setConfirmingOrder(false); confirmOrder.mutate(); }}
      />
      <ConfirmDialog
        open={confirmingAccept && Boolean(acceptConfirm)}
        title={acceptConfirm?.title}
        message={acceptConfirm?.message}
        busy={recordOutcome.isPending}
        onCancel={() => setConfirmingAccept(false)}
        onConfirm={() => { setConfirmingAccept(false); recordOutcome.mutate('ACCEPTED'); }}
      />
    </Panel>
  );
});
