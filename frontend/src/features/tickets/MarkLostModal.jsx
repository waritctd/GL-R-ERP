import { ReasonModal } from './ReasonModal.jsx';
import { EMPTY_STAGE_CATALOG } from './stageCatalog.js';
import { labelledReasons, LOST_REASON_F_CODE, LOST_REASON_LABEL } from './stageMeta.js';

/** ปิดงาน · เสียงาน (drawing m-lost, 2026-11-16): one reason, shown as a chip WITH its F1–F8 code, + an optional
 * note. The stored values are DealLostReason's unchanged; the deal keeps its stage, so reopening resumes there. */
export function MarkLostModal({ catalog = EMPTY_STAGE_CATALOG, onClose, onSubmit, submitting }) {
  // The CODE set is DealLostReason.ORDER, served by /api/meta/deal-stages; only the Thai wording and the F tag
  // are this side's.
  const reasons = labelledReasons(catalog.lostReasons, LOST_REASON_LABEL)
    .map((reason) => ({ ...reason, tag: LOST_REASON_F_CODE[reason.code] }));

  return (
    <ReasonModal
      title="ปิดงาน · เสียงาน"
      subtitle="เลือกเหตุผลหนึ่งข้อ ใช้ทำรายงาน F1–F8 เหมือนเดิม เปิดงานใหม่ได้ภายหลัง"
      closeLabel="ยกเลิก"
      submitLabel="ยืนยันเสียงาน"
      ariaLabel="เหตุผลที่เสียงาน"
      notePlaceholder="เช่น คู่แข่งเสนอต่ำกว่า 12%"
      reasons={reasons}
      onClose={onClose}
      onSubmit={onSubmit}
      submitting={submitting}
    />
  );
}
