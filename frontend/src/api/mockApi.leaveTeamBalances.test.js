import { describe, it, expect } from 'vitest';
import { api } from './mockApi.js';

// Guards mockApi's leave.teamBalances() SHAPE and SCOPE directly against the mock module (not
// through a UI test) -- see CLAUDE.md "Mock API contract". The arity check in contract.test.js
// only compares parameter COUNTS, so a missing/wrong field here would fail nothing there; these
// tests exercise the fixture genuinely, per that file's own guidance.
//
// Real authorization evidence for the backend scope decision lives in
// LeaveTeamBalancesIntegrationTest (real Postgres, real LeaveService) -- this file only pins that
// the MOCK's scope filter (deliberately byte-for-byte copied from employees()'s own filter, see
// mockApi.js's comment on teamBalances()) does not drift from what employees() itself reports, and
// that every balance entry carries the full LeaveBalanceDto shape TeamQuotaSummary.jsx reads.
describe('mockApi.leave.teamBalances', () => {
  it('every team entry carries the full LeaveBalanceDto shape per leave type', async () => {
    await api.auth.login({ email: 'warehouse.manager@glr.co.th', password: 'demo1234' });
    const { team } = await api.leave.teamBalances({});

    expect(team.length).toBeGreaterThan(0);
    team.forEach((member) => {
      expect(member).toHaveProperty('employeeId');
      expect(member).toHaveProperty('employeeCode');
      expect(member).toHaveProperty('employeeName');
      expect(member.balances.length).toBeGreaterThan(0);
      member.balances.forEach((balance) => {
        expect(balance).toHaveProperty('leaveTypeCode');
        expect(balance).toHaveProperty('remainingDays');
        expect(balance).toHaveProperty('annualQuotaDays');
        expect(balance).toHaveProperty('carriedInRemainingDays');
        expect(balance).toHaveProperty('ownQuotaRemainingDays');
      });
    });
  });

  // Wrong-way-round (CLAUDE.md): a division manager's team list must contain EXACTLY the
  // employees api.leave.employees() itself marks directReport: true for that same actor -- never
  // more (a colleague's report leaking in) and never fewer (a real report silently dropped).
  it("team scope matches api.leave.employees()'s own directReport flag exactly -- no leaked colleague reports, no self", async () => {
    await api.auth.login({ email: 'warehouse.manager@glr.co.th', password: 'demo1234' });
    const { employees } = await api.leave.employees();
    const { team } = await api.leave.teamBalances({});

    const expectedDirectReportIds = employees
      .filter((employee) => employee.directReport)
      .map((employee) => employee.employeeId)
      .sort((a, b) => a - b);
    const actualTeamIds = team.map((member) => member.employeeId).sort((a, b) => a - b);

    expect(actualTeamIds).toEqual(expectedDirectReportIds);

    const selfOption = employees.find((employee) => employee.self);
    expect(selfOption).toBeDefined();
    expect(actualTeamIds).not.toContain(selfOption.employeeId);
  });

  it('hr sees the whole company, matching employees()\'s own includeAll reach', async () => {
    await api.auth.login({ role: 'hr' });
    const { employees } = await api.leave.employees();
    const { team } = await api.leave.teamBalances({});

    // hr's own row (self: true) is excluded from the team list -- see teamBalances()'s comment on
    // why self-exclusion is not a security decision -- so the team list is "everyone minus hr
    // themselves", not literally every employees() row.
    const expectedIds = employees
      .filter((employee) => !employee.self)
      .map((employee) => employee.employeeId)
      .sort((a, b) => a - b);
    const actualIds = team.map((member) => member.employeeId).sort((a, b) => a - b);

    expect(actualIds).toEqual(expectedIds);
  });
});
