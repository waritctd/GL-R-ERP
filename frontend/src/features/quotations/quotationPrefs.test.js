import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  pushRecentCatalogPick, RECENT_PRODUCT_LIMIT, readQuotationDefaults, readRecentCatalogPicks,
  writeQuotationDefaults,
} from './quotationPrefs.js';

// This repo's Node ships a BROKEN built-in global `localStorage` (it needs --localstorage-file and
// shadows jsdom's — `clear` is not even a function on it; see TicketCreateModal.test.jsx's own
// note). So every test here stubs the global outright rather than clearing it.
function memoryStorage() {
  const map = new Map();
  return {
    getItem: (k) => (map.has(k) ? map.get(k) : null),
    setItem: (k, v) => { map.set(k, String(v)); },
    removeItem: (k) => { map.delete(k); },
    clear: () => map.clear(),
    _map: map,
  };
}

function throwingStorage() {
  const boom = () => { throw new DOMException('The operation is insecure.', 'SecurityError'); };
  return { getItem: boom, setItem: boom, removeItem: boom, clear: boom };
}

describe('quotationPrefs', () => {
  let store;
  beforeEach(() => {
    store = memoryStorage();
    vi.stubGlobal('localStorage', store);
  });
  afterEach(() => vi.unstubAllGlobals());

  it('round-trips only the remembered terms fields, dropping blanks', () => {
    writeQuotationDefaults(6, {
      depositPercent: 50, remainderMode: 'ON_DELIVERY', creditDays: '', validityDays: 45,
      originCountry: 'อิตาลี', customerNotes: 'should not be remembered',
    });

    expect(readQuotationDefaults(6)).toEqual({
      depositPercent: 50, remainderMode: 'ON_DELIVERY', validityDays: 45, originCountry: 'อิตาลี',
    });
  });

  // A shared sales machine is the normal case in this office — two reps on one browser must not
  // inherit each other's terms.
  it('keys defaults per user, so one rep never inherits another\'s', () => {
    writeQuotationDefaults(6, { depositPercent: 50 });
    expect(readQuotationDefaults(9)).toBeNull();
    expect(readQuotationDefaults(6)).toEqual({ depositPercent: 50 });
  });

  it('returns null rather than an empty object when nothing worth remembering was passed', () => {
    expect(writeQuotationDefaults(6, { depositPercent: '', remainderMode: null })).toBe(false);
    expect(readQuotationDefaults(6)).toBeNull();
  });

  it('survives a corrupt or hand-edited value instead of throwing', () => {
    store.setItem('glr.quotation.defaults.v1:6', '{not json');
    store.setItem('glr.quotation.recentProducts.v1:6', '{"not":"an array"}');
    expect(readQuotationDefaults(6)).toBeNull();
    expect(readRecentCatalogPicks(6)).toEqual([]);
  });

  it('keeps recent picks most-recent-first, deduped by priceId', () => {
    pushRecentCatalogPick(6, { priceId: 1, collection: 'A' });
    pushRecentCatalogPick(6, { priceId: 2, collection: 'B' });
    const after = pushRecentCatalogPick(6, { priceId: 1, collection: 'A' });

    expect(after.map((r) => r.priceId)).toEqual([1, 2]);
    expect(readRecentCatalogPicks(6).map((r) => r.priceId)).toEqual([1, 2]);
  });

  it('caps the recent list at RECENT_PRODUCT_LIMIT', () => {
    for (let i = 1; i <= RECENT_PRODUCT_LIMIT + 4; i += 1) {
      pushRecentCatalogPick(6, { priceId: i, collection: `C${i}` });
    }
    const stored = readRecentCatalogPicks(6);
    expect(stored).toHaveLength(RECENT_PRODUCT_LIMIT);
    expect(stored[0].priceId).toBe(RECENT_PRODUCT_LIMIT + 4);
  });

  // Only the fields the item editor consumes are cached — a catalog schema change must not be
  // able to bloat or poison this key.
  it('stores only the whitelisted catalog fields', () => {
    pushRecentCatalogPick(6, {
      priceId: 1, collection: 'A', originCountryCode: 'IT', thicknessMm: 10,
      price: 43, currency: 'EUR', someFutureField: { huge: true },
    });
    const [entry] = readRecentCatalogPicks(6);
    expect(entry).toEqual({ priceId: 1, collection: 'A', originCountryCode: 'IT', thicknessMm: 10 });
  });

  // The case this module exists for: in a private window the ACCESSOR ITSELF throws, so an
  // unguarded read would take the editor down rather than starting from the app defaults.
  describe('when localStorage throws on every access', () => {
    beforeEach(() => vi.stubGlobal('localStorage', throwingStorage()));

    it('reads degrade to "nothing saved yet"', () => {
      expect(readQuotationDefaults(6)).toBeNull();
      expect(readRecentCatalogPicks(6)).toEqual([]);
    });

    it('writes report failure instead of propagating', () => {
      expect(writeQuotationDefaults(6, { depositPercent: 50 })).toBe(false);
      expect(() => pushRecentCatalogPick(6, { priceId: 1, collection: 'A' })).not.toThrow();
    });
  });

  // Not merely "empty": some environments have no localStorage global at all.
  it('degrades when there is no localStorage global at all', () => {
    vi.stubGlobal('localStorage', undefined);
    expect(readQuotationDefaults(6)).toBeNull();
    expect(readRecentCatalogPicks(6)).toEqual([]);
    expect(writeQuotationDefaults(6, { depositPercent: 50 })).toBe(false);
  });
});
