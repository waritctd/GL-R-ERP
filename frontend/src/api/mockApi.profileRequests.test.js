import { describe, it, expect } from 'vitest';
import { api } from './mockApi.js';

// Guards mockApi.profileRequests.update's reviewerNote persistence directly
// against the mock module (not through a UI test) — see CLAUDE.md "Mock API
// contract": the mock must stay a faithful stand-in for
// ProfileRequestService.update, and a silently dropped field is the
// dangerous direction (nothing else fails, it just stops round-tripping).
// Reject carries a reviewer note; approve must not write one.

describe('mockApi.profileRequests.update reviewerNote persistence', () => {
  it('persists reviewerNote on reject, and omits it on approve', async () => {
    await api.auth.login({ role: 'hr' });
    const { profileRequests } = await api.profileRequests.list();
    const pending = profileRequests.filter((request) => request.status === 'pending');
    // Pick two pending requests defensively rather than assuming fixed seed ids —
    // this mutates shared mock state, so don't hard-code which rows are pending.
    expect(pending.length).toBeGreaterThanOrEqual(2);
    const [rejectTarget, approveTarget] = pending;

    const { profileRequest: rejected } = await api.profileRequests.update(rejectTarget.id, {
      status: 'rejected',
      reviewerNote: 'ข้อมูลไม่ตรง',
    });
    expect(rejected.status).toBe('rejected');
    expect(rejected.reviewerNote).toBe('ข้อมูลไม่ตรง');

    const { profileRequest: approved } = await api.profileRequests.update(approveTarget.id, {
      status: 'approved',
    });
    expect(approved.status).toBe('approved');
    expect(approved.reviewerNote).toBeUndefined();
  });
});

// Guards mockApi.profileRequests.create's widened gate: identity-based (employeeId != null),
// not role-based — mirrors ProfileRequestController#create / ProfileRequestService#create after
// the fix. Not authz evidence (CLAUDE.md: the mock is never authoritative for permissions) — this
// only proves mock-mode QA is not blocked and the mock stays a faithful stand-in for the shape of
// the real gate. The real evidence is ProfileRequestScopeIntegrationTest (real Postgres).

describe('mockApi.profileRequests.create identity gate', () => {
  it('lets a non-employee role WITH an employeeId create a request', async () => {
    // sales_manager was never in the old canSubmitProfileRequests role list. The demo
    // sales.manager@glr.co.th persona is linked to a real employee (employees[0].id), exactly the
    // caller this widening is for.
    await api.auth.login({ role: 'sales_manager' });

    const { profileRequest } = await api.profileRequests.create({
      fieldKey: 'phone',
      fieldLabel: 'เบอร์โทรศัพท์',
      oldValue: '02-000-0000',
      newValue: '089-999-9999',
    });

    expect(profileRequest.status).toBe('pending');
    expect(profileRequest.fieldKey).toBe('phone');
  });

  it('refuses a caller with no employeeId, matching ProfileRequestService#create', async () => {
    // sales@glr.co.th is seeded with employeeId: null (see the sales-picker fixture comment
    // further down this file, ~line 6910) — an account never linked to an employee record, the
    // case ProfileRequestService#create's own guard exists for.
    await api.auth.login({ role: 'sales' });

    await expect(api.profileRequests.create({
      fieldKey: 'phone',
      fieldLabel: 'เบอร์โทรศัพท์',
      oldValue: '02-000-0000',
      newValue: '089-999-9999',
    })).rejects.toThrow('บัญชีผู้ใช้นี้ยังไม่ได้ผูกกับข้อมูลพนักงาน กรุณาติดต่อฝ่ายบุคคล');
  });
});
