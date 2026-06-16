import { Icon } from './Icon.jsx';

export function Toast({ toast, onDismiss }) {
  if (!toast) return null;
  const iconName = toast.kind === 'success' ? 'check' : toast.kind === 'error' ? 'close' : 'clipboard';
  return (
    <button type="button" className={`toast toast-${toast.kind}`} onClick={onDismiss}>
      <Icon name={iconName} size={20} />
      <span>{toast.message}</span>
    </button>
  );
}
