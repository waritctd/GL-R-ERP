import { useState } from 'react';
import { FormField } from '../../components/common/FormField.jsx';
import { DesignerPicker } from './DesignerPicker.jsx';
import { ORDERED_BY_EMAIL_MAX, ORDERED_BY_PHONE_MAX, orderedByEmailError } from './quotationMeta.js';

/**
 * "ไม่แสดงชื่อโครงการในใบเสนอราคา" (sales feedback #5, 2026-10-06, V192.1).
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
export function ProjectNamePrintToggle({ id, checked, onChange }) {
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

// The one line that tells the rep these two print when filled (a blank one prints nothing). Said
// once, under the pair, and tied to both inputs.
const ORDERED_BY_CONTACT_HINT = 'Phone และ Email พิมพ์ในใบเสนอราคาเมื่อกรอก';

/**
 * ผู้ติดต่อ's Phone and Email (V192.3) — owner ruling 2026-10-13 (reversing 2026-10-06's "sales record
 * only, not in the pdf"): Phone and Email print on the document's customer contact line when filled
 * and are the only phone/email that line prints (the customer-master contact's are not printed); a
 * blank one prints nothing. Rendered directly under the ผู้ติดต่อ name field on both entry paths;
 * one component for both, for the reason ProjectNamePrintToggle above states. `idSuffix` is
 * "Inline" or "Card", the same pair of suffixes the name field's own two ids carry.
 *
 * The labels are the English words the admin team asked for. Neither field is required.
 *
 * Phone is a plain text box on purpose: an extension or a second number is normal
 * ("02-123-4567 ต่อ 12"), and a telephone keypad cannot type one.
 *
 * Email is checked for its shape (quotationMeta#orderedByEmailError, the shape the server refuses)
 * and the message appears once the rep has LEFT the field, then stays live until the address is
 * right — not from the first keystroke, when every address is still malformed. Not `type="email"`:
 * that brings the browser's own validation bubble and silently rewrites what was typed.
 */
export function OrderedByContactFields({ terms, onChange, idSuffix }) {
  const phoneId = `orderedByPhone${idSuffix}`;
  const emailId = `orderedByEmail${idSuffix}`;
  const hintId = `orderedByContactHint${idSuffix}`;
  const [emailLeft, setEmailLeft] = useState(false);
  const emailError = emailLeft ? orderedByEmailError(terms.orderedByEmail) : undefined;

  return (
    <div className="col-span-full grid gap-[7px]">
      <div className="grid grid-cols-2 gap-3 mobile:grid-cols-1">
        <FormField label="Phone" htmlFor={phoneId}>
          <input
            id={phoneId}
            autoComplete="off"
            maxLength={ORDERED_BY_PHONE_MAX}
            aria-describedby={hintId}
            value={terms.orderedByPhone}
            onChange={(e) => onChange({ orderedByPhone: e.target.value })}
          />
        </FormField>
        <FormField label="Email" htmlFor={emailId} error={emailError}>
          <input
            id={emailId}
            inputMode="email"
            autoCapitalize="none"
            autoCorrect="off"
            spellCheck={false}
            autoComplete="off"
            maxLength={ORDERED_BY_EMAIL_MAX}
            aria-describedby={hintId}
            value={terms.orderedByEmail}
            onChange={(e) => onChange({ orderedByEmail: e.target.value })}
            onBlur={() => setEmailLeft(true)}
          />
        </FormField>
      </div>
      {/* The same type as FormField's own hint line, so it reads as one. */}
      <p id={hintId} className="m-0 text-xs font-medium text-text-muted">{ORDERED_BY_CONTACT_HINT}</p>
    </div>
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
export function QuotationDealFields({ terms, onChange, idPrefix = 'quotation', showToast }) {
  return (
    <div className="col-span-full grid grid-cols-2 gap-3 mobile:grid-cols-1">
      <DesignerPicker
        value={terms.unitCode}
        onSelectCode={(code) => onChange({ unitCode: code })}
        idPrefix={`${idPrefix}-designer-picker`}
        label="หน่วยงาน / ผู้ออกแบบ"
        showToast={showToast}
      />
    </div>
  );
}
