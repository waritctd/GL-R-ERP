import { describe, it, expect } from 'vitest';
import { api } from './mockApi.js';

// Guards mockApi's customers.updateContact() SHAPE, PATCH semantics and authz-approximation
// directly against the mock module (not through a UI test) — see CLAUDE.md "Mock API contract".
// contract.test.js only compares parameter COUNTS, so a missing field or a wrong PATCH behaviour
// here would fail nothing there.
//
// ⚠️ Real authorization evidence for the backend gate (DealEntryAccess.requireCanEnterDeal) lives
// in DealEntryAccessIntegrationTest (real Postgres, real CustomerController) — this file only pins
// that the MOCK's own requireDealEntry() approximation behaves the way CustomerController#update /
// CustomerRepository.update's PATCH discipline already documents, per CLAUDE.md's own caution that
// mock authz is never authoritative.
describe('mockApi.customers.updateContact', () => {
  it('a sales rep updates only the field sent (PATCH semantics) — an omitted field is left alone', async () => {
    await api.auth.login({ role: 'sales' });
    const { contacts } = await api.customers.contacts(1);
    const contact = contacts[0];

    const { contact: updated } = await api.customers.updateContact(1, contact.id, { email: 'new@example.com' });

    expect(updated.email).toBe('new@example.com');
    expect(updated.phone).toBe(contact.phone); // untouched
    expect(updated.firstName).toBe(contact.firstName); // untouched

    const { contacts: refetched } = await api.customers.contacts(1);
    expect(refetched.find((c) => c.id === contact.id).email).toBe('new@example.com');
  });

  it('a sent blank clears a field (a non-null blank is a real value, not "leave alone")', async () => {
    await api.auth.login({ role: 'sales_manager' });
    const { contacts } = await api.customers.contacts(1);
    const contact = contacts[0];

    const { contact: updated } = await api.customers.updateContact(1, contact.id, { position: '' });

    expect(updated.position).toBe('');
  });

  it('a blank firstName is refused with a 400, mirroring CustomerController#requireNotBlankIfPresent', async () => {
    await api.auth.login({ role: 'sales' });
    const { contacts } = await api.customers.contacts(1);
    const contact = contacts[0];

    await expect(api.customers.updateContact(1, contact.id, { firstName: '  ' }))
      .rejects.toMatchObject({ status: 400 });
  });

  it('a mismatched customerId 404s, mirroring the real WHERE contact_id AND customer_id clause', async () => {
    await api.auth.login({ role: 'sales' });
    const { contacts: customer1Contacts } = await api.customers.contacts(1);
    const contactOfCustomer1 = customer1Contacts[0];

    await expect(api.customers.updateContact(2, contactOfCustomer1.id, { email: 'leaked@example.com' }))
      .rejects.toMatchObject({ status: 404 });
  });

  it('an unknown contact id 404s', async () => {
    await api.auth.login({ role: 'sales' });
    await expect(api.customers.updateContact(1, 999_999_999, { email: 'x@example.com' }))
      .rejects.toMatchObject({ status: 404 });
  });

  // Wrong-way-round (CLAUDE.md): a role OUTSIDE the deal-entry gate must be refused, not merely
  // "not tested for success". This is the mock's own approximation only — see the file header.
  // Deliberately 'hr', not 'employee': demoData.js's seeded plain-employee login
  // (employee@glr.co.th, id 4) carries canCreateQuotation: true, so it is INSIDE the gate and
  // would make this assertion pass for the wrong reason. 'hr' has neither a sales role nor the
  // grant, and the known contact id 1 (mockContacts' seed fixture) is used directly rather than
  // fetched under this role (hr is also outside CUSTOMER_VIEWER_ROLES).
  it('a role outside requireDealEntry() is refused (mock approximation only — verify against Java)', async () => {
    await api.auth.login({ role: 'hr' });

    await expect(api.customers.updateContact(1, 1, { email: 'x@example.com' }))
      .rejects.toMatchObject({ status: 403 });
  });
});
