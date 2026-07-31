import { useEffect, useRef } from 'react';

const FOCUSABLE = 'a[href], button:not([disabled]), textarea, input, select, [tabindex]:not([tabindex="-1"])';

// Shared focus-trap + Escape-to-close + focus-restoration behaviour for a TRUE modal dialog -- one
// that steals the whole interaction context until dismissed. Extracted from Modal.jsx (2026-07-31,
// B2 fix) so a second true-dialog surface -- the payroll detail panel's <1280px slide-over overlay
// (PayrollPage.jsx) -- doesn't hand-roll a second, incomplete copy of this logic (which is exactly
// what shipped without Escape/trap/focus-restoration in the first place).
//
// `active` gates ALL of it, and must be false whenever the same DOM node is instead presented as a
// persistent, non-modal side panel (e.g. the payroll panel at >=1280px, beside the table rather than
// over it) -- trapping Tab or closing on Escape would be wrong there, since the rest of the page is
// not actually hidden or inert in that mode.
export function useDialogFocus({ active, containerRef, onClose }) {
  const previouslyFocused = useRef(null);
  const onCloseRef = useRef(onClose);
  // Keep the ref current on every render without re-running the effect below.
  useEffect(() => { onCloseRef.current = onClose; });

  useEffect(() => {
    if (!active) return undefined;

    const container = containerRef.current;
    previouslyFocused.current = document.activeElement;
    const focusables = () => Array.from(container?.querySelectorAll(FOCUSABLE) ?? []);

    const initial = focusables();
    (initial[0] ?? container)?.focus();

    function onKeyDown(event) {
      if (event.key === 'Escape') {
        event.stopPropagation();
        onCloseRef.current?.();
        return;
      }
      if (event.key !== 'Tab') return;
      const items = focusables();
      if (items.length === 0) return;
      const first = items[0];
      const last = items[items.length - 1];
      if (event.shiftKey && document.activeElement === first) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault();
        first.focus();
      }
    }

    document.addEventListener('keydown', onKeyDown);
    return () => {
      document.removeEventListener('keydown', onKeyDown);
      if (previouslyFocused.current instanceof HTMLElement) {
        previouslyFocused.current.focus();
      }
    };
  }, [active, containerRef]);
}
