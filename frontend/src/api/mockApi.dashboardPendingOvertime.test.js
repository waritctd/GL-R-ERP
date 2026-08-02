import { describe, it, expect } from 'vitest';
import { api } from './mockApi.js';

// Guards dashboardPending()'s `overtime` count directly against the mock module.
// It used to be a literal `0` (and was left out of `total` to match), so the HR
// dashboard's "OT รออนุมัติ" tile read 0 for every role no matter how many
// requests were pending — the silent-divergence direction CLAUDE.md warns about,
// since nothing else on the page contradicted it.
//
// The predicate mirrors DashboardRepository.countOvertime — `status = 'SUBMITTED'`
// only, under the same `isHr || manager || employeeSelf` gate that
// DashboardService.pendingVisibility() gives both overtime and leave.
// MANAGER_APPROVED is deliberately excluded there: it is waiting on the CEO, not
// on the viewer. HrOverview renders this count read-only (HR monitors OT, it does
// not approve it) — that is about who may act, not about which rows are pending.

const PENDING_ADDENDS = ['profileRequests', 'overtime', 'leave', 'commissions', 'tickets'];

async function pendingApprovals() {
  const { summary } = await api.dashboard.summary();
  return summary.pendingApprovals;
}

describe('mockApi dashboard.summary — pendingApprovals.overtime', () => {
  it('counts the SUBMITTED overtime requests in scope, and folds them into total', async () => {
    await api.auth.login({ role: 'hr' });
    const { requests } = await api.overtime.list();
    const submitted = requests.filter((request) => request.status === 'SUBMITTED');
    // Without this the assertion below would pass vacuously against the old
    // hardcoded 0 — the seed must actually contain something pending.
    expect(submitted.length).toBeGreaterThan(0);

    const pending = await pendingApprovals();
    expect(pending.overtime).toBe(submitted.length);
    expect(pending.total).toBe(PENDING_ADDENDS.reduce((sum, key) => sum + pending[key], 0));
  });

  it('stops counting a request once a manager approves it', async () => {
    await api.auth.login({ role: 'hr' });
    const before = (await pendingApprovals()).overtime;

    // The WHL division manager the seed exists for (demoData.js: "lets the seeded
    // stage-1 OT approval be demoed"). Logs in by email on purpose — their role is
    // 'employee', so `{ role: 'employee' }` would resolve to the plain employee user.
    const { user: manager } = await api.auth.login({
      email: 'warehouse.manager@glr.co.th',
      password: 'demo1234',
    });
    const { requests } = await api.overtime.list();
    const target = requests.find(
      (request) => request.status === 'SUBMITTED' && request.employeeId !== manager.employeeId,
    );
    expect(target).toBeDefined();

    const { request: approved } = await api.overtime.approve(target.id);
    expect(approved.status).toBe('MANAGER_APPROVED');

    await api.auth.login({ role: 'hr' });
    expect((await pendingApprovals()).overtime).toBe(before - 1);
  });

  it('reports 0 for a role with no employee scope rather than leaking the company count', async () => {
    // `sales` has employeeId null -> dashboardEmployeeScope() label 'none', so the
    // gate must shut the count off entirely. Asserted wrong-way-round: the point is
    // that they cannot see other people's pending OT.
    await api.auth.login({ role: 'sales' });
    const pending = await pendingApprovals();
    expect(pending.scope).toBe('none');
    expect(pending.overtime).toBe(0);
  });
});
