import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { api } from './hrApi.js';
import { API_ROUTES } from './routes.js';

// #H1: EmployeeSignatureController's PUT returns 204 with NO body on success
// (EmployeeSignatureService.upload -> ResponseEntity.noContent().build()). The previous
// hrApi.employees.uploadSignature called `res.json()` unconditionally after the `res.ok` check,
// which throws "Unexpected end of JSON input" on an empty 204 body -- SignatureCard.jsx's
// onSuccess never ran, and the UI silently looked like the upload failed (its onError fired
// instead, with a generic parse-error message, not the real outcome).

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

  it('sends the file as multipart on the signature route', async () => {
    fetchMock.mockResolvedValue(new Response(null, { status: 204 }));
    await api.employees.uploadSignature(7, new Blob(['x'], { type: 'image/png' }));

    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [url, options] = fetchMock.mock.calls[0];
    expect(url).toBe(API_ROUTES.employees.signature(7));
    expect(options.method).toBe('PUT');
    expect(options.body).toBeInstanceOf(FormData);
  });

  it('a non-2xx response with a JSON error body throws that message, not a parse error', async () => {
    fetchMock.mockResolvedValue(new Response(JSON.stringify({ message: 'ไม่มีสิทธิ์อัปโหลดลายเซ็นนี้' }), {
      status: 403,
      headers: { 'content-type': 'application/json' },
    }));
    await expect(api.employees.uploadSignature(7, new Blob(['x'], { type: 'image/png' })))
      .rejects.toThrow('ไม่มีสิทธิ์อัปโหลดลายเซ็นนี้');
  });

  // A non-2xx status can ALSO carry no body (e.g. a 413 from a body-size limit upstream of the
  // controller) -- must not throw a second, unrelated parse error on top of the real failure.
  it('a non-2xx response with no body throws the generic Thai fallback, not a parse error', async () => {
    fetchMock.mockResolvedValue(new Response(null, { status: 413 }));
    await expect(api.employees.uploadSignature(7, new Blob(['x'], { type: 'image/png' })))
      .rejects.toThrow('อัปโหลดไม่สำเร็จ');
  });
});

// hasSignature is a PROBE (SignatureCard.jsx's #M1) -- it only ever reads `res.ok`, so it has no
// reason to pull the image bytes over the wire. EmployeeSignatureController declares only
// @GetMapping on this route; Spring MVC maps HEAD to the same handler transparently whenever GET
// is mapped (no separate @RequestMapping needed), stripping the body before it reaches the
// client -- so switching the probe from GET to HEAD changes nothing about what it observes.
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
