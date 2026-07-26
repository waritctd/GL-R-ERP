import { Icon } from '../../../components/common/Icon.jsx';
import { formatThaiDate } from '../../../utils/format.js';

const EVENT_KIND_LABEL = {
  CREATED: 'สร้างดีล',
  STAGE_CHANGED: 'เปลี่ยนสถานะดีล',
  MARKED_LOST: 'เสียงาน',
  REOPENED: 'เปิดดีลอีกครั้ง',
  ON_HOLD: 'พักดีลไว้',
  DORMANT: 'พักดีลไม่เคลื่อนไหว',
  RESUMED: 'ดำเนินการต่อ',
  POLICY_CHANGED: 'เปลี่ยนนโยบายดีล',
  CANCELLED: 'ยกเลิก',
  SUBMITTED: 'ส่งเรื่องเข้าระบบ',
  PICKED_UP: 'รับมอบหมาย',
  PRICE_PROPOSED: 'เสนอราคาสินค้า',
  APPROVED: 'อนุมัติ',
  REJECTED: 'ปฏิเสธ',
  QUOTATION_ISSUED: 'ออกใบเสนอราคา',
  QUOTATION_SENT: 'ส่งใบเสนอราคา',
  QUOTATION_ACCEPTED: 'ลูกค้ารับใบเสนอราคา',
  QUOTATION_REJECTED: 'ลูกค้าปฏิเสธใบเสนอราคา',
  DOCUMENT_ISSUED: 'ออกเอกสาร',
  PRICE_REVISED: 'แก้ไขราคาที่เสนอ',
  REVISION_REQUESTED: 'ขอแก้ไข',
  EDITED: 'แก้ไขรายการสินค้า',
  PAYMENT_RECORDED: 'บันทึกรับชำระเงิน',
  BILLING_UPDATED: 'อัปเดตข้อมูลวางบิล',
  COMMENTED: 'ความคิดเห็น',
  COMMENT: 'ความคิดเห็น',
  PRICE_OVERRIDDEN: 'แก้ไขราคาด้วยตนเอง (CEO)',
  CUSTOMER_CONFIRMED: 'ลูกค้ายืนยันคำสั่งซื้อ',
  DEPOSIT_NOTICE_ISSUED: 'ออกใบแจ้งมัดจำ',
  DEPOSIT_PAID: 'รับมัดจำแล้ว',
  IR_ISSUED: 'ออก Import Request (IR)',
  IR_SENT: 'ส่ง IR แล้ว',
  SHIPPING: 'สินค้าออกเดินทาง (Shipping)',
  GOODS_RECEIVED: 'รับสินค้าแล้ว',
  STOCK_RESERVED: 'จองสินค้าจากสต็อก',
  DELIVERY_RECORDED: 'บันทึกการส่งสินค้า',
  DELIVERY_COMPLETED: 'ส่งมอบครบแล้ว',
  AWAITING_FINAL_PAYMENT: 'รอชำระส่วนที่เหลือ',
  FULLY_PAID: 'ชำระครบแล้ว',
  CLOSED: 'ปิดเรื่อง',
  CLOSE_CONFIRMED: 'บัญชียืนยันพร้อมปิดงาน',
  CLOSE_VERIFIED: 'CEO ตรวจปิดงาน',
};

const PAYMENT_TRACK_KINDS = new Set([
  'CUSTOMER_CONFIRMED', 'DEPOSIT_NOTICE_ISSUED', 'DEPOSIT_PAID',
  'AWAITING_FINAL_PAYMENT', 'FULLY_PAID', 'PAYMENT_RECORDED', 'BILLING_UPDATED',
]);

const FULFILLMENT_TRACK_KINDS = new Set([
  'IR_ISSUED', 'IR_SENT', 'SHIPPING', 'GOODS_RECEIVED',
  'STOCK_RESERVED', 'DELIVERY_RECORDED', 'DELIVERY_COMPLETED',
]);

function eventDotClass(kind) {
  if (kind === 'CREATED') return 'event-dot created';
  if (kind === 'COMMENTED' || kind === 'COMMENT') return 'event-dot comment';
  if (PAYMENT_TRACK_KINDS.has(kind)) return 'event-dot success';
  if (FULFILLMENT_TRACK_KINDS.has(kind)) return 'event-dot transition';
  return 'event-dot transition';
}

function eventSnapshotItems(event) {
  if (event.kind !== 'PRICE_PROPOSED' || !event.itemSnapshot) return null;
  try {
    const parsed = JSON.parse(event.itemSnapshot);
    return Array.isArray(parsed) ? parsed : null;
  } catch {
    return null;
  }
}

export function DealActivityTimeline({
  events = [],
  canComment = false,
  commentText = '',
  onCommentTextChange,
  onSubmitComment,
  submitting = false,
}) {
  const orderedEvents = [...events].reverse();

  return (
    <section className="panel" data-testid="deal-activity-timeline">
      <div className="panel-header">
        <div>
          <h2>ประวัติการดำเนินการ</h2>
          <p className="mt-1 text-xs text-text-muted">
            เหตุการณ์ล่าสุด ความคิดเห็น และหลักฐานการเปลี่ยนสถานะ
          </p>
        </div>
      </div>

      <div className="px-4 pb-4 sm:px-5">
        {orderedEvents.length === 0 ? (
          <div className="rounded-md bg-surface-subtle px-3 py-3 text-sm">
            <strong className="block text-text">ยังไม่มีประวัติ</strong>
            <span className="mt-1 block text-xs text-text-muted">
              เหตุการณ์และความคิดเห็นจะปรากฏที่นี่เมื่อเริ่มมีการดำเนินการ
            </span>
          </div>
        ) : (
          <ol className="ticket-events m-0 list-none divide-y divide-border-subtle border-y border-border-subtle p-0" aria-label="ลำดับเหตุการณ์ดีล">
            {orderedEvents.map((event) => {
              const snapItems = eventSnapshotItems(event);
              return (
                <li key={event.id} className="ticket-event border-b-0">
                  <span className={eventDotClass(event.kind)} />
                  <div className="min-w-0 flex-1">
                    <div className="flex flex-wrap items-baseline gap-x-2 gap-y-1">
                      <strong className="text-sm text-text">
                        {EVENT_KIND_LABEL[event.kind] ?? event.kind}
                      </strong>
                      <span className="text-xs text-icon-muted">{event.actorName}</span>
                      <small className="text-xs text-text-muted">{formatThaiDate(event.createdAt)}</small>
                    </div>
                    {event.message ? (
                      <p className="m-0 mt-1 text-xs text-text-secondary">{event.message}</p>
                    ) : null}
                    {snapItems && snapItems.length > 0 ? (
                      <details className="mt-2 text-xs text-text-muted">
                        <summary className="cursor-pointer font-bold text-link">ดูรายการสินค้า ณ เวลาที่เสนอราคา</summary>
                        <div className="mt-2 divide-y divide-border-subtle border-y border-border-subtle">
                          {snapItems.map((item, index) => (
                            <div key={`${item.brand}-${item.model}-${index}`} className="py-1.5">
                              <span className="font-semibold text-text-secondary">
                                {item.brand} {item.model}
                              </span>
                              <span className="ml-1">
                                {item.qty} ชิ้น
                                {item.rawPrice != null ? ` @ ${item.rawPrice} ${item.rawCurrency}/${item.rawUnit}` : ''}
                              </span>
                            </div>
                          ))}
                        </div>
                      </details>
                    ) : null}
                  </div>
                </li>
              );
            })}
          </ol>
        )}

        {canComment ? (
          <div className="mt-4 flex flex-col gap-2 border-t border-border-subtle pt-4">
            <label className="flex flex-col gap-1.5 text-xs font-bold text-text-secondary">
              เพิ่มความคิดเห็น
              <textarea
                rows={2}
                value={commentText}
                onChange={(event) => onCommentTextChange?.(event.target.value)}
                placeholder="เพิ่มความคิดเห็น..."
                style={{ resize: 'vertical' }}
              />
            </label>
            <button
              type="button"
              className="secondary-button self-end"
              onClick={onSubmitComment}
              disabled={submitting || !commentText.trim()}
            >
              <Icon name="plus" size={14} />
              ส่งความคิดเห็น
            </button>
          </div>
        ) : null}
      </div>
    </section>
  );
}
