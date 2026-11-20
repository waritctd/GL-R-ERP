import { describe, expect, it, vi } from 'vitest';
import { purgeLegacyDealDrafts, PURGED_MARKER } from './purgeLegacyDealDrafts.js';

// The old create form's client-side drafts (dealDrafts.js, removed) may still sit in reps' browsers holding
// customer details. On app start, once per browser, they are removed.

function memoryStorage(initial = {}) {
  const store = new Map(Object.entries(initial));
  return {
    getItem: (k) => (store.has(k) ? store.get(k) : null),
    setItem: (k, v) => { store.set(k, String(v)); },
    removeItem: (k) => { store.delete(k); },
    key: (i) => Array.from(store.keys())[i] ?? null,
    get length() { return store.size; },
    keys: () => Array.from(store.keys()),
  };
}

describe('purgeLegacyDealDrafts', () => {
  it('removes every old draft key and nothing else', () => {
    const storage = memoryStorage({
      'glr:draft-deal': '{"legacy":true}',
      'glr:deal-drafts:index': '[{"id":"a"}]',
      'glr:draft-deal:a': '{"customer":{"name":"บริษัท ก"}}',
      'glr:draft-deal:b': '{}',
      'glr:other-thing': 'keep',
      'glr:draft-dealership': 'keep (not a draft key: no colon after the prefix)',
    });
    purgeLegacyDealDrafts(storage);
    expect(storage.keys().sort()).toEqual(['glr:draft-dealership', 'glr:other-thing', PURGED_MARKER].sort());
  });

  it('never throws when storage is unavailable or broken', () => {
    expect(() => purgeLegacyDealDrafts(null)).not.toThrow();
    const broken = { getItem: vi.fn(() => { throw new Error('denied'); }) };
    expect(() => purgeLegacyDealDrafts(broken)).not.toThrow();
  });
});
