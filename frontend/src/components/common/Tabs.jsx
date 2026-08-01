import { useRef } from 'react';

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
 * `items`: `[{ id, label, helper, badge }]`
 *   - `helper` — an optional short sub-label for context.
 *   - `badge`  — a count (or short string) shown when the tab holds real data.
 *
 * Every tab a caller passes in is fully functional and in the normal tab
 * order — this component has no "reachable but not ready yet" visual state.
 * A tab's own content decides what to show when there's nothing there yet
 * (its own empty state), it is never dimmed at the tab-bar level.
 *
 * Deliberately unstyled beyond layout + state so the caller owns density.
 */
export function Tabs({ items, value, onChange, ariaLabel, idPrefix }) {
  const listRef = useRef(null);

  function focusTabAt(index) {
    const tabs = listRef.current?.querySelectorAll('[role="tab"]');
    if (!tabs || !tabs.length) return;
    const bounded = (index + tabs.length) % tabs.length;
    tabs[bounded].focus();
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
      ref={listRef}
      role="tablist"
      aria-label={ariaLabel}
      // Horizontal scroll rather than wrap: a wrapped tablist on a phone
      // reads as two rows of unrelated buttons. `-mx-*` + matching padding
      // lets the row bleed to the screen edge so it looks scrollable.
      className="-mx-4 flex gap-1 overflow-x-auto border-b border-border px-4 sm:mx-0 sm:px-0"
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
              <span className="rounded-full bg-surface-subtle px-1.5 py-0.5 text-2xs font-extrabold text-text-secondary">
                {item.badge}
              </span>
            ) : null}
          </button>
        );
      })}
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
