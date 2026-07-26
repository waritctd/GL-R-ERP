import { formatMoney, formatThaiDate } from '../../../utils/format.js';

function valueOrDash(value) {
  return value || '-';
}

function SummaryRow({ label, value, emphasis = false, danger = false }) {
  const valueClass = [
    'min-w-0 text-right font-bold tabular-nums',
    emphasis ? 'text-text' : 'text-text-secondary',
    danger ? 'text-danger' : '',
  ].filter(Boolean).join(' ');

  return (
    <div className="flex items-baseline justify-between gap-4 border-b border-border-subtle py-2" data-testid="financial-summary-row">
      <dt className="text-xs font-bold text-text-muted">{label}</dt>
      <dd className={valueClass}>{value}</dd>
    </div>
  );
}

export function DealFinancialSummary({ summary }) {
  const rows = [
    { label: 'ยอดที่ต้องชำระ', value: formatMoney(summary.amountPayable ?? 0) },
    { label: 'รับชำระแล้ว', value: formatMoney(summary.amountPaid ?? 0) },
    {
      label: 'คงเหลือ',
      value: formatMoney(summary.amountOutstanding ?? 0),
      emphasis: true,
      danger: Boolean(summary.overdue && Number(summary.amountOutstanding ?? 0) > 0),
    },
    { label: 'วันวางบิล', value: valueOrDash(formatThaiDate(summary.billingDate)) },
    {
      label: 'วันครบกำหนด',
      value: valueOrDash(formatThaiDate(summary.dueDate)),
      danger: Boolean(summary.overdue),
    },
  ];

  if (summary.nextFollowUpAt) {
    rows.push({ label: 'ติดตามครั้งถัดไป', value: formatThaiDate(summary.nextFollowUpAt) });
  }

  return (
    <dl className="grid gap-x-6 text-sm sm:grid-cols-2" aria-label="สรุปการเงิน" data-testid="deal-financial-summary">
      {rows.map((row) => (
        <SummaryRow
          key={row.label}
          label={row.label}
          value={row.value}
          emphasis={row.emphasis}
          danger={row.danger}
        />
      ))}
    </dl>
  );
}
