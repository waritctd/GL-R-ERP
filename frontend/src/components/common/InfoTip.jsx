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
    <span className="info-tip">
      <button
        type="button"
        className="info-tip-trigger"
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
        className="info-tip-bubble"
        hidden={!visible}
      >
        {text}
      </span>
    </span>
  );
}
