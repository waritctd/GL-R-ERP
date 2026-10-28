import { ConfirmDialog } from '../../components/common/ConfirmDialog.jsx';
import { CONFIRM_ORDER_FROM_QUOTATION_COPY } from './quotationMeta.js';

/**
 * The one confirm dialog in front of every door to the irreversible ยืนยันคำสั่งซื้อ (deal-page panel, pricing-request
 * page, direct-quotation sticky CTA): same title, copy and button label everywhere. The caller owns the open state and
 * what confirming does; `number` is the quotation / request code the copy names.
 */
export function ConfirmOrderDialog({ open, number, busy, onCancel, onConfirm, cancelLabel }) {
  return (
    <ConfirmDialog
      open={open}
      title="ยืนยันคำสั่งซื้อ"
      message={(
        <p className="confirm-dialog-message m-0 leading-normal text-text-secondary">
          {CONFIRM_ORDER_FROM_QUOTATION_COPY.lead(number)} <strong>{CONFIRM_ORDER_FROM_QUOTATION_COPY.stage}</strong>
          {' '}{CONFIRM_ORDER_FROM_QUOTATION_COPY.tail}
        </p>
      )}
      confirmLabel="ยืนยันคำสั่งซื้อ"
      {...(cancelLabel ? { cancelLabel } : {})}
      busy={busy}
      onCancel={onCancel}
      onConfirm={onConfirm}
    />
  );
}
