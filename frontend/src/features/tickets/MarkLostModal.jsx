import { ReasonModal } from './ReasonModal.jsx';
import { EMPTY_STAGE_CATALOG } from './stageCatalog.js';
import { labelledReasons, LOST_REASON_LABEL } from './stageMeta.js';

/**
 * Mark-lost modal (from the Claude Design prototype): one reason from the
 * standardized list + optional note. The deal keeps its stage — reopening
 * later resumes exactly where it was.
 */
export function MarkLostModal({ catalog = EMPTY_STAGE_CATALOG, onClose, onSubmit, submitting }) {
  // The CODE set is DealLostReason.ORDER, served by /api/meta/deal-stages; only the Thai wording is
  // this side's. The hardcoded list this replaced was a copy of that enum with no guard.
  const reasons = labelledReasons(catalog.lostReasons, LOST_REASON_LABEL);

  return (
    <ReasonModal
      title="ทำเครื่องหมายเสียงาน"
      subtitle="เลือกเหตุผล — ดีลจะย้ายไปกลุ่ม “เสียงาน” และเปิดใหม่ได้ภายหลังโดยสถานะเดิมยังอยู่"
      closeLabel="ยกเลิก"
      submitLabel="ยืนยันเสียงาน"
      ariaLabel="เหตุผลที่เสียงาน"
      reasons={reasons}
      onClose={onClose}
      onSubmit={onSubmit}
      submitting={submitting}
    />
  );
}
