import { useEffect, useRef } from 'react';

// Announced politely (role=status, aria-live) and focused on its เลิกทำ when it appears, so a keyboard or
// screen-reader user can reach the undo inside its window.
export function StepUndoSnackbar({ undo, busy, onUndo }) {
  const buttonRef = useRef(null);
  useEffect(() => { buttonRef.current?.focus(); }, [undo.key]);
  return (
    <div
      role="status"
      aria-live="polite"
      data-testid="step-undo-snackbar"
      className="fixed inset-x-4 bottom-4 z-40 mx-auto flex max-w-xl flex-wrap items-center justify-between gap-x-4 gap-y-1 rounded-md bg-text px-4 py-3 text-sm text-surface shadow-lg"
    >
      <span className="min-w-0 [overflow-wrap:anywhere]">เลื่อนเป็น “{undo.label}”{undo.title ? ` · ${undo.title}` : ''}</span>
      <button
        ref={buttonRef}
        type="button"
        disabled={busy}
        onClick={onUndo}
        className="min-h-11 shrink-0 cursor-pointer rounded-md border-0 bg-transparent px-2 font-bold text-surface underline disabled:opacity-60"
      >
        เลิกทำ
      </button>
    </div>
  );
}
