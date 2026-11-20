/* Tokens only. The deposit a rep says the customer already paid outside the portal. Shown only where the action makes the
 * deal pre-portal (the สั่งซื้อแล้ว start, or the first request on an old deal). Required; 0 is allowed. */
import { cn } from '../../../utils/cn.js';

/** The typed text as a number of baht: NaN when empty, negative or with more than two decimals. */
export function parsePreDeposit(text) {
  const clean = String(text ?? '').replace(/[,\s฿]/g, '');
  if (clean === '') return Number.NaN;
  const n = Number(clean);
  if (!Number.isFinite(n) || n < 0 || Math.abs(n * 100 - Math.round(n * 100)) > 1e-9) return Number.NaN;
  return n;
}

export const PRE_DEPOSIT_ERROR = 'ใส่มัดจำที่รับแล้ว (0 ถ้ายังไม่ได้รับ) เป็นตัวเลขไม่ติดลบ';

export function PreDepositField({ id = 'pre-deposit', value, onChange, error = null, max = null, disabled = false }) {
  return (
    <div className="grid gap-1.5" data-testid="pre-deposit-field">
      <label htmlFor={id} className="text-[0.8125rem] font-bold text-text-secondary">มัดจำที่รับแล้วก่อนใช้ระบบ (บาท)</label>
      <div className={cn(
        'flex min-h-11 w-full max-w-60 items-center gap-2 rounded-md border bg-surface px-3 focus-within:outline focus-within:outline-2 focus-within:outline-primary',
        error ? 'border-danger' : 'border-border-input',
      )}
      >
        <span aria-hidden="true" className="text-sm font-bold text-text-muted">฿</span>
        <input
          id={id}
          inputMode="decimal"
          autoComplete="off"
          disabled={disabled}
          className="min-w-0 flex-1 border-0 bg-transparent p-0 tabular-nums outline-none"
          value={value}
          aria-invalid={error ? true : undefined}
          aria-describedby={`${id}-hint`}
          onChange={(e) => onChange(e.target.value)}
        />
      </div>
      <small id={`${id}-hint`} className={cn('text-xs', error ? 'text-danger' : 'text-text-muted')} role={error ? 'alert' : undefined}>
        {error || (max != null ? 'ใส่ 0 ถ้ายังไม่ได้รับ · ไม่เกินยอดใบเสนอราคา' : 'ใส่ 0 ถ้ายังไม่ได้รับ')}
      </small>
    </div>
  );
}
