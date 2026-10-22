import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { cn } from '../../utils/cn.js';
import { commissionStatusLabel as statusInfo, formatMoney, formatThaiDate } from '../../utils/format.js';
import {
  MANUAL_KIND_LABELS,
  baseContribution,
  countsTowardBase,
  describeCommissionWeight,
  formatWeight,
  isManualKind,
  recordWeight,
  weightExplanation,
} from './commissionRecord.js';

function Line({ op, label, value, tone, strong = false, muted = false }) {
  return (
    <div
      className={cn(
        'grid min-w-0 grid-cols-[minmax(0,1fr)_auto] items-baseline gap-x-3',
        strong && 'border-t border-border pt-1.5 font-extrabold text-text',
        muted && 'text-text-muted',
      )}
    >
      <dt className={cn('min-w-0 [overflow-wrap:anywhere]', !strong && !muted && 'text-text-secondary')}>
        <span aria-hidden="true" className="mr-1.5 inline-block w-3 text-text-muted">{op}</span>
        {label}
      </dt>
      <dd className={cn('m-0 text-right tabular-nums [overflow-wrap:anywhere]', tone === 'danger' && 'text-danger')}>{value}</dd>
    </div>
  );
}

/**
 * The full chain behind one receipt, top to bottom:
 *   ยอดตามใบกำกับ − deductions + รับเงินเกิน = ยอดรับจริง ÷ VAT = ยอดไม่รวม VAT × น้ำหนัก = ที่เข้าฐาน
 * Every figure except the last is a column the server already computed (the fields are exactly what
 * CommissionCalculator.calculateInvoice consumed); "ที่เข้าฐาน" is the display-only product of the
 * ex-VAT figure and the weight payroll uses. Deductions that are zero are left out. Used for the
 * rep's receipt ledger and for the manager / CEO review table's row expansion. `compact` drops the
 * status and weight badges for callers whose own row already shows them (the ledger).
 */
export function ReceiptChain({ record, compact = false }) {
  const status = statusInfo(record.status);
  const approvals = (
    <div className="grid grid-cols-2 gap-3 text-sm text-text-muted sm:grid-cols-4">
      <span>ผู้จัดการ: {record.managerApprovedAt ? `${record.managerApprovedByName || '-'} · ${formatThaiDate(record.managerApprovedAt)}` : '-'}</span>
      <span>CEO: {record.ceoApprovedAt ? `${record.ceoApprovedByName || '-'} · ${formatThaiDate(record.ceoApprovedAt)}` : '-'}</span>
    </div>
  );

  if (isManualKind(record.kind)) {
    const amount = Number(record.manualAmount || 0);
    return (
      <div data-testid={`receipt-chain-${record.id}`} className="grid gap-3 text-md">
        <div className="flex flex-wrap items-center gap-2">
          <StatusBadge tone={status.tone}>{status.label}</StatusBadge>
          <StatusBadge tone="info">{MANUAL_KIND_LABELS[record.kind] || record.kind}</StatusBadge>
        </div>
        <dl className="m-0 grid gap-1.5 rounded-md bg-surface-muted p-3">
          <Line label="จำนวนเงิน (พิมพ์เอง — ไม่ผ่านการคำนวณอัตโนมัติ)" value={formatMoney(amount)} tone={amount < 0 ? 'danger' : undefined} strong />
          <Line label="เหตุผล" value={record.manualReason || '-'} muted />
        </dl>
        {approvals}
      </div>
    );
  }

  const invoice = record.invoiceDetails;
  const weight = recordWeight(record);
  const explanation = weightExplanation(record);
  const showWeight = explanation != null || weight !== 1;
  const deductions = [
    ['ค่าธรรมเนียมธนาคาร', invoice.bankFees],
    ['ภาษีรอใช้สิทธิ (Suspense VAT)', invoice.suspenseVat],
    ['ค่าตัด', invoice.cutFee],
    ['ค่าขนส่ง', invoice.transportFee],
    ['หัก ณ ที่จ่าย (WHT)', invoice.withholdingTax],
    ['รับเงินขาด', invoice.shortfall],
  ].filter(([, value]) => Number(value || 0) !== 0);
  const weightBadge = describeCommissionWeight(record);

  return (
    <div data-testid={`receipt-chain-${record.id}`} className="grid gap-3 text-md">
      {!compact || record.dealAmountMismatch ? (
        <div className="flex flex-wrap items-center gap-2">
          {!compact ? <StatusBadge tone={status.tone}>{status.label}</StatusBadge> : null}
          {!compact && weightBadge ? <StatusBadge tone="info">{weightBadge.label}</StatusBadge> : null}
          {record.dealAmountMismatch ? <StatusBadge tone="warning">ยอดต่างจากยอดที่เรียกเก็บ</StatusBadge> : null}
        </div>
      ) : null}
      <dl className="m-0 grid gap-1.5 rounded-md bg-surface-muted p-3">
        <Line label="ยอดตามใบกำกับ" value={formatMoney(invoice.grossAmount)} />
        {deductions.map(([label, value]) => <Line key={label} op="−" label={label} value={formatMoney(value)} />)}
        {Number(invoice.overpayment || 0) !== 0 ? <Line op="+" label="รับเงินเกิน" value={formatMoney(invoice.overpayment)} /> : null}
        <Line op="=" label="ยอดรับจริง" value={formatMoney(record.actualReceived)} strong />
        {/* The VAT divisor is a policy number owned by CommissionCalculator, so it is not restated
            here: both figures either side of this step are server-computed columns off the record. */}
        <Line op="÷" label="แยกภาษีมูลค่าเพิ่ม (VAT)" value="" muted />
        <Line op="=" label="ยอดไม่รวม VAT" value={formatMoney(record.commissionableBase)} strong />
        {showWeight ? <Line op="×" label="น้ำหนัก" value={formatWeight(weight)} /> : null}
        <Line op="=" label="ที่เข้าฐาน" value={countsTowardBase(record) ? formatMoney(baseContribution(record)) : 'ไม่เข้าฐาน'} strong />
      </dl>
      {explanation ? <p className="m-0 text-sm text-text-muted">{explanation}</p> : null}
      <div className="grid grid-cols-2 gap-3 text-sm text-text-muted sm:grid-cols-4">
        <span>Invoice: {invoice.invoiceNumber} · {formatThaiDate(invoice.invoiceDate)}</span>
        <span>ผู้จัดการ: {record.managerApprovedAt ? `${record.managerApprovedByName || '-'} · ${formatThaiDate(record.managerApprovedAt)}` : '-'}</span>
        <span>CEO: {record.ceoApprovedAt ? `${record.ceoApprovedByName || '-'} · ${formatThaiDate(record.ceoApprovedAt)}` : '-'}</span>
        <span>ไฟล์: {invoice.invoiceAttachmentFileName || '-'}</span>
      </div>
    </div>
  );
}
