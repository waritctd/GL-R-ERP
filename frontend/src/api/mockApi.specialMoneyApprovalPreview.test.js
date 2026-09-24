import { describe, it, expect } from 'vitest';
import { api } from './mockApi.js';

// Mock-plumbing coverage for `specialMoney.approvalPreview()`, modelled on the backend's own
// SpecialMoneyApprovalPreviewIntegrationTest (backend/src/test/java/th/co/glr/hr/specialmoney/
// SpecialMoneyApprovalPreviewIntegrationTest.java, real Postgres, real SpecialMoneyService). Per
// CLAUDE.md "Mock API contract" / "Permission changes must ship evidence", this file is NOT itself
// authz evidence for the CEO-only gate -- the Java IT above is; this only pins that the MOCK's
// order (404 -> CEO-only 403 -> 409) and its deliberate `eligibleAmount: null` (the mock does not
// reimplement SpecialMoneyPolicyEvaluator -- see mockApi.js's own comment on this method) don't
// silently drift.
//
// TRAVEL_PER_DIEM is used throughout: `evidenceRequired: false` in SPECIAL_MONEY_TYPES, so a
// request can be created AND approved here without also exercising the (unrelated) evidence gate.

async function createTravelPerDiemRequest(amount) {
  await api.auth.login({ role: 'employee' });
  const { request } = await api.specialMoney.create({
    requestType: 'TRAVEL_PER_DIEM',
    eventDate: `${new Date().getFullYear()}-03-04`,
    requestedAmount: amount,
    reason: 'ไปดูงานต่างจังหวัด',
  });
  return request.id;
}

describe('mockApi specialMoney.approvalPreview', () => {
  it('missing request -> 404', async () => {
    await api.auth.login({ role: 'ceo' });
    await expect(api.specialMoney.approvalPreview(999_999))
      .rejects.toMatchObject({ status: 404 });
  });

  it('non-CEO caller -> 403, for the request\'s own owner too', async () => {
    const requestId = await createTravelPerDiemRequest(500);

    // Still logged in as the very employee who filed it -- welfare's approve-side preview is
    // CEO-only with no "or the owner" carve-out, same as SpecialMoneyService#approvalPreview.
    await api.auth.login({ role: 'employee' });
    await expect(api.specialMoney.approvalPreview(requestId))
      .rejects.toMatchObject({ status: 403 });

    await api.auth.login({ role: 'hr' });
    await expect(api.specialMoney.approvalPreview(requestId))
      .rejects.toMatchObject({ status: 403 });
  });

  it('already-decided (APPROVED) request -> 409', async () => {
    const requestId = await createTravelPerDiemRequest(500);

    await api.auth.login({ role: 'ceo' });
    await api.specialMoney.approve(requestId, {});

    await expect(api.specialMoney.approvalPreview(requestId))
      .rejects.toMatchObject({ status: 409 });
  });

  it('CEO on a still-SUBMITTED request gets the requestedAmount echoed back and a null eligibleAmount/payrollMonth', async () => {
    const requestId = await createTravelPerDiemRequest(500);

    await api.auth.login({ role: 'ceo' });
    const { preview } = await api.specialMoney.approvalPreview(requestId);

    expect(preview.requestedAmount).toBe(500);
    // The mock deliberately does NOT run SpecialMoneyPolicyEvaluator (see CLAUDE.md's "Mock API
    // contract": payroll/policy math is not reimplemented here) -- null is the honest "not
    // available in mock mode" signal, not a bug to "fix" by hardcoding a number.
    expect(preview.eligibleAmount).toBeNull();
    expect(preview.payrollMonth).toBeNull();
  });
});
