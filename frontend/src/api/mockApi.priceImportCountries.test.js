import { describe, it, expect } from 'vitest';
import { api } from './mockApi.js';

// PR-B REVIEW ROUND 1, B2: mirrors PriceImportController#requireCountriesReader — the country
// picker GET /api/price-import/countries widened READ to sales + sales_manager (alongside the
// existing import/ceo) so the owning sales rep's new-factory country picker
// (ImportRequestFactoryCard's createDrafts auto-create flow) is not permanently empty. Every
// WRITE on this controller (createFactory/updateFactory, and the factories LIST itself) stays
// import/ceo-only, untouched.
//
// NOT authz evidence about production — CLAUDE.md is explicit the mock is never authoritative.
// The real gate is proven by PriceImportFactoryMasterDataAuthzIntegrationTest against real
// Postgres (backend/src/test/java/th/co/glr/hr/catalog/importer/). This only pins the MIRROR.
describe('mockApi priceImport.countries — mirrors the widened requireCountriesReader gate', () => {
  it('import, ceo, sales, and sales_manager can all list countries', async () => {
    for (const role of ['import', 'ceo', 'sales', 'sales_manager']) {
      await api.auth.login({ role });
      const countries = await api.priceImport.countries();
      expect(Array.isArray(countries)).toBe(true);
      expect(countries.length).toBeGreaterThan(0);
    }
  });

  // Wrong-way-round: roles with no business reason to see the picker must still be refused.
  // warehouse/qc are excluded — demoData.js seeds no active user for either role, so a
  // role-only login 401s before the gate under test is even reached (see auth.login).
  it('employee, hr, and account are still refused', async () => {
    for (const role of ['employee', 'hr', 'account']) {
      await api.auth.login({ role });
      await expect(api.priceImport.countries()).rejects.toMatchObject({ status: 403 });
    }
  });

  // The write endpoints on the SAME controller must stay import/ceo-only — this is the boundary
  // B2 is careful NOT to widen, so sales must still be refused here even though it can now read
  // the country list.
  it('sales can read countries but still cannot create a factory (writes untouched)', async () => {
    await api.auth.login({ role: 'sales' });
    await expect(api.priceImport.countries()).resolves.toBeTruthy();
    await expect(api.priceImport.createFactory('Should Not Exist', 'TH', null, 'THB', null, null))
      .rejects.toMatchObject({ status: 403 });
  });
});
