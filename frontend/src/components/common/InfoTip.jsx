import { useId, useState } from 'react';

/**
 * Small field-level help tooltip. Renders a circular "?" trigger button next
 * to a field label; the tooltip text shows on hover AND on keyboard focus
 * (not hover-only), and is dismissible with Escape.
 *
 * There is no dedicated info/help glyph in Icon.jsx's registry (see
 * components/common/Icon.jsx), so the trigger uses a plain "?" text glyph
 * rather than importing a new lucide icon.
 *
 * Example:
 *   <label htmlFor="wht-rate">
 *     อัตราหัก ณ ที่จ่าย
 *     <InfoTip label="อัตราหัก ณ ที่จ่าย" text="คำนวณจากยอดก่อนภาษีมูลค่าเพิ่ม" />
 *   </label>
 */
export function InfoTip({ label, text, id }) {
  const [visible, setVisible] = useState(false);
  const autoId = useId();
  const tooltipId = id ?? `${autoId}-tooltip`;

  function hide() {
    setVisible(false);
  }

  function onKeyDown(event) {
    if (event.key === 'Escape') {
      event.stopPropagation();
      hide();
    }
  }

  return (
    <span className="relative inline-flex items-center ml-1">
      <button
        type="button"
        className="relative w-4 h-4 p-0 inline-flex items-center justify-center rounded-pill border border-border-strong bg-surface-subtle text-text-muted text-2xs font-bold leading-none before:content-[''] before:absolute before:inset-[-14px] hover:bg-info-bg-alt hover:text-info-dark hover:border-info focus-visible:outline-none focus-visible:[box-shadow:var(--shadow-focus-ring)]"
        aria-label={`${label} — ข้อมูลเพิ่มเติม`}
        aria-describedby={tooltipId}
        onMouseEnter={() => setVisible(true)}
        onMouseLeave={hide}
        onFocus={() => setVisible(true)}
        onBlur={hide}
        onKeyDown={onKeyDown}
      >
        ?
      </button>
      <span
        id={tooltipId}
        role="tooltip"
        className="info-tip-bubble absolute bottom-[calc(100%+6px)] left-[50%] [transform:translateX(-50%)] z-[100] w-max max-w-[240px] py-2 px-3 rounded-md bg-text text-surface text-[length:var(--text-xs)] font-medium [box-shadow:var(--shadow-md)] pointer-events-none [&[hidden]]:hidden"
        hidden={!visible}
      >
        {text}
      </span>
    </span>
  );
}
