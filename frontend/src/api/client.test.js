import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { apiRequest, ApiError } from './client.js';

function fakeResponse({ status = 200, json, text, contentType = 'application/json' }) {
  return {
    status,
    ok: status >= 200 && status < 300,
    headers: { get: (name) => (name.toLowerCase() === 'content-type' ? contentType : null) },
    json: async () => json,
    text: async () => text,
  };
}

function lastFetchOptions() {
  return fetch.mock.calls.at(-1)[1];
}

function clearCookies() {
  document.cookie.split('; ').forEach((entry) => {
    const name = entry.split('=')[0];
    if (name) document.cookie = `${name}=; expires=Thu, 01 Jan 1970 00:00:00 GMT`;
  });
}

describe('apiRequest', () => {
  beforeEach(() => {
    clearCookies();
    vi.stubGlobal('fetch', vi.fn());
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('sends credentials and omits the CSRF header on safe (GET) requests', async () => {
    document.cookie = 'XSRF-TOKEN=tok-123';
    fetch.mockResolvedValue(fakeResponse({ json: { ok: true } }));

    await apiRequest('/api/employees');

    const options = lastFetchOptions();
    expect(options.method).toBe('GET');
    expect(options.credentials).toBe('include');
    expect(options.headers['X-XSRF-TOKEN']).toBeUndefined();
  });

  it('attaches the CSRF header and JSON content-type on a POST with a body', async () => {
    document.cookie = 'XSRF-TOKEN=tok-123';
    fetch.mockResolvedValue(fakeResponse({ json: { id: 1 } }));

    await apiRequest('/api/employees', { method: 'POST', body: { name: 'A' } });

    const options = lastFetchOptions();
    expect(options.headers['X-XSRF-TOKEN']).toBe('tok-123');
    expect(options.headers['Content-Type']).toBe('application/json');
    expect(options.body).toBe(JSON.stringify({ name: 'A' }));
  });

  it('does not attach the CSRF header when no XSRF-TOKEN cookie is present', async () => {
    fetch.mockResolvedValue(fakeResponse({ json: {} }));

    await apiRequest('/api/employees', { method: 'POST', body: { name: 'A' } });

    expect(lastFetchOptions().headers['X-XSRF-TOKEN']).toBeUndefined();
  });

  it('returns null for a 204 No Content response', async () => {
    fetch.mockResolvedValue(fakeResponse({ status: 204, contentType: '' }));

    await expect(apiRequest('/api/logout', { method: 'POST' })).resolves.toBeNull();
  });

  it('throws an ApiError carrying status and message on a non-2xx JSON response', async () => {
    fetch.mockResolvedValue(fakeResponse({ status: 400, json: { message: 'Bad input' } }));

    await expect(apiRequest('/api/employees', { method: 'POST', body: {} }))
      .rejects.toMatchObject({ name: 'ApiError', status: 400, message: 'Bad input' });
  });

  it('parses a non-JSON body as text', async () => {
    fetch.mockResolvedValue(fakeResponse({ text: 'pong', contentType: 'text/plain' }));

    await expect(apiRequest('/api/ping')).resolves.toBe('pong');
  });

  it('exposes ApiError as an Error subclass', () => {
    const error = new ApiError('boom', 500, { detail: true });
    expect(error).toBeInstanceOf(Error);
    expect(error.status).toBe(500);
    expect(error.details).toEqual({ detail: true });
  });
});
