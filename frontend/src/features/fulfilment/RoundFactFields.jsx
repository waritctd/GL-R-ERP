import { ThaiDateInput } from '../../components/common/ThaiDateInput.jsx';

/** One labelled fact input (text or Thai-date), 44px tall so a thumb lands on it. */
export function RoundFactField({ field, value, onChange, idPrefix }) {
  const id = `${idPrefix}-${field.key}`;
  return (
    <div className="flex min-w-0 flex-col gap-1">
      <label htmlFor={id} className="text-xs font-bold text-text-secondary">{field.label}</label>
      {field.type === 'date' ? (
        <ThaiDateInput id={id} className="min-h-11 text-base" value={value ?? ''} onChange={(e) => onChange(e.target.value)} />
      ) : (
        <input id={id} type="text" className="min-h-11 w-full text-base" value={value ?? ''} onChange={(e) => onChange(e.target.value)} />
      )}
    </div>
  );
}

/** The complete-per-invoice tick; unticked, a note is required. */
export function CompletePerInvoiceField({ complete, note, onComplete, onNote, idPrefix }) {
  return (
    <div className="flex flex-col gap-2">
      <div className="flex min-h-11 items-start gap-3 rounded-md border border-border-input px-3 py-2">
        <input id={`${idPrefix}-complete`} type="checkbox" className="mt-1 size-5" checked={complete} onChange={(e) => onComplete(e.target.checked)} />
        <div className="flex flex-col">
          <label htmlFor={`${idPrefix}-complete`} className="cursor-pointer text-sm font-bold">ของเข้าครบตามอินวอยซ์</label>
          <small className="text-text-muted">ถ้าไม่ครบ ให้เอาเครื่องหมายออกแล้วพิมพ์หมายเหตุ</small>
        </div>
      </div>
      {complete ? null : (
        <div className="flex flex-col gap-1">
          <label htmlFor={`${idPrefix}-complete-note`} className="text-xs font-bold text-text-secondary">หมายเหตุ (ของไม่ครบ) *</label>
          <input id={`${idPrefix}-complete-note`} type="text" className="min-h-11 w-full text-base" value={note ?? ''} onChange={(e) => onNote(e.target.value)} />
        </div>
      )}
    </div>
  );
}
