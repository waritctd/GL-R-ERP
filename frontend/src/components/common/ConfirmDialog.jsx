import { useEffect, useRef, useState } from 'react';
import { Modal } from './Modal.jsx';
import { Button } from './Button.jsx';

/**
 * Branded confirmation dialog built on top of `Modal` (focus trap + Escape +
 * focus restore already handled there). Replaces `window.confirm`/`window.prompt`
 * call sites across the app.
 *
 * When `requireReason` is true, a textarea is rendered and `onConfirm` is
 * called with the trimmed reason string instead of no arguments. Pass
 * `optionalReason` to allow an empty reason (e.g. "หมายเหตุการยกเลิก (ถ้ามี)"),
 * otherwise Confirm stays disabled until the reason is non-empty. `validateReason`
 * is an optional second gate for consequential flows that require a deliberate
 * typed phrase rather than any non-empty note.
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
  validateReason,
  reasonInvalidMessage,
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
  const reasonTrimmed = reason.trim();
  const reasonInvalid = requireReason
    && reasonTrimmed.length > 0
    && typeof validateReason === 'function'
    && !validateReason(reasonTrimmed);
  const confirmDisabled = busy || reasonRequiredAndEmpty || reasonInvalid;

  function handleConfirm() {
    if (confirmDisabled) return;
    if (requireReason) {
      onConfirm?.(reason.trim());
    } else {
      onConfirm?.();
    }
  }

  const confirmVariant = tone === 'danger' ? 'danger' : 'primary';

  return (
    <Modal
      title={title}
      onClose={busy ? undefined : onCancel}
      footer={
        <>
          <Button variant="secondary" onClick={onCancel} disabled={busy}>
            {cancelLabel}
          </Button>
          <Button
            ref={confirmButtonRef}
            variant={confirmVariant}
            onClick={handleConfirm}
            disabled={confirmDisabled}
          >
            {busy ? 'กำลังดำเนินการ...' : confirmLabel}
          </Button>
        </>
      }
    >
      {typeof message === 'string' ? <p className="confirm-dialog-message text-text-secondary leading-normal">{message}</p> : message}
      {requireReason ? (
        <label
          className="confirm-dialog-reason grid content-start gap-[7px] mt-[14px] text-text-secondary text-[length:var(--text-sm)] font-bold"
          htmlFor="confirm-dialog-reason"
        >
          {reasonLabel}
          <textarea
            id="confirm-dialog-reason"
            className="min-h-[84px]"
            ref={reasonRef}
            value={reason}
            onChange={(event) => setReason(event.target.value)}
            placeholder={reasonPlaceholder}
            disabled={busy}
          />
          {reasonInvalid && reasonInvalidMessage ? (
            <small className="block text-danger">{reasonInvalidMessage}</small>
          ) : null}
        </label>
      ) : null}
    </Modal>
  );
}
