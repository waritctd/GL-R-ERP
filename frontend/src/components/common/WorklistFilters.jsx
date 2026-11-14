import { useEffect, useRef, useState } from 'react';
import { cn } from '../../utils/cn.js';
import { Icon } from './Icon.jsx';

/** Shared worklist filter-chip row — the pattern `/finance` used to use (AccountFinancePage.jsx has since moved
 * to a view switch plus selects): one horizontal row of real buttons, each a label plus an inline count,
 * `aria-pressed` reflecting selection. */
export function WorklistFilters({ items, activeKey, onSelect, ariaLabel }) {
  const scrollRef = useRef(null);
  const [canScrollLeft, setCanScrollLeft] = useState(false);
  const [canScrollRight, setCanScrollRight] = useState(false);

  useEffect(() => {
    const el = scrollRef.current;
    if (!el) return undefined;

    function updateAffordance() {
      setCanScrollLeft(el.scrollLeft > 1);
      setCanScrollRight(el.scrollLeft + el.clientWidth < el.scrollWidth - 1);
    }

    updateAffordance();
    el.addEventListener('scroll', updateAffordance, { passive: true });
    window.addEventListener('resize', updateAffordance);
    return () => {
      el.removeEventListener('scroll', updateAffordance);
      window.removeEventListener('resize', updateAffordance);
    };
  }, [items]);

  return (
    <div className="relative min-w-0">
      <div
        ref={scrollRef}
        role="group"
        aria-label={ariaLabel}
        // FIX F2 (review-remediation): `overflow-x-auto` forces `overflow-y` to compute as `auto` too (never
        // `visible`), so the scroller clips vertically at its own padding box.
        className="flex min-w-0 items-center gap-2 overflow-x-auto scroll-smooth scroll-px-1 px-1 py-1 [scrollbar-width:none] [&::-webkit-scrollbar]:hidden"
      >
        {items.map((item) => {
          const active = item.key === activeKey;
          return (
            <button
              key={item.key || 'all'}
              type="button"
              aria-pressed={active}
              onClick={() => onSelect(item.key)}
              className={cn(
                'inline-flex shrink-0 items-center gap-1.5 whitespace-nowrap rounded-lg border border-border px-3.5 py-1.5 text-sm font-bold',
                'mobile:min-h-[44px]',
                active
                  ? 'border-info-border bg-info-bg-alt text-info'
                  : 'bg-surface text-text-muted hover:text-text',
              )}
            >
              {item.dotClassName ? (
                <span aria-hidden="true" className={cn('h-2 w-2 shrink-0 rounded-full', item.dotClassName)} />
              ) : null}
              {item.label}
              {item.count != null ? (
                <span className="tabular-nums opacity-70">{item.count}</span>
              ) : null}
            </button>
          );
        })}
      </div>
      {canScrollLeft ? (
        <span
          aria-hidden="true"
          className="pointer-events-none absolute inset-y-0 left-0 flex w-7 items-center bg-gradient-to-r from-surface to-transparent"
        >
          <Icon name="chevronLeft" size={13} className="text-text-muted" />
        </span>
      ) : null}
      {canScrollRight ? (
        <span
          aria-hidden="true"
          className="pointer-events-none absolute inset-y-0 right-0 flex w-7 items-center justify-end bg-gradient-to-l from-surface to-transparent"
        >
          <Icon name="chevronRight" size={13} className="text-text-muted" />
        </span>
      ) : null}
    </div>
  );
}
