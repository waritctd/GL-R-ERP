import { describe, it, expect, beforeEach } from 'vitest';
import { api } from './mockApi.js';
import { LOR_YOR_01_ADDRESS_KEYS } from '../features/taxAllowance/taxAllowanceSchema.js';

// Guards the SHAPE of `headerPrefill` on `payroll.getMyTaxAllowanceDeclarations()`.
//
// Why this file exists at all: `contract.test.js` compares mockApi's method surface and each
// method's ARITY against hrApi.js. It never looks at a response's field names or types, so a new
// field on a response envelope has no guard whatsoever — and CLAUDE.md records that exact failure
// shape ("mock OMITS a field the feature keys on → the feature's actual code path is never
// entered"). If `headerPrefill` silently disappeared or lost its `address` block here, every
// mock-driven test and every mock-mode click-through would keep passing while the prefill was dead.
//
// What this file deliberately does NOT claim: nothing about the real backend. The scoping of the
// tax-ID read is authorization-shaped and is proven only by
// TaxAllowanceHeaderPrefillIntegrationTest against real Postgres — see this mock's own header
// comment on `lorYor01HeaderPrefillFor` for the three ways it knowingly differs from production.

describe('mock getMyTaxAllowanceDeclarations — headerPrefill', () => {
  beforeEach(async () => {
    await api.auth.login({ role: 'employee' });
  });

  it('rides on the /me envelope, not on the declaration DTO', async () => {
    const response = await api.payroll.getMyTaxAllowanceDeclarations(2026);

    expect(response.headerPrefill, 'the envelope must carry the prefill').toBeTruthy();
    for (const item of response.items) {
      expect(item.headerPrefill, 'a declaration DTO must never carry a header block').toBeUndefined();
    }
  });

  it("HR's register never carries a prefill — that would be a bulk tax-ID export", async () => {
    // The one assertion in this file that is about a security property rather than plumbing. The
    // real MyTaxAllowanceDeclarationsResponse is built only by getOwn, for the caller's own id;
    // TaxAllowanceDeclarationDto is what HR gets for EVERY employee. Keep them apart here too, so a
    // future "just add it to the DTO, it's simpler" edit fails a test instead of shipping.
    await api.auth.login({ role: 'hr' });
    const register = await api.payroll.getTaxAllowanceDeclarations({ year: 2026 });

    expect(register.headerPrefill).toBeUndefined();
    for (const item of register.items) {
      expect(item.headerPrefill).toBeUndefined();
    }
  });

  it('declares all four identity slots and all thirteen address slots', async () => {
    const { headerPrefill } = await api.payroll.getMyTaxAllowanceDeclarations(2026);

    // Present-as-a-key, not merely truthy: null is a legitimate value ("the master holds nothing
    // here") and must be distinguishable from the field having been dropped.
    for (const key of ['taxpayerId', 'firstNameTh', 'lastNameTh', 'maritalState', 'address']) {
      expect(Object.hasOwn(headerPrefill, key), `headerPrefill.${key} is missing`).toBe(true);
    }
    // Driven off the schema's own list rather than a copy of it, so adding a 14th slot to the form
    // fails here until the mock offers it too.
    for (const key of LOR_YOR_01_ADDRESS_KEYS) {
      expect(Object.hasOwn(headerPrefill.address, key), `address.${key} is missing`).toBe(true);
    }
    expect(Object.keys(headerPrefill.address)).toHaveLength(LOR_YOR_01_ADDRESS_KEYS.length);
  });

  it('maps สถานภาพ into ข้อ 1\'s vocabulary, never the raw Thai word', async () => {
    // Mirrors TaxAllowanceDeclarationService#maritalStateFromMaster. Shipping 'โสด' straight
    // through would leave every ข้อ 1 radio un-selected, because the radios' values are the enum
    // tokens — a failure that looks exactly like "the master has no marital status".
    const { headerPrefill } = await api.payroll.getMyTaxAllowanceDeclarations(2026);

    expect(['SINGLE', 'MARRIED', null]).toContain(headerPrefill.maritalState);
  });

  it('never returns an empty string — blank means null, so the seeding rule can tell them apart', async () => {
    // `defaultAllowanceValues`' composition treats '' as "not answered" on the DECLARATION side.
    // On the prefill side an '' would be offered as a real value and would seed an empty box over
    // nothing, which is harmless — but a '-' (the mock employee's placeholder address) would seed a
    // literal dash into the employee's tax form. Hence the blank() filter this pins.
    const { headerPrefill } = await api.payroll.getMyTaxAllowanceDeclarations(2026);

    const values = [
      headerPrefill.taxpayerId, headerPrefill.firstNameTh, headerPrefill.lastNameTh,
      ...Object.values(headerPrefill.address),
    ];
    for (const value of values) {
      expect(value === '' || value === '-', `"${value}" should have been normalised to null`).toBe(false);
    }
  });
});
