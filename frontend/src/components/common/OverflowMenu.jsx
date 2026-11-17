import { useEffect, useLayoutEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { cn } from '../../utils/cn.js';
import { useIsMobile } from '../../hooks/useIsMobile.js';
import { Button } from './Button.jsx';
import { Icon } from './Icon.jsx';

/** Generic "⋯" overflow trigger + menu (ticket-detail IA rebuild Phase 1 — see
 * docs/ui-repair/02-information-architecture/TICKET_INFORMATION_ARCHITECTURE.md, "Action bar (sticky)" /
 * Secondary actions). */
// Mirrors the old absolute-positioning classes (`top-full`/`mt-1`, `mobile:top-auto mobile:bottom-full
// mobile:mb-1`, `right-0`/`left-0`) as viewport-relative fixed coordinates. `+ 4` reproduces the old `mt-1`/
// `mb-1` (0.25rem) gap.
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
  // Extra attributes for the trigger button (e.g. a data-testid / data-action e2e hook); spread first,
  // so they can never override the menu's own handlers or ARIA.
  triggerProps,
  // Told when the menu opens and closes (also closed on unmount) — the deal page holds its sticky
  // header still while one is open, since condensing it remounts the trigger and closes the menu.
  onOpenChange,
}) {
  const [open, setOpen] = useState(false);
  useEffect(() => {
    if (!open) return undefined;
    onOpenChange?.(true);
    return () => onOpenChange?.(false);
  }, [open, onOpenChange]);
  const triggerRef = useRef(null);
  const menuRef = useRef(null);
  const isMobile = useIsMobile();

  // Position lives in state, computed from the trigger's live rect — not read from `triggerRef.current` during
  // render (React refs must only be read in effects/handlers).
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

  // Gated on `menuMounted`, not the raw `open` flag: `open` flips true a render before the position effect above
  // supplies coordinates and the portal mounts, so gating on `open` here raced `menuRef.current` — it was still
  // null when this ran, silently dropping the focus-first-item behaviour.
  const menuMounted = menuPosition !== null;
  useLayoutEffect(() => {
    if (!menuMounted) return undefined;

    const first = menuRef.current?.querySelector('[role="menuitem"]');
    first?.focus();

    function onKeyDown(event) {
      if (event.key === 'Escape') {
        event.stopPropagation();
        setOpen(false);
        triggerRef.current?.focus({ preventScroll: true });
        return;
      }
      // Tab/Shift+Tab: close the menu and let the browser's own default Tab behaviour carry focus onward. Not
      // preventDefault()'d — the point is to let Tab do its normal thing, just with the menu no longer in the way.
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
      {/* Two distinct render paths, not one <Button> with a conditional variant: the labeled path's classes are a
       * hand-rolled style that predates Button.jsx and isn't built from the icon-button legacy class, so it's left
       * untouched rather than guessed into a variant. */}
      {triggerLabel ? (
        <button
          {...triggerProps}
          type="button"
          ref={triggerRef}
          className={cn(
            'inline-flex min-h-[38px] items-center justify-center gap-[7px] rounded-md border-[1.5px] border-solid border-border-input bg-surface px-[13px] py-0 font-bold text-icon-muted mobile:min-h-[44px]',
            triggerClassName,
          )}
          aria-haspopup="menu"
          aria-expanded={open}
          aria-label={label}
          title={label}
          onClick={() => setOpen((v) => !v)}
        >
          {triggerIcon ? <Icon name={triggerIcon} size={18} /> : null}
          <span className="inline-flex items-center gap-[7px]">{triggerLabel}</span>
        </button>
      ) : (
        <Button
          {...triggerProps}
          type="button"
          ref={triggerRef}
          variant="icon"
          className={triggerClassName}
          aria-haspopup="menu"
          aria-expanded={open}
          aria-label={label}
          title={label}
          onClick={() => setOpen((v) => !v)}
        >
          <Icon name={triggerIcon} size={18} />
        </Button>
      )}
      {open && menuPosition ? createPortal(
        <div
          ref={menuRef}
          role="menu"
          aria-label={label}
          style={{ position: 'fixed', ...menuPosition }}
          // `max-w`: shrink-to-fit now resolves against the viewport instead of the old narrow `relative inline-block`
          // trigger wrapper, so an unlucky long label/disabledReason could otherwise balloon the panel toward full
          // viewport width instead of wrapping the way it already did under the 12rem floor.
          className={`z-20 min-w-[12rem] max-w-[min(20rem,calc(100vw-2rem))] rounded-lg border border-border bg-surface py-1 shadow-lg ${
            mobilePlacement === 'up' ? 'mobile:max-h-[calc(100dvh-7rem)] mobile:overflow-y-auto' : ''
          }`}
        >
          {items.map((item) => (
            <button
              key={item.key}
              type="button"
              role="menuitem"
              // Roving tabindex: items are reachable only via ArrowUp/ ArrowDown/Home/End (handled above through `.focus`,
              // which works regardless of tabIndex on a natively-focusable <button>).
              tabIndex={-1}
              data-testid={item.testId}
              aria-disabled={item.disabled || undefined}
              title={item.disabled ? item.disabledReason : undefined}
              className={`flex w-full flex-col items-start justify-center gap-0.5 border-0 bg-transparent px-3 py-2 text-left text-sm font-bold mobile:min-h-[44px] mobile:px-4 mobile:py-3 focus-visible:outline-none ${
                item.disabled
                  ? 'cursor-not-allowed opacity-60'
                  : 'hover:bg-surface-hover focus-visible:bg-surface-hover'
              } ${item.tone === 'danger' ? 'text-danger' : 'text-text'}`}
              onClick={() => {
                if (item.disabled) return;
                setOpen(false);
                // Focus back on the trigger first, so a dialog the item opens returns focus there. It
                // was just clicked, so it is on screen: no scroll (a padded scroller would jump).
                triggerRef.current?.focus({ preventScroll: true });
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
