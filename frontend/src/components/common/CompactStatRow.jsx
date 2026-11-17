import { cn } from '../../utils/cn.js';
import { Skeleton } from './Skeleton.jsx';

/** CompactStatRow — a single horizontal band of label/value readouts. Replaces StatCard × StatGrid ("the
 * hero-metric template": 269×161 fixed-height icon-tile cards, identical across ten screens per the 2026-07
 * card-diet audit) for stat cards that never navigate anywhere. */
export function CompactStatRow({
  items,
  loading = false,
  loadingCount = 4,
  className,
  'aria-label': ariaLabel,
  ...props
}) {
  const gridClassName = cn(
    'm-0 grid grid-cols-4 gap-x-6 gap-y-4 rounded-md border border-border bg-surface px-5 py-4',
    'nav-drawer:grid-cols-2 mobile:grid-cols-2 mobile:gap-x-4 mobile:gap-y-3 mobile:px-4 mobile:py-3.5',
    className,
  );

  if (loading) {
    // Skeleton.jsx's own contract: the shimmer blocks are decorative and the *surrounding container* carries
    // `aria-busy` + a caller-supplied `aria-label` so assistive tech announces the loading state — mirrors every
    // other `aria-busy` loading region in this codebase.
    return (
      <div
        className={gridClassName}
        aria-busy="true"
        aria-label={ariaLabel || 'กำลังโหลดข้อมูลสรุป'}
        data-testid="compact-stat-row"
        {...props}
      >
        {Array.from({ length: loadingCount }, (_, index) => (
          <div key={index} className="flex min-w-0 flex-col gap-0.5">
            <Skeleton width="65%" height={14} />
            <Skeleton width="45%" height={20} />
          </div>
        ))}
      </div>
    );
  }

  return (
    <dl className={gridClassName} aria-label={ariaLabel} data-testid="compact-stat-row" {...props}>
      {items.map((item) => (
        <div key={item.key ?? item.label} className="flex min-w-0 flex-col gap-0.5">
          <dt className="text-2xs font-bold uppercase tracking-wide text-text-muted">
            {item.label}
            {item.helper ? <span className="ml-1 font-normal normal-case">· {item.helper}</span> : null}
          </dt>
          <dd
            className={cn(
              'm-0 text-lg font-extrabold tabular-nums text-text sm:text-xl',
              // Not `cn('truncate', item.wrapValue && 'whitespace-normal')`: tailwind-merge does not treat a later
              // `whitespace-*` as displacing `truncate`, so both would survive and which one won would come down to
              // stylesheet order. Branching picks exactly one.
              item.wrapValue ? 'min-w-0 break-words' : 'truncate',
            )}
            title={String(item.value)}
          >
            {item.value}
          </dd>
        </div>
      ))}
    </dl>
  );
}
