import { useState } from 'react';
import { Icon } from '../../components/common/Icon.jsx';
import { useMediaQuery } from '../../hooks/useIsMobile.js';
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

// Thai label carries the meaning, English sits under it as a helper — the same
// pairing the workspace tabs use (ภาพรวม / Overview). The panel previously mixed
// the two registers inside one card: `ความคิดเห็น` in Thai next to `Next action`,
// `Key dates` and `People` in English, in a Thai-first product.
function ContextSection({ title, helper, icon, children }) {
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

export function TicketContextPanel({
  summary,
  pricingRequests = [],
  latestQuotation = null,
  events = [],
  bannerText = null,
  canComment = false,
  commentText = '',
  onCommentTextChange,
  onSubmitComment,
  commentSubmitting = false,
  showCommentForm = true,
  canViewPricingRequests = true,
}) {
  const isDesktopRail = useMediaQuery('(min-width: 1280px)');
  const [mobileOpen, setMobileOpen] = useState(false);
  const expanded = isDesktopRail || mobileOpen;
  const pricingSummary = activePricingRequestsSummary(pricingRequests);
  const latestPr = pricingSummary ? pricingSummary.requests[pricingSummary.requests.length - 1] : null;
  const assignedImport = canViewPricingRequests
    ? latestPr
      ? latestPr.assignedImportName || 'ยังไม่มีผู้รับเรื่อง'
      : 'ยังไม่มีใบขอราคา'
    : 'ไม่แสดงในมุมมองนี้';

  const recentComments = [...events]
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

  const content = expanded ? (
    <div className="flex flex-col gap-5">
      <ContextSection title="ขั้นตอนถัดไป" helper="Next action" icon="chevronRight">
        <p className="m-0 rounded-md border border-info-border bg-info-bg px-3 py-2 text-sm font-bold text-info">
          {bannerText || 'ไม่มีขั้นตอนถัดไปในสถานะนี้'}
        </p>
      </ContextSection>

      <ContextSection title="ความคิดเห็น" helper="Comments" icon="pencil">
        <div className="flex flex-col gap-3">
          {canComment && showCommentForm ? (
            <div className="flex flex-col gap-2">
              <textarea
                rows={3}
                value={commentText}
                onChange={(event) => onCommentTextChange?.(event.target.value)}
                placeholder="เพิ่มความคิดเห็น…"
                className="min-h-20 resize-y text-base"
              />
              <button
                type="button"
                className="secondary-button self-end"
                disabled={commentSubmitting || !commentText.trim()}
                onClick={onSubmitComment}
              >
                ส่งความคิดเห็น
              </button>
            </div>
          ) : null}
          {recentComments.length > 0 ? (
            <ul className="m-0 flex list-none flex-col gap-2 p-0">
              {recentComments.map((comment) => (
                <li key={comment.id} className="rounded-md bg-surface-muted px-3 py-2 text-xs">
                  <p className="m-0 text-text-secondary">{comment.message}</p>
                  <small className="mt-1 block text-2xs font-semibold text-text-muted">
                    {comment.actorName || '-'} · {formatThaiDate(comment.createdAt)}
                  </small>
                </li>
              ))}
            </ul>
          ) : (
            <p className="m-0 text-xs text-text-muted">ยังไม่มีความคิดเห็น</p>
          )}
        </div>
      </ContextSection>

      <ContextSection title="วันสำคัญ" helper="Key dates" icon="calendar">
        <dl className="m-0">
          <FieldRow label="ติดตามครั้งถัดไป" value={formatThaiDate(summary.nextFollowUpAt)} />
          <FieldRow label="ติดตามล่าสุด" value={formatThaiDate(summary.lastFollowUpAt)} />
          <FieldRow label="วันวางบิล" value={formatThaiDate(summary.billingDate)} />
          <FieldRow label="ครบกำหนดชำระ" value={formatThaiDate(summary.dueDate)} danger={summary.overdue} />
          <FieldRow label="ใบเสนอราคาหมดอายุ" value={formatThaiDate(latestQuotation?.validityDate)} />
        </dl>
      </ContextSection>

      <ContextSection title="ผู้เกี่ยวข้อง" helper="ทีมที่เกี่ยวข้อง" icon="users">
        <dl className="m-0">
          <FieldRow label="เจ้าของดีล" value={summary.createdByName} />
          <FieldRow label="ผู้รับเรื่องใบขอราคา" value={assignedImport} />
          <FieldRow label="บัญชี" value={summary.closeConfirmedByName || 'ยังไม่ระบุ'} />
          <FieldRow label="ผู้ติดต่อ" value={summary.contactName} />
        </dl>
      </ContextSection>
    </div>
  ) : null;

  return (
    <aside className="rounded-lg border border-border bg-surface p-4 shadow-sm" aria-label="บริบทดีล">
      <button
        type="button"
        className="flex min-h-11 w-full items-center justify-between gap-3 bg-transparent text-left xl:hidden"
        aria-expanded={mobileOpen}
        onClick={() => setMobileOpen((open) => !open)}
      >
        <span className="min-w-0">
          <span className="block text-sm font-extrabold text-text">บริบทดีล</span>
          <span className="block text-xs font-semibold text-text-muted">ความคิดเห็น · วันสำคัญ · ผู้เกี่ยวข้อง</span>
        </span>
        <Icon name="chevronDown" size={16} className={`shrink-0 text-text-muted transition-transform ${mobileOpen ? 'rotate-180' : ''}`} />
      </button>
      <div className={mobileOpen ? 'mt-4 xl:mt-0' : 'xl:mt-0'}>
        {content}
      </div>
    </aside>
  );
}
