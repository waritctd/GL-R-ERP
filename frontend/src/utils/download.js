// Shared helper for triggering a browser download from a Blob API response. The file extension is derived from
// the blob's actual MIME type rather than trusted from the requested format.
import { API_BASE_URL } from '../api/client.js';

const EXTENSION_BY_MIME = {
  'application/pdf': 'pdf',
  'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet': 'xlsx',
  'application/vnd.ms-excel': 'xls',
  'text/html': 'html',
  'text/plain': 'txt',
};

function extensionForBlob(blob, requestedFormat) {
  const type = (blob?.type || '').split(';')[0].trim().toLowerCase();
  return EXTENSION_BY_MIME[type] ?? requestedFormat;
}

// Downloads `blob` as `${filenameBase}.<ext>`, picking <ext> via `extensionForBlob`. `requestedFormat` (e.g.
// 'xlsx' | 'pdf') is the fallback extension when the blob's MIME type isn't recognized.
export function downloadBlob(blob, filenameBase, requestedFormat) {
  const ext = extensionForBlob(blob, requestedFormat);
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = `${filenameBase}.${ext}`;
  a.click();
  URL.revokeObjectURL(url);
}

/** Fetches a document the server pointed us at by path (e.g. a FinanceDealDto `downloadPath`, which targets the
 * EXISTING per-document file endpoint — that endpoint enforces its own authz). Plain GET with the session
 * cookie, like the typed hrApi downloads. */
export async function fetchDocumentBlob(path) {
  const refused = () => new Error('ดาวน์โหลดไม่สำเร็จ');
  if (typeof path !== 'string' || !path) throw refused();
  // Resolve exactly as the browser will, then compare origins: a hand-rolled regex misses browser URL
  // normalisation ('/\\evil.com/x', '\\\\evil.com/x' and '/\t/evil.com/x' all resolve cross-origin).
  let url;
  try {
    url = new URL(path, window.location.origin);
  } catch {
    throw refused();
  }
  if (url.origin !== window.location.origin || !/^\/api\//.test(url.pathname)) throw refused();
  const demo = import.meta.env.VITE_USE_MOCKS === 'true';
  let failure = refused();
  try {
    const res = await fetch(`${API_BASE_URL}${url.pathname}${url.search}`, { credentials: 'include' });
    if (res.ok) return res.blob();
    let message = null;
    try {
      const payload = await res.json();
      message = typeof payload === 'string' ? payload : payload?.message;
    } catch { /* non-JSON error body */ }
    if (message) failure = new Error(message);
  } catch {
    // network failure: keep the generic refusal message
  }
  if (demo) return new Blob(['Demo Mode — ไฟล์จริงสร้างจาก server\n', path], { type: 'text/plain;charset=utf-8' });
  throw failure;
}
