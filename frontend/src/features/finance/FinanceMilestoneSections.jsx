import { useState } from 'react';
import { Button } from '../../components/common/Button.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { cn } from '../../utils/cn.js';
import { downloadBlob, fetchDocumentBlob } from '../../utils/download.js';
import {
  depositNoticeStatusLabel, formatMoney, formatThaiDate, fulfilmentStatusLabel,
  depositPolicyLabel, quotationStatusLabel,
} from '../../utils/format.js';

// The five milestone sections of a finance deal, in order. Every figure, label and document comes
// from the server's FinanceDealDto (its `milestoneTrack` decides which section is current/skipped);
// nothing here derives a milestone from a stage.

const PAYMENT_KIND_LABEL = { DEPOSIT: 'มัดจำ', BALANCE: 'ส่วนที่เหลือ', ADJUSTMENT: 'ปรับปรุงยอด/คืนเงิน' };

export function vatCaption(basis) {
  if (basis === 'EXCLUDING_VAT') return 'ไม่รวม VAT';
  if (basis === 'INCLUDING_VAT') return 'รวม VAT';
  return null;
}

export const money = (value) => (value == null ? '—' : formatMoney(value));

function Amount({ value, basis, className }) {
  const caption = value == null ? null : vatCaption(basis);
  return (
    <span className={cn('flex min-w-0 flex-col items-end text-right', className)}>
      <span className="tabular-nums font-bold text-text whitespace-nowrap">{money(value)}</span>
      {caption ? <span className="text-sm font-medium text-text-muted whitespace-nowrap">{caption}</span> : null}
    </span>
  );
}

function splitFileName(fileName) {
  const name = fileName || 'document';
  const dot = name.lastIndexOf('.');
  if (dot > 0 && dot < name.length - 1) return { base: name.slice(0, dot), ext: name.slice(dot + 1).toLowerCase() };
  return { base: name, ext: 'pdf' };
}

function DownloadButton({ path, name, filenameBase, format = 'pdf', onError }) {
  const [busy, setBusy] = useState(false);
  async function run() {
    if (busy) return;
    setBusy(true);
    try {
      const blob = await fetchDocumentBlob(path);
      downloadBlob(blob, filenameBase ?? name, format);
    } catch (err) {
      onError?.(err.message || 'ดาวน์โหลดไม่สำเร็จ');
    } finally {
      setBusy(false);
    }
  }
  return (
    <Button
      type="button" variant="secondary" size="sm" loading={busy}
      className="w-full whitespace-nowrap pointer-coarse:min-h-11" aria-label={`ดาวน์โหลด ${name}`} onClick={run}
    >
      ดาวน์โหลด
    </Button>
  );
}

// ONE row layout for every document/payment row in every panel: title line (+ badge) with a meta line
// on the left, a fixed-width amount column, a fixed-width button column. Because the tracks are
// fixed-width and right-anchored, amounts and buttons share the same right edges across all panels.
// Compact (mobile): title + meta full width, then amount (left) and button (right) on one row.
const DOC_ROW_GRID = 'grid grid-cols-[minmax(0,1fr)_9rem_7rem] items-center gap-x-4 gap-y-2 py-3 mobile:grid-cols-[minmax(0,1fr)_auto]';

function DocRow({ title, meta, badge, amount, basis, download }) {
  return (
    <li data-doc-row="v1" className={DOC_ROW_GRID}>
      <div className="grid min-w-0 gap-0.5 mobile:col-span-2">
        <span className="flex min-w-0 flex-wrap items-center gap-x-2 gap-y-1">
          <span className="min-w-0 font-bold text-text [overflow-wrap:anywhere]">{title}</span>
          {badge}
        </span>
        {meta ? <span className="min-w-0 text-sm text-text-muted [overflow-wrap:anywhere]">{meta}</span> : null}
      </div>
      {amount !== undefined ? <Amount value={amount} basis={basis} className="mobile:items-start mobile:text-left" /> : <span aria-hidden="true" className="mobile:hidden" />}
      {download ? <div className="min-w-0 mobile:col-start-2">{download}</div> : <span aria-hidden="true" className="mobile:hidden" />}
    </li>
  );
}

function Empty({ children }) {
  return <p className="m-0 py-2 text-sm text-text-muted">{children}</p>;
}

function SubList({ label, children }) {
  return (
    <div className="grid gap-0.5 [&:not(:first-child)]:mt-5">
      {label ? <h3 className="m-0 text-sm font-bold text-text-secondary">{label}</h3> : null}
      {children}
    </div>
  );
}

function FileRows({ files, onError }) {
  return (
    <ul className="m-0 grid list-none divide-y divide-border p-0">
      {files.map((f) => {
        const { base, ext } = splitFileName(f.fileName);
        return (
          <DocRow
            key={f.id}
            title={f.fileName}
            meta={f.uploadedAt ? `อัปโหลด ${formatThaiDate(f.uploadedAt)}` : null}
            download={<DownloadButton path={f.downloadPath} name={f.fileName} filenameBase={base} format={ext} onError={onError} />}
          />
        );
      })}
    </ul>
  );
}

function PaymentRows({ payments }) {
  return (
    <ul className="m-0 grid list-none divide-y divide-border p-0">
      {payments.map((p) => (
        <DocRow
          key={p.id}
          title={`รับ${PAYMENT_KIND_LABEL[p.kind] ?? p.kind ?? 'ชำระ'}`}
          meta={[
            p.receivedAt ? formatThaiDate(p.receivedAt) : null,
            p.receiptRef ? `อ้างอิง ${p.receiptRef}` : null,
            p.recordedByName ? `บันทึกโดย ${p.recordedByName}` : null,
            p.note || null,
          ].filter(Boolean).join(' · ')}
          amount={p.amount}
        />
      ))}
    </ul>
  );
}

function ItemsTable({ items }) {
  if (!items.length) return <Empty>ยังไม่มีรายการสินค้า</Empty>;
  const num = 'text-right tabular-nums whitespace-nowrap';
  const cell = 'py-2 pl-3 first:pl-0 mobile:flex mobile:items-baseline mobile:justify-between mobile:gap-3 mobile:py-0.5 mobile:pl-0 mobile:before:text-sm mobile:before:text-text-muted mobile:before:content-[attr(data-label)]';
  return (
    <table className="w-full border-collapse text-sm mobile:block">
      <caption className="sr-only">รายการสินค้าในดีล</caption>
      <thead className="mobile:sr-only">
        <tr className="border-b border-border text-sm text-text-muted">
          <th scope="col" className="py-1.5 text-left font-bold">รายการ</th>
          <th scope="col" className={cn('py-1.5 pl-3 font-bold', num)}>จำนวน</th>
          <th scope="col" className={cn('py-1.5 pl-3 font-bold', num)}>ราคาต่อหน่วย</th>
          <th scope="col" className={cn('py-1.5 pl-3 font-bold', num)}>รวม</th>
        </tr>
      </thead>
      <tbody className="mobile:block">
        {items.map((it, i) => (
          <tr key={i} className="border-b border-border last:border-b-0 mobile:block mobile:py-2.5">
            <td data-label="รายการ" className={cn(cell, 'min-w-0 font-medium text-text [overflow-wrap:anywhere] mobile:font-bold')}>{it.description || '—'}</td>
            <td data-label="จำนวน" className={cn(cell, num)}>{it.qty == null ? '—' : `${Number(it.qty).toLocaleString('en-US')}${it.unit ? ` ${it.unit}` : ''}`}</td>
            <td data-label="ราคาต่อหน่วย" className={cn(cell, num)}>{money(it.unitPrice)}</td>
            <td data-label="รวม" className={cn(cell, num, 'font-bold text-text')}>{money(it.lineTotal)}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

// ── one body per milestone ───────────────────────────────────────────────────────────────

function OrderBody({ deal, onError }) {
  const q = deal.documents.acceptedQuotation;
  const { purchaseOrders, contracts } = deal.documents;
  return (
    <>
      {q ? (
        <SubList label="ใบเสนอราคาที่ตกลง">
          <ul className="m-0 grid list-none p-0">
            <DocRow
              title={q.number ?? `ใบเสนอราคา #${q.id}`}
              badge={<StatusBadge tone={quotationStatusLabel(q.status).tone}>{quotationStatusLabel(q.status).label}</StatusBadge>}
              meta={[q.acceptedAt ? `ตกลง ${formatThaiDate(q.acceptedAt)}` : null, q.issuedAt ? `ออก ${formatThaiDate(q.issuedAt)}` : null].filter(Boolean).join(' · ')}
              amount={q.totalAmount}
              basis={q.vatBasis}
              download={<DownloadButton path={q.downloadPath} name={q.number ?? 'ใบเสนอราคา'} filenameBase={q.number ?? `quotation-${q.id}`} onError={onError} />}
            />
          </ul>
        </SubList>
      ) : <Empty>ยังไม่มีใบเสนอราคาที่ลูกค้าตกลง</Empty>}
      <SubList label="รายการสินค้า"><ItemsTable items={deal.items} /></SubList>
      {purchaseOrders.length ? <SubList label="ใบสั่งซื้อ"><FileRows files={purchaseOrders} onError={onError} /></SubList> : null}
      {contracts.length ? <SubList label="สัญญาและเอกสารแนบ"><FileRows files={contracts} onError={onError} /></SubList> : null}
    </>
  );
}

function DepositBody({ deal, skipped, awaiting, onError }) {
  const notices = deal.documents.depositNotices;
  const payments = deal.money.payments.filter((p) => p.kind === 'DEPOSIT');
  return (
    <>
      {skipped ? (
        <p className="m-0 py-1 text-sm text-text-muted">
          ดีลนี้ไม่ต้องเก็บมัดจำ
          {deal.money.depositPolicy ? <span> · {depositPolicyLabel(deal.money.depositPolicy).label}</span> : null}
        </p>
      ) : null}
      {notices.length ? (
        <SubList label="ใบแจ้งมัดจำ">
          <ul className="m-0 grid list-none divide-y divide-border p-0">
            {notices.map((d) => (
              <DocRow
                key={d.id}
                title={<><span>{d.docNumber ?? `#${d.id}`}</span> <span className="text-sm font-medium text-text-muted">v{d.version}</span></>}
                badge={(
                  <>
                    <StatusBadge tone={depositNoticeStatusLabel(d.status).tone}>{depositNoticeStatusLabel(d.status).label}</StatusBadge>
                    {awaiting && d.status === 'ISSUED' ? <StatusBadge tone="warning">รอยืนยันรับมัดจำ</StatusBadge> : null}
                  </>
                )}
                meta={[d.issueDate ? `ออก ${formatThaiDate(d.issueDate)}` : null, d.depositAmount != null ? `มัดจำ ${money(d.depositAmount)}` : null].filter(Boolean).join(' · ')}
                amount={d.totalPayable}
                basis={d.totalPayableVatBasis}
                download={<DownloadButton path={d.downloadPath} name={d.docNumber ?? 'ใบแจ้งมัดจำ'} filenameBase={d.docNumber ?? `deposit-notice-${d.id}`} onError={onError} />}
              />
            ))}
          </ul>
        </SubList>
      ) : !skipped ? <Empty>ยังไม่มีใบแจ้งมัดจำ</Empty> : null}
      {payments.length ? <SubList label="รับมัดจำแล้ว"><PaymentRows payments={payments} /></SubList> : !skipped ? <Empty>ยังไม่มีรายการรับมัดจำ</Empty> : null}
    </>
  );
}

function ProcurementBody({ deal }) {
  const status = deal.money.fulfillmentStatus;
  return (
    <p className="m-0 py-1 text-sm text-text">
      {status ? fulfilmentStatusLabel(status).label : <span className="text-text-muted">ยังไม่มีสถานะสินค้า</span>}
    </p>
  );
}

function DeliveryBody({ deal, awaiting, onError }) {
  const { remainingInvoices, billingNotes } = deal.documents;
  const payments = deal.money.payments.filter((p) => p.kind !== 'DEPOSIT');
  return (
    <>
      {remainingInvoices.length ? (
        <SubList label="ใบแจ้งหนี้ส่วนที่เหลือ">
          <ul className="m-0 grid list-none divide-y divide-border p-0">
            {remainingInvoices.map((r) => (
              <DocRow
                key={r.id}
                title={<><span>{r.docNumber ?? `#${r.id}`}</span> <span className="text-sm font-medium text-text-muted">v{r.version}</span></>}
                badge={(
                  <>
                    <StatusBadge tone={depositNoticeStatusLabel(r.status).tone}>{depositNoticeStatusLabel(r.status).label}</StatusBadge>
                    {awaiting && r.status === 'ISSUED' ? <StatusBadge tone="warning">รอรับชำระ</StatusBadge> : null}
                  </>
                )}
                meta={r.docDate ? `ลงวันที่ ${formatThaiDate(r.docDate)}` : null}
                amount={r.grandTotal}
                basis={r.grandTotalVatBasis}
                download={<DownloadButton path={r.downloadPath} name={r.docNumber ?? 'ใบแจ้งหนี้'} filenameBase={r.docNumber ?? `remaining-invoice-${r.id}`} onError={onError} />}
              />
            ))}
          </ul>
        </SubList>
      ) : <Empty>ยังไม่มีใบแจ้งหนี้ส่วนที่เหลือ</Empty>}
      {billingNotes.length ? (
        <SubList label="ใบวางบิล">
          <ul className="m-0 grid list-none divide-y divide-border p-0">
            {billingNotes.map((b) => (
              <DocRow
                key={b.id}
                title={b.docNumber ?? `#${b.id}`}
                meta={b.billDate ? `วางบิล ${formatThaiDate(b.billDate)}` : null}
                amount={b.amountForThisDeal}
                basis={b.amountVatBasis}
                download={<DownloadButton path={b.downloadPath} name={b.docNumber ?? 'ใบวางบิล'} filenameBase={b.docNumber ?? `billing-note-${b.id}`} onError={onError} />}
              />
            ))}
          </ul>
        </SubList>
      ) : null}
      {payments.length ? <SubList label="รับชำระแล้ว"><PaymentRows payments={payments} /></SubList> : <Empty>ยังไม่มีรายการรับชำระ</Empty>}
    </>
  );
}

// Where the recorded tax invoice's commission request stands (FinanceDealDto.CommissionInvoice.approvalStatus).
// Status only -- finance never sees a commission amount, weight or approver.
const COMMISSION_APPROVAL = {
  SUBMITTED: { label: 'รอผู้จัดการฝ่ายขายอนุมัติ', tone: 'warning' },
  MANAGER_APPROVED: { label: 'รอ CEO อนุมัติ', tone: 'info' },
  APPROVED: { label: 'อนุมัติแล้ว', tone: 'success' },
  REJECTED: { label: 'ถูกตีกลับ', tone: 'danger' },
};

const INVOICE_FIELDS = [
  ['grossAmount', 'ยอดรวม (ก่อน VAT)'],
  ['bankFees', 'ค่าธรรมเนียมธนาคาร'],
  ['suspenseVat', 'ภาษีพัก (Suspense VAT)'],
  ['transportFee', 'ค่าขนส่ง'],
  ['cutFee', 'ค่าตัด'],
  ['shortfall', 'รับเงินขาด'],
  ['withholdingTax', 'หัก ณ ที่จ่าย'],
  ['overpayment', 'รับเงินเกิน'],
];

function CommissionInvoiceBlock({ invoice, onError }) {
  const approval = COMMISSION_APPROVAL[invoice.approvalStatus] ?? { label: invoice.approvalStatus, tone: 'neutral' };
  const { base, ext } = splitFileName(invoice.fileName);
  return (
    <SubList label="ใบกำกับที่บันทึกแล้ว">
      <div className="grid gap-3 py-2">
        <p className="m-0 flex flex-wrap items-center gap-x-3 gap-y-1 text-sm text-text">
          <strong className="font-bold [overflow-wrap:anywhere]">{invoice.invoiceNumber}</strong>
          <span className="text-text-muted">{formatThaiDate(invoice.invoiceDate)}</span>
          <StatusBadge tone={approval.tone}>{approval.label}</StatusBadge>
        </p>
        {invoice.approvalStatus === 'REJECTED' && invoice.rejectionReason ? (
          <p role="note" className="m-0 text-sm font-bold text-danger [overflow-wrap:anywhere]">{`เหตุผล: ${invoice.rejectionReason}`}</p>
        ) : null}
        <dl className="m-0 grid grid-cols-4 gap-x-4 gap-y-2 mobile:grid-cols-2">
          {INVOICE_FIELDS.map(([key, label]) => (
            <div key={key} className="grid min-w-0 gap-0.5">
              <dt className="text-sm text-text-muted">{label}</dt>
              <dd className="m-0 tabular-nums font-bold text-text">{money(invoice[key])}</dd>
            </div>
          ))}
        </dl>
        {invoice.downloadPath ? (
          <div className="max-w-56">
            <DownloadButton path={invoice.downloadPath} name={invoice.fileName ?? invoice.invoiceNumber} filenameBase={base} format={ext} onError={onError} />
          </div>
        ) : null}
      </div>
    </SubList>
  );
}

function ClosedBody({ deal, onError }) {
  const { taxInvoices } = deal.documents;
  const m = deal.money;
  return (
    <>
      {deal.commissionInvoice ? <CommissionInvoiceBlock invoice={deal.commissionInvoice} onError={onError} /> : null}
      {taxInvoices.length ? <SubList label="ใบกำกับภาษี"><FileRows files={taxInvoices} onError={onError} /></SubList> : null}
      <ul className="m-0 grid list-none gap-1 p-0 text-sm text-text">
        <li>{`ใบกำกับภาษี: ${m.invoiceOnFile ? 'มีแล้ว' : 'ยังไม่มี'}`}</li>
        <li>{`การยืนยันปิดงาน: ${m.closeConfirmedAt ? `ยืนยันแล้ว ${formatThaiDate(m.closeConfirmedAt)}` : 'ยังไม่ยืนยัน'}`}</li>
        <li>{`ค่าคอมมิชชัน: ${m.commissionRecorded ? 'บันทึกแล้ว' : 'ยังไม่ได้บันทึก'}`}</li>
      </ul>
    </>
  );
}

// ── section shell ────────────────────────────────────────────────────────────────────────

function hasData(index, deal) {
  const d = deal.documents;
  const m = deal.money;
  switch (index) {
    case 1: return Boolean(d.acceptedQuotation) || deal.items.length > 0 || d.purchaseOrders.length > 0 || d.contracts.length > 0;
    case 2: return d.depositNotices.length > 0 || m.payments.some((p) => p.kind === 'DEPOSIT');
    case 3: return m.fulfillmentStatus != null;
    case 4: return d.remainingInvoices.length > 0 || d.billingNotes.length > 0 || m.payments.some((p) => p.kind !== 'DEPOSIT');
    case 5: return d.taxInvoices.length > 0 || m.invoiceOnFile || m.closeConfirmedAt != null || m.commissionRecorded || deal.commissionInvoice != null;
    default: return false;
  }
}

const STATE_STATUS = {
  done: { label: 'เสร็จแล้ว', tone: 'success' },
  current: { label: 'ขั้นปัจจุบัน', tone: 'info' },
  skipped: { label: 'ข้าม', tone: 'neutral' },
  upcoming: { label: 'ยังไม่ถึง', tone: 'neutral' },
};

function MilestoneSection({ step, position, subject, deal, onError }) {
  // position: 'past' | 'current' | 'upcoming' relative to the server's current milestone.
  const titleId = `fin-milestone-${step.index}`;
  const data = hasData(step.index, deal);
  const state = step.skipped ? 'skipped' : position === 'past' ? 'done' : position;
  // Upcoming panels collapse to the header row unless they already hold data — data wins.
  const collapsed = position === 'upcoming' && !data && !step.skipped;
  // A panel holding the subject of an available action gets the current panel's emphasis, without
  // taking the server's current marker.
  const emphasised = position === 'current' || subject;
  // A non-current panel that holds an action's subject is waiting on finance, not "not yet": say so.
  const status = subject && position !== 'current' && state !== 'skipped'
    ? { label: 'รอดำเนินการ', tone: 'warning' }
    : STATE_STATUS[state];

  let body = null;
  if (!collapsed) {
    if (step.index === 1) body = <OrderBody deal={deal} onError={onError} />;
    else if (step.index === 2) body = <DepositBody deal={deal} skipped={step.skipped} awaiting={subject} onError={onError} />;
    else if (step.index === 3) body = <ProcurementBody deal={deal} />;
    else if (step.index === 4) body = <DeliveryBody deal={deal} awaiting={subject} onError={onError} />;
    else body = <ClosedBody deal={deal} onError={onError} />;
  }

  return (
    <section
      aria-labelledby={titleId}
      aria-current={position === 'current' ? 'step' : undefined}
      data-state={state}
      data-emphasis={emphasised ? 'true' : 'false'}
      className={cn(
        'min-w-0 rounded-md border bg-surface p-5 mobile:p-4',
        emphasised ? 'border-primary' : 'border-border',
      )}
    >
      <div className="flex flex-wrap items-center justify-between gap-x-3 gap-y-2">
        <div className="flex min-w-0 flex-[1_1_12rem] items-center gap-3">
          <span
            aria-hidden="true"
            className={cn(
              'inline-flex size-6 shrink-0 items-center justify-center rounded-pill text-sm font-extrabold tabular-nums',
              emphasised && 'bg-primary text-surface',
              !emphasised && state === 'done' && 'bg-success-bg text-success',
              !emphasised && state !== 'done' && 'border border-border-muted text-text-muted',
            )}
          >
            {state === 'done' && !emphasised ? <Icon name="check" size={14} strokeWidth={3} /> : step.index}
          </span>
          <h2 id={titleId} className={cn('m-0 min-w-0 text-lg font-extrabold [overflow-wrap:anywhere]', state === 'upcoming' && !emphasised ? 'text-text-muted' : 'text-text')}>
            <span className="sr-only">{step.index}</span>{' '}{step.label}
          </h2>
        </div>
        <StatusBadge tone={status.tone}>{status.label}</StatusBadge>
      </div>
      {collapsed ? null : <div data-panel-body className="mt-4 grid min-w-0">{body}</div>}
    </section>
  );
}

// Which milestone holds the subject of an available action (no invented rules: only the two actions
// whose object is a specific issued document): DEPOSIT_PAID -> the deposit notice, FINAL_PAYMENT ->
// the remaining invoice.
const ACTION_SUBJECT_MILESTONE = { DEPOSIT_PAID: 2, FINAL_PAYMENT: 4 };

export function MilestoneSections({ deal, onError }) {
  const subjects = new Set((deal.availableActions ?? []).map((a) => ACTION_SUBJECT_MILESTONE[a.action]).filter(Boolean));
  const track = deal.milestoneTrack ?? [];
  const currentIndex = track.find((m) => m.current)?.index ?? 0;
  return (
    <div className="grid gap-3">
      {track.map((step) => {
        let position = 'upcoming';
        if (currentIndex && step.index < currentIndex) position = 'past';
        else if (step.current) position = 'current';
        return <MilestoneSection key={step.key} step={step} position={position} subject={subjects.has(step.index)} deal={deal} onError={onError} />;
      })}
    </div>
  );
}
