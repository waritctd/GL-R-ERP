import { Icon } from '../../components/common/Icon.jsx';

// Lifted verbatim from the now-deleted TicketContextPanel.jsx (ticket-workspace
// IA rebuild, Slice B "retire the context rail, one comment composer"): small
// presentational helpers for a label/value row and a titled section, used by
// the ภาพรวม tab's "วันสำคัญ" / "ผู้เกี่ยวข้อง" blocks in TicketDetailPage.jsx —
// the two sections that used to live in the sticky rail. Kept in a shared
// module rather than re-authored inline since TicketDetailPage.jsx is already
// large and these are pure presentation, no state of their own.
export function FieldRow({ label, value, danger = false }) {
  return (
    <div className="grid grid-cols-[8.25rem_minmax(0,1fr)] gap-3 border-b border-border-subtle py-2 text-xs last:border-b-0">
      <dt className="font-bold text-text-muted">{label}</dt>
      <dd className={`m-0 min-w-0 font-semibold ${danger ? 'text-danger' : 'text-text-secondary'}`}>
        {value || '-'}
      </dd>
    </div>
  );
}

export function ContextSection({ title, helper, icon, children }) {
  return (
    <section className="border-t border-border pt-4 first:border-t-0 first:pt-0">
      <div className="mb-3 flex items-center gap-2">
        <Icon name={icon} size={15} className="text-text-muted" />
        <h2 className="m-0 flex flex-col text-sm font-extrabold leading-tight text-text">
          {title}
          {helper ? <span className="text-2xs font-normal text-text-muted">{helper}</span> : null}
        </h2>
      </div>
      {children}
    </section>
  );
}
