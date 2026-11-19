import { describe, it, expect } from 'vitest';
import { api } from './mockApi.js';

// GLA-129 branch 1 review round 1 (2026-09-23): "mock tests must pin the gates" — the same rule
// mockApi.remainingInvoiceAuthz.test.js/mockApi.gla118PaymentAuthz.test.js already state at length.

const GRANT_HOLDER = { email: 'employee@glr.co.th', password: 'demo1234' }; // role employee, canIssueBillingNote: true
const NON_OWNING_SALES = { email: 'sales2@glr.co.th', password: 'demo1234' };
const NO_GRANT_ROLES = ['account', 'sales_manager', 'import'];

async function loginRole(role) {
  await api.auth.login({ role });
}
async function loginGrantHolder() {
  await api.auth.login(GRANT_HOLDER);
}

// The seed carries exactly ONE real ISSUED source document under a customer row. Minting a fresh
// storedRemainingInvoices chain on ticket 19 (SALES1-owned, real approvedPrice items, status quotation_issued
// — createDraft's own precondition) gives each test its own independent candidate instead.
async function mintFreshRemainingInvoiceCandidate(quotationId) {
  await loginRole('sales'); // resolves to SALES1, ticket 19's owner
  const { remainingInvoice: draft } = await api.storedRemainingInvoices.createDraft(19, { quotationId });
  const { remainingInvoice: issued } = await api.storedRemainingInvoices.issue(draft.id);
  return issued;
}

describe('mockApi.billingNotes — write gate (mirrors BillingNoteService#hasWriteGrant)', () => {
  it('createDraft refuses every non-ceo, non-grant role with 403, and creates nothing', async () => {
    for (const role of [...NO_GRANT_ROLES, 'sales']) {
      await loginRole(role);
      await expect(api.billingNotes.createDraft(6, { type: 'GOODS' })).rejects.toMatchObject({ status: 403 });
    }
    await loginGrantHolder();
    const { billingNotes } = await api.billingNotes.listForCustomer(6);
    expect(billingNotes).toHaveLength(0);
  });

  it('createDraft succeeds for ceo and for a plain employee holding the grant', async () => {
    await loginRole('ceo');
    const { billingNote: byCeo } = await api.billingNotes.createDraft(6, { type: 'FREIGHT' });
    expect(byCeo.status).toBe('DRAFT');

    await loginGrantHolder();
    const { billingNote: byGrant } = await api.billingNotes.createDraft(6, { type: 'FREIGHT' });
    expect(byGrant.status).toBe('DRAFT');
    expect(byGrant.createdByName).toBeTruthy();
  });

  // F2/S7 (matching BillingNoteService's own fix): the write gate must run BEFORE the existence lookup, so an
  // unauthorized caller cannot tell "no such note" (404) from "wrong state" (409) without ever passing the gate.
  // Review round 1 caught this reversed for all seven methods.
  it('update/issue/revise/cancel/markReceived/markSettled/remove refuse a non-grant role with 403 even against a BOGUS id (gate before lookup)', async () => {
    const bogusId = 999999;
    for (const role of NO_GRANT_ROLES) {
      await loginRole(role);
      await expect(api.billingNotes.update(bogusId, {})).rejects.toMatchObject({ status: 403 });
      await expect(api.billingNotes.issue(bogusId)).rejects.toMatchObject({ status: 403 });
      await expect(api.billingNotes.revise(bogusId)).rejects.toMatchObject({ status: 403 });
      await expect(api.billingNotes.cancel(bogusId, 'เพราะเหตุผลทดสอบ')).rejects.toMatchObject({ status: 403 });
      await expect(api.billingNotes.markReceived(bogusId, { receivedByName: 'x' })).rejects.toMatchObject({ status: 403 });
      await expect(api.billingNotes.markSettled(bogusId)).rejects.toMatchObject({ status: 403 });
      await expect(api.billingNotes.remove(bogusId)).rejects.toMatchObject({ status: 403 });
    }
  });
});

describe('mockApi.billingNotes — read gate (mirrors BillingNoteService#requireReadAccess)', () => {
  it('account/sales_manager/ceo read a note unconditionally; import and a non-owning sales rep are refused', async () => {
    await loginGrantHolder();
    const { billingNote: draft } = await api.billingNotes.createDraft(6, { type: 'GOODS' });

    for (const role of ['account', 'sales_manager', 'ceo']) {
      await loginRole(role);
      await expect(api.billingNotes.get(draft.id)).resolves.toBeTruthy();
    }
    await loginRole('import');
    await expect(api.billingNotes.get(draft.id)).rejects.toMatchObject({ status: 403 });
    await api.auth.login(NON_OWNING_SALES);
    await expect(api.billingNotes.get(draft.id)).rejects.toMatchObject({ status: 403 });
  });

  it('a sales rep reads once the note references a ticket they own, but a different sales rep still cannot', async () => {
    await loginGrantHolder();
    const { candidates } = await api.billingNotes.candidates(6);
    const depositCandidate = candidates.find((c) => c.sourceType === 'DEPOSIT_NOTICE');
    expect(depositCandidate).toBeTruthy(); // ticket 19's ISSUED deposit notice, SALES1-owned

    const { billingNote: draft } = await api.billingNotes.createDraft(6, {
      type: 'GOODS',
      lines: [{ sourceType: 'DEPOSIT_NOTICE', sourceId: depositCandidate.sourceId }],
    });

    await loginRole('sales'); // resolves to SALES1, the owner of ticket 19
    await expect(api.billingNotes.get(draft.id)).resolves.toBeTruthy();

    await api.auth.login(NON_OWNING_SALES);
    await expect(api.billingNotes.get(draft.id)).rejects.toMatchObject({ status: 403 });
  });
});

describe('mockApi.billingNotes — double-billing claim tracking', () => {
  it('a second note cannot claim a source already claimed by a live (DRAFT or ISSUED) note', async () => {
    const ri = await mintFreshRemainingInvoiceCandidate(301);
    await loginGrantHolder();
    const { candidates } = await api.billingNotes.candidates(6);
    const riCandidate = candidates.find((c) => c.sourceType === 'REMAINING_INVOICE' && c.sourceId === ri.id);
    expect(riCandidate).toBeTruthy();

    const { billingNote: first } = await api.billingNotes.createDraft(6, {
      type: 'GOODS',
      lines: [{ sourceType: 'REMAINING_INVOICE', sourceId: riCandidate.sourceId }],
    });
    expect(first.lines).toHaveLength(1);

    await expect(api.billingNotes.createDraft(6, {
      type: 'GOODS',
      lines: [{ sourceType: 'REMAINING_INVOICE', sourceId: riCandidate.sourceId }],
    })).rejects.toMatchObject({ status: 409 });

    // Same source no longer shows as an open candidate — it shows as alreadyBilled instead.
    const { candidates: after, alreadyBilled } = await api.billingNotes.candidates(6);
    expect(after.some((c) => c.sourceId === riCandidate.sourceId)).toBe(false);
    expect(alreadyBilled.some((c) => c.sourceId === riCandidate.sourceId && c.blockedByNoteId === first.id)).toBe(true);
  });

  it('revise() releases the predecessor line immediately, and remove()ing the abandoned correction restores the claim', async () => {
    const ri = await mintFreshRemainingInvoiceCandidate(302);
    await loginGrantHolder();
    const { candidates } = await api.billingNotes.candidates(6);
    const riCandidate = candidates.find((c) => c.sourceType === 'REMAINING_INVOICE' && c.sourceId === ri.id);
    expect(riCandidate).toBeTruthy();

    const { billingNote: draft } = await api.billingNotes.createDraft(6, {
      type: 'GOODS',
      lines: [{ sourceType: 'REMAINING_INVOICE', sourceId: riCandidate.sourceId }],
    });
    const { billingNote: issued } = await api.billingNotes.issue(draft.id);
    expect(issued.status).toBe('ISSUED');

    // Claimed by the ISSUED note — a brand-new note cannot also claim it.
    await expect(api.billingNotes.createDraft(6, {
      type: 'GOODS',
      lines: [{ sourceType: 'REMAINING_INVOICE', sourceId: riCandidate.sourceId }],
    })).rejects.toMatchObject({ status: 409 });

    // revise() hands the claim to the correction draft, not to the open pool — mirrors releaseLines(id)
    // immediately followed by replaceLines(newId, copied) inside the SAME transaction, so the source is never
    // actually free mid-revise.
    const { billingNote: correction } = await api.billingNotes.revise(issued.id);
    expect(correction.status).toBe('DRAFT');
    expect(correction.lines).toHaveLength(1);
    const { alreadyBilled: duringCorrection } = await api.billingNotes.candidates(6);
    expect(duringCorrection.some((c) => c.sourceId === riCandidate.sourceId && c.blockedByNoteId === correction.id)).toBe(true);

    // Dropping the line from the correction (update() whole-value-replaces lines) frees the source — the
    // predecessor's own claim was already released at revise() time, so nothing else still holds it.
    await api.billingNotes.update(correction.id, { lines: [] });
    const { candidates: afterDrop } = await api.billingNotes.candidates(6);
    expect(afterDrop.some((c) => c.sourceId === riCandidate.sourceId)).toBe(true);

    // Re-claim it on the correction, then abandon the correction entirely — restores the predecessor's own claim
    // (mirrors restoreLinesToIssued).
    await api.billingNotes.update(correction.id, { lines: [{ sourceType: 'REMAINING_INVOICE', sourceId: riCandidate.sourceId }] });
    await api.billingNotes.remove(correction.id);
    const { candidates: afterAbandon, alreadyBilled: blockedAfterAbandon } = await api.billingNotes.candidates(6);
    expect(afterAbandon.some((c) => c.sourceId === riCandidate.sourceId)).toBe(false);
    expect(blockedAfterAbandon.some((c) => c.sourceId === riCandidate.sourceId && c.blockedByNoteId === issued.id)).toBe(true);
  });
});
