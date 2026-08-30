import { describe, it, expect } from 'vitest';
import { api } from './mockApi.js';

// Guards dashboardPending()'s `leave` count directly against the mock module.
//
// Before PR #846's D1 follow-up fix, `leave` was scoped by the SAME `employeeIds`
// set as `overtime` -- built from dashboardEmployeeScope(), which widens a
// division manager to their whole division_id. The Java side moved countLeave
// off division_id onto reports_to_employee_id (leave review authority is "is
// anyone's reports_to_employee_id", not "shares a division"), so a mock still
// keyed on division_id would count MORE than production for any manager whose
// division holds someone outside their own reports-to chain -- the "mock more
// permissive than production" direction CLAUDE.md warns about (issue #199).
//
// The demo seed's org structure (createDemoDatabase(): exactly one manager per
// division, and EVERY other member of that division reports to that same one
// manager -- see demoData.js) means no persona in this fixture can numerically
// tell "scoped by division_id" and "scoped by reports_to_employee_id" apart: the
// two sets always coincide for a division manager here. That is a property of
// the DATA, not of the code path, so this file cannot mutation-prove D1 the way
// DashboardLeaveScopeIntegrationTest does on the Java side (that class inserts
// its own out-of-chain fixture row via direct SQL). What it CAN and does pin:
// (a) the badge agrees with /leave's own list for two different managers (the
// actual product requirement -- see DashboardService#leaveScope's Javadoc, "a
// badge that leads to a page showing a different number IS the bug"), and (b)
// each manager's count is strictly narrower than hr's company-wide total,
// proving the scope is genuinely per-caller rather than accidentally `all()`.
// The actual reports-to-vs-division code shape is verified by reading the diff:
// `leave` below no longer references `employeeIds` at all, unlike `overtime`,
// which still does.

const PENDING_ADDENDS = ['profileRequests', 'overtime', 'leave', 'commissions', 'tickets'];

async function pendingApprovals() {
  const { summary } = await api.dashboard.summary();
  return summary.pendingApprovals;
}

async function submittedLeaveVisibleToCaller() {
  const { requests } = await api.leave.list({ status: 'SUBMITTED' });
  return requests;
}

describe('mockApi dashboard.summary — pendingApprovals.leave', () => {
  it("agrees with /leave's own SUBMITTED list for a division manager (own + active direct reports)", async () => {
    // warehouse.manager@glr.co.th (demoData.js: "lets the seeded stage-1 OT
    // approval be demoed") has both a SUBMITTED request of their own and
    // several active direct reports' in the seed.
    await api.auth.login({ email: 'warehouse.manager@glr.co.th', password: 'demo1234' });

    const visible = await submittedLeaveVisibleToCaller();
    // Without this the assertion below would pass vacuously with an empty list
    // on both sides.
    expect(visible.length).toBeGreaterThan(0);

    const pending = await pendingApprovals();
    expect(pending.leave).toBe(visible.length);
    expect(pending.total).toBe(PENDING_ADDENDS.reduce((sum, key) => sum + pending[key], 0));
  });

  it("agrees with /leave's own list for a SECOND manager, and is narrower than hr's company-wide total", async () => {
    await api.auth.login({ role: 'hr' });
    const companyWide = await submittedLeaveVisibleToCaller();

    // sales.manager@glr.co.th is a DIFFERENT division's manager (own SUBMITTED
    // request plus several direct reports') -- a second persona so the first
    // case isn't a one-manager fluke.
    await api.auth.login({ email: 'sales.manager@glr.co.th', password: 'demo1234' });
    const visible = await submittedLeaveVisibleToCaller();
    expect(visible.length).toBeGreaterThan(0);

    const pending = await pendingApprovals();
    expect(pending.leave).toBe(visible.length);
    // Proves the scope is genuinely narrower than "all", not accidentally
    // all() -- sales.manager's own chain has several SUBMITTED requests but
    // not every SUBMITTED request in the company.
    expect(pending.leave).toBeLessThan(companyWide.length);
  });

  it('counts every SUBMITTED request company-wide for hr, regardless of reports-to chain', async () => {
    await api.auth.login({ role: 'hr' });
    const visible = await submittedLeaveVisibleToCaller();
    expect(visible.length).toBeGreaterThan(0);

    const pending = await pendingApprovals();
    expect(pending.leave).toBe(visible.length);
    expect(pending.total).toBe(PENDING_ADDENDS.reduce((sum, key) => sum + pending[key], 0));
  });

  it('reports 0 for a role with no employee scope rather than leaking the company count', async () => {
    // Mirrors mockApi.dashboardPendingOvertime.test.js's equivalent case:
    // `sales` has employeeId null -> dashboardEmployeeScope() label 'none', so
    // the gate must shut the count off entirely. Asserted wrong-way-round: the
    // point is that they cannot see anyone's pending leave, their own included.
    await api.auth.login({ role: 'sales' });
    const pending = await pendingApprovals();
    expect(pending.scope).toBe('none');
    expect(pending.leave).toBe(0);
  });
});
