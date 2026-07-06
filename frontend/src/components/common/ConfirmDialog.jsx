import { useEffect, useRef, useState } from 'react';
import { Modal } from './Modal.jsx';

/**
 * Branded confirmation dialog built on top of `Modal` (focus trap + Escape +
 * focus restore already handled there). Replaces `window.confirm`/`window.prompt`
 * call sites across the app.
 *
 * When `requireReason` is true, a textarea is rendered and `onConfirm` is
 * called with the trimmed reason string instead of no arguments. Pass
 * `optionalReason` to allow an empty reason (e.g. "หมายเหตุการยกเลิก (ถ้ามี)"),
 * otherwise Confirm stays disabled until the reason is non-empty.
 */
export function ConfirmDialog({
  open,
  title,
  message,
  confirmLabel = 'ยืนยัน',
  cancelLabel = 'ยกเลิก',
  tone = 'default',
  busy = false,
  requireReason = false,
  optionalReason = false,
  reasonLabel = 'เหตุผล',
  reasonPlaceholder = '',
  onConfirm,
  onCancel,
}) {
  const [reason, setReason] = useState('');
  const reasonRef = useRef(null);
  const confirmButtonRef = useRef(null);

  useEffect(() => {
    if (!open) return;
    setReason('');
  }, [open]);

  useEffect(() => {
    if (!open) return;
    // Give the reason textarea initial focus when present, else the confirm button.
    const target = requireReason ? reasonRef.current : confirmButtonRef.current;
    target?.focus();
  }, [open, requireReason]);

  if (!open) return null;

  const reasonRequiredAndEmpty = requireReason && !optionalReason && reason.trim().length === 0;
  const confirmDisabled = busy || reasonRequiredAndEmpty;

  function handleConfirm() {
    if (confirmDisabled) return;
    if (requireReason) {
      onConfirm?.(reason.trim());
    } else {
      onConfirm?.();
    }
  }

  const confirmButtonClass = tone === 'danger' ? 'danger-button' : 'primary-button';

  return (
    <Modal
      title={title}
      onClose={busy ? undefined : onCancel}
      footer={
        <>
          <button type="button" className="secondary-button" onClick={onCancel} disabled={busy}>
            {cancelLabel}
          </button>
          <button
            type="button"
            ref={confirmButtonRef}
            className={confirmButtonClass}
            onClick={handleConfirm}
            disabled={confirmDisabled}
          >
            {busy ? 'กำลังดำเนินการ...' : confirmLabel}
          </button>
        </>
      }
    >
      {typeof message === 'string' ? <p className="confirm-dialog-message">{message}</p> : message}
      {requireReason ? (
        <label className="confirm-dialog-reason" htmlFor="confirm-dialog-reason">
          {reasonLabel}
          <textarea
            id="confirm-dialog-reason"
            ref={reasonRef}
            value={reason}
            onChange={(event) => setReason(event.target.value)}
            placeholder={reasonPlaceholder}
            disabled={busy}
          />
        </label>
      ) : null}
    </Modal>
  );
}
