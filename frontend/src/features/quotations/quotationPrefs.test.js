import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { readQuotationDefaults, writeQuotationDefaults } from './quotationPrefs.js';

// This repo's Node ships a BROKEN built-in global `localStorage` (it needs --localstorage-file and shadows
// jsdom's — `clear` is not even a function on it; see TicketCreateModal.test.jsx's own note). So every test
// here stubs the global outright rather than clearing it.
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

  // Item 4: unlike every other field above, noDeposit/fullPaymentTerm must NEVER round-trip — remembering
  // noDeposit would make every LATER new quotation start with no deposit, which is exactly the accidental-no-
  // deposit-quotation risk the reversal exists to prevent.
  it('never remembers noDeposit or fullPaymentTerm, even alongside fields that DO round-trip', () => {
    writeQuotationDefaults(6, {
      depositPercent: 50, noDeposit: true, fullPaymentTerm: 'ON_DELIVERY',
    });
    expect(readQuotationDefaults(6)).toEqual({ depositPercent: 50 });
  });
});
