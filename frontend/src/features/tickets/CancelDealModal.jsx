import { ReasonModal } from './ReasonModal.jsx';
import { EMPTY_STAGE_CATALOG } from './stageCatalog.js';
import { CANCEL_REASON_LABEL, labelledReasons } from './stageMeta.js';

/**
 * Cancel-deal modal — same shape as MarkLostModal, deliberately different
 * vocabulary. Lost means we were beaten on a deal we were competing for;
 * cancelled means the opportunity itself went away. Cancel previously recorded
 * nothing at all, so a cancelled deal carried no explanation (V56).
 *
 * Cancelling is irreversible, so the reason is required before the button
 * enables — matching mark-lost rather than the old bare confirm dialog.
 */
export function CancelDealModal({ catalog = EMPTY_STAGE_CATALOG, onClose, onSubmit, submitting }) {
  // Codes from DealCancelReason.ORDER via /api/meta/deal-stages; wording stays here.
  const reasons = labelledReasons(catalog.cancelReasons, CANCEL_REASON_LABEL);

  return (
    <ReasonModal
      title="ยกเลิกดีล"
      subtitle="เลือกเหตุผลที่โครงการไม่ได้ไปต่อ — ใช้ “เสียงาน” แทนหากลูกค้าซื้อจากคู่แข่ง"
      closeLabel="ปิด"
      submitLabel="ยืนยันยกเลิกดีล"
      ariaLabel="เหตุผลที่ยกเลิก"
      reasons={reasons}
      onClose={onClose}
      onSubmit={onSubmit}
      submitting={submitting}
    />
  );
}
