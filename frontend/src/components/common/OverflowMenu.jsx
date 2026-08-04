import { useLayoutEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { cn } from '../../utils/cn.js';
import { useIsMobile } from '../../hooks/useIsMobile.js';
import { Icon } from './Icon.jsx';

/**
 * Generic "⋯" overflow trigger + menu (ticket-detail IA rebuild Phase 1 —
 * see docs/ui-repair/02-information-architecture/TICKET_INFORMATION_ARCHITECTURE.md,
 * "Action bar (sticky)" / Secondary actions). Proper menu semantics:
 * `button[aria-haspopup="menu"][aria-expanded]` trigger, `role="menu"` panel,
 * `role="menuitem"` items, ArrowDown/ArrowUp (wrapping) plus Home/End cycle
 * focus between items, Escape closes and returns focus to the trigger, and
 * a click outside the menu also closes it. Same Escape/focus-return
 * convention as Modal.jsx, scaled down for a non-modal popover (no focus
 * trap — a menu doesn't own the whole page the way a dialog does).
 *
 * The open panel renders via `createPortal` to `document.body`, positioned
 * from the trigger's live `getBoundingClientRect()` instead of CSS
 * `absolute`. A plain descendant menu got silently clipped by any ancestor
 * with `overflow-hidden` (e.g. TicketDetailPage's rounded sticky-chrome
 * card — a regression from PR #523 that hid every item but the first on
 * `/tickets/:id`). Position is recomputed on open and on every scroll/
 * resize while open, so the menu keeps tracking the trigger the way a DOM
 * descendant would for free.
 *
 * `items`: `{ key, label, icon?, tone?: 'default' | 'danger', onSelect,
 * disabled?, disabledReason?, testId? }[]`. Callers filter out items that
 * shouldn't appear AT ALL themselves (same responsibility split as every
 * other `can.*`-gated button on this page — the menu never decides whether
 * an action exists, only presents what it's handed). `disabled` is a
 * different thing: an item the caller DOES want listed, but whose own
 * precondition isn't met right now (e.g. a stage-advance gate) — it renders
 * present-but-inert with `disabledReason` shown inline, per the IA rule that
 * an unavailable action must say why, never just vanish. `aria-disabled`
 * (not the native `disabled` attribute) is used so the item stays in the
 * roving-focus set below instead of being skipped by keyboard navigation.
 */
// Mirrors the old absolute-positioning classes (`top-full`/`mt-1`,
// `mobile:top-auto mobile:bottom-full mobile:mb-1`, `right-0`/`left-0`) as
// viewport-relative fixed coordinates. `+ 4` reproduces the old `mt-1`/
// `mb-1` (0.25rem) gap. `flipUp` is decided by the caller via `useIsMobile`
// rather than a `matchMedia` call in here, so this stays a plain, testable
// function of its arguments. `documentElement.client*` (not
// `window.inner*`) because a `position: fixed` element's containing block
// is sized excluding the scrollbar; the two only diverge when the document
// itself scrolls, which it doesn't here (`.content-scroll` owns scrolling),
// but the correct one costs nothing.
function computeMenuPosition(triggerEl, { align, flipUp }) {
  const rect = triggerEl.getBoundingClientRect();
  const viewportWidth = document.documentElement.clientWidth;
  const viewportHeight = document.documentElement.clientHeight;
  return {
    ...(flipUp ? { bottom: viewportHeight - rect.top + 4 } : { top: rect.bottom + 4 }),
    ...(align === 'end' ? { right: viewportWidth - rect.right } : { left: rect.left }),
  };
}

export function OverflowMenu({
  items,
  label = 'การดำเนินการเพิ่มเติม',
  align = 'end',
  mobilePlacement = 'down',
  triggerLabel,
  triggerIcon = 'moreHorizontal',
  triggerClassName,
}) {
  const [open, setOpen] = useState(false);
  const triggerRef = useRef(null);
  const menuRef = useRef(null);
  const isMobile = useIsMobile();

  // Position lives in state, computed from the trigger's live rect — not
  // read from `triggerRef.current` during render (React refs must only be
  // read in effects/handlers). `useLayoutEffect` (not `useEffect`) so the
  // first paint after opening already has the right coordinates instead of
  // flashing at (0,0) for a frame. As a portaled `position: fixed` node the
  // menu no longer scrolls with the trigger for free the way a plain
  // `absolute` descendant would, so this also re-measures on every scroll
  // (capture: true catches scroll on nested containers too, e.g. a table's
  // own overflow-x wrapper, not just window) and resize while open.
  const [menuPosition, setMenuPosition] = useState(null);
  const flipUp = mobilePlacement === 'up' && isMobile;
  useLayoutEffect(() => {
    if (!open) {
      setMenuPosition(null);
      return undefined;
    }
    function reposition() {
      if (!triggerRef.current) return;
      setMenuPosition(computeMenuPosition(triggerRef.current, { align, flipUp }));
    }
    reposition();
    window.addEventListener('scroll', reposition, true);
    window.addEventListener('resize', reposition);
    return () => {
      window.removeEventListener('scroll', reposition, true);
      window.removeEventListener('resize', reposition);
    };
  }, [open, align, flipUp]);

  // Gated on `menuMounted` (whether the portal has actually committed a menu
  // node), not the raw `open` flag: `open` flips true a render before the
  // position effect above supplies coordinates and the portal mounts, so
  // gating on `open` here raced `menuRef.current` — it was still null when
  // this ran, silently dropping the focus-first-item behaviour. `menuMounted`
  // only flips once per open/close (unlike `menuPosition`, which is a new
  // object on every scroll-driven reposition), so this doesn't re-steal
  // focus back to the first item while the user is arrow-keying around.
  const menuMounted = menuPosition !== null;
  useLayoutEffect(() => {
    if (!menuMounted) return undefined;

    const first = menuRef.current?.querySelector('[role="menuitem"]');
    first?.focus();

    function onKeyDown(event) {
      if (event.key === 'Escape') {
        event.stopPropagation();
        setOpen(false);
        triggerRef.current?.focus();
        return;
      }
      // Tab/Shift+Tab: close the menu and let the browser's own default Tab
      // behaviour carry focus onward — every item below has `tabIndex={-1}`
      // (out of the normal Tab order, reachable only via Arrow/Home/End),
      // so without this the currently-focused item was the only one
      // reachable by Tab at all, and leaving the menu OPEN while focus moved
      // past it into the page behind left an orphaned, still-interactive
      // popover on screen. Not preventDefault()'d — the point is to let Tab
      // do its normal thing, just with the menu no longer in the way.
      if (event.key === 'Tab') {
        setOpen(false);
        return;
      }
      if (!['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key)) return;
      const menuItems = Array.from(menuRef.current?.querySelectorAll('[role="menuitem"]') ?? []);
      if (menuItems.length === 0) return;
      event.preventDefault();
      if (event.key === 'Home') { menuItems[0].focus(); return; }
      if (event.key === 'End') { menuItems[menuItems.length - 1].focus(); return; }
      const currentIndex = menuItems.indexOf(document.activeElement);
      const delta = event.key === 'ArrowDown' ? 1 : -1;
      const nextIndex = currentIndex === -1
        ? (delta === 1 ? 0 : menuItems.length - 1)
        : (currentIndex + delta + menuItems.length) % menuItems.length;
      menuItems[nextIndex].focus();
    }
    function onPointerDown(event) {
      if (menuRef.current?.contains(event.target) || triggerRef.current?.contains(event.target)) return;
      setOpen(false);
    }

    document.addEventListener('keydown', onKeyDown);
    document.addEventListener('mousedown', onPointerDown);
    return () => {
      document.removeEventListener('keydown', onKeyDown);
      document.removeEventListener('mousedown', onPointerDown);
    };
  }, [menuMounted]);

  if (!items || items.length === 0) return null;

  return (
    <div className="relative inline-block">
      <button
        type="button"
        ref={triggerRef}
        className={cn(
          triggerLabel
            ? 'inline-flex min-h-[38px] items-center justify-center gap-[7px] rounded-md border-[1.5px] border-solid border-border-input bg-surface px-[13px] py-0 font-bold text-icon-muted mobile:min-h-[44px]'
            : 'icon-button',
          triggerClassName,
        )}
        aria-haspopup="menu"
        aria-expanded={open}
        aria-label={label}
        title={label}
        onClick={() => setOpen((v) => !v)}
      >
        <Icon name={triggerIcon} size={18} />
        {triggerLabel ? <span>{triggerLabel}</span> : null}
      </button>
      {open && menuPosition ? createPortal(
        <div
          ref={menuRef}
          role="menu"
          aria-label={label}
          style={{ position: 'fixed', ...menuPosition }}
          // `max-w`: shrink-to-fit now resolves against the viewport (fixed
          // positioning, see computeMenuPosition's doc comment) instead of
          // the old narrow `relative inline-block` trigger wrapper, so an
          // unlucky long label/disabledReason could otherwise balloon the
          // panel toward full viewport width instead of wrapping the way it
          // already did under the 12rem floor. `calc(100vw-2rem)` is the
          // floor's own floor, so a narrow phone still gets a 1rem margin on
          // each side rather than edge-to-edge or horizontal overflow.
          className={`z-20 min-w-[12rem] max-w-[min(20rem,calc(100vw-2rem))] rounded-lg border border-border bg-surface py-1 shadow-lg ${
            mobilePlacement === 'up' ? 'mobile:max-h-[calc(100dvh-7rem)] mobile:overflow-y-auto' : ''
          }`}
        >
          {items.map((item) => (
            <button
              key={item.key}
              type="button"
              role="menuitem"
              // Roving tabindex: items are reachable only via ArrowUp/
              // ArrowDown/Home/End (handled above through `.focus()`, which
              // works regardless of tabIndex on a natively-focusable
              // <button>) — tabIndex={-1} keeps every item OUT of the normal
              // page Tab order, so Tab from a focused item skips straight to
              // whatever follows this component in the DOM instead of
              // stopping on each item in turn.
              tabIndex={-1}
              data-testid={item.testId}
              aria-disabled={item.disabled || undefined}
              title={item.disabled ? item.disabledReason : undefined}
              className={`flex w-full flex-col items-start justify-center gap-0.5 px-3 py-2 text-left text-sm font-bold mobile:min-h-[44px] mobile:px-4 mobile:py-3 focus-visible:outline-none ${
                item.disabled
                  ? 'cursor-not-allowed opacity-60'
                  : 'hover:bg-surface-hover focus-visible:bg-surface-hover'
              } ${item.tone === 'danger' ? 'text-danger' : 'text-text'}`}
              onClick={() => {
                if (item.disabled) return;
                setOpen(false);
                item.onSelect();
              }}
            >
              <span className="flex items-center gap-2">
                {item.icon ? <Icon name={item.icon} size={14} /> : null}
                {item.label}
              </span>
              {item.disabled && item.disabledReason ? (
                <span className="pl-[22px] text-2xs font-normal normal-case text-text-muted">
                  {item.disabledReason}
                </span>
              ) : null}
            </button>
          ))}
        </div>,
        document.body,
      ) : null}
    </div>
  );
}
