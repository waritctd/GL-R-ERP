/* Hallmark · genre: modern-minimal · macrostructure: Workbench · design-system: design.md · designed-as-app · pre-emit critique: P4 H4 E5 S4 R5 V5 */
import { useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { Button } from '../../components/common/Button.jsx';
import { EmptyState } from '../../components/common/EmptyState.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { Panel } from '../../components/common/Layout.jsx';
import { SkeletonText } from '../../components/common/Skeleton.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { bangkokTodayIso, formatMoney, formatThaiDate, PAYMENT_KIND_LABEL } from '../../utils/format.js';
import { ACTIVITY_KINDS, activityKindLabel } from './dealTrackingMeta.js';
import { showsPlannedVisits, TIMELINE_FILTERS, timelineRowMatches } from './dealTimeline.js';
import { shortDayLabel, timeLabel, weekStartIso } from '../salesBoard/visitWeek.js';
import { CANCEL_REASON_LABEL, LOST_REASON_F_CODE, LOST_REASON_LABEL } from './stageMeta.js';
import { ThaiDateInput } from '../../components/common/ThaiDateInput.jsx';

// Moved verbatim from TicketDetailPage.jsx (IA rebuild — see docs/ui-repair/02-information-architecture/):
// this is the label table for `ticket.events`, unrelated to the DealActivityKind taxonomy
// (dealTrackingMeta.js's ACTIVITY_KINDS) that backs the activity log merged in below.
export const EVENT_KIND_LABEL = {
  // Owner ruling 2026-11-16: labels say งาน, not ดีล (the kinds themselves are untouched).
  CREATED:            'สร้างงาน',
  STAGE_CHANGED:      'เปลี่ยนสถานะงาน',
  MARKED_LOST:        'เสียงาน',
  REOPENED:           'เปิดงานอีกครั้ง',
  ON_HOLD:            'พักงานไว้',
  DORMANT:            'พัก dormant',
  RESUMED:            'ดำเนินการต่อ',
  POLICY_CHANGED:     'เปลี่ยนนโยบายงาน',
  PAYMENT_RECORDED:   'บันทึกรับชำระเงิน',
  BILLING_UPDATED:    'อัปเดตข้อมูลวางบิล',
  SUBMITTED:          'ส่งเรื่องเข้าระบบ',
  PICKED_UP:          'รับมอบหมาย',
  PRICE_PROPOSED:     'เสนอราคาสินค้า',
  APPROVED:           'อนุมัติ',
  REJECTED:           'ปฏิเสธ',
  QUOTATION_ISSUED:   'ออกใบเสนอราคา',
  QUOTATION_SENT:     'ส่งใบเสนอราคา',
  QUOTATION_ACCEPTED: 'ลูกค้ารับใบเสนอราคา',
  QUOTATION_REJECTED: 'ลูกค้าปฏิเสธใบเสนอราคา',
  DOCUMENT_ISSUED:    'ออกใบแจ้งยอดมัดจำ',
  PRICE_REVISED:      'แก้ไขราคาที่เสนอ',
  REVISION_REQUESTED: 'ขอแก้ไข',
  // GLA-74 part 1 ("สร้างจากใบเดิม" / สั่งเหมือนเดิม, V186) -- a genuinely new kind, not a reuse of
  // REVISION_REQUESTED: a reorder clones an APPROVED quotation into an independent draft without ever revising,
  // rejecting, or touching the source, so "ขอแก้ไข" above would misdescribe it.
  DEAL_QUOTATION_REORDERED: 'สร้างจากใบเดิม (สั่งเหมือนเดิม)',
  // Owner ruling 2026-09-19 ("a deal may hold only ONE APPROVED DEAL_DIRECT quotation") -- the same-ticket sweep
  // DealQuotationService#approve fires once per sibling it actually supersedes. Missing here would leave the raw
  // kind string on screen (Opus review finding).
  DEAL_QUOTATION_SUPERSEDED: 'ใบเสนอราคาถูกแทนที่',
  // GLA-136 (V193) -- DealQuotationService#promoteToDeal: the customer ordered on an APPROVED direct quotation.
  // Relabelled by slice 2 (IA §4/§8): under the linked design the deal already exists, so what this event
  // records is the ORDER, in the same words as the CTA that fires it.
  DEAL_PROMOTED_FROM_QUOTATION: 'ยืนยันคำสั่งซื้อจากใบเสนอราคา',
  CLOSED:             'ปิดเรื่อง',
  CANCELLED:          'ยกเลิก',
  EDITED:             'แก้ไขรายการสินค้า',
  COMMENTED:          'ความคิดเห็น',
  COMMENT:            'ความคิดเห็น',
  PRICE_OVERRIDDEN:   'แก้ไขราคาด้วยตนเอง (CEO)',
  // Dual-track post-quotation events (see backend TicketEventKind.java) — payment and fulfillment labels mirror
  // the DealStagePanel's การชำระเงิน/การนำเข้า sub-status chips so an event and its chip read as the same
  // real-world action.
  CUSTOMER_CONFIRMED:     'ลูกค้ายืนยันคำสั่งซื้อ',
  DEPOSIT_NOTICE_ISSUED:  'ออกใบแจ้งมัดจำ',
  DEPOSIT_PAID:           'รับมัดจำแล้ว',
  IR_ISSUED:              'ออกคำขอนำเข้า (IR)',
  IR_SENT:                'ส่งคำขอนำเข้าแล้ว',
  SHIPPING:               'สินค้าออกเดินทาง',
  GOODS_RECEIVED:         'รับสินค้าแล้ว',
  STOCK_RESERVED:         'จองสินค้าจากสต็อก',
  PROCUREMENT_SOURCE_DECIDED: 'เลือกที่มาสินค้า',
  DELIVERY_RECORDED:      'บันทึกการส่งสินค้า',
  DELIVERY_COMPLETED:     'ส่งมอบครบแล้ว',
  AWAITING_FINAL_PAYMENT: 'รอชำระส่วนที่เหลือ',
  FULLY_PAID:             'ชำระครบแล้ว',
  CLOSE_CONFIRMED:        'ยืนยันการปิดงาน',
  CLOSE_CONFIRM_REVOKED:  'ถอนการยืนยันปิดงาน',
  ORDER_CONFIRMED_FROM_QUOTATION: 'ยืนยันคำสั่งซื้อจากใบเสนอราคา',
  DEAL_QUOTATION_SUBMITTED: 'ส่งใบเสนอราคาขออนุมัติ',
  DEAL_QUOTATION_APPROVED: 'อนุมัติใบเสนอราคา',
  DEAL_QUOTATION_REJECTED: 'ไม่อนุมัติใบเสนอราคา',
  DEAL_QUOTATION_REVISION_SUBMITTED: 'ส่งใบเสนอราคาฉบับแก้ไขขออนุมัติ',
  IMPORT_STEP_ADVANCED:   'ฝ่ายนำเข้าเลื่อนขั้นตอน',
  IMPORT_REQUEST_EMAIL_SENT: 'ส่งอีเมลใบขอซื้อแล้ว',
};

// The backend writes "kind=…, amount=…, paid=…, payable=… — note" (TicketService#recordPayment); old rows
// keep it, so it is formatted here. Anything not in that shape is shown as written.
// TicketService.cancel / markLost write "ยกเลิกดีล (CODE)[ — note]" / "เสียงาน (CODE)[ — note]". This is the one place
// a cancelled or lost deal's reason is displayed, so the code reads as its label: the cancel codes offered since
// 2026-11-16 and the retired ones already-cancelled deals carry (CANCEL_REASON_LABEL holds both), and the lost codes
// with their F1–F8 tag. A message not in that shape, or an unknown code, is shown as written.
const REASON_MESSAGE = /^(ยกเลิกดีล|เสียงาน) \(([A-Z_]+)\)(?: — (.*))?$/s;

function reasonMessage(event) {
  const m = REASON_MESSAGE.exec(event.message ?? '');
  if (!m) return event.message;
  const label = event.kind === 'CANCELLED'
    ? CANCEL_REASON_LABEL[m[2]]
    : (LOST_REASON_LABEL[m[2]] ? `${LOST_REASON_F_CODE[m[2]] ?? ''} ${LOST_REASON_LABEL[m[2]]}`.trim() : null);
  if (!label) return event.message;
  return m[3] ? `${label} — ${m[3]}` : label;
}

function eventMessage(event) {
  if (event.kind === 'CANCELLED' || event.kind === 'MARKED_LOST') return reasonMessage(event);
  if (event.kind !== 'PAYMENT_RECORDED') return event.message;
  const m = /^kind=(\w+), amount=(-?[\d.]+), paid=([-\d.]+), payable=([-\d.]+)(?: — (.*))?$/s.exec(event.message ?? '');
  if (!m || !PAYMENT_KIND_LABEL[m[1]]) return event.message;
  return `${PAYMENT_KIND_LABEL[m[1]]} ${formatMoney(m[2])} · รับแล้วรวม ${formatMoney(m[3])} จากยอด ${formatMoney(m[4])}${m[5] ? ` — ${m[5]}` : ''}`;
}

// Payment-track events get a success-toned dot, fulfillment-track events get
// an info-toned dot — mirrors the two colour groups used by the sub-status
// chips in DealStagePanel. Exported (Slice E) so DealPaymentSection.jsx can
// filter `ticket.events` down to the payment track for its own timeline
// without redeclaring this list — two copies drifting apart is exactly how
// an event would silently stop appearing on one of the two surfaces.
export const PAYMENT_TRACK_KINDS = new Set([
  'CUSTOMER_CONFIRMED', 'DEPOSIT_NOTICE_ISSUED', 'DEPOSIT_PAID',
  'AWAITING_FINAL_PAYMENT', 'FULLY_PAID', 'PAYMENT_RECORDED', 'BILLING_UPDATED',
]);
const FULFILLMENT_TRACK_KINDS = new Set([
  'IR_ISSUED', 'IR_SENT', 'SHIPPING', 'GOODS_RECEIVED',
  'STOCK_RESERVED', 'PROCUREMENT_SOURCE_DECIDED', 'DELIVERY_RECORDED', 'DELIVERY_COMPLETED',
]);

const EVENT_DOT_BASE = 'w-2 h-2 rounded-full mt-[5px] shrink-0';

function eventDotClass(kind) {
  if (kind === 'CREATED') return `${EVENT_DOT_BASE} bg-success-soft`;
  if (kind === 'COMMENTED' || kind === 'COMMENT') return `${EVENT_DOT_BASE} bg-text-muted`;
  if (PAYMENT_TRACK_KINDS.has(kind)) return `${EVENT_DOT_BASE} bg-success`;
  if (FULFILLMENT_TRACK_KINDS.has(kind)) return `${EVENT_DOT_BASE} bg-info-dot`;
  return `${EVENT_DOT_BASE} bg-info-dot`;
}

// Read on every render/reset, not once at module load: today's date in Bangkok, never the UTC date.
const emptyActivityDraft = () => ({ activityDate: bangkokTodayIso(), kind: 'CALL', note: '' });

/** `Date.parse`-safe timestamp, or `null` for a missing/unparseable value — rows with a `null` timestamp sort
 * last rather than crashing `Array#sort` or floating to the top as `NaN` would. */
function toTimestamp(value) {
  if (!value) return null;
  const t = new Date(value).getTime();
  return Number.isNaN(t) ? null : t;
}

// An undo of a per-factory step advance (V216) is written as IMPORT_STEP_ADVANCED with the message
// "โรงงาน <name>: เลิกทำ <from> → <to>" (ImportRequestService#undoStep / mockApi undoStep) — no event kind of its own.
const IMPORT_STEP_UNDO_MARKER = ': เลิกทำ ';
function eventLabel(event) {
  if (event.kind === 'IMPORT_STEP_ADVANCED' && (event.message ?? '').includes(IMPORT_STEP_UNDO_MARKER)) {
    return 'ฝ่ายนำเข้าเลิกทำการเลื่อนขั้นตอน';
  }
  return EVENT_KIND_LABEL[event.kind] ?? event.kind;
}

function EventRow({ event }) {
  let snapItems = null;
  if (event.kind === 'PRICE_PROPOSED' && event.itemSnapshot) {
    try { snapItems = JSON.parse(event.itemSnapshot); } catch { snapItems = null; }
  }
  return (
    <li className="ticket-event">
      <span className={eventDotClass(event.kind)} />
      <div className="min-w-0 flex-1">
        <strong className="block text-sm text-text">{eventLabel(event)}</strong>
        <span className="text-xs text-icon-muted">{event.actorName}</span>
        {event.message ? (
          <p className="mt-1 rounded bg-surface-muted px-2 py-1 text-xs text-text-muted">{eventMessage(event)}</p>
        ) : null}
        {snapItems && snapItems.length > 0 ? (
          <div className="mt-1.5 rounded bg-surface-muted px-2.5 py-1.5 text-2xs text-icon-muted">
            <div className="mb-1 font-bold text-text-muted">รายการสินค้า ณ เวลาที่เสนอราคา</div>
            {snapItems.map((it, i) => (
              <div key={i} className="pb-0.5">
                {it.brand} {it.model} — {it.qty} ชิ้น
                {it.rawPrice != null ? (
                  <span className="ml-1 text-text-muted">@ {it.rawPrice} {it.rawCurrency}/{it.rawUnit}</span>
                ) : null}
              </div>
            ))}
          </div>
        ) : null}
        <small className="text-2xs text-text-muted">{formatThaiDate(event.createdAt)}</small>
      </div>
    </li>
  );
}

// Same rendering DealTrackingPanel's activity log used before this merge — a `StatusBadge` (not a coloured
// dot) is what visually marks a row as "a rep's own follow-up" rather than "the system's audit trail" (the
// event rows' `eventDotClass` dot).
function ActivityRow({ activity }) {
  return (
    <li className="flex flex-col gap-0.5 rounded-lg border border-border-subtle bg-surface-subtle px-3 py-2">
      <div className="flex flex-wrap items-center gap-2">
        <StatusBadge tone="neutral">{activityKindLabel(activity.kind)}</StatusBadge>
        <span className="text-xs font-bold text-text-muted">{formatThaiDate(activity.activityDate)}</span>
        <span className="text-2xs text-text-muted">· {activity.createdByName}</span>
      </div>
      {activity.note ? <p className="m-0 text-xs text-text-secondary">{activity.note}</p> : null}
    </li>
  );
}

/**
 * Slice 6.5 (prototype screen D): one PLANNED visit on this deal, above the dated history — it is what happens next,
 * not something that happened. A dashed card with นัดไว้ (or เลยวันนัด once its day has passed unticked), who is being
 * met, when, by whom, and the purpose. With `href` the whole card opens the นัดเข้าพบ page on the week holding the
 * visit (`?week=<Monday>`), where it is ticked.
 */
function PlannedVisitRow({ visit, todayIso, href }) {
  const overdue = visit.plannedDate < todayIso;
  const when = [shortDayLabel(visit.plannedDate, todayIso), timeLabel(visit.plannedTime)].filter(Boolean).join(' · ');
  const body = (
    <>
      <span className="flex flex-wrap items-center gap-2">
        <StatusBadge tone={overdue ? 'warning' : 'info'}>{overdue ? 'เลยวันนัด' : 'นัดไว้'}</StatusBadge>
        <strong className="min-w-0 text-sm text-text [overflow-wrap:anywhere]">{`นัดเข้าพบ · ${visit.contactName}`}</strong>
      </span>
      <span className="text-xs font-bold text-text-secondary">
        {when}
        {visit.ownerName ? <span className="font-normal text-text-muted">{` · ${visit.ownerName}`}</span> : null}
      </span>
      {visit.note ? <span className="text-xs text-text-secondary [overflow-wrap:anywhere]">{visit.note}</span> : null}
    </>
  );
  const card = 'flex flex-col gap-1 rounded-lg border border-dashed border-border-strong bg-surface px-3 py-2';
  return (
    <li data-testid="timeline-planned-visit" className={href ? '' : card}>
      {href ? (
        <Link
          to={`${href}?week=${weekStartIso(visit.plannedDate)}`}
          className={`${card} min-h-11 no-underline transition-colors hover:border-primary hover:bg-surface-hover focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-primary`}
        >
          {body}
        </Link>
      ) : body}
    </li>
  );
}

/** "ประวัติ" (drawing a-deal, on the ภาพรวม tab): one chronological history merging the system audit trail with the
 * rep's own follow-up log (`activities`, formerly DealTrackingPanel's "ประวัติการติดตาม"), filtered by
 * ทั้งหมด / เข้าพบ / เอกสาร (dealTimeline.js). The note composer (comment + the any-kind activity form) is closed
 * until "+ บันทึก" — the header's โทรแล้ว / นัดเข้าพบ are the everyday way to log a touch now. Each half keeps its
 * own gate (`canComment`, `canAddActivity`); "+ บันทึก" shows when either applies. A row with no parseable
 * timestamp sorts last rather than crashing or floating to the top. */
export function DealHistoryPanel({
  events = [],
  activities = [],
  loading = false,
  canViewActivityFeed = true,
  canComment = false,
  commentText = '',
  onCommentTextChange,
  onSubmitComment,
  commentSubmitting = false,
  canAddActivity = false,
  onAddActivity,
  addingActivity = false,
  // Slice 6.5: the deal's PLANNED visits as the server served them to this viewer (already narrowed to the visits they
  // may read); `visitsHref` links each to the นัดเข้าพบ page when the viewer may open it; `todayIso` marks the overdue.
  plannedVisits = [],
  visitsHref = null,
  todayIso = bangkokTodayIso(),
}) {
  const [activityDraft, setActivityDraft] = useState(emptyActivityDraft);
  const [filter, setFilter] = useState('all');
  const [composerOpen, setComposerOpen] = useState(false);
  const canNote = canComment || canAddActivity;

  const allRows = useMemo(() => {
    const eventRows = events.map((event) => ({ key: `event-${event.id}`, type: 'event', ts: toTimestamp(event.createdAt), event }));
    const activityRows = activities.map((activity) => ({ key: `activity-${activity.id}`, type: 'activity', ts: toTimestamp(activity.activityDate), activity }));
    return [...eventRows, ...activityRows].sort((a, b) => {
      if (a.ts == null && b.ts == null) return 0;
      if (a.ts == null) return 1;
      if (b.ts == null) return -1;
      if (b.ts !== a.ts) return b.ts - a.ts;
      // FIX 4 (Opus review — comparator was not a strict weak ordering): the old tiebreak below only compared
      // `event.id` when BOTH rows were events, and fell through to a bare `0` for every other combination
      // (event-vs-activity, activity-vs-activity).
      const aRank = a.type === 'event' ? 0 : 1;
      const bRank = b.type === 'event' ? 0 : 1;
      if (aRank !== bRank) return aRank - bRank;
      const aId = a.type === 'event' ? a.event.id : a.activity.id;
      const bId = b.type === 'event' ? b.event.id : b.activity.id;
      return bId - aId;
    });
  }, [events, activities]);
  const rows = allRows.filter((row) => timelineRowMatches(row, filter));
  const upcoming = showsPlannedVisits(filter) ? plannedVisits : [];
  const nothing = rows.length === 0 && upcoming.length === 0;

  async function submitActivity() {
    if (!activityDraft.activityDate) return;
    await onAddActivity({
      activityDate: activityDraft.activityDate,
      kind: activityDraft.kind,
      note: activityDraft.note.trim() || null,
    });
    setActivityDraft(emptyActivityDraft());
  }

  return (
    <Panel
      title="ประวัติ"
      data-testid="deal-timeline"
      actions={(
        <div className="flex flex-wrap items-center gap-1.5">
          {canNote ? (
            <Button
              type="button" variant="secondary" size="sm" className="mobile:min-h-[44px]"
              aria-expanded={composerOpen}
              onClick={() => setComposerOpen((open) => !open)}
            >
              + บันทึก
            </Button>
          ) : null}
          <div role="group" aria-label="กรองประวัติ" className="flex flex-wrap gap-1">
            {TIMELINE_FILTERS.map((option) => (
              <button
                key={option.id}
                type="button"
                aria-pressed={filter === option.id}
                onClick={() => setFilter(option.id)}
                className={`min-h-[32px] rounded-full border px-3 text-xs font-bold mobile:min-h-[44px] ${filter === option.id
                  ? 'border-primary bg-primary-bg text-primary'
                  : 'border-border-input bg-surface text-text-secondary hover:bg-surface-hover'}`}
              >
                {option.label}
              </button>
            ))}
          </div>
        </div>
      )}
    >
      {composerOpen && canNote ? (
        <Composer
          canComment={canComment}
          commentText={commentText}
          onCommentTextChange={onCommentTextChange}
          onSubmitComment={onSubmitComment}
          commentSubmitting={commentSubmitting}
          canAddActivity={canAddActivity}
          activityDraft={activityDraft}
          setActivityDraft={setActivityDraft}
          addingActivity={addingActivity}
          submitActivity={submitActivity}
        />
      ) : null}
      <div className="ticket-events">
        {nothing && !loading && (allRows.length > 0 || plannedVisits.length > 0) ? (
          <p className="m-0 px-1 py-3 text-sm text-text-muted">ไม่มีรายการในตัวกรองนี้</p>
        ) : nothing && !loading ? (
          // FIX 1 (Opus review): when `canViewActivityFeed` is false, `activities` is always `[]` because it was never
          // fetched for this viewer (see the parent's `activitiesQuery.enabled`) — an empty `events` list here only
          // proves the AUDIT TRAIL is empty, not that "nothing has happened on this deal".
          canViewActivityFeed ? (
            <EmptyState icon="clock" title="ยังไม่มีประวัติ" description="เหตุการณ์และกิจกรรมจะปรากฏที่นี่" />
          ) : (
            <EmptyState
              icon="clock"
              title="ยังไม่มีประวัติการดำเนินการ"
              description="บันทึกกิจกรรมติดตามแสดงเฉพาะเจ้าของงาน ผู้จัดการฝ่ายขาย และ CEO"
            />
          )
        ) : (
          <ul className="m-0 flex list-none flex-col gap-2 p-0">
            {upcoming.map((visit) => (
              <PlannedVisitRow key={`visit-${visit.id}`} visit={visit} todayIso={todayIso} href={visitsHref} />
            ))}
            {rows.map((row) => (
              row.type === 'event'
                ? <EventRow key={row.key} event={row.event} />
                : <ActivityRow key={row.key} activity={row.activity} />
            ))}
          </ul>
        )}
        {/* activitiesQuery loading never blocks the events that are already part of the ticket payload — this is an
         * additive "more is coming" line, not a full-panel skeleton swap. */}
        {loading ? (
          <div className="px-1 pt-2">
            <SkeletonText lines={2} />
          </div>
        ) : null}
      </div>

    </Panel>
  );
}

/** "+ บันทึก"'s composer: the comment box (`canComment`) and the any-kind activity form (`canAddActivity`), each
 * behind its own gate exactly as before. */
function Composer({
  canComment, commentText, onCommentTextChange, onSubmitComment, commentSubmitting,
  canAddActivity, activityDraft, setActivityDraft, addingActivity, submitActivity,
}) {
  return (
    <div className="mb-3 flex flex-col rounded-md border border-border-subtle bg-surface-muted">
      {canComment ? (
        <div className="flex flex-col gap-2 p-4">
          <textarea
            rows={2}
            value={commentText}
            onChange={(e) => onCommentTextChange?.(e.target.value)}
            placeholder="เพิ่มความคิดเห็น…"
            style={{ resize: 'vertical' }}
          />
          <Button
            type="button" variant="secondary" className="self-end"
            onClick={onSubmitComment}
            disabled={commentSubmitting || !commentText.trim()}
          >
            ส่งความคิดเห็น
          </Button>
        </div>
      ) : null}

      {canAddActivity ? (
        <div className="flex flex-col gap-2 p-4 [&:not(:first-child)]:border-t [&:not(:first-child)]:border-border">
          <h3 className="m-0 text-sm font-extrabold text-text">บันทึกกิจกรรมติดตาม</h3>
          <div className="grid grid-cols-1 gap-2 sm:grid-cols-[minmax(0,1fr)_minmax(0,1fr)]">
            <label className="flex flex-col gap-1 text-xs font-bold text-text-secondary">
              วันที่
              <ThaiDateInput
                className="text-base"
                value={activityDraft.activityDate}
                onChange={(e) => setActivityDraft((d) => ({ ...d, activityDate: e.target.value }))}
              />
            </label>
            <label className="flex flex-col gap-1 text-xs font-bold text-text-secondary">
              ประเภท
              <select
                className="text-base"
                value={activityDraft.kind}
                onChange={(e) => setActivityDraft((d) => ({ ...d, kind: e.target.value }))}
              >
                {ACTIVITY_KINDS.map((k) => (
                  <option key={k.code} value={k.code}>{k.label}</option>
                ))}
              </select>
            </label>
          </div>
          <label className="flex flex-col gap-1 text-xs font-bold text-text-secondary">
            บันทึก (ถ้ามี)
            <textarea
              rows={2}
              value={activityDraft.note}
              onChange={(e) => setActivityDraft((d) => ({ ...d, note: e.target.value }))}
              placeholder="รายละเอียดการติดต่อ / ผลที่ได้"
            />
          </label>
          <Button
            type="button" variant="secondary" className="self-end"
            disabled={addingActivity || !activityDraft.activityDate}
            onClick={submitActivity}
          >
            <Icon name="plus" size={14} />
            บันทึกกิจกรรม
          </Button>
        </div>
      ) : null}
    </div>
  );
}
