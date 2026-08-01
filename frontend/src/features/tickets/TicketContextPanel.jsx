import { Icon } from '../../components/common/Icon.jsx';
import { formatThaiDate } from '../../utils/format.js';
import { activePricingRequestsSummary } from '../pricingRequests/pricingRequestMeta.js';

const COMMENT_EVENT_KINDS = new Set(['COMMENTED', 'COMMENT']);

function timestamp(value) {
  if (!value) return null;
  const parsed = new Date(value).getTime();
  return Number.isNaN(parsed) ? null : parsed;
}

function FieldRow({ label, value, danger = false }) {
  return (
    <div className="grid grid-cols-[8.25rem_minmax(0,1fr)] gap-3 border-b border-border-subtle py-2 text-xs last:border-b-0">
      <dt className="font-bold text-text-muted">{label}</dt>
      <dd className={`m-0 min-w-0 font-semibold ${danger ? 'text-danger' : 'text-text-secondary'}`}>
        {value || '-'}
      </dd>
    </div>
  );
}

function ContextSection({ title, icon, children }) {
  return (
    <section className="border-t border-border pt-4 first:border-t-0 first:pt-0">
      <div className="mb-3 flex items-center gap-2">
        <Icon name={icon} size={15} className="text-text-muted" />
        <h2 className="m-0 text-sm font-extrabold text-text">{title}</h2>
      </div>
      {children}
    </section>
  );
}

export function TicketContextPanel({
  summary,
  pricingRequests = [],
  latestQuotation = null,
  events = [],
  bannerText = null,
  notesAvailable = false,
  canComment = false,
  noteText = '',
  onNoteTextChange,
  onSubmitNote,
  noteSubmitting = false,
  canViewPricingRequests = true,
}) {
  const pricingSummary = activePricingRequestsSummary(pricingRequests);
  const latestPr = pricingSummary ? pricingSummary.requests[pricingSummary.requests.length - 1] : null;
  const assignedImport = canViewPricingRequests
    ? latestPr
      ? latestPr.assignedImportName || 'ยังไม่มีผู้รับเรื่อง'
      : 'ยังไม่มี PCR'
    : 'ไม่แสดงในมุมมองนี้';

  const recentNotes = [...events]
    .filter((event) => COMMENT_EVENT_KINDS.has(event.kind) && event.message)
    .sort((a, b) => {
      const at = timestamp(a.createdAt);
      const bt = timestamp(b.createdAt);
      if (at == null && bt == null) return (b.id ?? 0) - (a.id ?? 0);
      if (at == null) return 1;
      if (bt == null) return -1;
      return bt - at || (b.id ?? 0) - (a.id ?? 0);
    })
    .slice(0, 3);

  return (
    <aside className="rounded-lg border border-border bg-surface p-4 shadow-sm" aria-label="บริบทดีล">
      <div className="flex flex-col gap-5">
        <ContextSection title="Next action" icon="chevronRight">
          <p className="m-0 rounded-md border border-info-border bg-info-bg px-3 py-2 text-sm font-bold text-info">
            {bannerText || 'ไม่มี Next action ในสถานะนี้'}
          </p>
        </ContextSection>

        <ContextSection title="Notes" icon="pencil">
          {notesAvailable ? (
            <div className="flex flex-col gap-3">
              {canComment ? (
                <div className="flex flex-col gap-2">
                  <textarea
                    rows={3}
                    value={noteText}
                    onChange={(event) => onNoteTextChange?.(event.target.value)}
                    placeholder="เพิ่มบันทึก..."
                    className="min-h-20 resize-y text-base"
                  />
                  <button
                    type="button"
                    className="secondary-button self-end"
                    disabled={noteSubmitting || !noteText.trim()}
                    onClick={onSubmitNote}
                  >
                    เพิ่มบันทึก
                  </button>
                </div>
              ) : null}
              {recentNotes.length > 0 ? (
                <ul className="m-0 flex list-none flex-col gap-2 p-0">
                  {recentNotes.map((note) => (
                    <li key={note.id} className="rounded-md bg-surface-muted px-3 py-2 text-xs">
                      <p className="m-0 text-text-secondary">{note.message}</p>
                      <small className="mt-1 block text-2xs font-semibold text-text-muted">
                        {note.actorName || '-'} · {formatThaiDate(note.createdAt)}
                      </small>
                    </li>
                  ))}
                </ul>
              ) : (
                <p className="m-0 text-xs text-text-muted">ยังไม่มีบันทึก</p>
              )}
            </div>
          ) : (
            <p className="m-0 text-xs text-text-muted">Notes ยังไม่พร้อมใช้งานใน API ปัจจุบัน</p>
          )}
        </ContextSection>

        <ContextSection title="Key dates" icon="calendar">
          <dl className="m-0">
            <FieldRow label="ติดตามครั้งถัดไป" value={formatThaiDate(summary.nextFollowUpAt)} />
            <FieldRow label="ติดตามล่าสุด" value={formatThaiDate(summary.lastFollowUpAt)} />
            <FieldRow label="วันวางบิล" value={formatThaiDate(summary.billingDate)} />
            <FieldRow label="ครบกำหนดชำระ" value={formatThaiDate(summary.dueDate)} danger={summary.overdue} />
            <FieldRow label="ใบเสนอราคาหมดอายุ" value={formatThaiDate(latestQuotation?.validityDate)} />
          </dl>
        </ContextSection>

        <ContextSection title="People" icon="users">
          <dl className="m-0">
            <FieldRow label="เจ้าของดีล" value={summary.createdByName} />
            <FieldRow label="Import บน PCR" value={assignedImport} />
            <FieldRow label="บัญชี" value={summary.closeConfirmedByName || 'ยังไม่ระบุ'} />
            <FieldRow label="ผู้ติดต่อ" value={summary.contactName} />
          </dl>
        </ContextSection>
      </div>
    </aside>
  );
}
