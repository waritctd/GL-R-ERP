import { useMemo, useState } from 'react';
import { EmptyState } from '../../components/common/EmptyState.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { SkeletonText } from '../../components/common/Skeleton.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { formatThaiDate } from '../../utils/format.js';
import { ACTIVITY_KINDS, activityKindLabel } from './dealTrackingMeta.js';

// Moved verbatim from TicketDetailPage.jsx (IA rebuild — see
// docs/ui-repair/02-information-architecture/): this is the label table for
// `ticket.events`, unrelated to the DealActivityKind taxonomy
// (dealTrackingMeta.js's ACTIVITY_KINDS) that backs the activity log merged
// in below. Kept as one map, not duplicated — see this file's own history if
// TicketDetailPage ever needs it again.
export const EVENT_KIND_LABEL = {
  CREATED:            'สร้างดีล',
  STAGE_CHANGED:      'เปลี่ยนสถานะดีล',
  MARKED_LOST:        'เสียงาน',
  REOPENED:           'เปิดดีลอีกครั้ง',
  ON_HOLD:            'พักดีลไว้',
  DORMANT:            'พัก dormant',
  RESUMED:            'ดำเนินการต่อ',
  POLICY_CHANGED:     'เปลี่ยนนโยบายดีล',
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
  CLOSED:             'ปิดเรื่อง',
  CANCELLED:          'ยกเลิก',
  EDITED:             'แก้ไขรายการสินค้า',
  COMMENTED:          'ความคิดเห็น',
  COMMENT:            'ความคิดเห็น',
  PRICE_OVERRIDDEN:   'แก้ไขราคาด้วยตนเอง (CEO)',
  // Dual-track post-quotation events (see backend TicketEventKind.java) — payment
  // and fulfillment labels mirror the DealStagePanel's การชำระเงิน/การนำเข้า
  // sub-status chips so an event and its chip read
  // as the same real-world action.
  CUSTOMER_CONFIRMED:     'ลูกค้ายืนยันคำสั่งซื้อ',
  DEPOSIT_NOTICE_ISSUED:  'ออกใบแจ้งมัดจำ',
  DEPOSIT_PAID:           'รับมัดจำแล้ว',
  IR_ISSUED:              'ออกคำขอนำเข้า (IR)',
  IR_SENT:                'ส่งคำขอนำเข้าแล้ว',
  SHIPPING:               'สินค้าออกเดินทาง',
  GOODS_RECEIVED:         'รับสินค้าแล้ว',
  STOCK_RESERVED:         'จองสินค้าจากสต็อก',
  DELIVERY_RECORDED:      'บันทึกการส่งสินค้า',
  DELIVERY_COMPLETED:     'ส่งมอบครบแล้ว',
  AWAITING_FINAL_PAYMENT: 'รอชำระส่วนที่เหลือ',
  FULLY_PAID:             'ชำระครบแล้ว',
};

// Payment-track events get a success-toned dot, fulfillment-track events get
// an info-toned dot — mirrors the two colour groups used by the sub-status
// chips in DealStagePanel. Exported (Slice E) so DealMoneyTimeline.jsx can
// filter `ticket.events` down to the payment track for its own timeline
// without redeclaring this list — two copies drifting apart is exactly how
// an event would silently stop appearing on one of the two surfaces.
export const PAYMENT_TRACK_KINDS = new Set([
  'CUSTOMER_CONFIRMED', 'DEPOSIT_NOTICE_ISSUED', 'DEPOSIT_PAID',
  'AWAITING_FINAL_PAYMENT', 'FULLY_PAID', 'PAYMENT_RECORDED', 'BILLING_UPDATED',
]);
const FULFILLMENT_TRACK_KINDS = new Set([
  'IR_ISSUED', 'IR_SENT', 'SHIPPING', 'GOODS_RECEIVED',
  'STOCK_RESERVED', 'DELIVERY_RECORDED', 'DELIVERY_COMPLETED',
]);

export function eventDotClass(kind) {
  if (kind === 'CREATED') return 'event-dot created';
  if (kind === 'COMMENTED' || kind === 'COMMENT') return 'event-dot comment';
  if (PAYMENT_TRACK_KINDS.has(kind)) return 'event-dot success';
  if (FULFILLMENT_TRACK_KINDS.has(kind)) return 'event-dot transition';
  return 'event-dot transition';
}

function today() {
  return new Date().toISOString().slice(0, 10);
}

const EMPTY_ACTIVITY_DRAFT = { activityDate: today(), kind: 'CALL', note: '' };

/** `Date.parse`-safe timestamp, or `null` for a missing/unparseable value —
 * rows with a `null` timestamp sort last rather than crashing `Array#sort` or
 * floating to the top as `NaN` would. */
function toTimestamp(value) {
  if (!value) return null;
  const t = new Date(value).getTime();
  return Number.isNaN(t) ? null : t;
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
        <strong className="block text-sm text-text">{EVENT_KIND_LABEL[event.kind] ?? event.kind}</strong>
        <span className="text-xs text-icon-muted">{event.actorName}</span>
        {event.message ? (
          <p className="mt-1 rounded bg-surface-muted px-2 py-1 text-xs text-text-muted">{event.message}</p>
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

// Same rendering DealTrackingPanel's activity log used before this merge —
// a `StatusBadge` (not a coloured dot) is what visually marks a row as "a
// rep's own follow-up" rather than "the system's audit trail" (the event
// rows' `eventDotClass` dot).
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
 * "ประวัติดีล" (IA rebuild — see docs/ui-repair/02-information-architecture/):
 * one chronological history merging the system audit trail (`ticket.events`,
 * formerly its own "ประวัติการดำเนินการ" panel ~1,500px away from this one)
 * with the rep's own follow-up log (`activities`, formerly DealTrackingPanel's
 * "ประวัติการติดตาม (Activity log)"). Two separate timelines describing the
 * same deal is exactly the redundancy this rebuild folds — see the measured
 * facts in TicketDetailPage's own doc comments.
 *
 * Sorted by `event.createdAt` / `activity.activityDate` descending — those are
 * each stream's own canonical timestamp field (see dealTrackingMeta.js's
 * `lastStageChangeAt`/`hasActivitySince`, which already key activities off
 * `activityDate`, not `createdAt`). A row with no parseable timestamp sorts
 * last rather than crashing or floating to the top.
 *
 * Both input affordances survive verbatim: the comment box (`canComment`,
 * moved from TicketDetailPage's own state/handler — passed down rather than
 * duplicated so `resetActionDrafts` still clears it after any action) and the
 * add-activity form (`canAddActivity`, state now owned locally here since
 * DealTrackingPanel no longer renders it).
 *
 * FIX 1 (Opus review): this tab (and so this panel) now renders for every
 * viewer of the deal — the audit trail (`events`) and the comment box
 * (`canComment`) are backed by `TicketService.projectForRole`/`comment`'s
 * `requireViewAccess`, which every viewer passes. Only the follow-up feed
 * (`activities`, `TicketService.listActivities`) stays genuinely gated on
 * `requireDealOwnership` — the parent never fetches it for a role that gate
 * would refuse (`activitiesQuery`'s own `enabled`), so `activities` here is
 * always `[]` for those roles, not a real "nothing logged yet" answer.
 * `canViewActivityFeed` (true only for the roles that DID fetch it) exists
 * so the empty state below can tell those two situations apart instead of
 * claiming "ยังไม่มีประวัติ ... กิจกรรมจะปรากฏที่นี่" when the feed was simply
 * never asked for on this viewer's behalf.
 */
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
}) {
  const [activityDraft, setActivityDraft] = useState(EMPTY_ACTIVITY_DRAFT);

  const rows = useMemo(() => {
    const eventRows = events.map((event) => ({ key: `event-${event.id}`, type: 'event', ts: toTimestamp(event.createdAt), event }));
    const activityRows = activities.map((activity) => ({ key: `activity-${activity.id}`, type: 'activity', ts: toTimestamp(activity.activityDate), activity }));
    return [...eventRows, ...activityRows].sort((a, b) => {
      if (a.ts == null && b.ts == null) return 0;
      if (a.ts == null) return 1;
      if (b.ts == null) return -1;
      if (b.ts !== a.ts) return b.ts - a.ts;
      // FIX 4 (Opus review — comparator was not a strict weak ordering): the
      // old tiebreak below only compared `event.id` when BOTH rows were
      // events, and fell through to a bare `0` for every other combination
      // (event-vs-activity, activity-vs-activity). That made the "tie"
      // relation non-transitive: for two events E1(id 1)/E2(id 2) and an
      // activity A sharing one instant, cmp(E1,A)=0 and cmp(A,E2)=0 (neither
      // is event-vs-event) implied E1~A~E2, yet cmp(E1,E2) actually resolves
      // via the id tiebreak to a definite (non-zero) order — Array#sort has
      // no obligation to honor a comparator whose "equal" pairs aren't
      // mutually consistent, so the final order for that trio depended on
      // the engine's internal comparison sequence, not on any rule this file
      // states. D5: sales.ticket_event.created_at defaults to Postgres now()
      // (transaction_timestamp()), so every event written in the same
      // transaction (e.g. confirmCustomer's CUSTOMER_CONFIRMED then
      // STAGE_CHANGED) shares one identical instant — this is the case the
      // tiebreak exists for at all.
      //
      // Fixed by applying ONE secondary key to every row regardless of kind,
      // not just to event/event pairs: events sort before activities at a
      // shared instant (arbitrary but FIXED, so every pair — including one
      // event and one activity — now has a definite, mutually consistent
      // order instead of a same-kind-only special case), and within one kind
      // a numerically higher id (written later) sorts first — the exact
      // event-vs-event rule the passing D5 test below already requires,
      // unchanged.
      const aRank = a.type === 'event' ? 0 : 1;
      const bRank = b.type === 'event' ? 0 : 1;
      if (aRank !== bRank) return aRank - bRank;
      const aId = a.type === 'event' ? a.event.id : a.activity.id;
      const bId = b.type === 'event' ? b.event.id : b.activity.id;
      return bId - aId;
    });
  }, [events, activities]);

  async function submitActivity() {
    if (!activityDraft.activityDate) return;
    await onAddActivity({
      activityDate: activityDraft.activityDate,
      kind: activityDraft.kind,
      note: activityDraft.note.trim() || null,
    });
    setActivityDraft(EMPTY_ACTIVITY_DRAFT);
  }

  return (
    <section className="panel">
      <div className="panel-header">
        <h2>ประวัติดีล</h2>
        <span className="text-2xs font-normal text-text-muted">History</span>
      </div>

      <div className="ticket-events">
        {rows.length === 0 && !loading ? (
          // FIX 1 (Opus review): when `canViewActivityFeed` is false,
          // `activities` is always `[]` because it was never fetched for
          // this viewer (see the parent's `activitiesQuery.enabled`) — an
          // empty `events` list here only proves the AUDIT TRAIL is empty,
          // not that "nothing has happened on this deal". The default copy
          // ("เหตุการณ์และกิจกรรมจะปรากฏที่นี่") promises the follow-up feed will
          // show up here too, which is untrue for these viewers — say what
          // this panel actually knows instead.
          canViewActivityFeed ? (
            <EmptyState icon="clock" title="ยังไม่มีประวัติ" description="เหตุการณ์และกิจกรรมจะปรากฏที่นี่" />
          ) : (
            <EmptyState
              icon="clock"
              title="ยังไม่มีประวัติการดำเนินการ"
              description="บันทึกกิจกรรมติดตามแสดงเฉพาะเจ้าของดีล ผู้จัดการฝ่ายขาย และ CEO"
            />
          )
        ) : (
          <ul className="m-0 flex list-none flex-col gap-2 p-0">
            {rows.map((row) => (
              row.type === 'event'
                ? <EventRow key={row.key} event={row.event} />
                : <ActivityRow key={row.key} activity={row.activity} />
            ))}
          </ul>
        )}
        {/* activitiesQuery loading never blocks the events that are already
            part of the ticket payload — this is an additive "more is coming"
            line, not a full-panel skeleton swap. */}
        {loading ? (
          <div className="px-1 pt-2">
            <SkeletonText lines={2} />
          </div>
        ) : null}
      </div>

      {canComment ? (
        <div className="flex flex-col gap-2 border-t border-border p-4">
          <textarea
            rows={2}
            value={commentText}
            onChange={(e) => onCommentTextChange?.(e.target.value)}
            placeholder="เพิ่มความคิดเห็น…"
            style={{ resize: 'vertical' }}
          />
          <button
            type="button" className="secondary-button self-end"
            onClick={onSubmitComment}
            disabled={commentSubmitting || !commentText.trim()}
          >
            ส่งความคิดเห็น
          </button>
        </div>
      ) : null}

      {canAddActivity ? (
        <div className="mt-1 flex flex-col gap-2 border-t border-border p-4">
          <h3 className="m-0 text-sm font-extrabold text-text">บันทึกกิจกรรมติดตาม</h3>
          <div className="grid grid-cols-1 gap-2 sm:grid-cols-[minmax(0,1fr)_minmax(0,1fr)]">
            <label className="flex flex-col gap-1 text-xs font-bold text-text-secondary">
              วันที่
              <input
                type="date" className="text-base"
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
          <button
            type="button" className="secondary-button self-end"
            disabled={addingActivity || !activityDraft.activityDate}
            onClick={submitActivity}
          >
            <Icon name="plus" size={14} />
            บันทึกกิจกรรม
          </button>
        </div>
      ) : null}
    </section>
  );
}
