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
