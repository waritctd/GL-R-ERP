import { describe, it, expect } from 'vitest';
import { api } from './mockApi.js';

// Mock mirror of the v2.3.0 read-all grant (hr.employee.can_read_all_sales): the holder reads every deal and its tabs,
// the finance deal (empty actions) and the pages, and writes nothing. The Java integration tests are the authz evidence.
const login = (email) => api.auth.login({ email, password: 'demo1234' });

describe('mockApi read-all grant', () => {
  it('carries readAllSales in the auth response only for the holder', async () => {
    expect((await login('warehouse.readall@glr.co.th')).readAllSales).toBe(true);
    expect((await login('warehouse@glr.co.th')).readAllSales).toBe(false);
  });

  it('lets a flagged warehouse read a deal, its activities, expected payments, finance view, but not write', async () => {
    await login('warehouse.readall@glr.co.th');
    const id = (await api.tickets.list()).tickets[0].id;
    expect((await api.tickets.get(id)).ticket.summary.id).toBe(id);
    await expect(api.tickets.listActivities(id)).resolves.toBeDefined();
    await expect(api.expectedPayments.list(id)).resolves.toBeDefined();
    const { deal } = await api.finance.getDeal(id);
    expect(deal.availableActions).toEqual([]);
    expect(deal.commissionInvoice).toBeNull();

    await expect(api.tickets.comment(id, { message: 'x' })).rejects.toMatchObject({ status: 403 });
    await expect(api.tickets.addActivity(id, { activityDate: '2026-11-20', kind: 'CALL' })).rejects.toMatchObject({ status: 403 });
    await expect(api.expectedPayments.create(id, { kind: 'DEPOSIT', amount: 1, expectedWeek: '2026-11-23' })).rejects.toMatchObject({ status: 403 });
    await expect(api.finance.addComment(id, { message: 'x' })).rejects.toMatchObject({ status: 403 });
    await expect(api.finance.confirmDepositPaid(id)).rejects.toMatchObject({ status: 403 });
  });

  it('keeps the same warehouse without the flag out of the deal and the finance view', async () => {
    await login('warehouse@glr.co.th');
    await login('ceo@glr.co.th');
    const id = (await api.tickets.list()).tickets[0].id;
    await login('warehouse@glr.co.th');
    await expect(api.tickets.get(id)).rejects.toMatchObject({ status: 403 });
    await expect(api.finance.getDeal(id)).rejects.toMatchObject({ status: 403 });
  });

  it('gives a sales manager the finance deal read-only', async () => {
    await login('ceo@glr.co.th');
    const id = (await api.tickets.list()).tickets[0].id;
    await login('sales.manager@glr.co.th');
    expect((await api.finance.getDeal(id)).deal.availableActions).toEqual([]);
    await expect(api.finance.confirmDepositPaid(id)).rejects.toMatchObject({ status: 403 });
  });
});
