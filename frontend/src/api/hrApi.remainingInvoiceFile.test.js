import { afterEach, describe, expect, it, vi } from 'vitest';
import { api } from './hrApi.js';

// The stored remaining invoice is handed over as a PDF now (the server's default stays .xls for back-compat,
// so the client has to ask). The route is spelled out so a helper in routes.js cannot just agree with itself.
describe('storedRemainingInvoices.download', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('requests the PDF', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, blob: async () => new Blob(['%PDF']) });
    vi.stubGlobal('fetch', fetchMock);
    await api.storedRemainingInvoices.download(7);
    expect(fetchMock.mock.calls[0][0]).toBe('/api/remaining-invoices/7/file?format=pdf');
  });
});
