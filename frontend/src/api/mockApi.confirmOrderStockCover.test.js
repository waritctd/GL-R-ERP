import { describe, expect, it } from 'vitest';
import { api, setPricingRequestStatusForTests, setTicketItemQtyFromStockForTests } from './mockApi.js';

// Mirrors OrderConfirmationService#reconcileTicketItems (walk 4): confirmOrder writes qty_from_stock only for
// IN_THAILAND lines (qty); every other line keeps what it had (a legacy reserve survives).
let seq = 0;
const uuid = () => `deadbeef-1111-4000-8000-${String(++seq).padStart(12, '0')}`;

async function confirmedDeal(lines) {
  await api.auth.login({ role: 'sales' });
  const { customer } = await api.customers.create({
    name: `บริษัท สต็อกทดสอบ ${seq + 1} จำกัด`, taxId: `03000000${seq + 1}`, address: 'ถนนทดสอบ', branch: 'สาขา', phone: '02-111-1111',
  });
  const { project } = await api.customers.createProject(customer.id, { name: `โครงการสต็อก ${seq + 1}` });
  const { ticket: created } = await api.tickets.create({
    entryChannel: 'DESIGNER_LED', title: `ดีลสต็อก ${seq + 1}`, priority: 'NORMAL', customerName: customer.name,
    customerId: customer.id, projectId: project.id,
    items: lines.map((l) => ({ brand: 'SCG', model: l.model, qty: 1, currency: 'THB', factory: 'Panaria SpA' })),
  });
  const ticketId = created.summary.id;
  const { pricingRequest } = await api.pricingRequests.create(ticketId, {
    recipientType: 'DESIGNER', recipientLabel: 'ผู้ออกแบบ', clientRequestId: uuid(),
    items: lines.map((l, i) => ({
      sourceTicketItemId: created.items[i].id, productId: 1, brand: 'SCG', model: l.model, factory: 'Panaria SpA',
      requestedQty: l.qty, requestedUnit: 'แผ่น', requestedUnitBasis: 'PER_PIECE', quantityType: 'ESTIMATE',
      color: 'ขาว', texture: 'ด้าน', size: '60x60', thicknessMm: 10, sqmPerPiece: 0.36, quantityMode: 'PIECES',
      piecesInput: l.qty, wastageMode: 'NONE', piecesPerBox: 4, roundToFullBox: false,
      // (the mock's create form still asks every line for these)
      originCountry: 'อิตาลี', leadTimeMinDays: 75, leadTimeMaxDays: 90,
      stockSource: l.stock ? 'IN_THAILAND' : null,
    })),
  });
  lines.forEach((l, i) => { if (l.legacy) setTicketItemQtyFromStockForTests(ticketId, created.items[i].id, l.legacy); });
  setPricingRequestStatusForTests(pricingRequest.summary.id, 'QUOTATION_ACCEPTED');
  await api.pricingRequests.confirmOrder(pricingRequest.summary.id, { clientRequestId: uuid() });
  return (await api.tickets.get(ticketId)).ticket.items;
}

describe('mock confirmOrder: qtyFromStock is written for stock lines only', () => {
  it('covers a stock line in full and leaves an import line with a legacy reserve as it was', async () => {
    const items = await confirmedDeal([
      { model: 'Stock S', qty: 10, stock: true },
      { model: 'Import I', qty: 20, stock: false, legacy: 5 },
      { model: 'Import J', qty: 8, stock: false },
    ]);
    const by = (m) => items.find((i) => i.model === m);
    expect(Number(by('Stock S').qtyFromStock)).toBe(10);
    expect(Number(by('Import I').qtyFromStock)).toBe(5);
    expect(Number(by('Import J').qtyFromStock)).toBe(0);
  });
});
