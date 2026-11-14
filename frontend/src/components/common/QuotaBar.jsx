import { cn } from '../../utils/cn.js';
import { formatMoney } from '../../utils/format.js';

/** QuotaBar — "you have used X of your Y" as one labelled progress bar. Other quota displays in the app render
 * a bare tinted `<div>` whose width is the only signal — invisible to a screen reader and to anyone who cannot
 * separate the two fills by color. */
export function QuotaBar({
  label,
  caption,
  used,
  cap,
  formatValue = formatMoney,
  overMessage,
  className,
}) {
  if (cap == null) return null;

  const usedValue = Number(used) || 0;
  const capValue = Number(cap) || 0;
  // Clamped to 0..100 in both directions: a negative `used` (a refund/reversal) would otherwise render a
  // negative width, and `aria-valuenow` must stay inside the min/max it declares.
  const pct = capValue > 0 ? Math.min(100, Math.max(0, (usedValue / capValue) * 100)) : 0;
  const over = usedValue > capValue;

  return (
    <div className={cn('rounded-md border border-border-subtle bg-surface-subtle p-3', className)}>
      <div className="flex items-center justify-between gap-3 text-xs font-bold text-text-muted">
        <span>{caption ?? label}</span>
        <span className={cn('tabular-nums', over ? 'text-danger' : 'text-text')}>
          {formatValue(usedValue)} / {formatValue(capValue)}
        </span>
      </div>
      <div
        className="mt-2 h-2 w-full overflow-hidden rounded-full bg-border-subtle"
        role="progressbar"
        aria-valuenow={Math.round(pct)}
        aria-valuemin={0}
        aria-valuemax={100}
        aria-label={`${label} ใช้ไปแล้ว ${Math.round(pct)}%`}
      >
        <div className={cn('h-full rounded-full', over ? 'bg-danger' : 'bg-primary')} style={{ width: `${pct}%` }} />
      </div>
      {over && overMessage ? (
        <p className="m-0 mt-1.5 text-xs font-bold text-danger">{overMessage}</p>
      ) : null}
    </div>
  );
}
