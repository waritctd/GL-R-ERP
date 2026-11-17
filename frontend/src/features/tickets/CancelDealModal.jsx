import { ReasonModal } from './ReasonModal.jsx';
import { EMPTY_STAGE_CATALOG } from './stageCatalog.js';
import { CANCEL_REASON_LABEL, labelledReasons } from './stageMeta.js';

/** ยกเลิกงาน (drawing m-cancel, 2026-11-16) — same shape as ปิดงาน · เสียงาน, deliberately different meaning: lost
 * means we were beaten on a deal we were competing for; cancelled means the deal was opened by mistake. The offered
 * codes are DealCancelReason.OFFERED, served as the catalog's `cancelReasons`; retired codes stay labelled in
 * CANCEL_REASON_LABEL for deals already cancelled with them. */
export function CancelDealModal({ catalog = EMPTY_STAGE_CATALOG, onClose, onSubmit, submitting }) {
  const reasons = labelledReasons(catalog.cancelReasons, CANCEL_REASON_LABEL);

  return (
    <ReasonModal
      title="ยกเลิกงาน"
      subtitle="ใช้เมื่อเปิดงานผิดหรือซ้ำ ไม่นับเป็นเสียงาน"
      closeLabel="กลับ"
      submitLabel="ยกเลิกงาน"
      ariaLabel="เหตุผลที่ยกเลิก"
      notePlaceholder="เช่น ซ้ำกับ PR-2026-0028"
      warning="ยกเลิกแล้วกลับมาไม่ได้ ถ้ายังมีโอกาสขาย ใช้ “พักงาน” หรือ “เสียงาน” แทน"
      reasons={reasons}
      onClose={onClose}
      onSubmit={onSubmit}
      submitting={submitting}
    />
  );
}
