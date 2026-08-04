import { useRef } from 'react';
import { Icon } from './Icon.jsx';
import { useDialogFocus } from '../../hooks/useDialogFocus.js';
import { cn } from '../../utils/cn.js';

// Tailwind port of `.modal-backdrop` / `.modal-panel` / `.modal-header` /
// `.modal-body` / `.modal-footer` (styles.css, retired). The scrim reuses the
// shared `--color-overlay` token rather than repeating its rgba literal.
export const modalBackdropClass = 'modal-backdrop fixed inset-0 z-50 grid place-items-center p-5 bg-overlay';
export const modalPanelClass = [
  'modal-panel w-[min(720px,100%)] max-h-[min(760px,calc(100dvh-40px))]',
  'flex flex-col overflow-hidden rounded-md bg-surface shadow-lg-heavy',
].join(' ');
// Header and footer share a bar shape and differ only in which edge is ruled
// and where the content sits.
const BAR_CLASS = 'flex items-center justify-between gap-4 px-5 py-[18px]';
export const modalHeaderClass = `${BAR_CLASS} border-b border-border`;
export const modalBodyClass = 'modal-body overflow-auto p-5';
export const modalFooterClass = `${BAR_CLASS} justify-end border-t border-border`;

// 'lg' widens/heightens the panel. `cn` (tailwind-merge) is what makes the
// override work now that PANEL_CLASS is itself utilities: a later `w-*` /
// `max-h-*` in the same conflict group replaces the base one, instead of both
// landing on the element and the cascade picking a winner.
const SIZE_CLASSNAMES = {
  md: '',
  lg: 'w-[min(1040px,100%)] max-h-[calc(100dvh-40px)]',
};

export function Modal({ title, subtitle, children, footer, onClose, testId, size = 'md' }) {
  const panelRef = useRef(null);
  // Modal is only ever mounted while shown, so the trap is unconditionally active for its lifetime
  // (see useDialogFocus.js -- the payroll detail panel is the other consumer, and gates this on its
  // >=1280px side-by-side vs <1280px overlay mode instead).
  useDialogFocus({ active: true, containerRef: panelRef, onClose });

  return (
    <div className={modalBackdropClass} role="presentation" onMouseDown={onClose}>
      {/* eslint-disable-next-line jsx-a11y/no-noninteractive-element-interactions -- dialog only stops backdrop click-through; it is not an interactive control */}
      <section
        ref={panelRef}
        className={cn(modalPanelClass, SIZE_CLASSNAMES[size])}
        role="dialog"
        aria-modal="true"
        aria-label={title}
        tabIndex={-1}
        onMouseDown={(event) => event.stopPropagation()}
        data-testid={testId}
      >
        <header className={modalHeaderClass}>
          <div>
            <h2 className="m-0">{title}</h2>
            {subtitle ? <p className="m-0 text-text-muted text-sm/[inherit]">{subtitle}</p> : null}
          </div>
          <button type="button" className="icon-button" onClick={onClose} title="ปิด" aria-label="ปิด">
            <Icon name="close" />
          </button>
        </header>
        <div className={modalBodyClass}>{children}</div>
        {footer ? <footer className={modalFooterClass}>{footer}</footer> : null}
      </section>
    </div>
  );
}
