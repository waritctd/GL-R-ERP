import { Icon } from './Icon.jsx';

export function Toast({ toast, onDismiss }) {
  if (!toast) return null;
  const iconName = toast.kind === 'success' ? 'check' : toast.kind === 'error' ? 'close' : 'clipboard';
  // Errors interrupt (assertive); success/info are announced politely. role makes the
  // message reach assistive tech even though it is rendered as a dismiss button.
  const isError = toast.kind === 'error';
  return (
    <button
      type="button"
      className={`toast toast-${toast.kind}`}
      onClick={onDismiss}
      role={isError ? 'alert' : 'status'}
      aria-live={isError ? 'assertive' : 'polite'}
    >
      <Icon name={iconName} size={20} />
      <span>{toast.message}</span>
    </button>
  );
}
