/* Hallmark · genre: modern-minimal · macrostructure: Feature Stack · tone: utilitarian · theme: project system (Sarabun + indigo, owner-locked) · redesign */
/* Hallmark · pre-emit critique: P4 H4 E4 S4 R4 V4 (rendered at 375/1280 and scroll-width checked at 320/375/414/768; Feature Stack's sticky pane collapses to one column at <=1040px with money first) */
import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useParams } from 'react-router-dom';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { Button } from '../../components/common/Button.jsx';
import { ConfirmDialog } from '../../components/common/ConfirmDialog.jsx';
import { FormField } from '../../components/common/FormField.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { OverflowMenu } from '../../components/common/OverflowMenu.jsx';
import { cn } from '../../utils/cn.js';
import { dealStageLabel, formatBangkokTime, formatThaiDate } from '../../utils/format.js';
import { InvoiceFromDealForm } from '../commissions/InvoiceFromDealForm.jsx';
import { emptyInvoiceForm, prepareInvoiceAttachment, toCreateFromDealPayload } from '../commissions/invoiceFromDeal.js';
import { findStage, useStageCatalog } from '../tickets/stageCatalog.js';
import {
  RecordPaymentModal, RevokeCloseModal, StageModal,
} from './FinanceActionModals.jsx';
import { MilestoneSections, money, vatCaption } from './FinanceMilestoneSections.jsx';
import { orderActions, pickPrimaryAction } from './financeDealActions.js';

const ACTION_LABEL = {
  DEPOSIT_PAID: 'ยืนยันรับมัดจำ',
  FINAL_PAYMENT: 'รับชำระส่วนที่เหลือ',
  RECORD_PAYMENT: 'บันทึกรับชำระ',
  CONFIRM_CLOSE: 'ยืนยันพร้อมปิดงาน',
  RECORD_INVOICE: 'บันทึกใบกำกับ',
  UPDATE_STAGE: 'แก้ไขสถานะ',
  REVOKE_CLOSE_CONFIRM: 'ยกเลิกการยืนยันปิดงาน',
};

// The stages account may write (DealStage.ACCOUNT_TARGET_STAGES) — only used to label the choices in
// the UPDATE_STAGE form; the server decides whether the move is accepted.
const MONEY_STAGE_OPTIONS = ['DEPOSIT_RECEIVED', 'CLOSED_PAID'];

function Bone({ className }) {
  return <span aria-hidden="true" className={cn('block rounded-md bg-surface-muted motion-safe:animate-pulse', className)} />;
}

function PageSkeleton() {
  return (
    <div
      className="grid w-full min-w-0 max-w-[1200px] items-start gap-x-6 gap-y-4 grid-cols-[minmax(17rem,21rem)_minmax(0,1fr)] nav-drawer:grid-cols-[minmax(0,1fr)]"
      aria-busy="true" aria-label="กำลังโหลดดีล"
    >
      <div className="grid gap-4 rounded-md border border-border bg-surface p-5 mobile:p-4">
        <Bone className="h-4 w-24" />
        <Bone className="h-6 w-2/3" />
        <Bone className="h-4 w-1/2" />
        <Bone className="mt-2 h-10 w-full" />
        <Bone className="h-4 w-full" />
        <Bone className="h-4 w-5/6" />
      </div>
      <div className="grid min-w-0 gap-px rounded-md border border-border bg-border">
        {[0, 1, 2, 3, 4].map((i) => (
          <div key={i} className="grid gap-3 bg-surface p-5 mobile:p-4">
            <div className="flex items-center gap-3">
              <Bone className="size-6 !rounded-pill" />
              <Bone className="h-5 w-1/3" />
            </div>
            {i < 2 ? <><Bone className="h-4 w-full" /><Bone className="h-4 w-5/6" /></> : null}
          </div>
        ))}
      </div>
    </div>
  );
}

function BackLink({ children = 'งานการเงิน' }) {
  return (
    <Link to="/finance" className="inline-flex min-h-11 items-center gap-1 self-start whitespace-nowrap text-sm font-bold text-link hover:underline">
      <Icon name="chevronLeft" size={16} />
      {children}
    </Link>
  );
}

function BlockedState({ title, description, icon }) {
  return (
    <div className="grid max-w-[520px] gap-3 py-8">
      <Icon name={icon} size={30} className="text-text-muted" />
      <h1 className="m-0 text-xl font-extrabold text-text">{title}</h1>
      <p className="m-0 text-sm text-text-muted">{description}</p>
      <BackLink>กลับไปงานการเงิน</BackLink>
    </div>
  );
}

function MoneyBand({ deal }) {
  const m = deal.money;
  const caption = vatCaption(m.amountVatBasis);
  // Derived from the payable quotation's terms + the delivery date (server-side); nothing is invented here.
  const basisText = m.paymentDueBasis === 'CREDIT_FROM_DELIVERY'
    ? `เครดิต ${m.paymentDueCreditDays} วันนับจากวันส่งมอบ`
    : m.paymentDueBasis === 'ON_DELIVERY' ? 'ชำระเมื่อส่งมอบ' : null;
  let dueText = null;
  if (m.paymentDueDate) dueText = `ครบกำหนด ${formatThaiDate(m.paymentDueDate)}${basisText ? ` · ${basisText}` : ''}`;
  else if (basisText) dueText = 'ยังไม่ถึงกำหนด — รอส่งมอบ';
  const row = 'flex min-w-0 items-baseline justify-between gap-3';
  return (
    <div role="group" aria-label="สรุปยอดเงิน" className="grid gap-4 border-t border-border pt-4">
      <div className="grid min-w-0 gap-1">
        <span className="text-sm font-bold text-text">คงค้าง</span>
        <span className={cn('text-4xl font-extrabold leading-tight tabular-nums [overflow-wrap:anywhere]', m.overdue ? 'text-danger' : 'text-text')}>
          {money(m.amountOutstanding)}
        </span>
      </div>
      <div className="grid gap-1.5">
        <div className={row}>
          <span className="text-sm font-bold text-text-secondary">ยอดที่ต้องชำระ</span>
          <span className="text-right text-md font-bold tabular-nums text-text [overflow-wrap:anywhere]">{money(m.amountPayable)}</span>
        </div>
        <div className={row}>
          <span className="text-sm font-bold text-text-secondary">รับแล้ว</span>
          <span className="text-right text-md font-bold tabular-nums text-text [overflow-wrap:anywhere]">{money(m.amountPaid)}</span>
        </div>
      </div>
      {caption || dueText || m.overdue || m.closeConfirmedAt ? (
        <p className="m-0 grid gap-1 rounded-md bg-surface-muted px-3 py-2.5 text-sm text-text-muted">
          {caption ? <span>{caption}</span> : null}
          {dueText ? <span className="[overflow-wrap:anywhere]">{dueText}</span> : null}
          {m.overdue ? (
            <span className="inline-flex items-center gap-1 font-bold text-danger">
              <Icon name="triangleAlert" size={14} />
              <span>เกินกำหนดชำระ</span>
            </span>
          ) : null}
          {m.closeConfirmedAt ? (
            <span className="inline-flex items-center gap-1 font-bold text-success">
              <Icon name="check" size={14} />
              <span>{`ยืนยันพร้อมปิดงานแล้ว ${formatThaiDate(m.closeConfirmedAt)}`}</span>
            </span>
          ) : null}
        </p>
      ) : null}
    </div>
  );
}

function CommentsSection({ deal, onPosted }) {
  const [text, setText] = useState('');
  const [error, setError] = useState('');
  const mutation = useMutation({
    mutationFn: (message) => api.finance.addComment(deal.id, { message }),
    onSuccess: (response) => {
      setText('');
      setError('');
      onPosted(response.deal);
    },
    onError: (err) => setError(err.message || 'ส่งบันทึกไม่สำเร็จ'),
  });
  const empty = text.trim().length === 0;
  return (
    <section aria-labelledby="fin-comments-title" className="grid gap-3 rounded-md border border-border bg-surface p-5 mobile:p-4">
      <h2 id="fin-comments-title" className="m-0 text-lg font-extrabold text-text">บันทึกถึงฝ่ายขาย</h2>
      {deal.comments.length ? (
        <ul className="m-0 grid list-none divide-y divide-border p-0">
          {deal.comments.map((c) => (
            <li key={c.id} className="grid gap-0.5 py-2.5">
              <span className="flex flex-wrap items-baseline gap-x-2 text-sm text-text-muted">
                <strong className="font-bold text-text">{c.authorName ?? '—'}</strong>
                <span className="tabular-nums">{`${formatThaiDate(c.createdAt)} ${formatBangkokTime(c.createdAt)}`}</span>
              </span>
              <span className="whitespace-pre-wrap text-sm text-text [overflow-wrap:anywhere]">{c.message}</span>
            </li>
          ))}
        </ul>
      ) : <p className="m-0 text-sm text-text-muted">ยังไม่มีบันทึก</p>}
      <div className="grid max-w-[640px] gap-2.5">
        <FormField
          label="ข้อความถึงฝ่ายขาย" htmlFor="fin-comment-text" error={error}
          hint={empty ? 'พิมพ์ข้อความก่อนจึงจะส่งบันทึกได้' : undefined}
        >
          <textarea id="fin-comment-text" rows={3} value={text} onChange={(e) => { setText(e.target.value); setError(''); }} />
        </FormField>
        <Button
          type="button" className="justify-self-start whitespace-nowrap pointer-coarse:min-h-11" disabled={empty} loading={mutation.isPending}
          onClick={() => { if (!empty && !mutation.isPending) mutation.mutate(text.trim()); }}
        >
          ส่งบันทึก
        </Button>
      </div>
    </section>
  );
}

/**
 * /finance/deals/:id — one deal as finance sees it. Everything on the page comes from the
 * finance-only read model (GET /api/finance/deals/:id, FinanceDealDto): its `milestoneTrack` is the
 * page's structure, its `availableActions` are the only money actions offered, and every money
 * write returns the refreshed `{ deal }`, which replaces the cached view. Presentation only — the
 * server enforces every gate.
 */
export function FinanceDealPage() {
  const { id } = useParams();
  const queryClient = useQueryClient();
  const { catalog } = useStageCatalog();
  const key = queryKeys.financeDeal(id);
  const [flow, setFlow] = useState(null);
  const [flowError, setFlowError] = useState('');
  const [downloadError, setDownloadError] = useState('');
  const [invoiceForm, setInvoiceForm] = useState(null); // RECORD_INVOICE form state while it is open
  const [invoiceFileKey, setInvoiceFileKey] = useState(0);

  const dealQuery = useQuery({
    queryKey: key,
    queryFn: () => api.finance.getDeal(id),
    retry: false,
  });

  function applyDeal(deal) {
    queryClient.setQueryData(key, { deal });
    // The worklist, the ticket lists and the dashboard counts all derive from money state.
    queryClient.invalidateQueries({ queryKey: ['tickets'] });
    queryClient.invalidateQueries({ queryKey: queryKeys.dashboardSummary() });
    queryClient.invalidateQueries({ queryKey: queryKeys.notifications() });
  }

  const actionMutation = useMutation({
    mutationFn: ({ call }) => call(),
    onSuccess: (response) => {
      applyDeal(response.deal);
      setFlow(null);
      setFlowError('');
    },
    onError: (err) => setFlowError(err.message || 'ดำเนินการไม่สำเร็จ'),
  });
  // RECORD_INVOICE: the existing POST /api/commissions/from-deal (account only), then the deal is re-read so
  // milestone 5 shows the recorded invoice and the action disappears. Not a finance endpoint: no new write API.
  const invoiceMutation = useMutation({
    mutationFn: async ({ ticketId, form }) => {
      const invoiceAttachment = await prepareInvoiceAttachment(form.invoiceAttachment);
      return api.commissions.createFromDeal(toCreateFromDealPayload(ticketId, form, invoiceAttachment));
    },
    onSuccess: async () => {
      setFlow(null);
      setInvoiceForm(null);
      setFlowError('');
      queryClient.invalidateQueries({ queryKey: ['tickets'] });
      queryClient.invalidateQueries({ queryKey: queryKeys.dashboardSummary() });
      queryClient.invalidateQueries({ queryKey: ['payroll'] });
      await queryClient.invalidateQueries({ queryKey: key });
    },
    onError: (err) => setFlowError(err.message || 'บันทึกใบกำกับไม่สำเร็จ'),
  });
  const busy = actionMutation.isPending;

  if (dealQuery.isLoading) return <PageSkeleton />;

  if (dealQuery.error) {
    const status = dealQuery.error.status;
    if (status === 403) {
      return <BlockedState icon="lock" title="ไม่มีสิทธิ์เข้าถึงดีลนี้" description="ดีลนี้อยู่นอกขอบเขตงานการเงินของคุณ" />;
    }
    if (status === 404) {
      return <BlockedState icon="search" title="ไม่พบดีลนี้" description="ดีลอาจถูกลบไป หรือเลขที่ในลิงก์ไม่ถูกต้อง" />;
    }
    return (
      <div className="grid max-w-[520px] gap-3 py-8">
        <p role="alert" className="m-0 flex items-start gap-2 text-sm font-bold text-danger">
          <Icon name="triangleAlert" size={16} className="mt-0.5 shrink-0" />
          <span>{dealQuery.error.message || 'โหลดดีลไม่สำเร็จ'}</span>
        </p>
        <Button type="button" variant="secondary" className="justify-self-start whitespace-nowrap" onClick={() => dealQuery.refetch()}>ลองอีกครั้ง</Button>
        <BackLink />
      </div>
    );
  }

  const deal = dealQuery.data.deal;
  const stage = findStage(catalog, deal.salesStage);
  const stageText = dealStageLabel(deal.salesStage).label;
  const stageCode = stage?.businessCode ?? null;

  // Billing (ตั้งค่าการวางบิล) is out of scope for the finance page: never offered, whatever the server lists.
  const offered = (deal.availableActions ?? []).filter((a) => a.action !== 'SET_BILLING');
  const actions = orderActions(offered);
  const primary = pickPrimaryAction(offered);

  function actionLabel(a) {
    if (a.action === 'ADVANCE_STAGE') {
      const target = findStage(catalog, a.targetStage)?.businessCode ?? dealStageLabel(a.targetStage).label;
      return `เลื่อนไปขั้น ${target}`;
    }
    return ACTION_LABEL[a.action] ?? a.label;
  }

  function startAction(a) {
    setFlowError('');
    if (a.action === 'RECORD_INVOICE') {
      // ex-VAT default (the invoice is raised before VAT); fall back to the payable only for an older payload.
      const gross = deal.money.amountPayableExVat ?? deal.money.amountPayable;
      setInvoiceForm(emptyInvoiceForm(gross != null ? String(gross) : ''));
      setInvoiceFileKey((k) => k + 1);
    }
    setFlow({ action: a });
  }

  const overflowItems = actions
    .filter((a) => a !== primary)
    .map((a) => ({ key: a.action + (a.targetStage ?? ''), label: actionLabel(a), onSelect: () => startAction(a) }));

  function run(call) {
    if (busy) return;
    actionMutation.mutate({ call });
  }

  // ConfirmDialog-driven actions: every one is a financial write that cannot be undone, so each
  // asks first (the sanctioned exception to undo-over-confirm).
  function confirmConfig(a) {
    switch (a.action) {
      case 'DEPOSIT_PAID':
        return {
          title: 'ยืนยันรับมัดจำ', confirmLabel: 'ยืนยันรับมัดจำ',
          text: `ยืนยันว่าได้รับเงินมัดจำของดีล ${deal.code} แล้ว? รายการรับเงินจะถูกบันทึกและย้อนกลับไม่ได้`,
          call: () => api.finance.confirmDepositPaid(deal.id),
        };
      case 'FINAL_PAYMENT':
        return {
          title: 'ยืนยันรับชำระส่วนที่เหลือ', confirmLabel: 'ยืนยันรับชำระส่วนที่เหลือ',
          text: `ยืนยันว่าได้รับเงินส่วนที่เหลือของดีล ${deal.code} ครบแล้ว? ยอดคงค้างปัจจุบัน ${money(deal.money.amountOutstanding)}`,
          call: () => api.finance.confirmFinalPayment(deal.id),
        };
      case 'CONFIRM_CLOSE':
        return {
          title: 'ยืนยันพร้อมปิดงาน', confirmLabel: 'ยืนยันพร้อมปิดงาน',
          text: `ยืนยันว่าดีล ${deal.code} ชำระครบและส่งมอบครบแล้ว? หลังยืนยัน ระบบจะรอการตรวจสอบปิดงาน`,
          call: () => api.finance.confirmCloseReady(deal.id),
        };
      case 'ADVANCE_STAGE':
        return {
          title: 'เลื่อนสถานะดีล', confirmLabel: actionLabel(a),
          text: `เลื่อนดีล ${deal.code} ไปขั้น ${dealStageLabel(a.targetStage).label}`,
          call: () => api.finance.updateStage(deal.id, { stage: a.targetStage }),
        };
      default:
        return null;
    }
  }

  const activeAction = flow?.action;
  const confirm = activeAction ? confirmConfig(activeAction) : null;
  // Mirrors TicketDetailPage's openPaymentModal (something already paid -> BALANCE), plus a skipped
  // deposit (nothing to collect up front, so the first payment is the balance).
  const defaultPaymentKind = (Number(deal.money.amountPaid) > 0 || deal.milestoneTrack.some((step) => step.skipped)) ? 'BALANCE' : 'DEPOSIT';

  return (
    <div className="grid w-full min-w-0 max-w-[1200px] items-start gap-x-6 gap-y-4 grid-cols-[minmax(17rem,21rem)_minmax(0,1fr)] nav-drawer:grid-cols-[minmax(0,1fr)]">
      {/* Sticky identity + money pane: the figures and the primary action stay in view while the five
          milestones scroll past. Below the nav-drawer breakpoint it is an ordinary first block. */}
      <aside className="grid min-w-0 gap-4 self-start rounded-md border border-border bg-surface p-5 sticky top-4 nav-drawer:static mobile:p-4">
        <header className="grid gap-2">
          <BackLink />
          <div className="grid min-w-0 gap-1.5">
            <h1 className="m-0 min-w-0 text-xl font-extrabold leading-snug text-text [overflow-wrap:anywhere]">
              <span className="tabular-nums">{deal.code}</span>{' '}<span className="font-bold">{deal.title}</span>
            </h1>
            <p className="m-0 grid min-w-0 gap-0.5 text-sm text-text-muted [overflow-wrap:anywhere]">
              {[deal.customerName, deal.projectName, deal.contactName].filter(Boolean).map((part, i) => (
                <span key={i} className="min-w-0">{part}</span>
              ))}
            </p>
            <p className="m-0 text-sm font-bold text-text-secondary">
              {stageCode ? `${stageCode} · ` : ''}{stageText}
            </p>
          </div>
        </header>

        <MoneyBand deal={deal} />

        {primary || overflowItems.length ? (
          <div className="flex flex-wrap items-center gap-2 border-t border-border pt-4">
            {primary ? (
              <Button type="button" className="min-w-0 flex-1 whitespace-nowrap pointer-coarse:min-h-11" onClick={() => startAction(primary)}>
                {actionLabel(primary)}
              </Button>
            ) : null}
            {overflowItems.length ? <OverflowMenu items={overflowItems} /> : null}
          </div>
        ) : null}
      </aside>

      <div className="grid min-w-0 gap-4">
      {downloadError ? (
        <p role="alert" className="m-0 flex items-start gap-2 text-sm font-bold text-danger">
          <Icon name="triangleAlert" size={16} className="mt-0.5 shrink-0" />
          <span>{downloadError}</span>
        </p>
      ) : null}
      {activeAction?.action === 'RECORD_INVOICE' && invoiceForm ? (
        <section aria-labelledby="fin-invoice-title" className="grid gap-4 rounded-md border-2 border-primary bg-surface p-5 mobile:p-4">
          <div className="grid gap-1">
            <h2 id="fin-invoice-title" className="m-0 text-lg font-extrabold text-text">บันทึกใบกำกับภาษี</h2>
            <p className="m-0 text-sm text-text-muted">กรอกข้อมูลเอกสาร แล้วตามด้วยยอดและรายการหัก ระบบจะสร้างคำขอค่าคอมมิชชันจากใบกำกับนี้</p>
          </div>
          {flowError ? (
            <p role="alert" className="m-0 flex items-start gap-2 text-sm font-bold text-danger">
              <Icon name="triangleAlert" size={16} className="mt-0.5 shrink-0" />
              <span>{flowError}</span>
            </p>
          ) : null}
          <InvoiceFromDealForm
            form={invoiceForm}
            onChange={(field, value) => setInvoiceForm((current) => ({ ...current, [field]: value }))}
            onSubmit={(event) => {
              event.preventDefault();
              if (!invoiceMutation.isPending) invoiceMutation.mutate({ ticketId: deal.id, form: invoiceForm });
            }}
            saving={invoiceMutation.isPending}
            fileInputKey={invoiceFileKey}
            fileInputId="fin-invoice-file"
            submitLabel="บันทึกใบกำกับ"
            onCancel={() => { setFlow(null); setInvoiceForm(null); setFlowError(''); }}
          />
        </section>
      ) : null}
      <MilestoneSections deal={deal} onError={setDownloadError} />

      <CommentsSection deal={deal} onPosted={applyDeal} />
      </div>

      {confirm ? (
        <ConfirmDialog
          open
          title={confirm.title}
          confirmLabel={confirm.confirmLabel}
          busy={busy}
          message={(
            <>
              <p className="m-0 text-text-secondary leading-normal">{confirm.text}</p>
              {flowError ? (
                <p role="alert" className="m-0 mt-3 flex items-start gap-2 text-sm font-bold text-danger">
                  <Icon name="triangleAlert" size={16} className="mt-0.5 shrink-0" />
                  <span>{flowError}</span>
                </p>
              ) : null}
            </>
          )}
          onConfirm={() => run(confirm.call)}
          onCancel={() => setFlow(null)}
        />
      ) : null}

      {activeAction?.action === 'RECORD_PAYMENT' ? (
        <RecordPaymentModal
          defaultKind={defaultPaymentKind} busy={busy} error={flowError}
          onSubmit={(body) => run(() => api.finance.recordPayment(deal.id, body))}
          onClose={() => setFlow(null)}
        />
      ) : null}
      {activeAction?.action === 'REVOKE_CLOSE_CONFIRM' ? (
        <RevokeCloseModal
          busy={busy} error={flowError}
          onSubmit={(body) => run(() => api.finance.revokeCloseConfirmation(deal.id, body))}
          onClose={() => setFlow(null)}
        />
      ) : null}
      {activeAction?.action === 'UPDATE_STAGE' ? (
        <StageModal
          options={MONEY_STAGE_OPTIONS.map((code) => ({ value: code, label: `${findStage(catalog, code)?.businessCode ?? ''} ${dealStageLabel(code).label}`.trim() }))}
          busy={busy} error={flowError}
          onSubmit={(body) => run(() => api.finance.updateStage(deal.id, body))}
          onClose={() => setFlow(null)}
        />
      ) : null}
    </div>
  );
}
