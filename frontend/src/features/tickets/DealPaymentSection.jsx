import { useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { Button, buttonVariants } from '../../components/common/Button.jsx';
import { ConfirmDialog } from '../../components/common/ConfirmDialog.jsx';
import { EmptyState } from '../../components/common/EmptyState.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { Panel } from '../../components/common/Layout.jsx';
import { SkeletonText } from '../../components/common/Skeleton.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { cn } from '../../utils/cn.js';
import { downloadBlob } from '../../utils/download.js';
import {
  formatMoney, formatThaiDate, overdueBadgeLabel, paymentStageLabel, PAYMENT_KIND_LABEL,
} from '../../utils/format.js';
import { depositNoticesQueryOptions, pickDepositNotice } from '../deposits/createDepositNoticeDraft.js';
import { DepositIssueSummary } from '../deposits/DepositNoticePage.jsx';
import { canCreateDepositNoticeFromQuotation } from '../pricingRequests/pricingRequestMeta.js';
import { newestQuotationByRequest } from '../quotations/quotationMeta.js';
import { depositStatus } from './dealMoneyStatus.js';
import { EVENT_KIND_LABEL, PAYMENT_TRACK_KINDS } from './DealHistoryPanel.jsx';
import { isRemainingInvoiceReady, pickRemainingInvoice } from './remainingInvoiceReadiness.js';

/** `Date.parse`-safe timestamp, or `null`: an unparseable row sorts last rather than floating to the top as `NaN` would. */
function toTimestamp(value) {
  if (!value) return null;
  const t = new Date(value).getTime();
  return Number.isNaN(t) ? null : t;
}

function MoneyEventRow({ event }) {
  return (
    <li className="flex items-start gap-2">
      <span className="mt-1.5 h-2 w-2 shrink-0 rounded-full bg-success" aria-hidden="true" />
      <div className="min-w-0 flex-1">
        <strong className="block text-sm text-text">{EVENT_KIND_LABEL[event.kind] ?? event.kind}</strong>
        {event.actorName ? <span className="text-xs text-icon-muted">{event.actorName}</span> : null}
        <small className="block text-2xs text-text-muted">{formatThaiDate(event.createdAt)}</small>
      </div>
    </li>
  );
}

function MoneyReceiptRow({ receipt }) {
  return (
    <li className="flex items-start gap-2">
      <span className="mt-1.5 h-2 w-2 shrink-0 rounded-full bg-info" aria-hidden="true" />
      <div className="min-w-0 flex-1">
        <div className="flex flex-wrap items-baseline gap-2">
          <strong className="text-sm text-text">{PAYMENT_KIND_LABEL[receipt.kind] ?? receipt.kind}</strong>
          <span className="font-mono text-sm text-text">{formatMoney(receipt.amount)}</span>
        </div>
        <small className="block text-2xs text-text-muted">
          {receipt.recordedByName || '-'}{receipt.note ? ` · ${receipt.note}` : ''}
        </small>
        <small className="block text-2xs text-text-muted">{formatThaiDate(receipt.receivedAt)}</small>
      </div>
    </li>
  );
}

// Step-card look shared with the old document pipeline: number (a check once done), title, the
// "ขั้นตอนถัดไป" chip on the current step, a status badge, then meta / hint / actions.
function Step({
  no, id, title, done, current, muted, badge, meta, hint, extra, children,
}) {
  return (
    <div
      data-testid={`payment-step-${id}`}
      className={cn(
        'flex flex-col gap-2 rounded-md border p-3',
        current ? 'border-info bg-info-bg/40' : 'border-border bg-surface',
        muted && 'opacity-65',
      )}
    >
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="flex min-w-0 flex-wrap items-center gap-2">
          <span className={cn(
            'grid h-6 w-6 shrink-0 place-items-center rounded-full text-2xs font-extrabold text-surface',
            done ? 'bg-success' : 'bg-info',
          )}
          >
            {done ? <Icon name="check" size={13} /> : no}
          </span>
          <strong className="text-md">{title}</strong>
          {current ? <span className="rounded-full bg-info-bg px-2 py-0.5 text-2xs font-bold text-info">ขั้นตอนถัดไป</span> : null}
        </div>
        <StatusBadge tone={badge.tone}>{badge.label}</StatusBadge>
      </div>
      {meta ? <div className="flex flex-wrap gap-x-3.5 gap-y-1 text-xs text-text-muted mobile:pl-0 pl-8">{meta}</div> : null}
      {hint ? <p className="m-0 text-xs text-text-muted pl-8 mobile:pl-0">{hint}</p> : null}
      {children ? <div className="flex flex-wrap gap-2 pl-8 mobile:pl-0">{children}</div> : null}
      {extra}
    </div>
  );
}

const DEP_BADGE = {
  skipped: { label: 'ไม่เก็บมัดจำ', tone: 'neutral' },
  notyet: { label: 'ยังไม่ถึงขั้นตอน', tone: 'neutral' },
  create: { label: 'ยังไม่สร้าง', tone: 'warning' },
  draft: { label: 'ร่าง', tone: 'warning' },
  issued: { label: 'รอชำระ', tone: 'warning' },
  paid: { label: 'ชำระแล้ว', tone: 'success' },
};

const DEP_HINT = {
  skipped: 'ข้ามขั้นตอนนี้ — ไม่ต้องเก็บมัดจำสำหรับดีลนี้',
  notyet: 'สร้างได้เมื่อลูกค้ายืนยันคำสั่งซื้อ',
  issued: 'รอลูกค้าชำระ — ฝ่ายบัญชียืนยันรับเงินที่หน้าการเงินของฝ่ายบัญชี',
};

const REM_BADGE = {
  wait: { label: 'รอรับมัดจำก่อน', tone: 'neutral' },
  create: { label: 'ยังไม่ออก', tone: 'warning' },
  draft: { label: 'ร่าง', tone: 'warning' },
  issued: { label: 'รอชำระ', tone: 'warning' },
  paid: { label: 'ชำระแล้ว', tone: 'success' },
  notneeded: { label: 'ไม่ต้องออก — ชำระครบจากมัดจำ', tone: 'neutral' },
};

const ERROR_BADGE = { label: 'โหลดไม่สำเร็จ', tone: 'danger' };

// A failed list read must never fall through to "none yet" (that would offer สร้าง over an existing document).
function ListError({ onRetry }) {
  return (
    <>
      <span role="alert" className="text-xs text-danger">โหลดเอกสารไม่สำเร็จ</span>
      <Button type="button" variant="secondary" onClick={onRetry}>ลองใหม่</Button>
    </>
  );
}

const mono = (text) => <span className="font-mono text-sm font-bold text-text">{text}</span>;

/**
 * The deal page's การเงิน tab, as ONE panel (owner design 2026-10-31): the payment-stage badge, the
 * balance readout with a paid bar, three document steps (ใบแจ้งยอดเงินรับมัดจำ → ใบแจ้งหนี้ส่วนที่เหลือ →
 * รับชำระครบ), and the payment timeline underneath. Replaces the old สถานะการเงิน strip/pipeline, the
 * มัดจำ card, the remaining-invoice card and the timeline panel; the วางบิล step and controls are gone.
 *
 * The deposit step keeps the old มัดจำ card's gates: the owning sales rep creates / edits / issues;
 * every viewer who can read the notice can preview a draft and download an issued one. The remaining
 * invoice opens the existing RemainingInvoiceDialog through the page (`onManageRemainingInvoice`),
 * gated by `canManageRemainingInvoice` (= can.downloadRemainingInvoice). Account records payments on
 * /finance/deals/:id, so the last step is status only.
 *
 * `mainButtonCreatesNotice`: the page's main button already offers สร้างใบแจ้งยอดเงินรับมัดจำ, so the
 * step carries a hint instead of a second create button.
 */
export function DealPaymentSection({
  ticketId, user, summary, events = [], paymentReceipts = [], paymentsLoading = false,
  pricingRequests = [], dealQuotations = [], availableActions = [],
  canReadDepositNotices = true, canManageRemainingInvoice = false, onManageRemainingInvoice,
  mainButtonCreatesNotice = false, onCreateDepositNotice,
  canRecordPayment = false, actionLoading = false, onRecordPayment, showToast,
}) {
  const queryClient = useQueryClient();
  const role = user?.role;
  const isOwnerSales = role === 'sales' && user?.id === summary.createdById;
  const ps = summary.paymentStatus;
  const hasAction = (action) => availableActions.some((item) => item.action === action);

  const [downloadingFormat, setDownloadingFormat] = useState(null);
  const [previewHtml, setPreviewHtml] = useState('');
  const [confirmIssue, setConfirmIssue] = useState(false);

  const depositQuery = useQuery({ ...depositNoticesQueryOptions(ticketId), enabled: !!ticketId && canReadDepositNotices });
  const remainingQuery = useQuery({
    queryKey: queryKeys.storedRemainingInvoices(ticketId),
    queryFn: () => api.storedRemainingInvoices.listForTicket(ticketId).then((r) => r.remainingInvoices ?? []),
    enabled: !!ticketId,
  });
  const doc = useMemo(() => pickDepositNotice(depositQuery.data), [depositQuery.data]);
  const invoice = pickRemainingInvoice(remainingQuery.data ?? []);

  function invalidateAfterDepositChange() {
    queryClient.invalidateQueries({ queryKey: queryKeys.depositNotices(ticketId) });
    queryClient.invalidateQueries({ queryKey: queryKeys.ticketDetail(ticketId) });
    queryClient.invalidateQueries({ queryKey: queryKeys.ticketActions(ticketId) });
    queryClient.invalidateQueries({ queryKey: queryKeys.ticketPayments(ticketId) });
    queryClient.invalidateQueries({ queryKey: ['tickets', 'list'] });
  }

  const issueMutation = useMutation({
    // The dialog shows the amounts as listed; the stamp makes the server refuse if the draft moved on since.
    mutationFn: () => api.depositNotices.issue(doc.id, { expectedUpdatedAt: doc.updatedAt ?? null }),
    onSuccess: (res) => {
      showToast?.('success', `ออกเอกสาร ${res.depositNotice.docNumber} แล้ว`);
      setPreviewHtml('');
      setConfirmIssue(false);
      invalidateAfterDepositChange();
    },
    onError: (err) => {
      setConfirmIssue(false);
      showToast?.('error', err.message || 'ออกเอกสารไม่สำเร็จ');
    },
  });
  const previewMutation = useMutation({
    mutationFn: () => api.depositNotices.preview(doc.id),
    onSuccess: (html) => setPreviewHtml(html),
    onError: (err) => showToast?.('error', err.message || 'Preview ไม่สำเร็จ'),
  });

  async function download(key, fetchBlob, name, format) {
    setDownloadingFormat(key);
    try {
      downloadBlob(await fetchBlob(), name, format);
    } catch (err) {
      showToast?.('error', err.message || 'ดาวน์โหลดไม่สำเร็จ');
    } finally {
      setDownloadingFormat(null);
    }
  }
  const downloadButton = (key, label, fetchBlob, name, format) => (
    <Button type="button" variant="secondary" disabled={downloadingFormat === key}
      onClick={() => download(key, fetchBlob, name, format)}>
      {downloadingFormat === key ? 'กำลังดาวน์โหลด…' : label}
    </Button>
  );

  // ── Step 1: ใบแจ้งยอดเงินรับมัดจำ ───────────────────────────────────────────────────────────────
  const { bypassed, paid: depositPaid, noticeIssued } = depositStatus(summary);
  const acceptedPr = useMemo(
    () => pricingRequests.filter((pr) => pr.status === 'QUOTATION_ACCEPTED').sort((a, b) => b.id - a.id)[0] ?? null,
    [pricingRequests],
  );
  const orderConfirmed = Boolean(acceptedPr?.orderConfirmedAt) || ps != null;
  const canCreateNotice = acceptedPr != null && canCreateDepositNoticeFromQuotation(
    user, acceptedPr, newestQuotationByRequest(dealQuotations).get(acceptedPr.id) ?? null, depositQuery.data,
  );
  // Legacy dual-track deal (no pricing-request chain): the notice page creates it, as the old card linked.
  const legacyCreate = !doc && !canCreateNotice && isOwnerSales && hasAction('ISSUE_DEPOSIT_NOTICE')
    && summary.status === 'quotation_issued' && ps === 'CUSTOMER_CONFIRMED';

  let dep;
  if (bypassed) dep = 'skipped';
  else if (depositPaid) dep = 'paid';
  else if (doc?.status === 'ISSUED' || noticeIssued) dep = 'issued';
  else if (doc?.status === 'DRAFT') dep = 'draft';
  else if (orderConfirmed) dep = 'create';
  else dep = 'notyet';
  const depositDone = dep === 'skipped' || dep === 'paid';

  const depositLoading = depositQuery.isLoading;
  const depositError = depositQuery.isError;
  const depositReceipt = paymentReceipts
    .filter((r) => r.kind === 'DEPOSIT')
    .sort((a, b) => (toTimestamp(b.receivedAt) ?? -Infinity) - (toTimestamp(a.receivedAt) ?? -Infinity))[0];
  const docMeta = doc ? (
    <>
      {doc.docNumber ? mono(doc.docNumber) : <span>ฉบับร่าง — ยังไม่ออกเลขที่เอกสาร</span>}
      <span>มัดจำ {Math.round(Number(doc.depositPercent ?? 0.5) * 100)}%</span>
      <span className="font-bold tabular-nums text-text-secondary">{formatMoney(doc.totalPayable)}</span>
      {dep === 'paid' && depositReceipt ? <span>รับเงิน {formatThaiDate(depositReceipt.receivedAt)}</span>
        : doc.docDate ? <span>ออกเมื่อ {formatThaiDate(doc.docDate)}</span> : null}
    </>
  ) : null;
  let depositHint = DEP_HINT[dep];
  if (dep === 'create') {
    if (mainButtonCreatesNotice) depositHint = 'ลูกค้ายืนยันคำสั่งซื้อแล้ว — กดปุ่ม "สร้างใบแจ้งยอดเงินรับมัดจำ" ด้านบน';
    else depositHint = isOwnerSales ? 'ลูกค้ายืนยันคำสั่งซื้อแล้ว — สร้างจากใบเสนอราคาที่ยอมรับได้เลย' : 'ลูกค้ายืนยันคำสั่งซื้อแล้ว — รอฝ่ายขายสร้าง';
  }

  // ── Step 2: ใบแจ้งหนี้ส่วนที่เหลือ ──────────────────────────────────────────────────────────────
  const fullyPaid = ps === 'FULLY_PAID';
  const remainingReady = isRemainingInvoiceReady(summary);
  let rem;
  if (invoice?.status === 'ISSUED') rem = fullyPaid ? 'paid' : 'issued';
  else if (invoice?.status === 'DRAFT') rem = 'draft';
  else if (fullyPaid) rem = 'notneeded'; // paid in full by the deposit (custom 100%): no remaining invoice to issue
  else rem = depositDone ? 'create' : 'wait';
  const remDone = invoice?.status === 'ISSUED' || rem === 'notneeded';
  const remainingLoading = remainingQuery.isLoading;
  const remainingError = remainingQuery.isError;
  let remainingHint = null;
  if (rem === 'wait') remainingHint = 'ออกได้หลังฝ่ายบัญชียืนยันรับมัดจำ';
  else if (rem === 'create') {
    if (!remainingReady) remainingHint = 'ออกได้เมื่อสินค้าถึงขั้นรับ/ส่งมอบแล้ว';
    else if (!canManageRemainingInvoice) remainingHint = 'รอฝ่ายขายออกเอกสาร';
  }

  // ── Step 3: รับชำระครบ ────────────────────────────────────────────────────────────────────────
  const outstanding = Number(summary.amountOutstanding ?? 0);
  const finalHint = fullyPaid ? null
    : rem === 'issued' ? 'รอลูกค้าชำระ — ฝ่ายบัญชีบันทึกรับชำระที่หน้าการเงินของฝ่ายบัญชี'
      : 'รอใบแจ้งหนี้ส่วนที่เหลือ';

  const doneFlags = [depositDone, remDone, fullyPaid];
  // Nothing is "next" before the order is confirmed (even if step 1 is skipped), nor while a list is
  // loading or failed (the marker would jump the moment data settles).
  const currentIndex = (!orderConfirmed || depositLoading || remainingLoading || depositError || remainingError) ? -1 : doneFlags.indexOf(false);

  // ── Balance + progress ────────────────────────────────────────────────────────────────────────
  const payable = Number(summary.amountPayable ?? 0);
  const paidAmount = Number(summary.amountPaid ?? 0);
  const percentPaid = payable > 0 ? Math.min(100, Math.max(0, Math.floor((paidAmount / payable) * 100))) : 0;

  // ── Timeline rows: money-track events + receipts, newest first ────────────────────────────────
  const rows = useMemo(() => {
    const eventRows = events
      .filter((event) => PAYMENT_TRACK_KINDS.has(event.kind))
      .map((event) => ({ key: `event-${event.id}`, type: 'event', ts: toTimestamp(event.createdAt), event }));
    const receiptRows = paymentReceipts.map((receipt) => ({
      key: `receipt-${receipt.receiptId}`, type: 'receipt', ts: toTimestamp(receipt.receivedAt), receipt,
    }));
    return [...eventRows, ...receiptRows].sort((a, b) => {
      if (a.ts == null && b.ts == null) return 0;
      if (a.ts == null) return 1;
      if (b.ts == null) return -1;
      if (b.ts !== a.ts) return b.ts - a.ts;
      // Same instant: one fixed secondary key for EVERY pair (the non-transitive same-kind-only
      // shortcut is the bug DealHistoryPanel's "FIX 4" comment documents): events before receipts,
      // then the higher id (written later) first.
      const aRank = a.type === 'event' ? 0 : 1;
      const bRank = b.type === 'event' ? 0 : 1;
      if (aRank !== bRank) return aRank - bRank;
      const aId = a.type === 'event' ? a.event.id : a.receipt.receiptId;
      const bId = b.type === 'event' ? b.event.id : b.receipt.receiptId;
      return bId - aId;
    });
  }, [events, paymentReceipts]);

  const stage = paymentStageLabel(summary.paymentStage);

  return (
    <Panel
      title="การชำระเงิน"
      actions={(
        <div className="flex flex-wrap gap-2">
          <StatusBadge tone={stage.tone}>{stage.label}</StatusBadge>
          {summary.overdue && <StatusBadge tone={overdueBadgeLabel(true).tone}>{overdueBadgeLabel(true).label}</StatusBadge>}
        </div>
      )}
    >
      <div className="flex flex-col gap-4 px-[18px] pb-4">
        <div className="flex flex-col gap-2 rounded-lg border border-border-subtle bg-surface-muted px-3 py-2.5">
          <div className="flex flex-wrap items-end gap-x-6 gap-y-2">
            <div className="flex flex-col">
              <span className="text-2xs text-text-muted">ยอดที่ต้องชำระ</span>
              <strong className="text-sm text-text">{formatMoney(payable)}</strong>
            </div>
            <div className="flex flex-col">
              <span className="text-2xs text-text-muted">ชำระแล้ว</span>
              <strong className="text-sm text-text">{formatMoney(paidAmount)}</strong>
            </div>
            <div className="flex flex-col">
              <span className="text-2xs font-bold text-text-muted">คงเหลือ</span>
              <strong className={`text-lg ${outstanding > 0 ? 'text-danger' : 'text-text'}`}>{formatMoney(outstanding)}</strong>
            </div>
          </div>
          <div
            role="progressbar" aria-label={`ชำระแล้ว ${percentPaid}%`}
            aria-valuemin={0} aria-valuemax={100} aria-valuenow={percentPaid}
            className="h-1.5 overflow-hidden rounded-full bg-surface-subtle"
          >
            <div className="h-full rounded-full bg-success" style={{ width: `${percentPaid}%` }} />
          </div>
          <span className="text-2xs text-text-muted">ชำระแล้ว {percentPaid}%</span>
        </div>

        <div>
          <h3 className="m-0 mb-2 text-sm font-extrabold text-text-secondary">เอกสารและการรับชำระ</h3>
          <div className="flex flex-col gap-2">
            <Step
              no={1} id="deposit" title="ใบแจ้งยอดเงินรับมัดจำ" done={depositDone} muted={dep === 'skipped'}
              current={currentIndex === 0}
              badge={depositLoading ? { label: 'กำลังโหลด…', tone: 'neutral' } : depositError ? ERROR_BADGE : DEP_BADGE[dep]}
              meta={depositLoading || depositError ? null : docMeta} hint={depositLoading || depositError ? null : depositHint}
              extra={previewHtml ? (
                <div className="flex flex-col gap-1 pl-8 mobile:pl-0">
                  <div className="flex items-center justify-between">
                    <span className="text-2xs font-bold text-text-muted">ตัวอย่างเอกสาร</span>
                    <Button type="button" variant="icon" onClick={() => setPreviewHtml('')} aria-label="ปิดตัวอย่าง">×</Button>
                  </div>
                  <iframe srcDoc={previewHtml} title="Deposit notice preview" className="h-64 w-full rounded border border-border-subtle" />
                </div>
              ) : null}
            >
              {depositLoading ? null : depositError ? <ListError onRetry={() => depositQuery.refetch()} /> : (
                <>
                  {dep === 'create' && !mainButtonCreatesNotice && canCreateNotice ? (
                    <Button type="button" variant="primary" disabled={actionLoading} onClick={() => onCreateDepositNotice?.()}>
                      สร้างใบแจ้งยอดเงินรับมัดจำ
                    </Button>
                  ) : null}
                  {dep === 'create' && !mainButtonCreatesNotice && legacyCreate ? (
                    <Link to={`/tickets/${ticketId}/deposit`} className={buttonVariants({ variant: 'primary' })}>
                      สร้างใบแจ้งยอดเงินรับมัดจำ
                    </Link>
                  ) : null}
                  {dep === 'draft' ? (
                    <>
                      <Button type="button" variant="secondary" disabled={previewMutation.isPending}
                        onClick={() => previewMutation.mutate()}>
                        {previewMutation.isPending ? 'กำลังโหลดตัวอย่าง…' : 'ตัวอย่าง'}
                      </Button>
                      {isOwnerSales ? (
                        <>
                          <Link to={`/tickets/${ticketId}/deposit`} className={buttonVariants({ variant: 'secondary' })}>แก้ไขรายการ</Link>
                          <Button type="button" variant="primary" disabled={issueMutation.isPending}
                            onClick={() => setConfirmIssue(true)} data-testid="deal-deposit-issue-notice">
                            ออกเอกสาร
                          </Button>
                        </>
                      ) : null}
                    </>
                  ) : null}
                  {(dep === 'issued' || dep === 'paid') && doc?.status === 'ISSUED' ? (
                    <>
                      {downloadButton('dep-pdf', 'PDF', () => api.depositNotices.downloadPdf(doc.id), doc.docNumber ?? 'deposit-notice', 'pdf')}
                      {downloadButton('dep-xlsx', 'Excel', () => api.depositNotices.downloadXlsx(doc.id), doc.docNumber ?? 'deposit-notice', 'xlsx')}
                    </>
                  ) : null}
                </>
              )}
            </Step>
            <Step
              no={2} id="remaining" title="ใบแจ้งหนี้ส่วนที่เหลือ" done={remDone} current={currentIndex === 1}
              badge={remainingLoading ? { label: 'กำลังโหลด…', tone: 'neutral' } : remainingError ? ERROR_BADGE : REM_BADGE[rem]}
              meta={invoice && !remainingLoading && !remainingError ? (
                <>
                  {invoice.docNumber ? mono(invoice.docNumber) : <span>ฉบับร่าง — ยังไม่ออกเลขที่เอกสาร</span>}
                  {invoice.docDate ? <span>{invoice.status === 'ISSUED' ? 'ออกเมื่อ' : 'วันที่'} {formatThaiDate(invoice.docDate)}</span> : null}
                </>
              ) : null}
              hint={remainingLoading || remainingError ? null : remainingHint}
            >
              {remainingLoading ? null : remainingError ? <ListError onRetry={() => remainingQuery.refetch()} /> : (
                <>
                  {rem === 'create' && canManageRemainingInvoice && remainingReady ? (
                    <Button type="button" variant="primary" onClick={() => onManageRemainingInvoice?.()}>สร้างใบแจ้งหนี้ส่วนที่เหลือ</Button>
                  ) : null}
                  {invoice?.status === 'ISSUED' ? downloadButton(
                    'rem-pdf', 'PDF', () => api.storedRemainingInvoices.download(invoice.id), invoice.docNumber, 'pdf',
                  ) : null}
                  {invoice && canManageRemainingInvoice ? (
                    <Button type="button" variant="secondary" onClick={() => onManageRemainingInvoice?.()}>จัดการเอกสาร</Button>
                  ) : null}
                </>
              )}
            </Step>

            <Step
              no={3} id="final" title="รับชำระครบ" done={fullyPaid} current={currentIndex === 2}
              badge={fullyPaid ? { label: 'ชำระครบแล้ว', tone: 'success' } : { label: `คงเหลือ ${formatMoney(outstanding)}`, tone: 'neutral' }}
              hint={finalHint}
            />
          </div>
        </div>

        {canRecordPayment ? (
          <div className="flex flex-wrap gap-2">
            <Button type="button" variant="primary" disabled={actionLoading} onClick={onRecordPayment}>บันทึกรับชำระเงิน</Button>
          </div>
        ) : null}

        <div className="border-t border-border pt-3">
          <h3 className="m-0 mb-2.5 text-sm font-bold">ไทม์ไลน์การชำระเงิน</h3>
          {paymentsLoading ? (
            <SkeletonText lines={2} />
          ) : rows.length === 0 ? (
            <EmptyState icon="clock" title="ยังไม่มีความเคลื่อนไหว" description="เหตุการณ์และการรับชำระของดีลนี้จะปรากฏที่นี่" />
          ) : (
            <ul className="m-0 flex list-none flex-col gap-2.5 p-0">
              {rows.map((row) => (row.type === 'event'
                ? <MoneyEventRow key={row.key} event={row.event} />
                : <MoneyReceiptRow key={row.key} receipt={row.receipt} />))}
            </ul>
          )}
        </div>
      </div>

      {doc ? (
        <ConfirmDialog
          open={confirmIssue}
          title="ยืนยันการออกเอกสาร"
          message={(
            <DepositIssueSummary customerName={doc.customerName} depositPercent={doc.depositPercent}
              deposit={doc.depositAmount} vatPercent={doc.vatPercent} vat={doc.vatAmount} total={doc.totalPayable} />
          )}
          confirmLabel="ออกเอกสาร"
          busy={issueMutation.isPending}
          onCancel={() => setConfirmIssue(false)}
          onConfirm={() => issueMutation.mutate()}
        />
      ) : null}
    </Panel>
  );
}
