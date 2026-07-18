// Shared helper for triggering a browser download from a Blob API response.
//
// The file extension is derived from the blob's actual MIME type rather than
// trusted from the requested format. In VITE_USE_MOCKS=true mode every
// document endpoint returns a demo placeholder blob (text/plain for
// "xlsx" requests, text/html for "pdf" requests — see src/api/mockApi.js
// mockDocPlaceholderBlob/buildMockQuotationHtml) instead of a real xlsx/pdf,
// so trusting the requested format produced files like "quotation.xlsx"
// that were actually plain text. Falls back to the requested format when the
// MIME type isn't one we recognize (e.g. a real server content-type we
// haven't listed here).
const EXTENSION_BY_MIME = {
  'application/pdf': 'pdf',
  'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet': 'xlsx',
  'application/vnd.ms-excel': 'xls',
  'text/html': 'html',
  'text/plain': 'txt',
};

export function extensionForBlob(blob, requestedFormat) {
  const type = (blob?.type || '').split(';')[0].trim().toLowerCase();
  return EXTENSION_BY_MIME[type] ?? requestedFormat;
}

// Downloads `blob` as `${filenameBase}.<ext>`, picking <ext> via
// `extensionForBlob`. `requestedFormat` (e.g. 'xlsx' | 'pdf') is the fallback
// extension when the blob's MIME type isn't recognized.
export function downloadBlob(blob, filenameBase, requestedFormat) {
  const ext = extensionForBlob(blob, requestedFormat);
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = `${filenameBase}.${ext}`;
  a.click();
  URL.revokeObjectURL(url);
}
