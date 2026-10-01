import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { formatThaiDate } from '../../utils/format.js';
import { depositStatus } from './dealMoneyStatus.js';

// Review round 1 (2026-09-23): this used to check paymentStatus alone, which is wrong for any
// bypass-policy deal (CREDIT_CUSTOMER/WAIVED/NOT_REQUIRED) — TicketService#recordPayment skips
// DEPOSIT_PAID entirely for those, advancing straight to AWAITING_FINAL_PAYMENT, so a customer who
// simply paid their invoice showed as "มัดจำ: ชำระแล้ว" though no deposit was ever requested. The
// bypass check must run FIRST, same priority DealDepositPanel's own skipsNotice/alreadyPaid chain
// uses — see dealMoneyStatus.js, now shared with DealDocumentPipeline's identical step 1 so the
// two cannot diverge again.
function depositChip(summary) {
  const { bypassed, paid, noticeIssued } = depositStatus(summary);
  if (bypassed) return { label: 'มัดจำ', value: 'ไม่ต้องมัดจำ', tone: 'neutral' };
  if (paid) return { label: 'มัดจำ', value: 'ชำระแล้ว', tone: 'success' };
  if (noticeIssued) return { label: 'มัดจำ', value: 'รอชำระ', tone: 'warning' };
  return { label: 'มัดจำ', value: 'ยังไม่แจ้ง', tone: 'neutral' };
}

// Same compact chip shape as DealStateHeader.jsx's own (module-private) StatChip — copied, not
// imported, since that component isn't exported; see this feature's own
// .design/gla-129-money-tab/INFORMATION_ARCHITECTURE.md Component Reuse Map.
function StatChip({ label, value, tone }) {
  return (
    <div className="flex min-w-0 flex-col gap-1">
      <dt className="text-2xs font-bold uppercase tracking-wide text-text-muted">{label}</dt>
      <dd className="m-0 min-w-0">
        {tone ? (
          <StatusBadge tone={tone}>{value}</StatusBadge>
        ) : (
          <span className="block truncate text-sm font-extrabold text-text">{value}</span>
        )}
      </dd>
    </div>
  );
}

/**
 * GLA-129: an at-a-glance strip atop the money tab's document pipeline — วันวางบิล/ครบกำหนด/
 * ติดตามครั้งถัดไป mirror the "วันสำคัญ" ContextSection already on the deal tab (same `summary`
 * fields, same `formatThaiDate`), repeated here so a user reading money-tab status doesn't have
 * to switch tabs to see the dates it depends on. มัดจำ is new to this strip specifically — the
 * document pipeline below covers the SAME lifecycle step in more detail; this is the one-glance
 * version.
 */
export function DealMoneyStatusStrip({ summary }) {
  const chips = [
    { label: 'วันวางบิล', value: formatThaiDate(summary.billingDate) },
    { label: 'ครบกำหนดชำระ', value: formatThaiDate(summary.dueDate), tone: summary.overdue ? 'danger' : undefined },
    { label: 'ติดตามครั้งถัดไป', value: formatThaiDate(summary.nextFollowUpAt) },
    depositChip(summary),
  ];
  return (
    <dl className="m-0 grid grid-cols-2 gap-x-3 gap-y-3 tablet:grid-cols-4">
      {chips.map((chip) => (
        <StatChip key={chip.label} label={chip.label} value={chip.value} tone={chip.tone} />
      ))}
    </dl>
  );
}
