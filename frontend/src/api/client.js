export class ApiError extends Error {
  constructor(message, status, details) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.details = details;
  }
}

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? '';

const SAFE_METHODS = new Set(['GET', 'HEAD', 'OPTIONS', 'TRACE']);

// ── Session expiry ──────────────────────────────────────────────────────────
//
// Sessions idle out after 30 minutes (`app.session.timeout`, application.yml), and the app had
// nowhere to notice. `App.jsx` handled 401 on the login form and on the boot `auth.me` probe only,
// so a session that died mid-use left every subsequent query throwing "คำขอไม่สำเร็จ" — a generic
// failure with no hint that signing in again is the fix — until the user reloaded of their own
// accord. Reported as an open gap by the pre-UAT integration check.
//
// A subscriber rather than a direct import of app state: `client.js` is the transport and must not
// know about React. `App.jsx` registers a handler; if none is registered (unit tests, mock mode,
// anything driving hrApi directly), this is inert.
let unauthorizedHandler = null;

/**
 * Registers the "the server says we are no longer signed in" callback. Returns an unsubscribe
 * function, so a caller can register from an effect and clean up on unmount.
 */
export function setUnauthorizedHandler(handler) {
  unauthorizedHandler = handler;
  return () => {
    if (unauthorizedHandler === handler) unauthorizedHandler = null;
  };
}

// Endpoints whose 401 is NORMAL and must not be read as an expiry:
//   • `login`  — a wrong password is a 401, and announcing "your session expired" over the login
//                form the user is already looking at is nonsense.
//   • `me`     — the boot probe. Every first, anonymous visit answers 401 here; that is how the
//                app decides to show the login screen at all.
//   • `logout` — signing out of an already-dead session must not toast on the way out.
const SESSION_PROBE_PATHS = ['/api/auth/login', '/api/auth/me', '/api/auth/logout'];

function readCookie(name) {
  const prefix = `${name}=`;
  const entry = document.cookie.split('; ').find((part) => part.startsWith(prefix));
  return entry ? decodeURIComponent(entry.slice(prefix.length)) : null;
}

export async function apiRequest(path, options = {}) {
  const { method = 'GET', body, headers, signal } = options;
  const response = await fetch(`${API_BASE_URL}${path}`, {
    method,
    credentials: 'include',
    headers: {
      ...(body ? { 'Content-Type': 'application/json' } : {}),
      ...csrfHeaders(method),
      ...headers,
    },
    body: body ? JSON.stringify(body) : undefined,
    signal,
  });

  if (response.status === 204) return null;

  const contentType = response.headers.get('content-type') ?? '';
  const payload = contentType.includes('application/json') ? await response.json() : await response.text();

  if (!response.ok) {
    // Fire BEFORE throwing, so it runs regardless of what each caller does with the rejection —
    // several of them swallow it (`.catch(() => null)` on a dashboard tile), and an expiry noticed
    // only by the callers that happen to rethrow would be noticed almost nowhere.
    if (response.status === 401 && !SESSION_PROBE_PATHS.some((probe) => path.startsWith(probe))) {
      unauthorizedHandler?.();
    }
    const message = typeof payload === 'string' ? payload : payload?.message;
    throw new ApiError(message || 'คำขอไม่สำเร็จ', response.status, payload);
  }

  return payload;
}

export function csrfHeaders(method = 'POST') {
  const csrfToken = SAFE_METHODS.has(method.toUpperCase()) ? null : readCookie('XSRF-TOKEN');
  return csrfToken ? { 'X-XSRF-TOKEN': csrfToken } : {};
}
