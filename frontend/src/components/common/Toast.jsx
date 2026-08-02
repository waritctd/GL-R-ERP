import { Icon } from './Icon.jsx';

export function Toast({ toast, onDismiss }) {
  if (!toast) return null;
  const iconName = toast.kind === 'success' ? 'check' : toast.kind === 'error' ? 'close' : 'clipboard';
  const iconClassName = toast.kind === 'success'
    ? 'text-teal-focus'
    : toast.kind === 'error'
      ? 'text-danger-soft'
      : 'text-surface';
  // Errors interrupt (assertive); success/info are announced politely. role makes the
  // message reach assistive tech without replacing the dismiss button's semantics.
  const isError = toast.kind === 'error';
  return (
    <div
      className="pointer-events-none fixed right-6 bottom-[max(24px,calc(env(safe-area-inset-bottom)+16px))] z-[80] w-[min(420px,calc(100vw-32px))] max-[720px]:right-4 max-[720px]:bottom-[max(72px,calc(env(safe-area-inset-bottom)+16px))]"
      role={isError ? 'alert' : 'status'}
      aria-live={isError ? 'assertive' : 'polite'}
      aria-atomic="true"
    >
      <button
        type="button"
        className="pointer-events-auto inline-flex min-h-12 w-full items-center justify-start gap-[10px] rounded-md border-0 bg-text px-4 py-3 text-left text-surface shadow-md"
        onClick={onDismiss}
        aria-label={`ปิดข้อความ: ${toast.message}`}
      >
        <Icon name={iconName} size={20} className={iconClassName} />
        <span className="min-w-0 [overflow-wrap:anywhere]">{toast.message}</span>
      </button>
    </div>
  );
}
