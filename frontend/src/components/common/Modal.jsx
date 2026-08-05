import { useRef } from 'react';
import { Icon } from './Icon.jsx';
import { useDialogFocus } from '../../hooks/useDialogFocus.js';
import { Button } from './Button.jsx';

// 'lg' widens/heightens the panel via Tailwind utilities layered AFTER .modal-panel's own rules
// (frontend/src/index.css: `@layer theme, legacy, utilities` — styles.css imports into `legacy`,
// so a `utilities`-layer class always beats it regardless of selector specificity). No new CSS
// rule needed; this is why the size stays a plain className swap instead of a `.modal-panel--lg`
// selector in styles.css.
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
    <div className="modal-backdrop" role="presentation" onMouseDown={onClose}>
      {/* eslint-disable-next-line jsx-a11y/no-noninteractive-element-interactions -- dialog only stops backdrop click-through; it is not an interactive control */}
      <section
        ref={panelRef}
        className={`modal-panel ${SIZE_CLASSNAMES[size] || ''}`.trim()}
        role="dialog"
        aria-modal="true"
        aria-label={title}
        tabIndex={-1}
        onMouseDown={(event) => event.stopPropagation()}
        data-testid={testId}
      >
        <header className="modal-header">
          <div>
            <h2>{title}</h2>
            {subtitle ? <p>{subtitle}</p> : null}
          </div>
          <Button variant="icon" onClick={onClose} title="ปิด" aria-label="ปิด">
            <Icon name="close" />
          </Button>
        </header>
        <div className="modal-body">{children}</div>
        {footer ? <footer className="modal-footer">{footer}</footer> : null}
      </section>
    </div>
  );
}
