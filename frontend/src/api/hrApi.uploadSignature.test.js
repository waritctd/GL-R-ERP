import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { api } from './hrApi.js';
import { API_ROUTES } from './routes.js';

// #H1: EmployeeSignatureController's PUT returns 204 with NO body on success (EmployeeSignatureService.upload
// -> ResponseEntity.noContent().build()).

describe('hrApi.employees.uploadSignature -- 204 no-body response', () => {
  let fetchMock;

  beforeEach(() => {
    fetchMock = vi.fn();
    globalThis.fetch = fetchMock;
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('a 204 response with no body resolves to null, not a JSON-parse throw', async () => {
    fetchMock.mockResolvedValue(new Response(null, { status: 204 }));
    await expect(api.employees.uploadSignature(7, new Blob(['x'], { type: 'image/png' }))).resolves.toBeNull();
  });

  it('a non-2xx response with a JSON error body throws that message, not a parse error', async () => {
    fetchMock.mockResolvedValue(new Response(JSON.stringify({ message: 'ไม่มีสิทธิ์อัปโหลดลายเซ็นนี้' }), {
      status: 403,
      headers: { 'content-type': 'application/json' },
    }));
    await expect(api.employees.uploadSignature(7, new Blob(['x'], { type: 'image/png' })))
      .rejects.toThrow('ไม่มีสิทธิ์อัปโหลดลายเซ็นนี้');
  });

});

// hasSignature is a PROBE (SignatureCard.jsx's #M1) -- it only ever reads `res.ok`, so it has no reason to
// pull the image bytes over the wire.
describe('hrApi.employees.hasSignature -- probes with HEAD, not GET', () => {
  let fetchMock;

  beforeEach(() => {
    fetchMock = vi.fn();
    globalThis.fetch = fetchMock;
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('sends a HEAD request to the signature route', async () => {
    fetchMock.mockResolvedValue(new Response(null, { status: 200 }));
    await api.employees.hasSignature(7);

    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [url, options] = fetchMock.mock.calls[0];
    expect(url).toBe(API_ROUTES.employees.signature(7));
    expect(options.method).toBe('HEAD');
  });

  it('resolves true on a 200 and false on a 404, same as the old GET probe', async () => {
    fetchMock.mockResolvedValueOnce(new Response(null, { status: 200 }));
    await expect(api.employees.hasSignature(7)).resolves.toBe(true);

    fetchMock.mockResolvedValueOnce(new Response(null, { status: 404 }));
    await expect(api.employees.hasSignature(7)).resolves.toBe(false);
  });
});
