import { useRef } from 'react';
import { Icon } from './Icon.jsx';
import { useDialogFocus } from '../../hooks/useDialogFocus.js';

export function Modal({ title, subtitle, children, footer, onClose, testId }) {
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
        className="modal-panel"
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
          <button type="button" className="icon-button" onClick={onClose} title="ปิด" aria-label="ปิด">
            <Icon name="close" />
          </button>
        </header>
        <div className="modal-body">{children}</div>
        {footer ? <footer className="modal-footer">{footer}</footer> : null}
      </section>
    </div>
  );
}
