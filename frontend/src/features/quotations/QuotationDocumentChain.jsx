// "เอกสารต่อจากใบเสนอราคานี้" — the document chain card on an APPROVED direct quotation (owner-approved design
// 2026-11-17): ใบเสนอราคา → ยืนยันคำสั่งซื้อ → ใบแจ้งยอดเงินรับมัดจำ → ใบแจ้งหนี้ส่วนที่เหลือ. Every state is the server's
// (GET /tickets/{id}/document-chain, the same summary the deal page's เงิน tab reads); quotationChain.js turns it into
// steps. Every action goes through an existing endpoint behind an existing confirm: the page's ยืนยันคำสั่งซื้อ dialog (and its undo, ยกเลิกการยืนยันคำสั่งซื้อ, while nothing downstream exists),
// the deposit notice's issue dialog below (the deal page's own DepositIssueSummary body), RemainingInvoiceDialog.
import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { api } from '../../api/index.js';
import { Button } from '../../components/common/Button.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { Modal } from '../../components/common/Modal.jsx';
import { SkeletonText } from '../../components/common/Skeleton.jsx';
import { cn } from '../../utils/cn.js';
import { downloadBlob } from '../../utils/download.js';
import {
  createDepositNoticeDraft, dealQuotationsWithConfirmedQueryOptions, depositNoticesQueryOptions, pickDepositNotice,
  resolveNoticeQuotation,
} from '../deposits/createDepositNoticeDraft.js';
import { DepositIssueSummary } from '../deposits/DepositNoticePage.jsx';
import { invalidateDealDocuments } from '../documentChain/documentChain.js';
import { chainNextStep } from '../documentChain/documentChainText.js';
import { RemainingInvoiceDialog } from '../tickets/RemainingInvoiceDialog.jsx';
import { CONFIRM_ORDER_STEP_KEY, quotationChainSteps } from './quotationChain.js';

/** The step marker — DealStepper's visual language: check (done), filled ring (the viewer's turn), outlined number. */
function Marker({ state, n }) {
  return (
    <span
      aria-hidden="true"
      className={cn(
        'relative z-[1] grid h-6 w-6 shrink-0 place-items-center rounded-full text-2xs font-extrabold mobile:h-5 mobile:w-5',
        state === 'done' && 'bg-success text-surface',
        state === 'current' && 'bg-primary text-surface ring-4 ring-primary-bg',
        state === 'waiting' && 'border border-info-border bg-info-bg text-info',
        state === 'todo' && 'border border-border-strong bg-surface text-text-muted',
        state === 'skipped' && 'border border-dashed border-border-muted bg-surface-subtle text-text-faint',
      )}
    >
      {state === 'done' ? <Icon name="check" size={12} />
        : state === 'waiting' ? <Icon name="clock" size={12} />
          : state === 'skipped' ? '–' : n}
    </span>
  );
}

const mono = 'font-mono text-xs font-bold text-text';

/**
 * One step. Desktop: a column (marker over title, number, meta, the action). Phone: a row — finished and skipped steps
 * fold to ONE line (marker, title · number, a 44px download), the viewer's own step is its full-width button, a
 * waiting step is its title and who it waits on, a future step its title alone (the lock reason is desktop-only, to
 * keep the card near 160px above the document at 375px).
 */
function Step({ index, step, last, railDone, onAction, actionBusy, download, onUndo }) {
  const folded = step.state === 'done' || step.state === 'skipped';
  const muted = step.state === 'todo' || step.state === 'skipped';
  return (
    <li
      data-step={index + 1}
      data-step-key={step.key}
      data-state={step.state}
      aria-current={step.state === 'current' ? 'step' : undefined}
      className="relative grid min-w-0 grid-cols-1 content-start gap-y-1.5 pr-5 mobile:grid-cols-[1.25rem_minmax(0,1fr)_auto] mobile:items-center mobile:gap-x-2.5 mobile:gap-y-0 mobile:pr-0"
    >
      {!last ? (
        <>
          <span aria-hidden="true" className={cn('absolute left-8 right-2 top-3 h-0.5 mobile:hidden', railDone ? 'bg-success' : 'bg-border')} />
          <span aria-hidden="true" className={cn('absolute -bottom-1.5 left-[9px] top-1/2 hidden w-0.5 mobile:block', railDone ? 'bg-success' : 'bg-border')} />
        </>
      ) : null}
      <Marker state={step.state} n={index + 1} />
      <div className={cn('flex min-w-0 flex-col items-start gap-1 mobile:flex-row mobile:flex-wrap mobile:items-baseline mobile:gap-x-1.5 mobile:gap-y-0', muted && 'opacity-80', step.state !== 'current' && 'mobile:py-0.5')}>
        <strong
          className={cn(
            'text-sm leading-snug',
            step.state === 'current' ? 'font-extrabold text-text mobile:hidden' : muted ? 'font-bold text-text-secondary' : 'font-extrabold text-text',
          )}
        >
          {step.title}
        </strong>
        {step.detail ? (
          <span className={cn(mono, step.state === 'skipped' && 'font-sans font-normal text-text-muted')}>
            <span aria-hidden="true" className="hidden text-text-muted mobile:inline">· </span>{step.detail}
          </span>
        ) : null}
        {step.meta ? <span className={cn('text-xs leading-snug text-text-muted', folded && step.detail ? 'mobile:hidden' : null, step.state === 'current' && 'mobile:hidden')}>{step.meta}</span> : null}
        {step.waitingOn ? (
          <span className="inline-flex items-center gap-1 text-xs font-bold leading-snug text-info">
            <Icon name="clock" size={12} className="shrink-0" />
            รอ{step.waitingOn}
          </span>
        ) : null}
        {step.locked ? (
          <span className={cn('inline-flex items-start gap-1 text-xs leading-snug text-text-muted', (step.waitingOn || step.state === 'todo') && 'mobile:hidden')}>
            <Icon name="lock" size={12} className="mt-0.5 shrink-0" />
            <span>{step.locked}</span>
          </span>
        ) : null}
        {step.action ? (
          <Button
            type="button" variant="primary" className="mt-1 mobile:mt-0 mobile:w-full" loading={actionBusy}
            onClick={onAction} data-chain-primary="true" data-testid={`quotation-chain-${step.action}`}
          >
            {step.actionLabel}
          </Button>
        ) : null}
        {onUndo ? (
          <Button
            type="button" variant="text" onClick={onUndo} data-testid="quotation-chain-undo-confirm"
            className="mt-1 text-xs font-bold mobile:mt-0"
          >
            ยกเลิกการยืนยันคำสั่งซื้อ
          </Button>
        ) : null}
        {download ? (
          <Button
            type="button" variant="secondary" size="sm" disabled={download.busy} onClick={download.onClick}
            aria-label={download.label} title={download.label}
            className="mt-1 gap-1.5 mobile:hidden"
          >
            <Icon name="download" size={14} />
            {download.busy ? 'กำลังดาวน์โหลด…' : 'ดาวน์โหลด PDF'}
          </Button>
        ) : null}
      </div>
      {download ? (
        <Button
          type="button" variant="icon" disabled={download.busy} onClick={download.onClick}
          aria-label={download.label} title={download.label} className="hidden shrink-0 mobile:inline-flex"
        >
          <Icon name="download" size={16} />
        </Button>
      ) : <span className="hidden mobile:block" aria-hidden="true" />}
    </li>
  );
}

/** The one-line situation for a quotation that does not carry the chain (quotationChainView's `notice`). */
export function QuotationChainNotice({ view }) {
  return (
    <p
      data-testid="quotation-chain-notice"
      data-reason={view.reason}
      className="m-0 flex flex-wrap items-center gap-x-3 gap-y-1 rounded-md border border-border bg-surface px-4 py-2.5 text-sm text-text-secondary"
    >
      <Icon name="info" size={14} className="shrink-0 text-text-muted" />
      <span className="min-w-0 flex-1">{view.text}</span>
      {view.link ? (
        <Link
          to={view.link.to}
          className="inline-flex min-h-[38px] items-center gap-1 font-bold text-primary no-underline hover:text-primary-hover mobile:min-h-[44px]"
        >
          {view.link.label}
          <Icon name="chevronRight" size={14} />
        </Link>
      ) : null}
    </p>
  );
}

export function QuotationDocumentChain({ quotation, chain, onConfirmOrder, onUndoConfirm, confirmBusy = false, showToast }) {
  const queryClient = useQueryClient();
  const ticketId = quotation.ticketId;
  const [noticeDialogOpen, setNoticeDialogOpen] = useState(false);
  const [remainingOpen, setRemainingOpen] = useState(false);
  const [downloading, setDownloading] = useState(null);

  // สร้าง/ออกใบแจ้งยอดเงินรับมัดจำ: the deal page's own path — the list is read fresh, an existing DRAFT is opened
  // (never doubled), otherwise the server makes the draft. Then the dialog shows THAT draft.
  const openNotice = useMutation({
    mutationFn: async () => {
      const docs = await queryClient.fetchQuery({ ...depositNoticesQueryOptions(ticketId), staleTime: 0 });
      return pickDepositNotice(docs)?.status === 'DRAFT' ? null : createDepositNoticeDraft(ticketId);
    },
    onSuccess: async (created) => {
      if (created) await invalidateDealDocuments(queryClient, ticketId);
      setNoticeDialogOpen(true);
    },
    onError: (err) => showToast?.('error', err.message || 'สร้างเอกสารฉบับร่างไม่สำเร็จ'),
  });

  if (!chain) {
    return (
      <section data-testid="quotation-document-chain" className="rounded-md border border-border bg-surface p-5 mobile:px-3.5 mobile:py-2.5">
        <SkeletonText lines={2} />
      </section>
    );
  }

  const steps = quotationChainSteps(quotation, chain);
  const next = chainNextStep(chain);
  const hasAction = steps.some((s) => s.action);
  const deposit = chain.deposit ?? {};
  const remaining = chain.remaining ?? {};
  // Server's say-so (order.undoable: nothing downstream blocks), the viewer's right to confirm, and THIS quotation being the one confirmed.
  const canUndo = Boolean(onUndoConfirm && chain.order?.undoable && chain.viewer?.mayConfirmOrder
    && chain.order.confirmedFromQuotationId != null && Number(chain.order.confirmedFromQuotationId) === Number(quotation.id));

  async function download(key, fetchBlob, name) {
    setDownloading(key);
    try {
      downloadBlob(await fetchBlob(), name, 'pdf');
    } catch (err) {
      showToast?.('error', err.message || 'ดาวน์โหลดไม่สำเร็จ');
    } finally {
      setDownloading(null);
    }
  }
  const downloads = {
    notice: deposit.issuedNoticeId != null ? {
      label: 'ดาวน์โหลด PDF ใบแจ้งยอดเงินรับมัดจำ', busy: downloading === 'notice',
      onClick: () => download('notice', () => api.depositNotices.downloadPdf(deposit.issuedNoticeId), deposit.docNumber ?? 'deposit-notice'),
    } : null,
    invoice: remaining.invoiceId != null ? {
      label: 'ดาวน์โหลด PDF ใบแจ้งหนี้ส่วนที่เหลือ', busy: downloading === 'invoice',
      onClick: () => download('invoice', () => api.storedRemainingInvoices.download(remaining.invoiceId), remaining.docNumber ?? 'remaining-invoice'),
    } : null,
  };
  const actions = {
    confirm: { onClick: onConfirmOrder, busy: confirmBusy },
    deposit: { onClick: () => openNotice.mutate(), busy: openNotice.isPending },
    remaining: { onClick: () => setRemainingOpen(true), busy: false },
  };

  return (
    <section
      data-testid="quotation-document-chain"
      aria-labelledby="quotation-chain-title"
      className="rounded-md border border-border bg-surface p-5 mobile:px-3.5 mobile:py-2.5"
    >
      <div className="flex flex-wrap items-baseline justify-between gap-x-4 gap-y-0.5">
        <h2 id="quotation-chain-title" className="m-0 text-lg mobile:text-sm mobile:font-extrabold">เอกสารต่อจากใบเสนอราคานี้</h2>
        {!hasAction && next.line ? (
          <p data-testid="quotation-chain-next" className="m-0 text-xs text-text-muted">{next.line}</p>
        ) : null}
      </div>
      <ol
        aria-label="เอกสารต่อจากใบเสนอราคานี้"
        className="m-0 mt-3.5 grid list-none grid-cols-4 p-0 mobile:mt-2 mobile:grid-cols-1 mobile:gap-y-1"
      >
        {steps.map((step, index) => {
          const action = step.action ? actions[step.action] : null;
          return (
            <Step
              key={step.title}
              index={index}
              step={step}
              last={index === steps.length - 1}
              railDone={step.state === 'done' || step.state === 'skipped'}
              onAction={action?.onClick}
              actionBusy={action?.busy ?? false}
              download={step.download ? downloads[step.download] : null}
              onUndo={canUndo && step.key === CONFIRM_ORDER_STEP_KEY ? onUndoConfirm : null}
            />
          );
        })}
      </ol>

      {noticeDialogOpen ? (
        <DepositNoticeIssueDialog
          ticketId={ticketId}
          quotationNumber={quotation.number}
          onClose={() => setNoticeDialogOpen(false)}
          onIssued={(docNumber) => {
            setNoticeDialogOpen(false);
            showToast?.('success', `ออกเอกสาร ${docNumber} แล้ว`);
          }}
          showToast={showToast}
        />
      ) : null}
      {remainingOpen ? (
        <RemainingInvoiceDialog
          ticketId={ticketId}
          canWrite={Boolean(chain.viewer?.mayWriteRemainingInvoice)}
          onClose={() => {
            setRemainingOpen(false);
            invalidateDealDocuments(queryClient, ticketId);
          }}
        />
      ) : null}
    </section>
  );
}

/**
 * The deposit notice's issue dialog on the quotation page: the saved DRAFT's own figures (DepositIssueSummary — the
 * deal page's issue confirm body, its "cannot be edited after issuing" warning first), แก้รายละเอียด to the full notice
 * page, and ออกเอกสาร — the same request the deal page sends (the draft's updatedAt stamp). Cancelling keeps the draft.
 */
function DepositNoticeIssueDialog({ ticketId, quotationNumber, onClose, onIssued, showToast }) {
  const queryClient = useQueryClient();
  const listQuery = useQuery({ ...depositNoticesQueryOptions(ticketId), staleTime: 0 });
  const draft = (listQuery.data ?? []).find((d) => d.status === 'DRAFT') ?? null;
  // The deal's quotation list (cache-shared with the document register): the dialog names the quotation when the
  // draft's subtotal is not its quotation's. A failed read (no view access) just shows no line.
  const quotationsQuery = useQuery(dealQuotationsWithConfirmedQueryOptions(ticketId));
  const issue = useMutation({
    mutationFn: () => api.depositNotices.issue(draft.id, { expectedUpdatedAt: draft.updatedAt ?? null }),
    onSuccess: (res) => {
      invalidateDealDocuments(queryClient, ticketId);
      onIssued(res.depositNotice.docNumber);
    },
    onError: (err) => {
      invalidateDealDocuments(queryClient, ticketId);
      showToast?.('error', err.message || 'ออกเอกสารไม่สำเร็จ');
    },
  });

  return (
    <Modal
      title="ออกใบแจ้งยอดเงินรับมัดจำ"
      subtitle={`ฉบับร่างจากใบเสนอราคา ${quotationNumber}`}
      onClose={onClose}
      testId="quotation-chain-deposit-dialog"
      placement="sheet-on-phone"
      footer={(
        <div className="flex w-full flex-wrap items-center justify-between gap-x-4 gap-y-2">
          <Link
            to={`/tickets/${ticketId}/deposit`}
            className="inline-flex min-h-[38px] items-center gap-1.5 text-sm font-bold text-primary no-underline hover:text-primary-hover mobile:min-h-[44px]"
          >
            <Icon name="pencil" size={14} />
            แก้รายละเอียด
          </Link>
          <div className="ml-auto flex gap-2">
            <Button type="button" variant="secondary" onClick={onClose} disabled={issue.isPending}>ยกเลิก</Button>
            <Button type="button" variant="primary" loading={issue.isPending} disabled={!draft} onClick={() => issue.mutate()}>
              ออกเอกสาร
            </Button>
          </div>
        </div>
      )}
    >
      {listQuery.isLoading ? (
        <SkeletonText lines={4} />
      ) : draft ? (
        <DepositIssueSummary
          customerName={draft.customerName} depositPercent={draft.depositPercent} subtotal={draft.subtotal}
          reference={draft.reference} deposit={draft.depositAmount} vatPercent={draft.vatPercent}
          vat={draft.vatAmount} total={draft.totalPayable}
          quotation={resolveNoticeQuotation(quotationsQuery.data, draft.reference)}
        />
      ) : (
        <p className="m-0 text-sm text-danger">ไม่พบฉบับร่าง — ลองใหม่อีกครั้ง</p>
      )}
    </Modal>
  );
}
