import { describe, it, expect } from 'vitest';
import { api } from './mockApi.js';

// Mirrors TicketService.waiveDeposit Rule 4 (FR-A-05): once a deposit notice has been issued
// (payment track past CUSTOMER_CONFIRMED) the deposit policy can no longer be changed — the fix is
// a credit note, not a policy flip. The mock used to accept it silently, which made it more
// permissive than production. Written wrong-way-round: the refusal must leave the deal untouched.

const RULE4_MESSAGE =
  'ยกเลิกนโยบายมัดจำไม่ได้: มีการออกใบแจ้งรับมัดจำแล้ว — การแก้ไขต้องออกเป็นใบลดหนี้ ไม่ใช่การเปลี่ยนนโยบาย';

// Seed deal 19 (demoSales.js) is owned by the `sales` quick-login rep and sits at
// DEPOSIT_NOTICE_ISSUED, so the owner check passes and only Rule 4 can refuse.
const NOTICE_ISSUED_TICKET_ID = 19;

describe('mockApi.tickets.setDepositPolicy — Rule 4 (FR-A-05)', () => {
  it('refuses with 409 once a deposit notice has been issued, and changes nothing', async () => {
    await api.auth.login({ role: 'sales' });
    const { ticket: before } = await api.tickets.get(NOTICE_ISSUED_TICKET_ID);
    expect(before.summary.paymentStatus).toBe('DEPOSIT_NOTICE_ISSUED');
    const eventCountBefore = before.events.length;

    await expect(
      api.tickets.setDepositPolicy(NOTICE_ISSUED_TICKET_ID, { policy: 'WAIVED', reason: 'ลูกค้าขอยกเว้น' }),
    ).rejects.toMatchObject({ status: 409, message: RULE4_MESSAGE });

    const { ticket: after } = await api.tickets.get(NOTICE_ISSUED_TICKET_ID);
    expect(after.summary.depositPolicy).toBe(before.summary.depositPolicy);
    expect(after.summary.depositPolicyReason).toBe(before.summary.depositPolicyReason);
    expect(after.events.length).toBe(eventCountBefore);
  });

  it('still lets the owning rep set the policy before any deposit notice exists', async () => {
    await api.auth.login({ role: 'sales' });
    const { customer } = await api.customers.create({
      name: 'บริษัท ทดสอบนโยบาย จำกัด', taxId: '0100000009991', address: '1 ถนนทดสอบ',
      branch: 'สำนักงานใหญ่', phone: '02-000-0000',
    });
    const { project } = await api.customers.createProject(customer.id, { name: 'โครงการทดสอบนโยบาย' });
    const { ticket: created } = await api.tickets.create({
      title: 'ดีลทดสอบนโยบายมัดจำ',
      priority: 'NORMAL',
      customerName: customer.name,
      customerId: customer.id,
      projectId: project.id,
      items: [{ brand: 'Cotto', model: 'Test', qty: 1, currency: 'THB' }],
    });
    const id = created.summary.id;
    expect(created.summary.paymentStatus ?? null).toBeNull();

    const { ticket } = await api.tickets.setDepositPolicy(id, { policy: 'WAIVED', reason: 'ลูกค้าเครดิตดี' });
    expect(ticket.summary.depositPolicy).toBe('WAIVED');
  });
});
