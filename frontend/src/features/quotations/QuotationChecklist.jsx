import { Icon } from '../../components/common/Icon.jsx';

/** Moves focus to a checklist entry's field. `scrollIntoView` first so the field is on screen
 * before the focus ring lands on it; optional-chained because jsdom does not implement it. */
export function focusQuotationField(targetId) {
  if (!targetId || typeof document === 'undefined') return false;
  const element = document.getElementById(targetId);
  if (!element) return false;
  element.scrollIntoView?.({ block: 'center', behavior: 'smooth' });
  element.focus({ preventScroll: true });
  return true;
}

function Entry({ entry, tone }) {
  const text = <span className="min-w-0">{entry.message}</span>;
  if (!entry.targetId) {
    return <li className="flex min-h-8 items-center gap-2 px-1 text-xs">{text}</li>;
  }
  return (
    <li>
      {/* A button, not a link: it moves focus within the page. `min-h-8` / `mobile:min-h-[44px]`
          keeps each entry a real touch target on a phone. */}
      <button
        type="button"
        className={`flex min-h-8 w-full items-center gap-2 rounded px-1 text-left text-xs mobile:min-h-[44px] hover:underline ${tone}`}
        onClick={() => focusQuotationField(entry.targetId)}
      >
        <Icon name="chevronRight" size={12} className="shrink-0" />
        {text}
      </button>
    </li>
  );
}

/**
 * "ข้อมูลที่ยังไม่ครบ" — owner, 2026-09-11: "validate with the quotation which field have not been
 * filled yet". Two lists from quotationMeta#buildQuotationChecklist:
 *   - ต้องกรอก (blocking): what the backend already refuses. These disable บันทึกร่าง/ส่งขออนุมัติ.
 *   - ควรกรอก (warning): what the document prints but no rule requires. Never blocking.
 * Each entry with a field moves focus to it. Rendered near the top of the editor so it is seen
 * before ส่งขออนุมัติ, which lives in the page header.
 */
export function QuotationChecklist({ entries }) {
  const blocking = entries.filter((entry) => entry.blocking);
  const warnings = entries.filter((entry) => !entry.blocking);
  if (blocking.length === 0 && warnings.length === 0) {
    return (
      <p role="status" className="m-0 flex items-center gap-1.5 rounded-md border border-success-border bg-success/10 px-3.5 py-2.5 text-xs font-bold text-success">
        <Icon name="badgeCheck" size={14} />
        ข้อมูลในเอกสารครบแล้ว
      </p>
    );
  }
  return (
    <section
      aria-label="ข้อมูลที่ยังไม่ครบ"
      className="grid gap-3 rounded-md border border-warning-border bg-warning/10 p-3.5"
      data-testid="quotation-checklist"
    >
      <strong className="text-sm text-text">ข้อมูลที่ยังไม่ครบ</strong>
      {blocking.length ? (
        // role="alert": these are the reasons the save/submit buttons are disabled.
        <div role="alert" className="grid gap-1">
          <p className="m-0 text-2xs font-extrabold text-danger">ต้องกรอกก่อนบันทึกหรือส่งขออนุมัติ</p>
          <ul className="m-0 grid list-none gap-0.5 pl-0" data-testid="checklist-blocking">
            {blocking.map((entry) => <Entry key={`${entry.check}-${entry.message}`} entry={entry} tone="text-danger" />)}
          </ul>
        </div>
      ) : null}
      {warnings.length ? (
        <div className="grid gap-1">
          {/* Deliberately NOT "…จะไม่ปรากฏในเอกสาร": the Thai F-SM-002 has no address line at all
              (only the English form prints one), so that promise would be false for the commonest
              document. The data still autofills this customer's next quotation. */}
          <p className="m-0 text-2xs font-extrabold text-warning">ควรกรอก — ไม่บังคับ ยังบันทึกและส่งขออนุมัติได้</p>
          <ul className="m-0 grid list-none gap-0.5 pl-0" data-testid="checklist-warnings">
            {warnings.map((entry) => <Entry key={`${entry.check}-${entry.message}`} entry={entry} tone="text-warning" />)}
          </ul>
        </div>
      ) : null}
    </section>
  );
}
