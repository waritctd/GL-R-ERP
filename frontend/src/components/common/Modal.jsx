import { useRef } from 'react';
import { Icon } from './Icon.jsx';
import { useDialogFocus } from '../../hooks/useDialogFocus.js';
import { Button } from './Button.jsx';
import { cn } from '../../utils/cn.js';

// Sizes carry their own width/height so exactly one w-[...]/max-h-[...] pair is ever present on
// the panel — `.modal-panel`'s width/height are now Tailwind utilities too (Group C primitives
// port), so a base pair PLUS an `lg` override pair would both land in the same `@layer utilities`
// and rely on Tailwind's internal generated-stylesheet order (not className string order) to
// arbitrate, which is unverified. One size's classes on the element at a time sidesteps that.
const SIZE_CLASSNAMES = {
  md: 'w-[min(720px,100%)] max-h-[min(760px,calc(100dvh-40px))]',
  lg: 'w-[min(1040px,100%)] max-h-[calc(100dvh-40px)]',
};

export function Modal({ title, subtitle, children, footer, onClose, testId, size = 'md' }) {
  const panelRef = useRef(null);
  // Modal is only ever mounted while shown, so the trap is unconditionally active for its lifetime
  // (see useDialogFocus.js -- the payroll detail panel is the other consumer, and gates this on its
  // >=1280px side-by-side vs <1280px overlay mode instead).
  useDialogFocus({ active: true, containerRef: panelRef, onClose });

  return (
    <div
      className="modal-backdrop fixed inset-0 z-50 grid place-items-center p-5 bg-overlay"
      role="presentation"
      onMouseDown={onClose}
    >
      {/* eslint-disable-next-line jsx-a11y/no-noninteractive-element-interactions -- dialog only stops backdrop click-through; it is not an interactive control */}
      <section
        ref={panelRef}
        className={cn(
          'modal-panel flex flex-col overflow-hidden rounded-md bg-surface shadow-[var(--shadow-lg-heavy)]',
          SIZE_CLASSNAMES[size] || SIZE_CLASSNAMES.md,
        )}
        role="dialog"
        aria-modal="true"
        aria-label={title}
        tabIndex={-1}
        onMouseDown={(event) => event.stopPropagation()}
        data-testid={testId}
      >
        <header className="modal-header flex items-center justify-between gap-4 px-5 py-[18px] border-b border-border">
          <div>
            <h2 className="m-0">{title}</h2>
            {subtitle ? <p className="m-0 text-text-muted text-[length:var(--text-sm)]">{subtitle}</p> : null}
          </div>
          <Button variant="icon" onClick={onClose} title="ปิด" aria-label="ปิด">
            <Icon name="close" />
          </Button>
        </header>
        <div className="modal-body overflow-auto p-5">{children}</div>
        {footer ? (
          <footer className="modal-footer flex items-center justify-end gap-4 px-5 py-[18px] border-t border-border">
            {footer}
          </footer>
        ) : null}
      </section>
    </div>
  );
}
