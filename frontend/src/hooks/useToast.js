import { useCallback, useEffect, useRef, useState } from 'react';
import { sanitizeToastMessage } from '../utils/userMessages.js';

export function useToast() {
  const [toast, setToast] = useState(null);
  const toastTimer = useRef(null);

  // useCallback([]) here is load-bearing, not a micro-optimization. App.jsx calls useToast() once and threads
  // `showToast` as a prop everywhere; dozens of call sites key an effect on it (`}, [query.error, showToast])`).
  const showToast = useCallback((kind, message) => {
    window.clearTimeout(toastTimer.current);
    setToast({ kind, message: sanitizeToastMessage(kind, message) });
    toastTimer.current = window.setTimeout(() => setToast(null), 3200);
  }, []);

  const dismissToast = useCallback(() => {
    setToast(null);
  }, []);

  useEffect(() => () => window.clearTimeout(toastTimer.current), []);

  return { toast, showToast, dismissToast };
}
