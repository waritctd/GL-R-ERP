import { describe, it, expect } from 'vitest';
import { api } from './mockApi.js';

// GLA-129 branch 1 review round 1 (2026-09-23): "mock tests must pin the gates" — the same rule
// mockApi.remainingInvoiceAuthz.test.js/mockApi.gla118PaymentAuthz.test.js already state at length.
// Before this file, requireBillingNoteWriteGate/requireBillingNoteReadAccess (mirrors
// BillingNoteService#hasWriteGrant/#requireReadAccess) had ZERO mock-side pinning — a future edit
// could silently drift back to more-permissive-than-production, or drop the existence-oracle
// ordering fix (F2/S7), with nothing here to go red. Per CLAUDE.md, this is NOT authz evidence on
// its own — the mock's authorization is never authoritative, see
// BillingNoteServiceIntegrationTest (backend/src/test/java/th/co/glr/hr/billing/, including its
// own wrong-way-round suite) for the real evidence — it only proves the mock's plumbing matches
// the rules it claims to mirror.
//
// Customer 6 / ticket 19 (demoSales.js, SALES1-owned, ISSUED deposit notice 2026-08-11) is the
// one seed customer wired to a real candidate — see mockCustomers' own comment on why. SALES1 is
// sales@glr.co.th (role quick-login `sales` resolves to the FIRST active `sales`-role db.users
// row, which is this one); sales2@glr.co.th is a non-owning sales rep, same as the sibling
// remaining-invoice test's OTHER_SALES_REP.

const GRANT_HOLDER = { email: 'employee@glr.co.th', password: 'demo1234' }; // role employee, canIssueBillingNote: true
const NON_OWNING_SALES = { email: 'sales2@glr.co.th', password: 'demo1234' };
const NO_GRANT_ROLES = ['account', 'sales_manager', 'import'];

async function loginRole(role) {
  await api.auth.login({ role });
}
async function loginGrantHolder() {
  await api.auth.login(GRANT_HOLDER);
}

// The seed carries exactly ONE real ISSUED source document under a customer row (ticket 19's
// deposit notice, via customer 6) — every earlier test in this file that needs a claimable
// candidate consumes it and never releases it, so a LATER test cannot reuse it (state persists
// across it()s in this module, same as the sibling remaining-invoice test file). Minting a fresh
// storedRemainingInvoices chain on ticket 19 (SALES1-owned, real approvedPrice items, status
// quotation_issued — createDraft's own precondition) gives each test its own independent
// candidate instead. A distinct `quotationId` tag per call keeps the chains from colliding with
// each other (storedRemainingInvoices' own one-live-per-chain rule).
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

  // F2/S7 (matching BillingNoteService's own fix): the write gate must run BEFORE the existence
  // lookup, so an unauthorized caller cannot tell "no such note" (404) from "wrong state" (409)
  // without ever passing the gate. Review round 1 caught this reversed for all seven methods.
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

  it('update refuses a non-owning-grant role and leaves the draft unchanged', async () => {
    await loginGrantHolder();
    const { billingNote: draft } = await api.billingNotes.createDraft(6, { type: 'GOODS' });

    await loginRole('account');
    await expect(api.billingNotes.update(draft.id, { note: 'SHOULD-NEVER-STICK' }))
      .rejects.toMatchObject({ status: 403 });

    await loginGrantHolder();
    const { billingNote: reread } = await api.billingNotes.get(draft.id);
    expect(reread.note).not.toBe('SHOULD-NEVER-STICK');
  });
});

describe('mockApi.billingNotes — createDraft leaves no orphan on a line-validation failure', () => {
  it('a fully-typed MANUAL line with a zero amount 409s and creates nothing', async () => {
    await loginGrantHolder();
    const before = (await api.billingNotes.listForCustomer(6)).billingNotes.length;

    await expect(api.billingNotes.createDraft(6, {
      type: 'FREIGHT',
      lines: [{ sourceType: 'MANUAL', manualDocNumber: 'INV-1', manualAmount: 0 }],
    })).rejects.toMatchObject({ status: 400 });

    const after = (await api.billingNotes.listForCustomer(6)).billingNotes.length;
    expect(after).toBe(before); // no orphan DRAFT with zero lines
  });

  // Distinct from the zero-amount case above: 0 is rejected whether or not the cent-rounding step
  // runs at all, so it cannot catch a regression in that specific line. A sub-satang amount only
  // 409s if it is rounded to the cent FIRST (mirrors setScale(2, HALF_UP).signum() <= 0) —
  // reverting to a bare `manualAmount > 0` check would let this one through.
  it('a sub-satang MANUAL amount (0.004) rounds to zero and is refused', async () => {
    await loginGrantHolder();
    await expect(api.billingNotes.createDraft(6, {
      type: 'FREIGHT',
      lines: [{ sourceType: 'MANUAL', manualDocNumber: 'INV-2', manualAmount: 0.004 }],
    })).rejects.toMatchObject({ status: 400 });
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

    // revise() hands the claim to the correction draft, not to the open pool — mirrors
    // releaseLines(id) immediately followed by replaceLines(newId, copied) inside the SAME
    // transaction, so the source is never actually free mid-revise. It shows as alreadyBilled,
    // now blocked by the CORRECTION rather than by the original issued note.
    const { billingNote: correction } = await api.billingNotes.revise(issued.id);
    expect(correction.status).toBe('DRAFT');
    expect(correction.lines).toHaveLength(1);
    const { alreadyBilled: duringCorrection } = await api.billingNotes.candidates(6);
    expect(duringCorrection.some((c) => c.sourceId === riCandidate.sourceId && c.blockedByNoteId === correction.id)).toBe(true);

    // Dropping the line from the correction (update() whole-value-replaces lines) frees the
    // source — the predecessor's own claim was already released at revise() time, so nothing
    // else still holds it. This is the scenario release existed for: "revise note N, then update
    // the correction to drop a line — the dropped source must become billable again immediately."
    await api.billingNotes.update(correction.id, { lines: [] });
    const { candidates: afterDrop } = await api.billingNotes.candidates(6);
    expect(afterDrop.some((c) => c.sourceId === riCandidate.sourceId)).toBe(true);

    // Re-claim it on the correction, then abandon the correction entirely — restores the
    // predecessor's own claim (mirrors restoreLinesToIssued).
    await api.billingNotes.update(correction.id, { lines: [{ sourceType: 'REMAINING_INVOICE', sourceId: riCandidate.sourceId }] });
    await api.billingNotes.remove(correction.id);
    const { candidates: afterAbandon, alreadyBilled: blockedAfterAbandon } = await api.billingNotes.candidates(6);
    expect(afterAbandon.some((c) => c.sourceId === riCandidate.sourceId)).toBe(false);
    expect(blockedAfterAbandon.some((c) => c.sourceId === riCandidate.sourceId && c.blockedByNoteId === issued.id)).toBe(true);
  });

  // Review round 2 (2026-09-23): remove() used to restore the predecessor's claim
  // UNCONDITIONALLY, which let it double-claim a source a THIRD note had grabbed in the meantime
  // — a sequential path a UI user can walk (no concurrency needed), and the exact
  // more-permissive-than-production direction CLAUDE.md warns about. Mirrors
  // restoreLinesToIssued's own DataIntegrityViolationException -> 409 mapping.
  it('remove() on an abandoned correction refuses to restore a claim a THIRD note has since taken, and changes nothing', async () => {
    const ri = await mintFreshRemainingInvoiceCandidate(303);
    await loginGrantHolder();
    const { candidates } = await api.billingNotes.candidates(6);
    const riCandidate = candidates.find((c) => c.sourceType === 'REMAINING_INVOICE' && c.sourceId === ri.id);

    const { billingNote: draft } = await api.billingNotes.createDraft(6, {
      type: 'GOODS',
      lines: [{ sourceType: 'REMAINING_INVOICE', sourceId: riCandidate.sourceId }],
    });
    const { billingNote: issued } = await api.billingNotes.issue(draft.id);
    const { billingNote: correction } = await api.billingNotes.revise(issued.id);
    // Drop the line from the correction — frees the source (finding #6's own fix, tested above).
    await api.billingNotes.update(correction.id, { lines: [] });
    // A third, unrelated note claims the now-free source.
    const { billingNote: interloper } = await api.billingNotes.createDraft(6, {
      type: 'GOODS',
      lines: [{ sourceType: 'REMAINING_INVOICE', sourceId: riCandidate.sourceId }],
    });

    // Abandoning the correction must now refuse — restoring issued's claim would double-claim
    // the source the interloper already holds.
    await expect(api.billingNotes.remove(correction.id)).rejects.toMatchObject({ status: 409 });

    // Nothing changed: the correction draft still exists, the interloper still holds the claim.
    const { billingNote: correctionReread } = await api.billingNotes.get(correction.id);
    expect(correctionReread.status).toBe('DRAFT');
    const { alreadyBilled } = await api.billingNotes.candidates(6);
    expect(alreadyBilled.some((c) => c.sourceId === riCandidate.sourceId && c.blockedByNoteId === interloper.id)).toBe(true);
  });
});

describe('mockApi.billingNotes — field handling on issue/markReceived/revise', () => {
  it('issue() defaults billDate to today when the draft never set one', async () => {
    await loginGrantHolder();
    const { billingNote: draft } = await api.billingNotes.createDraft(6, { type: 'FREIGHT',
      lines: [{ sourceType: 'MANUAL', manualDocNumber: 'INV-3', manualAmount: 500 }] });
    expect(draft.billDate).toBeNull();
    const { billingNote: issued } = await api.billingNotes.issue(draft.id);
    expect(issued.billDate).toBe(new Date().toISOString().slice(0, 10));
  });

  it('markReceived clears paymentAppointmentDate on a second call that omits it, rather than keeping the first value', async () => {
    await loginGrantHolder();
    const { billingNote: draft } = await api.billingNotes.createDraft(6, { type: 'FREIGHT',
      lines: [{ sourceType: 'MANUAL', manualDocNumber: 'INV-4', manualAmount: 500 }] });
    const { billingNote: issued } = await api.billingNotes.issue(draft.id);

    const { billingNote: firstReceipt } = await api.billingNotes.markReceived(issued.id,
      { receivedByName: 'คุณทดสอบ', paymentAppointmentDate: '2026-10-15' });
    expect(firstReceipt.paymentAppointmentDate).toBe('2026-10-15');

    const { billingNote: secondReceipt } = await api.billingNotes.markReceived(issued.id, { receivedByName: 'คุณทดสอบสอง' });
    expect(secondReceipt.paymentAppointmentDate).toBeNull();
  });

  it('revise() does not carry receivedByName/receivedAt/paymentAppointmentDate onto the correction draft', async () => {
    await loginGrantHolder();
    const { billingNote: draft } = await api.billingNotes.createDraft(6, { type: 'FREIGHT',
      lines: [{ sourceType: 'MANUAL', manualDocNumber: 'INV-5', manualAmount: 500 }] });
    const { billingNote: issued } = await api.billingNotes.issue(draft.id);
    await api.billingNotes.markReceived(issued.id, { receivedByName: 'คุณทดสอบ', paymentAppointmentDate: '2026-11-01' });

    const { billingNote: correction } = await api.billingNotes.revise(issued.id);
    expect(correction.receivedByName).toBeNull();
    expect(correction.receivedAt).toBeNull();
    expect(correction.paymentAppointmentDate).toBeNull();
  });
});

describe('mockApi.billingNotes — response shape', () => {
  it('never exposes the mock-only `released` bookkeeping field on a line (not on the real BillingNoteLineDto)', async () => {
    await loginGrantHolder();
    const { billingNote: draft } = await api.billingNotes.createDraft(6, { type: 'FREIGHT',
      lines: [{ sourceType: 'MANUAL', manualDocNumber: 'INV-6', manualAmount: 500 }] });
    expect(draft.lines[0]).not.toHaveProperty('released');

    const { billingNotes: listed } = await api.billingNotes.listForCustomer(6);
    for (const note of listed) {
      for (const line of note.lines) expect(line).not.toHaveProperty('released');
    }
  });
});
