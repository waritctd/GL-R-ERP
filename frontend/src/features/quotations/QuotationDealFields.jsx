import { DesignerPicker } from './DesignerPicker.jsx';

/**
 * "ไม่แสดงชื่อโครงการในใบเสนอราคา" (sales feedback #5, 2026-10-06, V200).
 *
 * Sales asked for โครงการ to be optional. The ruling was the other half of that: the project stays
 * REQUIRED on the deal ("กรณียังไม่ทราบชัดเจน เป็นชื่อกลางๆได้ เช่น บ้านเอกมัย ซอย 5") and the rep
 * chooses, per document, whether it PRINTS ("สร้างทางเลือก แสดง หรือไม่แสดง") — so a placeholder
 * name typed to get past the required field does not have to go out to the customer.
 *
 * Worded as an opt-OUT and unticked by default, the same voice as "ไม่รับมัดจำ" on this page: an
 * all-unticked form is the standard document. The second line says what ticking does NOT do —
 * "hide" is easy to read as "delete", and the name is still what the deal and the register show.
 *
 * One component for both entry paths (a new deal's card and an existing draft's), for the reason
 * QuotationDealFields below states: so the two cannot drift apart. Same markup as the page's other
 * tick boxes — the whole row is the label, and it grows to the 44px touch floor on a phone.
 */
export function ProjectNamePrintToggle({ id, checked, onChange, disabled = false }) {
  return (
    <label
      htmlFor={id}
      className="flex min-h-[38px] cursor-pointer items-start gap-2 rounded-md px-1 py-1 mobile:min-h-[44px] mobile:py-2"
    >
      <input
        id={id}
        type="checkbox"
        className="mt-0.5 h-4 w-4 shrink-0"
        checked={!!checked}
        disabled={disabled}
        onChange={(e) => onChange(e.target.checked)}
      />
      <span className="text-xs">
        ไม่แสดงชื่อโครงการในใบเสนอราคา
        <span className="mt-0.5 block text-2xs text-text-muted">
          ชื่อโครงการยังอยู่ในดีล แต่จะไม่พิมพ์ลงเอกสารที่ส่งให้ลูกค้า
        </span>
      </span>
    </label>
  );
}

/**
 * The field that identifies the quotation's designer / unit (หน่วยงาน / ผู้ออกแบบ).
 * Kept shared between standalone creation and an existing deal/draft so the two
 * entry paths cannot drift apart visually or in how they update quotation terms.
 *
 * It used to hold a second field, ฝ่าย, beside this one. That is gone (sales feedback Admin 1d,
 * owner 2026-10-06: "remove ฝ่าย field completely" — reps filled it wrongly, some with their own
 * name): no input here, nothing printed on the document, and QuotationEditorPage sends
 * `deptCode: null` on every save so a value an older draft still carries is cleared rather than
 * kept where nobody can see or correct it. The two-column grid stays so the picker keeps the
 * width it had beside ฝ่าย instead of stretching across the whole card.
 */
export function QuotationDealFields({ terms, onChange, disabled = false, idPrefix = 'quotation', showToast }) {
  const setTerm = (key, value) => onChange({ [key]: value });

  return (
    <div className="col-span-full grid grid-cols-2 gap-3 mobile:grid-cols-1">
      <DesignerPicker
        value={terms.unitCode}
        disabled={disabled}
        onSelectCode={(code) => setTerm('unitCode', code)}
        idPrefix={`${idPrefix}-designer-picker`}
        label="หน่วยงาน / ผู้ออกแบบ"
        showToast={showToast}
      />
    </div>
  );
}
