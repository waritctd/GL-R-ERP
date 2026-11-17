import { afterEach, describe, expect, it, vi } from 'vitest';
import { api } from './mockApi.js';

// mockApi simulates network latency with a real timer on every round trip. In the dev server that is useful (QA sees
// loading states); under vitest it was pure wall-clock waste — ~140ms × every call, the bulk of the slowest
// mockApi.*.test.js files. vitest.config.js sets VITE_MOCK_LATENCY_MS=0; anything else keeps the 140ms default.

/** Starts a mock call and reports whether it has settled after advancing fake time by `ms`. */
async function settledAfter(ms) {
  let settled = false;
  const call = api.auth.login({ role: 'sales' }).then(() => { settled = true; });
  await vi.advanceTimersByTimeAsync(ms);
  const result = settled;
  await vi.runAllTimersAsync();
  await call;
  return result;
}

describe('mockApi simulated latency', () => {
  afterEach(() => {
    vi.unstubAllEnvs();
    vi.useRealTimers();
  });

  it('resolves on the next tick under the test config (VITE_MOCK_LATENCY_MS=0)', async () => {
    vi.useFakeTimers();
    expect(await settledAfter(0)).toBe(true);
  });

  it('stays asynchronous even at zero latency — never resolves synchronously', async () => {
    vi.useFakeTimers();
    let settled = false;
    const call = api.auth.login({ role: 'sales' }).then(() => { settled = true; });
    expect(settled).toBe(false);
    await vi.runAllTimersAsync();
    await call;
    expect(settled).toBe(true);
  });

  it('keeps the 140ms default when the variable is unset (dev server behaviour)', async () => {
    vi.stubEnv('VITE_MOCK_LATENCY_MS', undefined);
    vi.useFakeTimers();
    expect(await settledAfter(139)).toBe(false);
    expect(await settledAfter(140)).toBe(true);
  });

  it('falls back to the 140ms default on a non-numeric value rather than resolving instantly', async () => {
    vi.stubEnv('VITE_MOCK_LATENCY_MS', 'fast');
    vi.useFakeTimers();
    expect(await settledAfter(139)).toBe(false);
  });
});
