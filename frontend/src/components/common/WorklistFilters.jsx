import { useEffect, useRef, useState } from 'react';
import { cn } from '../../utils/cn.js';
import { Icon } from './Icon.jsx';

/**
 * Shared worklist filter-chip row — the pattern `/finance`
 * (AccountFinancePage.jsx's `STAGE_FILTERS`) already got right: one
 * horizontal row of real buttons, each a label plus an inline count,
 * `aria-pressed` reflecting selection. Factored out here so `/tickets` (and
 * any future worklist) can use the same chip row instead of hand-rolling a
 * second copy that can drift in touch-target size or accessibility.
 *
 * `items`: `{ key, label, count?, dotClassName? }[]`. `count` is optional —
 * omit it (or pass `null`/`undefined`) for a chip that names a filter action
 * rather than a population (e.g. an "everything" chip whose label already
 * says what it does, with no single honest count to attach to it).
 * `dotClassName` is an optional decorative color-dot class (e.g. a phase
 * accent) rendered before the count — purely visual, so it is marked
 * `aria-hidden`.
 *
 * Meets the 44px mobile touch floor via the same `mobile:min-h-[44px]`
 * utility Button.jsx uses, so this row's chips grow exactly like every other
 * touch target in the app once viewport crosses 720px.
 *
 * The row scrolls horizontally instead of clipping when it doesn't fit
 * (the /tickets phase strip used to clip silently at 820px/390px with no
 * indication more chips existed) and shows a small edge affordance — a fading
 * gradient plus a decorative chevron — whenever there is more to scroll to,
 * so the presence of hidden chips is never silent.
 */
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
        // FIX F2 (review-remediation): `overflow-x-auto` forces `overflow-y`
        // to compute as `auto` too (never `visible`), so the scroller clips
        // vertically at its own padding box. The old `pb-0.5` (2px) left
        // ~0px of clearance above a chip and ~2px below — nowhere near the
        // app's focus ring (`outline: 2px` at `outline-offset: 2px`, i.e.
        // ~4px total). `py-1` (4px) gives every chip's ring room on top and
        // bottom; `px-1` + `scroll-px-1` do the same horizontally so the
        // first/last chip's ring isn't clipped by the scroller edge either.
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
