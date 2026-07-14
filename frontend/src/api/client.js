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
    const message = typeof payload === 'string' ? payload : payload?.message;
    throw new ApiError(message || 'Request failed', response.status, payload);
  }

  return payload;
}

export function csrfHeaders(method = 'POST') {
  const csrfToken = SAFE_METHODS.has(method.toUpperCase()) ? null : readCookie('XSRF-TOKEN');
  return csrfToken ? { 'X-XSRF-TOKEN': csrfToken } : {};
}
