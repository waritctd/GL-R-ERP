import { useCallback, useEffect, useRef, useState } from 'react';
import { Icon } from './Icon.jsx';

/**
 * Shared tab primitive for the workspace pages (authored for the deal
 * workspace IA rebuild — see docs/ui-repair/02-information-architecture/).
 *
 * WAI-ARIA "tabs with automatic activation": ArrowLeft/ArrowRight move AND
 * select, Home/End jump to the ends, and a roving tabindex keeps exactly one
 * tab in the page tab order. That is the right pattern here because switching
 * a tab is cheap and non-destructive — every panel is already-fetched data,
 * so arrow-to-activate never costs the user a request or loses input.
 *
 * `items`: `[{ id, label, helper, badge, badgeLabel }]`
 *   - `helper`    — an optional short sub-label for context.
 *   - `badge`     — a count (or short string) shown when the tab holds real data. Omit/leave
 *     `null`/`undefined` while the count is unknown (loading, disabled, errored) — do not pass `0`
 *     as a stand-in for "no count yet", it renders as a real, confident zero.
 *   - `badgeLabel` — optional accessible text for the badge (e.g. `"2 รายการ"`). Without it, the
 *     raw `badge` value is read as part of the tab's name (`Tabs.jsx`'s own `helper` is
 *     `aria-hidden` for the same reason a bare number is not: a screen reader announcing
 *     "งานของฉัน2" back-to-back reads as one run-on word, not "2 items"). Pass it whenever `badge`
 *     is a plain count so the announced name is "<label> <badgeLabel>" instead.
 *
 * Every tab a caller passes in is fully functional and in the normal tab
 * order — this component has no "reachable but not ready yet" visual state.
 * A tab's own content decides what to show when there's nothing there yet
 * (its own empty state), it is never dimmed at the tab-bar level.
 *
 * Deliberately unstyled beyond layout + state so the caller owns density.
 *
 * `onSurface` — set it when the tablist sits on a `bg-surface` card rather than
 * on the page background. It only affects the scroll-affordance gradients, and
 * getting it wrong is visible: those overlays fade the scrolled-away tab text
 * out by painting the BACKGROUND colour over it, so they have to be the colour
 * that is actually behind the strip. They were hardcoded `from-surface`
 * (`#fff`) while two of the three call sites — LeaveSurfacePage and
 * AttendanceCalendarPage — mount the strip straight onto the page background
 * (`--color-bg`, `#eef1f6`, measured: every ancestor up to <body> is
 * transparent). On those pages the "fade" was a white smear over grey-blue with
 * the tab label still legible underneath, which reads as the chevron sitting on
 * top of the text rather than the text fading out. TicketDetailPage really is
 * on a white card, hence the prop rather than just swapping the token.
 */
export function Tabs({ items, value, onChange, ariaLabel, idPrefix, onSurface = false }) {
  const listRef = useRef(null);
  const [canScrollLeft, setCanScrollLeft] = useState(false);
  const [canScrollRight, setCanScrollRight] = useState(false);

  // Dev-only, no-op in production (import.meta.env.DEV is a Vite build-time constant, so this
  // branch is dead code in a prod build even though it isn't stripped from the source). Catches a
  // real incident: a caller shipped `items: [{ value: 'x', label }]` instead of `{ id: 'x', label }`
  // — this component reads `item.id` everywhere (onClick/onChange/selected-state/keyboard nav),
  // never `value` or `key` (other shared components, OverflowMenu/WorklistFilters, use `key`; ~77
  // select/radio arrays elsewhere use `value` — three conventions, nothing used to guard against
  // picking the wrong one for THIS component). With `id: undefined` on every item, every tab
  // rendered with the same DOM id, never showed aria-selected, was unreachable by keyboard, and
  // crashed the whole page on the first click — undetected for ~2 weeks because the page had no
  // component test and was excluded from e2e. This turns that into an immediate, loud dev-console
  // failure at first render instead of a silent, delayed crash on click.
  useEffect(() => {
    if (!import.meta.env.DEV) return;

    const missing = items.filter((item) => item.id == null);
    if (missing.length > 0) {
      console.error(
        `Tabs: ${missing.length} of ${items.length} item(s) passed to <Tabs items={...}> ` +
        `(idPrefix="${idPrefix}") are missing an \`id\`: ${missing.map((item) => JSON.stringify(item.label ?? item)).join(', ')}. ` +
        'Tabs reads item.id for onClick/onChange/selected-state/keyboard navigation — not `value` ' +
        'or `key`. Every item without an id will render id: undefined, share one DOM id with every ' +
        'other such item, never show as selected, be unreachable by keyboard, and crash on click.',
      );
    }

    const seenIds = new Set();
    const duplicateIds = new Set();
    for (const item of items) {
      if (item.id == null) continue;
      if (seenIds.has(item.id)) duplicateIds.add(item.id);
      seenIds.add(item.id);
    }
    if (duplicateIds.size > 0) {
      console.error(
        `Tabs: duplicate id(s) [${[...duplicateIds].join(', ')}] in items passed to <Tabs items={...}> ` +
        `(idPrefix="${idPrefix}"). Each item's id must be unique — duplicates break the DOM key, ` +
        'aria-controls, and selected-state matching the same way a missing id does.',
      );
    }
  }, [items, idPrefix]);

  const updateScrollAffordance = useCallback(() => {
    const el = listRef.current;
    if (!el) return;
    setCanScrollLeft(el.scrollLeft > 1);
    setCanScrollRight(el.scrollLeft + el.clientWidth < el.scrollWidth - 1);
  }, []);

  const scrollTabIntoView = useCallback((tab) => {
    const list = listRef.current;
    if (!list || !tab) return;
    tab.scrollIntoView?.({ inline: 'nearest', block: 'nearest', behavior: 'instant' });

    const styles = getComputedStyle(list);
    const paddingLeft = Number.parseFloat(styles.paddingLeft) || 0;
    const paddingRight = Number.parseFloat(styles.paddingRight) || 0;
    const edgePadding = 8;
    const visibleLeft = list.scrollLeft + paddingLeft + edgePadding;
    const visibleRight = list.scrollLeft + list.clientWidth - paddingRight - edgePadding;
    const tabLeft = tab.offsetLeft;
    const tabRight = tab.offsetLeft + tab.offsetWidth;

    if (tabLeft < visibleLeft) {
      list.scrollLeft = tabLeft - paddingLeft - edgePadding;
    } else if (tabRight > visibleRight) {
      list.scrollLeft = tabRight - list.clientWidth + paddingRight + edgePadding;
    }
    updateScrollAffordance();
  }, [updateScrollAffordance]);

  useEffect(() => {
    const el = listRef.current;
    if (!el) return undefined;

    updateScrollAffordance();
    el.addEventListener('scroll', updateScrollAffordance, { passive: true });
    window.addEventListener('resize', updateScrollAffordance);
    return () => {
      el.removeEventListener('scroll', updateScrollAffordance);
      window.removeEventListener('resize', updateScrollAffordance);
    };
  }, [items, updateScrollAffordance]);

  useEffect(() => {
    const selectedTab = listRef.current?.querySelector('[role="tab"][aria-selected="true"]');
    scrollTabIntoView(selectedTab);
  }, [items, value, scrollTabIntoView]);

  function focusTabAt(index) {
    const tabs = listRef.current?.querySelectorAll('[role="tab"]');
    if (!tabs || !tabs.length) return;
    const bounded = (index + tabs.length) % tabs.length;
    tabs[bounded].focus();
    scrollTabIntoView(tabs[bounded]);
    onChange(items[bounded].id);
  }

  function handleKeyDown(event) {
    const current = items.findIndex((item) => item.id === value);
    if (current < 0) return;
    switch (event.key) {
      case 'ArrowRight': event.preventDefault(); focusTabAt(current + 1); break;
      case 'ArrowLeft':  event.preventDefault(); focusTabAt(current - 1); break;
      case 'Home':       event.preventDefault(); focusTabAt(0); break;
      case 'End':        event.preventDefault(); focusTabAt(items.length - 1); break;
      default: break;
    }
  }

  return (
    <div
      className={`relative min-w-0 -mx-4 sm:mx-0 ${onSurface ? '[--tabs-fade-from:var(--color-surface)]' : '[--tabs-fade-from:var(--color-bg)]'}`}
    >
      <div
        ref={listRef}
        role="tablist"
        aria-label={ariaLabel}
        // Horizontal scroll rather than wrap: a wrapped tablist on a phone
        // reads as two rows of unrelated buttons. Matching padding lets the
        // row bleed to the screen edge, `scroll-px-*` keeps focused tabs clear
        // of that edge, and the overlays below signal hidden tabs.
        className="flex gap-1 overflow-x-auto scroll-smooth scroll-px-1 border-b border-border px-4 py-1 [scrollbar-width:none] sm:px-0 [&::-webkit-scrollbar]:hidden"
      >
        {items.map((item) => {
          const selected = item.id === value;
          return (
            <button
              key={item.id}
              type="button"
              role="tab"
              id={`${idPrefix}-tab-${item.id}`}
              aria-selected={selected}
              aria-controls={`${idPrefix}-panel-${item.id}`}
              tabIndex={selected ? 0 : -1}
              onClick={() => onChange(item.id)}
              // Arrow-key handling sits on the tabs themselves, not the
              // tablist: the roving tabindex means the tablist container is
              // never the focus target, so a handler there would only fire by
              // bubbling — and `jsx-a11y/interactive-supports-focus` is right to
              // reject a keyboard handler on an unfocusable interactive role.
              onKeyDown={handleKeyDown}
              className={[
                // This project ships no Tailwind preflight, so a bare <button>
                // keeps the UA's own `2px outset` border and grey face — measured
                // rgb(239,239,239) with 2px on all four sides, which rendered the
                // tablist as a row of boxes rather than tabs. Reset explicitly;
                // `border-x-0 border-t-0` before `border-b-2` so only the
                // underline survives whatever order Tailwind emits.
                'appearance-none bg-transparent border-x-0 border-t-0 border-solid',
                'flex shrink-0 items-center gap-2 whitespace-nowrap border-b-2 px-3 py-2.5 text-sm transition-colors',
                'focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-[-2px] focus-visible:outline-primary',
                selected
                  ? 'border-primary font-extrabold text-primary'
                  : 'border-transparent font-bold text-text-muted hover:border-border hover:text-text-secondary',
              ].join(' ')}
            >
              <span className="flex flex-col items-start leading-tight">
                <span>{item.label}</span>
                {item.helper ? (
                  <span className="text-2xs font-normal text-text-muted" aria-hidden="true">{item.helper}</span>
                ) : null}
              </span>
              {item.badge != null ? (
                <span
                  className="rounded-full bg-surface-subtle px-1.5 py-0.5 text-2xs font-extrabold text-text-secondary"
                  // Hidden from the accessible name whenever the caller supplied a proper
                  // `badgeLabel` to say the same thing in words instead (see the badgeLabel doc
                  // above) — otherwise left in-band, unchanged, for a caller that has none.
                  aria-hidden={item.badgeLabel ? 'true' : undefined}
                >
                  {item.badge}
                </span>
              ) : null}
              {item.badge != null && item.badgeLabel ? (
                <span className="sr-only">{item.badgeLabel}</span>
              ) : null}
            </button>
          );
        })}
      </div>
      {canScrollLeft ? (
        <span
          aria-hidden="true"
          data-testid="tabs-scroll-left-affordance"
          className="pointer-events-none absolute inset-y-1 left-0 z-10 flex w-7 items-center bg-gradient-to-r from-[var(--tabs-fade-from)] to-transparent pl-1"
        >
          <Icon name="chevronLeft" size={13} className="text-text-muted" />
        </span>
      ) : null}
      {canScrollRight ? (
        <span
          aria-hidden="true"
          data-testid="tabs-scroll-right-affordance"
          className="pointer-events-none absolute inset-y-1 right-0 z-10 flex w-7 items-center justify-end bg-gradient-to-l from-[var(--tabs-fade-from)] to-transparent pr-1"
        >
          <Icon name="chevronRight" size={13} className="text-text-muted" />
        </span>
      ) : null}
    </div>
  );
}

/**
 * The panel half of the pair. Kept mounted-only-when-active on purpose: these
 * panels hold live forms and query-driven sections, and rendering all five at
 * once is exactly the 4,000px page this IA exists to retire.
 */
export function TabPanel({ id, idPrefix, active, children }) {
  if (!active) return null;
  return (
    <div
      role="tabpanel"
      id={`${idPrefix}-panel-${id}`}
      aria-labelledby={`${idPrefix}-tab-${id}`}
      tabIndex={0}
      className="flex flex-col gap-4 pt-4 focus-visible:outline-none"
    >
      {children}
    </div>
  );
}
