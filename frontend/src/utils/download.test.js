import { afterEach, describe, expect, it, vi } from 'vitest';
import { fetchDocumentBlob } from './download.js';

describe('fetchDocumentBlob', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.unstubAllEnvs();
  });

  it('GETs the server-supplied path with the session cookie and returns the blob', async () => {
    const blob = new Blob(['pdf']);
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, blob: async () => blob });
    vi.stubGlobal('fetch', fetchMock);
    await expect(fetchDocumentBlob('/api/deposit-notices/7/file')).resolves.toBe(blob);
    expect(fetchMock).toHaveBeenCalledWith('/api/deposit-notices/7/file', { credentials: 'include' });
  });

  it.each([
    'https://evil.example/api/x',
    '//evil.example/api/x',
    '/\\evil.com/x',
    '\\\\evil.com/x',
    '/\t/evil.com/x',
    '/not-api/file',
    '',
    null,
  ])('refuses %j without ever fetching (the cookie stays on this origin and on /api/)', async (input) => {
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
    await expect(fetchDocumentBlob(input)).rejects.toThrow('ดาวน์โหลดไม่สำเร็จ');
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('surfaces the server error message on a non-2xx, parsed like the api client', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: false, status: 403, json: async () => ({ message: 'ไม่มีสิทธิ์ดาวน์โหลดเอกสารนี้' }),
    }));
    await expect(fetchDocumentBlob('/api/attachments/1/file')).rejects.toThrow('ไม่มีสิทธิ์ดาวน์โหลดเอกสารนี้');
  });

  it('a non-2xx or a network failure is an error, not an empty file', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 403 }));
    await expect(fetchDocumentBlob('/api/attachments/1/file')).rejects.toThrow('ดาวน์โหลดไม่สำเร็จ');
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('offline')));
    await expect(fetchDocumentBlob('/api/attachments/1/file')).rejects.toThrow('ดาวน์โหลดไม่สำเร็จ');
  });

  it('in mock mode with no backend it falls back to a labelled demo blob instead of failing', async () => {
    vi.stubEnv('VITE_USE_MOCKS', 'true');
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('offline')));
    const blob = await fetchDocumentBlob('/api/attachments/1/file');
    expect(await blob.text()).toContain('Demo Mode');
  });

  describe('with VITE_API_BASE_URL configured (local dev against a separate backend)', () => {
    afterEach(() => vi.resetModules());

    it('fetches from `${base}/api/...` — the same base the api client uses — not from the page origin', async () => {
      vi.stubEnv('VITE_API_BASE_URL', 'http://127.0.0.1:8080');
      vi.resetModules();
      const { fetchDocumentBlob: withBase } = await import('./download.js');
      const fetchMock = vi.fn().mockResolvedValue({ ok: true, blob: async () => new Blob(['x']) });
      vi.stubGlobal('fetch', fetchMock);
      await withBase('/api/deposit-notices/7/file?format=pdf');
      expect(fetchMock).toHaveBeenCalledWith('http://127.0.0.1:8080/api/deposit-notices/7/file?format=pdf', { credentials: 'include' });
    });

    it('still refuses cross-origin and non-/api/ inputs with a base configured', async () => {
      vi.stubEnv('VITE_API_BASE_URL', 'http://127.0.0.1:8080');
      vi.resetModules();
      const { fetchDocumentBlob: withBase } = await import('./download.js');
      const fetchMock = vi.fn();
      vi.stubGlobal('fetch', fetchMock);
      for (const bad of ['https://evil.example/api/x', '//evil.example/api/x', '/\\evil.com/x', '/not-api/file']) {
        await expect(withBase(bad)).rejects.toThrow('ดาวน์โหลดไม่สำเร็จ');
      }
      expect(fetchMock).not.toHaveBeenCalled();
    });
  });
});
