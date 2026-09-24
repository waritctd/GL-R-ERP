import { describe, it, expect } from 'vitest';
import { api } from './mockApi.js';

// GLA-118 review fix 2: "Mock tests must pin the gates. Today they don't: two mutations
// survived." — the backend's DepositPolicyAuthzIntegrationTest / PaymentRecordingAuthzIntegrationTest
// are the real evidence (real Java service, real Postgres); this file pins the MOCK's own gates so
// a future edit to mockApi.js's role checks cannot silently drift back to more-permissive-than-
// production without a red test here. Per CLAUDE.md, this is NOT authz evidence on its own — the
// mock's authorization is never authoritative — it only proves the mock's plumbing matches the
// rules it claims to mirror.
//
// Seed deal 19 (demoSales.js) is owned by the `sales` quick-login rep (id 6) and sits at
// DEPOSIT_NOTICE_ISSUED with a real payable amount (quotationTotal 486,000) — reused across every
// refusal case below because every gate under test does its role check BEFORE touching the ticket
// (hasRole/requireDepositPolicyOwner run first), so a refusal never mutates it. Seed deal 20 is a
// second DEPOSIT_NOTICE_ISSUED deal, also owned by the same rep, reserved for the one test that
// DOES need to mutate a ticket (the account recordPayment grant), so ticket 19 stays untouched for
// every other case in this file.
const NOTICE_ISSUED_TICKET_ID = 19;
const NOTICE_ISSUED_TICKET_ID_2 = 20;

// A second seeded `sales`-role user (demoData.js id 12) — needed because `api.auth.login({role:
// 'sales'})` always resolves to the SAME single quick-login persona (id 6, ticket 19's owner), so
// "a non-owning sales rep" cannot be reached by role alone.
const OTHER_SALES_REP = { email: 'sales2@glr.co.th', password: 'demo1234' };

async function loginRole(role) {
  await api.auth.login({ role });
}

describe('mockApi.tickets.setDepositPolicy — GLA-118 role gate (rule B)', () => {
  it('refuses account, ceo, import, and a non-owning sales rep with 403, and leaves the row unchanged', async () => {
    await loginRole('sales');
    const { ticket: before } = await api.tickets.get(NOTICE_ISSUED_TICKET_ID);

    await loginRole('account');
    await expect(
      api.tickets.setDepositPolicy(NOTICE_ISSUED_TICKET_ID, { policy: 'WAIVED', reason: 'account ขอเอง' }),
    ).rejects.toMatchObject({ status: 403 });

    await loginRole('ceo');
    await expect(
      api.tickets.setDepositPolicy(NOTICE_ISSUED_TICKET_ID, { policy: 'WAIVED', reason: 'ceo ขอเอง' }),
    ).rejects.toMatchObject({ status: 403 });

    await loginRole('import');
    await expect(
      api.tickets.setDepositPolicy(NOTICE_ISSUED_TICKET_ID, { policy: 'WAIVED', reason: 'import ขอเอง' }),
    ).rejects.toMatchObject({ status: 403 });

    await api.auth.login(OTHER_SALES_REP);
    await expect(
      api.tickets.setDepositPolicy(NOTICE_ISSUED_TICKET_ID, { policy: 'WAIVED', reason: 'sales อื่นขอเอง' }),
    ).rejects.toMatchObject({ status: 403 });

    // get() is itself owner-scoped for a `sales` actor (a non-owning rep can't even READ someone
    // else's deal) — switch back to a role that can view it before the final assertion.
    await loginRole('account');
    const { ticket: after } = await api.tickets.get(NOTICE_ISSUED_TICKET_ID);
    expect(after.summary.depositPolicy).toBe(before.summary.depositPolicy);
    expect(after.summary.depositPolicyReason).toBe(before.summary.depositPolicyReason);
  });

  it('grants the owning rep, and separately sales_manager as a backup, on a fresh deal', async () => {
    await loginRole('sales');
    const { customer } = await api.customers.create({
      name: 'บริษัท ทดสอบสิทธินโยบาย จำกัด', taxId: '0100000009992', address: '2 ถนนทดสอบ',
      branch: 'สำนักงานใหญ่', phone: '02-000-0001',
    });
    const { project } = await api.customers.createProject(customer.id, { name: 'โครงการทดสอบสิทธิ' });
    const { ticket: created } = await api.tickets.create({
      title: 'ดีลทดสอบสิทธินโยบายมัดจำ',
      priority: 'NORMAL',
      customerName: customer.name,
      customerId: customer.id,
      projectId: project.id,
      items: [{ brand: 'Cotto', model: 'Test', qty: 1, currency: 'THB' }],
    });
    const id = created.summary.id;
    expect(created.summary.paymentStatus ?? null).toBeNull();

    // Owner (the sales rep who created it) grants.
    const { ticket: afterOwner } = await api.tickets.setDepositPolicy(id, {
      policy: 'WAIVED', reason: 'เจ้าของดีลขอเอง',
    });
    expect(afterOwner.summary.depositPolicy).toBe('WAIVED');

    // sales_manager, as a backup, ALSO grants — Rule B (owner ruling 2026-09-20, part B). The
    // ticket's paymentStatus is still null (setDepositPolicy never touches it), so Rule 4 does not
    // block this second write.
    await loginRole('sales_manager');
    const { ticket: afterManager } = await api.tickets.setDepositPolicy(id, {
      policy: 'CREDIT_CUSTOMER', reason: 'sales_manager สำรอง',
    });
    expect(afterManager.summary.depositPolicy).toBe('CREDIT_CUSTOMER');
    expect(afterManager.summary.depositPolicyReason).toBe('sales_manager สำรอง');
  });
});

describe('mockApi.tickets.confirmDepositPaid — GLA-118 account only', () => {
  it('refuses ceo with 403 and leaves the payment track unchanged', async () => {
    await loginRole('ceo');
    const { ticket: before } = await api.tickets.get(NOTICE_ISSUED_TICKET_ID);
    expect(before.summary.paymentStatus).toBe('DEPOSIT_NOTICE_ISSUED');

    await expect(api.tickets.confirmDepositPaid(NOTICE_ISSUED_TICKET_ID))
      .rejects.toMatchObject({ status: 403 });

    const { ticket: after } = await api.tickets.get(NOTICE_ISSUED_TICKET_ID);
    expect(after.summary.paymentStatus).toBe('DEPOSIT_NOTICE_ISSUED');
  });

  it('lets account confirm the deposit and advance the payment track', async () => {
    await loginRole('account');
    const { ticket } = await api.tickets.confirmDepositPaid(NOTICE_ISSUED_TICKET_ID);
    expect(ticket.summary.paymentStatus).toBe('DEPOSIT_PAID');
  });
});

describe('mockApi.tickets.recordPayment — GLA-118 owner ruling 2026-09-20, part A', () => {
  it.each(['DEPOSIT', 'BALANCE'])('refuses ceo, the owning sales rep, and import for a %s receipt, and records nothing', async (kind) => {
    await loginRole('account');
    const { items: before } = await api.tickets.listPayments(NOTICE_ISSUED_TICKET_ID_2);

    await loginRole('ceo');
    await expect(api.tickets.recordPayment(NOTICE_ISSUED_TICKET_ID_2,
      { kind, amount: 100, note: 'ลองบันทึก' })).rejects.toMatchObject({ status: 403 });

    await loginRole('sales');
    await expect(api.tickets.recordPayment(NOTICE_ISSUED_TICKET_ID_2,
      { kind, amount: 100, note: 'ลองบันทึก' })).rejects.toMatchObject({ status: 403 });

    await loginRole('import');
    await expect(api.tickets.recordPayment(NOTICE_ISSUED_TICKET_ID_2,
      { kind, amount: 100, note: 'ลองบันทึก' })).rejects.toMatchObject({ status: 403 });

    await loginRole('account');
    const { items: after } = await api.tickets.listPayments(NOTICE_ISSUED_TICKET_ID_2);
    expect(after.length).toBe(before.length);
  });

  it('lets account record a DEPOSIT receipt, and the row is actually written', async () => {
    await loginRole('account');
    const { items: before } = await api.tickets.listPayments(NOTICE_ISSUED_TICKET_ID_2);

    await api.tickets.recordPayment(NOTICE_ISSUED_TICKET_ID_2,
      { kind: 'DEPOSIT', amount: 5000, note: 'บันทึกมัดจำบางส่วน' });

    const { items: after } = await api.tickets.listPayments(NOTICE_ISSUED_TICKET_ID_2);
    expect(after.length).toBe(before.length + 1);
    expect(after.at(-1)).toMatchObject({ kind: 'DEPOSIT', amount: 5000 });
  });
});
