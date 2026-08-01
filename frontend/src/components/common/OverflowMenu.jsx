import { useEffect, useRef, useState } from 'react';
import { cn } from '../../utils/cn.js';
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
export function OverflowMenu({
  items,
  label = 'การดำเนินการเพิ่มเติม',
  align = 'end',
  triggerLabel,
  triggerIcon = 'moreHorizontal',
  triggerClassName,
}) {
  const [open, setOpen] = useState(false);
  const triggerRef = useRef(null);
  const menuRef = useRef(null);

  useEffect(() => {
    if (!open) return undefined;

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
  }, [open]);

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
      {open ? (
        <div
          ref={menuRef}
          role="menu"
          aria-label={label}
          className={`absolute top-full z-20 mt-1 min-w-[12rem] rounded-lg border border-border bg-surface py-1 shadow-lg ${
            align === 'end' ? 'right-0' : 'left-0'
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
              className={`flex w-full flex-col items-start gap-0.5 px-3 py-2 text-left text-sm font-bold focus-visible:outline-none ${
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
        </div>
      ) : null}
    </div>
  );
}
