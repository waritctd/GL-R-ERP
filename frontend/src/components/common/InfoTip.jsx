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
    <span className="info-tip relative inline-flex items-center ml-1">
      <button
        type="button"
        // Tailwind port of `.info-tip-trigger` + its `::before` hit-slop
        // (styles.css, retired). The VISIBLE badge stays a small 16px circle
        // deliberately -- there are ~11 of these in the payroll detail panel
        // alone, and a 44px glyph on every field label would be visual noise the
        // "?" hint doesn't warrant. `relative` anchors the invisible `before:`
        // pseudo-element, which is what actually grows the touch/click target to
        // >=44x44 (16 + 14 + 14) without changing what's drawn. A pseudo-element's
        // rendered area is not a separate event target, so a click anywhere inside
        // it dispatches to this <button> with no extra JS.
        className={[
          'info-tip-trigger relative w-4 h-4 p-0 inline-flex items-center justify-center',
          'rounded-pill border border-solid border-border-strong bg-surface-subtle text-text-muted',
          'text-2xs font-bold leading-none',
          "before:content-[''] before:absolute before:inset-[-14px]",
          'hover:bg-info-bg-alt hover:text-info-dark hover:border-info',
          'focus-visible:outline-none focus-visible:shadow-[var(--shadow-focus-ring)]',
        ].join(' ')}
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
        // Port of `.info-tip-bubble`, including its `[hidden]` rule: the UA's
        // own `display: none` for `[hidden]` is weak, so the stylesheet always
        // restated it. `[&[hidden]]:hidden` is that restatement as a utility.
        className={[
          'info-tip-bubble absolute bottom-[calc(100%+6px)] left-1/2 -translate-x-1/2 z-[100]',
          'w-max max-w-[240px] px-3 py-2 rounded-md',
          'bg-text text-surface text-xs/[inherit] font-medium shadow-md pointer-events-none',
          '[&[hidden]]:hidden',
        ].join(' ')}
        hidden={!visible}
      >
        {text}
      </span>
    </span>
  );
}
