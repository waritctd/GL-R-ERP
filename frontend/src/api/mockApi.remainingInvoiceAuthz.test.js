import { describe, it, expect } from 'vitest';
import { api } from './mockApi.js';

// "mock tests must pin the gates" — the same rule mockApi.gla118PaymentAuthz.test.js already states at length.
// The money-cycle deals are the one seed family with real approvedPrice items AND status 'quotation_issued',
// which is exactly createDraft's own precondition.

const OTHER_SALES_REP = { email: 'sales2@glr.co.th', password: 'demo1234' };

async function loginRole(role) {
  await api.auth.login({ role });
}

describe('mockApi.storedRemainingInvoices — write gate (mirrors requireRemainingInvoiceWriteGate)', () => {
  it('createDraft refuses account/ceo/import/a non-owning sales rep with 403, and creates nothing', async () => {
    const ticketId = 19;
    await loginRole('sales');

    for (const role of ['account', 'ceo', 'import']) {
      await loginRole(role);
      await expect(api.storedRemainingInvoices.createDraft(ticketId, {}))
        .rejects.toMatchObject({ status: 403 });
    }
    await api.auth.login(OTHER_SALES_REP);
    await expect(api.storedRemainingInvoices.createDraft(ticketId, {}))
      .rejects.toMatchObject({ status: 403 });

    await loginRole('sales');
    const { remainingInvoices } = await api.storedRemainingInvoices.listForTicket(ticketId);
    expect(remainingInvoices).toHaveLength(0);
  });

  it('update/issue/revise/deleteDraft refuse a non-owning sales rep with 403, and leave the row unchanged', async () => {
    const ticketId = 20;
    await loginRole('sales');
    const { remainingInvoice: draft } = await api.storedRemainingInvoices.createDraft(ticketId, {});

    await api.auth.login(OTHER_SALES_REP);
    await expect(api.storedRemainingInvoices.update(draft.id, { reference: 'SHOULD-NEVER-STICK' }))
      .rejects.toMatchObject({ status: 403 });
    await expect(api.storedRemainingInvoices.issue(draft.id)).rejects.toMatchObject({ status: 403 });
    await expect(api.storedRemainingInvoices.deleteDraft(draft.id)).rejects.toMatchObject({ status: 403 });

    await loginRole('sales');
    const { remainingInvoice: reread } = await api.storedRemainingInvoices.get(draft.id);
    expect(reread.status).toBe('DRAFT');
    expect(reread.reference).not.toBe('SHOULD-NEVER-STICK');

    const { remainingInvoice: issued } = await api.storedRemainingInvoices.issue(draft.id);
    await api.auth.login(OTHER_SALES_REP);
    await expect(api.storedRemainingInvoices.revise(issued.id)).rejects.toMatchObject({ status: 403 });

    await loginRole('sales');
    const { remainingInvoice: issuedReread } = await api.storedRemainingInvoices.get(issued.id);
    expect(issuedReread.status).toBe('ISSUED');
    expect(issuedReread.supersededById).toBeNull();
  });
});

describe('mockApi.storedRemainingInvoices — O2 cross-chain rules (GLA-99 step 2 review-round-2, P6)', () => {
  it('createDraft refuses a second DRAFT on the deal for a DIFFERENT quotation chain', async () => {
    const ticketId = 22;
    await loginRole('sales');
    const { remainingInvoice: draftA } = await api.storedRemainingInvoices.createDraft(ticketId, { quotationId: 111 });
    expect(draftA.status).toBe('DRAFT');

    await expect(api.storedRemainingInvoices.createDraft(ticketId, { quotationId: 222 }))
      .rejects.toMatchObject({ status: 409 });

    const { remainingInvoices } = await api.storedRemainingInvoices.listForTicket(ticketId);
    expect(remainingInvoices).toHaveLength(1);
  });

  it('issue supersedes EVERY other currently-ISSUED remaining invoice on the deal, even a different chain', async () => {
    const ticketId = 23;
    await loginRole('sales');
    const { remainingInvoice: draftA } = await api.storedRemainingInvoices.createDraft(ticketId, { quotationId: 111 });
    const { remainingInvoice: issuedA } = await api.storedRemainingInvoices.issue(draftA.id);
    expect(issuedA.status).toBe('ISSUED');

    // A DRAFT for a DIFFERENT chain is fine once A is ISSUED (not DRAFT any more) — createDraft's own
    // anotherDraftLive check only blocks against a live DRAFT, never an ISSUED sibling.
    const { remainingInvoice: draftB } = await api.storedRemainingInvoices.createDraft(ticketId, { quotationId: 222 });
    const { remainingInvoice: issuedB } = await api.storedRemainingInvoices.issue(draftB.id);
    expect(issuedB.status).toBe('ISSUED');
    expect(issuedB.baseNumber).not.toBe(issuedA.baseNumber); // a NEW chain's first issue

    const { remainingInvoice: aReread } = await api.storedRemainingInvoices.get(issuedA.id);
    expect(aReread.status).toBe('SUPERSEDED');
    expect(aReread.supersededById).toBe(issuedB.id);

    const { remainingInvoices: all } = await api.storedRemainingInvoices.listForTicket(ticketId);
    const stillIssued = all.filter((r) => r.status === 'ISSUED');
    expect(stillIssued).toHaveLength(1); // exactly one live, per O2
    expect(stillIssued[0].id).toBe(issuedB.id);
  });

  it('revise refuses when a DRAFT for a DIFFERENT chain already exists on the deal', async () => {
    const ticketId = 24;
    await loginRole('sales');
    const { remainingInvoice: draftA } = await api.storedRemainingInvoices.createDraft(ticketId, { quotationId: 111 });
    const { remainingInvoice: issuedA } = await api.storedRemainingInvoices.issue(draftA.id);

    // A DRAFT for a DIFFERENT chain now exists on the same deal (legal at createDraft time — A is ISSUED, not
    // DRAFT, so createDraft's own anotherDraftLive check does not fire).
    const { remainingInvoice: draftB } = await api.storedRemainingInvoices.createDraft(ticketId, { quotationId: 222 });
    expect(draftB.status).toBe('DRAFT');

    // Revising A (chain 111) is refused while B's own DRAFT (chain 222) is live — O2/P3: one live DRAFT per DEAL,
    // not one per chain.
    await expect(api.storedRemainingInvoices.revise(issuedA.id)).rejects.toMatchObject({ status: 409 });

    const { remainingInvoice: aReread } = await api.storedRemainingInvoices.get(issuedA.id);
    expect(aReread.status).toBe('ISSUED'); // untouched — no orphaned revision draft
    const { remainingInvoice: bReread } = await api.storedRemainingInvoices.get(draftB.id);
    expect(bReread.status).toBe('DRAFT'); // untouched
  });
});
