import { useEffect, useRef, useState } from 'react';
import { Modal } from './Modal.jsx';

/**
 * Tailwind port of `.confirm-dialog-message` (styles.css, retired). Exported
 * because ~13 call sites across the app render their own richer message body
 * (multiple paragraphs, inline totals) instead of passing a plain string, and
 * must keep matching this component's own message typography exactly.
 */
export const confirmDialogMessageClass = 'text-text-secondary leading-normal';

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
      {typeof message === 'string' ? <p className={confirmDialogMessageClass}>{message}</p> : message}
      {requireReason ? (
        <label className="grid gap-[7px] mt-[14px] text-text-secondary text-sm/[inherit] font-bold" htmlFor="confirm-dialog-reason">
          {reasonLabel}
          <textarea
            id="confirm-dialog-reason"
            ref={reasonRef}
            value={reason}
            onChange={(event) => setReason(event.target.value)}
            placeholder={reasonPlaceholder}
            disabled={busy}
            className="min-h-[84px]"
          />
          {reasonInvalid && reasonInvalidMessage ? (
            <small className="block text-danger">{reasonInvalidMessage}</small>
          ) : null}
        </label>
      ) : null}
    </Modal>
  );
}
