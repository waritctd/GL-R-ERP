import { useCallback, useEffect, useRef, useState } from 'react';
import { sanitizeToastMessage } from '../utils/userMessages.js';

export function useToast() {
  const [toast, setToast] = useState(null);
  const toastTimer = useRef(null);

  // useCallback([]) here is load-bearing, not a micro-optimization. App.jsx calls useToast()
  // once and threads `showToast` as a prop everywhere; dozens of call sites key an effect on it
  // (`}, [query.error, showToast])`). A plain function gets a new identity on every render of
  // whatever owns useToast() -- calling it does setToast(...), which re-renders the owner, which
  // hands back a new `showToast` identity, which re-fires every such effect for as long as the
  // query's `.error` stays truthy (with the app's default `retry: 1`, a failed query settles into
  // a PERMANENTLY truthy `.error`, so nothing ever breaks the cycle). React throws "Maximum update
  // depth exceeded" and the nearest ErrorBoundary replaces the page. See useToast.test.jsx for the
  // regression test and PR history (#426, and the fix that moved this to the hook itself) for
  // where this was previously patched per-call-site instead of at the source.
  //
  // The empty dep array is genuinely correct, not just convenient: `setToast` is React's
  // guaranteed-stable useState setter, `toastTimer` is a ref (mutated via `.current`, never read
  // reactively), and `sanitizeToastMessage` is a pure module-level import. Nothing in this body
  // closes over props, state, or anything else that changes across renders.
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
