import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { api } from './hrApi.js';
import { API_ROUTES } from './routes.js';

// Pins the FOUR-VALUE mapping `policyDocumentAvailable` makes from a raw HTTP response, directly against a
// stubbed `globalThis.fetch` -- see that method's own comment in hrApi.js for the full table and the reasoning
// behind each row.

function mockResponse({ status, ok, contentType }) {
  return {
    status,
    ok,
    headers: { get: (name) => (name.toLowerCase() === 'content-type' ? contentType ?? null : null) },
  };
}

describe('hrApi.leave.policyDocumentAvailable — HTTP response to label', () => {
  let fetchMock;

  beforeEach(() => {
    fetchMock = vi.fn();
    globalThis.fetch = fetchMock;
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  // Each row differs only in the stubbed response and the expected label. The rejection case stubs fetch
  // differently (no HTTP response at all), so it stays a separate test below.
  // Columns: test name, stubbed response, expected label.
  it.each([
    ['200 + content-type application/pdf -> available',
      { status: 200, ok: true, contentType: 'application/pdf' }, 'available'],
    // The server ANSWERED "nothing stored" -- that is confirmation the bundled fallback IS current, not a failure
    // to learn anything.
    ['404 -> absent', { status: 404, ok: false }, 'absent'],
    // A plain static host with no /api proxy answers every unmatched path with its SPA's index.html, as 200
    // text/html -- the exact case the content-type check exists to catch.
    ['200 but not a PDF (a SPA catch-all serving index.html) -> unverified',
      { status: 200, ok: true, contentType: 'text/html' }, 'unverified'],
    // A 500 is POSITIVE evidence a backend exists, could be holding a newer announcement, and is failing to say so
    // -- which is why it gets the STRONGER label, not the same one as a rejection.
    ['500 -> check-failed', { status: 500, ok: false }, 'check-failed'],
    ['502 -> check-failed', { status: 502, ok: false }, 'check-failed'],
    ['403 -> check-failed', { status: 403, ok: false }, 'check-failed'],
    // 401 is deliberately NOT special-cased.
    ['401 -> check-failed, deliberately with no special case', { status: 401, ok: false }, 'check-failed'],
  ])('%s', async (_name, response, label) => {
    fetchMock.mockResolvedValue(mockResponse(response));
    await expect(api.leave.policyDocumentAvailable()).resolves.toBe(label);
  });

  it('fetch rejects (no HTTP response at all -- network/DNS/CORS) -> unverified', async () => {
    fetchMock.mockRejectedValue(new TypeError('Failed to fetch'));
    await expect(api.leave.policyDocumentAvailable()).resolves.toBe('unverified');
  });

  it('probes with HEAD and credentials: include, no body transfer', async () => {
    fetchMock.mockResolvedValue(mockResponse({ status: 404, ok: false }));
    await api.leave.policyDocumentAvailable();

    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [url, options] = fetchMock.mock.calls[0];
    expect(url).toBe(API_ROUTES.leave.policyDocument);
    expect(options).toMatchObject({ method: 'HEAD', credentials: 'include' });
  });

  // Every row above still has a document to fall back to (the bundled copy), so this function does the falling
  // back itself rather than raising and forcing every caller to catch-and-fall-back independently.
  it('never rejects for any HTTP outcome, including a hard transport failure', async () => {
    const setups = [
      () => fetchMock.mockResolvedValue(mockResponse({ status: 200, ok: true, contentType: 'application/pdf' })),
      () => fetchMock.mockResolvedValue(mockResponse({ status: 404, ok: false })),
      () => fetchMock.mockResolvedValue(mockResponse({ status: 200, ok: true, contentType: 'text/html' })),
      () => fetchMock.mockRejectedValue(new TypeError('Failed to fetch')),
      () => fetchMock.mockResolvedValue(mockResponse({ status: 500, ok: false })),
      () => fetchMock.mockResolvedValue(mockResponse({ status: 502, ok: false })),
      () => fetchMock.mockResolvedValue(mockResponse({ status: 403, ok: false })),
      () => fetchMock.mockResolvedValue(mockResponse({ status: 401, ok: false })),
    ];
    // Sequential by design (each row must resolve before the next stub is installed) -- no-await-in-loop isn't
    // enabled in this project's ESLint config, so no disable directive is needed here (see TaxAllowancePage.jsx's
    // identical note).
    for (const setup of setups) {
      setup();
      await expect(api.leave.policyDocumentAvailable()).resolves.toEqual(expect.any(String));
    }
  });
});
