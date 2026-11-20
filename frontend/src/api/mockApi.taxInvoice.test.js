import { describe, expect, it } from 'vitest';
import { api, patchTicketForTests, receiptSourceForTests } from './mockApi.js';

// DeliveryRequestService#confirmInvoice's twin: the issuer is a per-person grant (qc here), the money counts up to what the
// order still owes, a second confirm is a 409, and the warehouse never reads the invoice total.
const login = (email) => api.auth.login({ email, password: 'demo1234' });
const pdf = (name) => new File(['%PDF-'], name, { type: 'application/pdf' });

// Deal 18 starts as an old deal (stage before ORDER_RECEIVED), which a first request would mark pre-portal and ask a deposit
// of. These tests are about the invoice, so the deal is first made an already-confirmed one.
async function confirmedDeal18() {
  await login('sales@glr.co.th');
  const { ticket } = await api.tickets.get(18);
  if (ticket.events.some((e) => e.kind === 'DEAL_PROMOTED_FROM_QUOTATION')) return;
  patchTicketForTests(18, { salesStage: 'DELIVERY_SCHEDULING', events: [...ticket.events, { id: 99018, kind: 'DEAL_PROMOTED_FROM_QUOTATION', createdAt: '2026-11-01T03:00:00.000Z' }] });
}

describe('mockApi delivery rounds — tax invoice', () => {
  it('refuses a delivery with no invoice and no note (the server\'s NO_INVOICE_NOTE_REQUIRED)', async () => {
    await confirmedDeal18();
    const items = (await api.tickets.get(18)).ticket.items;
    await expect(api.tickets.recordDelivery(18, { source: 'WAREHOUSE', lines: [{ itemId: items[0].id, qty: 1 }], noInvoiceNote: '  ' }))
      .rejects.toThrow('กรุณาระบุหมายเหตุหรือเลขอ้างอิง');
  });

  it('records the invoice as money received (capped at what is owed), once, for the grant holder only', async () => {
    await confirmedDeal18();
    patchTicketForTests(18, { quotations: [{ id: 999, docStatus: 'ACCEPTED', totalAmount: 100, issuedAt: '2026-11-01' }] });
    await login('sales@glr.co.th');
    const first = await api.deliveryRequests.list(18);
    const { round } = await api.deliveryRequests.createDraft(18, { quotationId: first.approvedQuotations[0].id });
    await api.deliveryRequests.send(round.id, { systemDocs: [] });
    await login('warehouse@glr.co.th');
    await api.deliveryRequests.forward(round.id);
    expect((await api.deliveryRequests.list(18)).rounds[0].viewer.canIssue).toBe(false);

    await login('sales@glr.co.th');
    await expect(api.deliveryRequests.attachTaxInvoice(round.id, pdf('IV2611-0042.pdf'))).rejects.toThrow('ไม่มีสิทธิ์');
    await login('qc@glr.co.th');
    const listed = await api.deliveryRequests.list(18);
    expect(listed.rounds[0].viewer.canIssue).toBe(true);
    expect(listed.approvedQuotations).toEqual([]);
    const up = await api.deliveryRequests.attachTaxInvoice(round.id, pdf('IV2611-0042.pdf'));
    expect(up.readOk).toBe(true);
    expect(up.read.invoiceNumber).toBe('IV2611-0042');
    expect((await api.deliveryRequests.attachTaxInvoice(round.id, pdf('scan.pdf'))).readOk).toBe(false);

    // The order's invoices together never exceed what it is worth; the refusal says what is left.
    await expect(api.deliveryRequests.confirmInvoice(round.id, { invoiceNumber: 'IV2611-0042', invoiceDate: '2026-11-19', total: 120 }))
      .rejects.toThrow('ออกใบกำกับได้อีกไม่เกิน ฿100.00');
    const body = { invoiceNumber: 'IV2611-0042', invoiceDate: '2026-11-19', total: 100 };
    const done = (await api.deliveryRequests.confirmInvoice(round.id, body)).round;
    expect(done.status).toBe('INVOICED');
    expect(done.invoice.recordedAmount).toBe(100);
    expect(done.viewer.canVoid).toBe(true);
    await expect(api.deliveryRequests.confirmInvoice(round.id, body)).rejects.toThrow('ถูกดำเนินการแล้ว');

    // Account (in scope) may read non-draft rounds and void; a rep may not void; import never sees money or files.
    await login('sales@glr.co.th');
    await expect(api.deliveryRequests.voidInvoice(round.id, { reason: 'x' })).rejects.toThrow('ไม่มีสิทธิ์');
    await login('qc@glr.co.th');
    await expect(api.deliveryRequests.voidInvoice(round.id, { reason: ' ' })).rejects.toThrow('ระบุเหตุผล');
    const voided = (await api.deliveryRequests.voidInvoice(round.id, { reason: 'พิมพ์ยอดผิด' })).round;
    expect(voided.status).toBe('FORWARDED');
    expect(voided.invoice).toBeNull();
    await login('sales@glr.co.th');
    expect((await api.tickets.listPayments(18)).items).toHaveLength(0);
    await login('qc@glr.co.th');
    // The number is free again: confirm anew.
    await api.deliveryRequests.confirmInvoice(round.id, body);
    await login('warehouse@glr.co.th');
    expect((await api.deliveryRequests.list(18)).rounds[0].invoice.total).toBeNull();
  });

  it('refuses a delivery that over-covers its invoiced round (per-round cap)', async () => {
    await login('sales@glr.co.th');
    const round = (await api.deliveryRequests.list(18)).rounds.find((r) => r.status === 'INVOICED');
    const line = round.lines[0];
    await expect(api.tickets.recordDelivery(18, { source: 'WAREHOUSE', deliveryRequestId: round.id, lines: [{ itemId: line.itemId, qty: Number(line.coveredQty) + 1 }] }))
      .rejects.toThrow('จำนวนเกินที่รอบนี้ครอบคลุม');
  });

  it('lets the LATEST invoiced round be voided beside another forwarded round (no jam), and import can never issue', async () => {
    // Ticket 18 already carries the test above (one invoiced round, 100 recorded): the order is now worth 1,000.
    patchTicketForTests(18, { quotations: [{ id: 998, docStatus: 'ACCEPTED', totalAmount: 1000, issuedAt: '2026-11-01' }] });
    const moreToCover = async () => {
      await login('sales@glr.co.th');
      const items = (await api.tickets.get(18)).ticket?.items ?? (await api.tickets.get(18)).items ?? [];
      patchTicketForTests(18, { items: items.map((i) => ({ ...i, qty: Number(i.qty) * 2 })) });
    };
    const forwarded = async () => {
      await moreToCover();
      const quotationId = (await api.deliveryRequests.list(18)).approvedQuotations[0].id;
      const { round } = await api.deliveryRequests.createDraft(18, { quotationId });
      await api.deliveryRequests.send(round.id, { systemDocs: [] });
      await login('warehouse@glr.co.th');
      await api.deliveryRequests.forward(round.id);
      return round;
    };
    const paymentCount = async () => { await login('sales@glr.co.th'); return ((await api.tickets.listPayments(18)).items).length; };
    const r1 = await forwarded();
    await login('qc@glr.co.th');
    await api.deliveryRequests.attachTaxInvoice(r1.id, pdf('IV2611-0101.pdf'));
    const typo = { invoiceNumber: 'IV2611-0101', invoiceDate: '2026-11-19', total: 800 }; // should have been 500
    await api.deliveryRequests.confirmInvoice(r1.id, typo);
    const withTypo = await paymentCount();
    const r2 = await forwarded();
    await login('qc@glr.co.th');
    await api.deliveryRequests.attachTaxInvoice(r2.id, pdf('IV2611-0102.pdf'));
    const second = { invoiceNumber: 'IV2611-0102', invoiceDate: '2026-11-19', total: 300 };
    await expect(api.deliveryRequests.confirmInvoice(r2.id, second)).rejects.toThrow('ออกใบกำกับได้อีกไม่เกิน');
    // r1 is the latest invoiced round and r2 only waits: the void works, then both confirm.
    await api.deliveryRequests.voidInvoice(r1.id, { reason: 'พิมพ์ยอดผิด' });
    expect(await paymentCount()).toBe(withTypo - 1);
    await login('qc@glr.co.th');
    await api.deliveryRequests.confirmInvoice(r1.id, { ...typo, total: 500 });
    expect((await api.deliveryRequests.confirmInvoice(r2.id, second)).round.status).toBe('INVOICED');
    // Now r1 is not the latest any more: the reason is shown and the void refused.
    const shown = (await api.deliveryRequests.list(18)).rounds;
    expect(shown.find((r) => r.id === r1.id).viewer).toMatchObject({ canVoid: false, voidBlockedReason: expect.stringContaining(`รอบส่งที่ ${r2.roundNo}`) });
    await expect(api.deliveryRequests.voidInvoice(r1.id, { reason: 'x' })).rejects.toThrow('เฉพาะใบกำกับที่ยืนยันล่าสุด');
    // The latest invoice cannot be voided once money was recorded after it, nor once the deal is confirmed paid in full.
    await login('account@glr.co.th');
    await api.finance.recordPayment(18, { kind: 'BALANCE', amount: 1, allowOverpayment: true, note: 'ทดสอบ', confirmPossibleDuplicate: true });
    await login('qc@glr.co.th');
    await expect(api.deliveryRequests.voidInvoice(r2.id, { reason: 'x' })).rejects.toThrow('มีการบันทึกรับเงินหลังใบกำกับนี้แล้ว');
    patchTicketForTests(18, { paymentStatus: 'FULLY_PAID' });
    expect((await api.deliveryRequests.list(18)).rounds.find((r) => r.id === r2.id).viewer.voidBlockedReason).toContain('ชำระครบแล้ว');
    patchTicketForTests(18, { paymentStatus: 'DEPOSIT_PAID' });
    await login('import@glr.co.th');
    await expect(api.deliveryRequests.voidInvoice(r2.id, { reason: 'x' })).rejects.toThrow('บทบาทนำเข้าไม่สามารถออกใบกำกับได้');
  });

  it('asks the pre-portal deposit on an old deal and writes exactly one PRE_PORTAL receipt', async () => {
    await login('sales@glr.co.th');
    const { ticket } = await api.tickets.get(18);
    patchTicketForTests(18, { status: 'draft', salesStage: 'QUOTATION_ISSUED', prePortal: false, events: ticket.events.filter((e) => e.kind !== 'DEAL_PROMOTED_FROM_QUOTATION') });
    const before = await api.deliveryRequests.list(18);
    expect(before.order.asksPreDeposit).toBe(true);
    const { round } = await api.deliveryRequests.createDraft(18, { quotationId: before.approvedQuotations[0].id });
    await expect(api.deliveryRequests.send(round.id, { systemDocs: [] })).rejects.toThrow('ใส่มัดจำที่รับแล้วก่อนใช้ระบบ');
    await api.deliveryRequests.send(round.id, { systemDocs: [], preDeposit: 5000 });
    const pre = (await api.tickets.listPayments(18)).items.filter((p) => p.source === 'PRE_PORTAL');
    expect(pre).toHaveLength(1);
    expect(Number(pre[0].amount)).toBe(5000);
    expect((await api.deliveryRequests.list(18)).order.asksPreDeposit).toBe(false);
  });

  it('labels the sources present when a tax invoice sits among others', () => {
    const r = (source, extra = {}) => ({ kind: 'BALANCE', amount: 1, source, ...extra });
    expect(receiptSourceForTests([r('TAX_INVOICE')])).toBe('TAX_INVOICE');
    expect(receiptSourceForTests([r('MANUAL'), r('TAX_INVOICE')])).toBe('MANUAL+TAX_INVOICE');
    expect(receiptSourceForTests([r('EXPRESS'), r('TAX_INVOICE')])).toBe('EXPRESS+TAX_INVOICE');
    expect(receiptSourceForTests([r('EXPRESS'), r('MANUAL')])).toBe('MIXED');
  });
});
