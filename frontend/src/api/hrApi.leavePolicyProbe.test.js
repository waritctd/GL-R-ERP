import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { api } from './hrApi.js';
import { API_ROUTES } from './routes.js';

// Pins the FOUR-VALUE mapping `policyDocumentAvailable` makes from a raw HTTP response, directly
// against a stubbed `globalThis.fetch` -- see that method's own comment in hrApi.js for the full
// table and the reasoning behind each row. The mapping is MADE in hrApi.js, not mirrored anywhere
// else (mockApi.js only ever answers two of the four rows -- see its own "A STRING ENUM, NOT A
// BOOLEAN" comment), so it has to be pinned here, not only through a component that happens to
// call it.
//
// `res.ok` alone cannot see this table: it collapses 404 in with every other non-2xx status, and
// would read a non-PDF 200 (a static host's SPA catch-all serving index.html) as "available".
// Every row below exists because a naive `res.ok`/`res.status` check gets at least one of them
// wrong.

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

  it('200 + content-type application/pdf -> available', async () => {
    fetchMock.mockResolvedValue(mockResponse({ status: 200, ok: true, contentType: 'application/pdf' }));
    await expect(api.leave.policyDocumentAvailable()).resolves.toBe('available');
  });

  // The server ANSWERED "nothing stored" -- that is confirmation the bundled fallback IS current,
  // not a failure to learn anything.
  it('404 -> absent', async () => {
    fetchMock.mockResolvedValue(mockResponse({ status: 404, ok: false }));
    await expect(api.leave.policyDocumentAvailable()).resolves.toBe('absent');
  });

  // A plain static host with no /api proxy answers every unmatched path with its SPA's
  // index.html, as 200 text/html -- the exact case the content-type check exists to catch.
  it('200 but not a PDF (a SPA catch-all serving index.html) -> unverified', async () => {
    fetchMock.mockResolvedValue(mockResponse({ status: 200, ok: true, contentType: 'text/html' }));
    await expect(api.leave.policyDocumentAvailable()).resolves.toBe('unverified');
  });

  it('fetch rejects (no HTTP response at all -- network/DNS/CORS) -> unverified', async () => {
    fetchMock.mockRejectedValue(new TypeError('Failed to fetch'));
    await expect(api.leave.policyDocumentAvailable()).resolves.toBe('unverified');
  });

  // A 500 is POSITIVE evidence a backend exists, could be holding a newer announcement, and is
  // failing to say so -- which is why it gets the STRONGER label, not the same one as a rejection.
  it('500 -> check-failed', async () => {
    fetchMock.mockResolvedValue(mockResponse({ status: 500, ok: false }));
    await expect(api.leave.policyDocumentAvailable()).resolves.toBe('check-failed');
  });

  it('502 -> check-failed', async () => {
    fetchMock.mockResolvedValue(mockResponse({ status: 502, ok: false }));
    await expect(api.leave.policyDocumentAvailable()).resolves.toBe('check-failed');
  });

  it('403 -> check-failed', async () => {
    fetchMock.mockResolvedValue(mockResponse({ status: 403, ok: false }));
    await expect(api.leave.policyDocumentAvailable()).resolves.toBe('check-failed');
  });

  // 401 is deliberately NOT special-cased. This app has no global 401 handler to defer to -- the
  // only `status === 401` handling anywhere is the login form itself (App.jsx) -- and inventing a
  // second, competing re-auth path inside this reference bar would be worse than letting a 401
  // land in the same generic "a backend refused to answer" bucket every other non-404 status does.
  it('401 -> check-failed, deliberately with no special case', async () => {
    fetchMock.mockResolvedValue(mockResponse({ status: 401, ok: false }));
    await expect(api.leave.policyDocumentAvailable()).resolves.toBe('check-failed');
  });

  it('probes with HEAD and credentials: include, no body transfer', async () => {
    fetchMock.mockResolvedValue(mockResponse({ status: 404, ok: false }));
    await api.leave.policyDocumentAvailable();

    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [url, options] = fetchMock.mock.calls[0];
    expect(url).toBe(API_ROUTES.leave.policyDocument);
    expect(options).toMatchObject({ method: 'HEAD', credentials: 'include' });
  });

  // Every row above still has a document to fall back to (the bundled copy), so this function does
  // the falling back itself rather than raising and forcing every caller to catch-and-fall-back
  // independently. Proven directly (not just implied by the individual `.resolves.toBe` assertions
  // above), because it is the one property a later edit could weaken silently while every
  // individual value-mapping test still passes for whatever it was newly thrown around.
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
    // Sequential by design (each row must resolve before the next stub is installed) --
    // no-await-in-loop isn't enabled in this project's ESLint config, so no disable directive is
    // needed here (see TaxAllowancePage.jsx's identical note).
    for (const setup of setups) {
      setup();
      await expect(api.leave.policyDocumentAvailable()).resolves.toEqual(expect.any(String));
    }
  });
});
