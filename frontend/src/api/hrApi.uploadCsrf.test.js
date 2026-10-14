import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { api } from './hrApi.js';
import source from './hrApi.js?raw';
import { API_ROUTES } from './routes.js';

// CsrfCookieFilter 403s every unsafe /api/ request whose X-XSRF-TOKEN header does not match the
// XSRF-TOKEN cookie, and it has no exemption for multipart bodies (CsrfCookieFilterTest pins that
// half). apiRequest() adds the header itself, but an upload needs a raw fetch() for its FormData
// body, so each one has to add it by hand. Five did not, and were refused with "Invalid CSRF token"
// on every attempt against the real backend. The mock never noticed: it has no CSRF filter.

const TOKEN = 'tok-upload-123';
const file = () => new Blob(['x'], { type: 'application/pdf' });

const UPLOADS = [
  ['attachments.upload (ไฟล์แนบของดีล)',
    () => api.attachments.upload(11, file(), 'OTHER'),
    () => API_ROUTES.attachments.upload(11)],
  ['commissions.create with an invoice file',
    () => api.commissions.create({
      invoiceNumber: 'IV-1', invoiceDate: '2026-10-01', grossAmount: 100, invoiceAttachment: file(),
    }),
    () => API_ROUTES.commissions.create],
  ['commissions.createFromDeal (บันทึกใบกำกับ)',
    () => api.commissions.createFromDeal({
      ticketId: 5, invoiceNumber: 'IV-1', invoiceDate: '2026-10-01', invoiceAttachment: file(),
    }),
    () => API_ROUTES.commissions.createFromDeal],
  ['pricingRequests.uploadFactoryQuoteAttachment (ใบเสนอราคาโรงงาน)',
    () => api.pricingRequests.uploadFactoryQuoteAttachment(7, file()),
    () => API_ROUTES.pricingRequests.factoryQuoteAttachments(7)],
  ['pricingRequests.uploadAttachment (ไฟล์ประกอบคำขอราคา)',
    () => api.pricingRequests.uploadAttachment(7, file()),
    () => API_ROUTES.pricingRequests.attachments(7)],
  // Written after the five above were fixed, and listed so it can never join them. The route is
  // spelled out, because a helper in routes.js would only agree with itself.
  ['dealQuotations.uploadAttachment (เอกสารแนบของใบเสนอราคา)',
    () => api.dealQuotations.uploadAttachment(7, file()),
    () => '/api/deal-quotations/7/attachments'],
];

describe('multipart uploads carry the CSRF header', () => {
  let fetchMock;

  beforeEach(() => {
    document.cookie = `XSRF-TOKEN=${TOKEN}`;
    fetchMock = vi.fn(async () => new Response('{}', {
      status: 200, headers: { 'content-type': 'application/json' },
    }));
    globalThis.fetch = fetchMock;
  });

  afterEach(() => {
    document.cookie = 'XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT';
    vi.restoreAllMocks();
  });

  it.each(UPLOADS)('%s sends X-XSRF-TOKEN from the cookie', async (_name, call, route) => {
    await call();

    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [url, options] = fetchMock.mock.calls[0];
    expect(url).toBe(route());
    expect(options.method).toBe('POST');
    expect(new Headers(options.headers).get('X-XSRF-TOKEN')).toBe(TOKEN);
  });

  // The browser has to write the multipart boundary itself; a hand-set Content-Type would drop it
  // and the server could not parse the body.
  it.each(UPLOADS)('%s still sends a FormData body with no hand-set Content-Type', async (_name, call) => {
    await call();

    const [, options] = fetchMock.mock.calls[0];
    expect(options.body).toBeInstanceOf(FormData);
    expect(new Headers(options.headers).has('content-type')).toBe(false);
  });
});

// The five above are the ones found by hand. This reads the source so the NEXT raw fetch() that
// forgets the header fails here by line number, instead of in production.
describe('every unsafe raw fetch() in hrApi.js sends the CSRF header', () => {
  function rawFetchCalls() {
    const calls = [];
    const pattern = /(?<![\w.])fetch\(/g;
    for (let match = pattern.exec(source); match; match = pattern.exec(source)) {
      let depth = 1;
      let end = match.index + match[0].length;
      while (depth > 0 && end < source.length) {
        if (source[end] === '(') depth += 1;
        if (source[end] === ')') depth -= 1;
        end += 1;
      }
      calls.push({
        line: source.slice(0, match.index).split('\n').length,
        text: source.slice(match.index, end),
      });
    }
    return calls;
  }

  const unsafe = rawFetchCalls().filter((call) => /method:\s*['"`](POST|PUT|PATCH|DELETE)['"`]/.test(call.text));

  it('finds the raw unsafe fetch() calls it is meant to check', () => {
    expect(unsafe.length).toBeGreaterThanOrEqual(UPLOADS.length);
  });

  it('none of them omits the header', () => {
    const missing = unsafe
      .filter((call) => !/csrfHeaders\(|X-XSRF-TOKEN/.test(call.text))
      .map((call) => `hrApi.js:${call.line}`);
    expect(missing).toEqual([]);
  });
});
