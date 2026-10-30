import { Icon } from '../../components/common/Icon.jsx';

/** DESIGN.md §11/§19 — "Reduced motion is not optional." An explicit `behavior: 'smooth'` passed to
 * `scrollIntoView` is NOT overridden by a CSS `scroll-behavior`, so the preference is read here.
 * Guarded because jsdom has no `matchMedia` at all. */
function prefersReducedMotion() {
  return typeof window !== 'undefined'
    && typeof window.matchMedia === 'function'
    && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
}

/** Moves focus to a field by id — a checklist entry's field, and (sales feedback #3, 2026-10-06)
 * the row or ตำแหน่ง a "เพิ่ม…" action has just added in QuotationEditorPage. `scrollIntoView`
 * first so the field is on screen before the focus ring lands on it; optional-chained because
 * jsdom does not implement it. */
export function focusQuotationField(targetId) {
  if (!targetId || typeof document === 'undefined') return false;
  const element = document.getElementById(targetId);
  if (!element) return false;
  element.scrollIntoView?.({ block: 'center', behavior: prefersReducedMotion() ? 'auto' : 'smooth' });
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
        className={`flex min-h-8 w-full items-center gap-2 rounded border-0 bg-transparent px-1 text-left text-xs mobile:min-h-[44px] hover:underline ${tone}`}
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
 *   - ต้องกรอกก่อนส่งขออนุมัติ (submitBlocking): บันทึกร่างได้ แต่ส่งขออนุมัติไม่ได้จนกว่าจะกรอก.
 *   - ควรกรอก (warning): what the document prints but no rule requires. Never blocking.
 * Each entry with a field moves focus to it. Rendered near the top of the editor so it is seen
 * before ส่งขออนุมัติ, which lives in the page header.
 */
export function QuotationChecklist({ entries }) {
  const blocking = entries.filter((entry) => entry.blocking);
  const submitBlocking = entries.filter((entry) => !entry.blocking && entry.submitBlocking);
  const warnings = entries.filter((entry) => !entry.blocking && !entry.submitBlocking);
  if (blocking.length === 0 && submitBlocking.length === 0 && warnings.length === 0) {
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
      className="grid gap-3 rounded-md border border-warning-border bg-warning-bg-soft p-3.5"
      data-testid="quotation-checklist"
    >
      <strong className="text-sm text-text">ข้อมูลที่ยังไม่ครบ</strong>
      {blocking.length ? (
        // role="alert": these are the reasons the save/submit buttons are disabled.
        <div role="alert" className="grid gap-1">
          <p className="m-0 text-2xs font-extrabold text-danger-dark">ต้องกรอกก่อนบันทึกหรือส่งขออนุมัติ</p>
          <ul className="m-0 grid list-none gap-0.5 pl-0" data-testid="checklist-blocking">
            {blocking.map((entry) => <Entry key={`${entry.check}-${entry.message}`} entry={entry} tone="text-danger-dark" />)}
          </ul>
        </div>
      ) : null}
      {submitBlocking.length ? (
        <div className="grid gap-1">
          <p className="m-0 text-2xs font-extrabold text-danger-dark">ต้องกรอกก่อนส่งขออนุมัติ — ยังบันทึกร่างได้</p>
          <ul className="m-0 grid list-none gap-0.5 pl-0" data-testid="checklist-submit-blocking">
            {submitBlocking.map((entry) => <Entry key={`${entry.check}-${entry.message}`} entry={entry} tone="text-danger-dark" />)}
          </ul>
        </div>
      ) : null}
      {warnings.length ? (
        <div className="grid gap-1">
          {/* Deliberately NOT "…จะไม่ปรากฏในเอกสาร": the Thai F-SM-002 has no address line at all
              (only the English form prints one), so that promise would be false for the commonest
              document. The data still autofills this customer's next quotation. */}
          <p className="m-0 text-2xs font-extrabold text-warning-dark">ควรกรอก — ไม่บังคับ ยังบันทึกและส่งขออนุมัติได้</p>
          <ul className="m-0 grid list-none gap-0.5 pl-0" data-testid="checklist-warnings">
            {warnings.map((entry) => <Entry key={`${entry.check}-${entry.message}`} entry={entry} tone="text-warning-dark" />)}
          </ul>
        </div>
      ) : null}
    </section>
  );
}
