import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { api } from './hrApi.js';

// Calls the page makes for a quotation's เอกสารแนบ, as the REAL client issues them.
// Mirrors th.co.glr.hr.dealquotation.DealQuotationAttachmentController:
//
//   GET    /api/deal-quotations/{id}/attachments             → { items }
//   POST   /api/deal-quotations/{id}/attachments  (multipart `file`) → { attachment }
//   GET    /api/deal-quotation-attachments/{attachmentId}/file       → the bytes
//   DELETE /api/deal-quotation-attachments/{attachmentId}            → { ok }
//
// The paths are written out here, not read from API_ROUTES. A route helper that drifted from the
// controller would otherwise agree with itself. The upload's CSRF header is ALSO a row of
// hrApi.uploadCsrf.test.js's table, beside the five uploads that once shipped without it.

const TOKEN = 'tok-attachments-7';
const json = (body, status = 200) => new Response(JSON.stringify(body), {
  status, headers: { 'content-type': 'application/json' },
});

describe('hrApi.dealQuotations, เอกสารแนบ', () => {
  let fetchMock;

  beforeEach(() => {
    document.cookie = `XSRF-TOKEN=${TOKEN}`;
    fetchMock = vi.fn(async () => json({}));
    globalThis.fetch = fetchMock;
  });

  afterEach(() => {
    document.cookie = 'XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT';
    vi.restoreAllMocks();
  });

  it('uploadAttachment(id, file) POSTs the file as the multipart part `file`, with the CSRF header', async () => {
    const attachment = { id: 72, fileName: 'vendor.pdf', inherited: false };
    fetchMock.mockResolvedValue(json({ attachment }));
    const file = new File(['%PDF-1.4'], 'vendor.pdf', { type: 'application/pdf' });

    const answer = await api.dealQuotations.uploadAttachment(41, file);

    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [url, options] = fetchMock.mock.calls[0];
    expect(url).toBe('/api/deal-quotations/41/attachments');
    expect(options.method).toBe('POST');
    expect(options.credentials).toBe('include');
    expect(new Headers(options.headers).get('X-XSRF-TOKEN')).toBe(TOKEN);
    // The browser writes the multipart boundary; a hand-set Content-Type would drop it.
    expect(new Headers(options.headers).has('content-type')).toBe(false);
    expect(options.body).toBeInstanceOf(FormData);
    expect([...options.body.keys()]).toEqual(['file']);
    const part = options.body.get('file');
    expect(part.name).toBe('vendor.pdf');
    expect(part.type).toBe('application/pdf');
    expect(part.size).toBe(file.size);
    expect(answer).toEqual({ attachment });
  });

  it('uploadAttachment rejects with the server\'s own message when the file is refused', async () => {
    fetchMock.mockResolvedValue(json({ message: 'ใบเสนอราคานี้แนบเอกสารได้สูงสุด 10 ไฟล์', status: 409 }, 409));

    await expect(api.dealQuotations.uploadAttachment(41, new File(['x'], 'eleventh.pdf', { type: 'application/pdf' })))
      .rejects.toThrow('ใบเสนอราคานี้แนบเอกสารได้สูงสุด 10 ไฟล์');
  });

  it('deleteAttachment(attachmentId) DELETEs the file by its own id, with the CSRF header', async () => {
    fetchMock.mockResolvedValue(json({ ok: true }));

    const answer = await api.dealQuotations.deleteAttachment(72);

    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [url, options] = fetchMock.mock.calls[0];
    expect(url).toBe('/api/deal-quotation-attachments/72');
    expect(options.method).toBe('DELETE');
    expect(new Headers(options.headers).get('X-XSRF-TOKEN')).toBe(TOKEN);
    expect(answer).toEqual({ ok: true });
  });
});
