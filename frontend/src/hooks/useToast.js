import { useEffect, useRef, useState } from 'react';

export function useToast() {
  const [toast, setToast] = useState(null);
  const toastTimer = useRef(null);

  function showToast(kind, message) {
    window.clearTimeout(toastTimer.current);
    setToast({ kind, message });
    toastTimer.current = window.setTimeout(() => setToast(null), 3200);
  }

  function dismissToast() {
    setToast(null);
  }

  useEffect(() => () => window.clearTimeout(toastTimer.current), []);

  return { toast, showToast, dismissToast };
}
