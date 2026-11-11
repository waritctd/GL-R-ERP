import { describe, it, expect, beforeEach } from 'vitest';
import { api, patchTicketForTests, setTicketItemQtyFromStockForTests } from './mockApi.js';

// Mirrors the backend procurement-source contract (Tasks 1-3). Fixtures: ticket 11 is owned by
// sales@glr.co.th (id 6), sits at ORDER_RECEIVED with fulfillmentStatus null; beforeAll makes it
// ACTIVE (the demo seed parks it DORMANT) and sets `legacyStockSource: true` (an OLD deal); each test re-seeds it as legacy (OLD deal) or not (NEW deal, source follows the quotation). Mirror-drift guard only -- NOT authz evidence about production.
const OLD_DEAL = 11;
// Every test starts from the same undecided, ACTIVE, ORDER_RECEIVED, nothing-covered state.
function reset({ legacy }) {
  patchTicketForTests(OLD_DEAL, {
    lifecycle: 'ACTIVE', salesStage: 'ORDER_RECEIVED', fulfillmentStatus: null,
    legacyStockSource: legacy, procurementDecidedAt: null,
  });
  setTicketItemQtyFromStockForTests(OLD_DEAL, ITEM, 0);
}
const ITEM = 14;
const SALES = { email: 'sales@glr.co.th', password: 'demo1234' };

describe('mockApi reserveStock is CEO-only (matches production)', () => {
  beforeEach(() => reset({ legacy: true }));

  it('refuses the owning rep and no longer advertises RESERVE_STOCK to them', async () => {
    await api.auth.login(SALES);
    const { availableActions } = await api.tickets.actions(OLD_DEAL);
    expect(availableActions.map((a) => a.action)).not.toContain('RESERVE_STOCK');
    await expect(api.tickets.reserveStock(OLD_DEAL, { lines: [{ itemId: 14, qtyFromStock: 1 }] }))
      .rejects.toMatchObject({ status: 403 });
  });

  it('still advertises it to the CEO', async () => {
    await api.auth.login({ role: 'ceo' });
    const { availableActions } = await api.tickets.actions(OLD_DEAL);
    expect(availableActions.map((a) => a.action)).toContain('RESERVE_STOCK');
  });
});

describe('mockApi procurement-source (mirrors TicketService.decideProcurementSource)', () => {
  const lines = (fromStock) => ({ lines: [{ itemId: ITEM, fromStock }] });
  const CEO = { role: 'ceo' };

  beforeEach(() => reset({ legacy: true }));

  it('POST is the owning rep or CEO only: sales_manager, import and a stranger get 403', async () => {
    for (const login of [{ role: 'sales_manager' }, { role: 'import' }, { email: 'sales2@glr.co.th', password: 'demo1234' }]) {
      await api.auth.login(login);
      await expect(api.tickets.decideProcurementSource(OLD_DEAL, lines(true))).rejects.toMatchObject({ status: 403 });
    }
  });

  it('GET: the owner and the CEO may read; a stranger gets 403; editable needs a decider', async () => {
    await api.auth.login(SALES);
    expect(await api.tickets.getProcurementSource(OLD_DEAL))
      .toMatchObject({ legacy: true, editable: true, quotationNumber: 'QT-2026-0004' });
    await api.auth.login(CEO);
    expect(await api.tickets.getProcurementSource(OLD_DEAL)).toMatchObject({ legacy: true, editable: true });
    await api.auth.login({ role: 'import' });
    expect(await api.tickets.getProcurementSource(OLD_DEAL)).toMatchObject({ legacy: true, editable: false });
    await api.auth.login({ email: 'sales2@glr.co.th', password: 'demo1234' });
    await expect(api.tickets.getProcurementSource(OLD_DEAL)).rejects.toMatchObject({ status: 403 });
  });

  it('editable is false once the stage window has closed (fulfillment set)', async () => {
    patchTicketForTests(OLD_DEAL, { fulfillmentStatus: 'IR_ISSUED' });
    await api.auth.login(SALES);
    expect((await api.tickets.getProcurementSource(OLD_DEAL)).editable).toBe(false);
    await expect(api.tickets.decideProcurementSource(OLD_DEAL, lines(true))).rejects.toMatchObject({ status: 409 });
  });

  it('refuses below ORDER_RECEIVED with 409', async () => {
    patchTicketForTests(OLD_DEAL, { salesStage: 'QUOTE_BUYER' });
    await api.auth.login(SALES);
    await expect(api.tickets.decideProcurementSource(OLD_DEAL, lines(true))).rejects.toMatchObject({ status: 409 });
  });

  it('400 for an empty or partial lines list', async () => {
    await api.auth.login(SALES);
    await expect(api.tickets.decideProcurementSource(OLD_DEAL, { lines: [] }))
      .rejects.toMatchObject({ status: 400, message: 'ต้องระบุที่มาสินค้าให้ครบทุกรายการ' });
    await api.auth.login(CEO);
    await expect(api.tickets.decideProcurementSource(OLD_DEAL, { lines: [{ itemId: 999, fromStock: true }] }))
      .rejects.toMatchObject({ status: 400 });
  });

  it('old deal: the owner decides once, it logs PROCUREMENT_SOURCE_DECIDED, and a second decision is 409', async () => {
    await api.auth.login(SALES);
    const { ticket } = await api.tickets.decideProcurementSource(OLD_DEAL, lines(true));
    expect(ticket.summary.id).toBe(OLD_DEAL);
    const after = await api.tickets.getProcurementSource(OLD_DEAL);
    expect(after).toMatchObject({ editable: false, lines: [{ itemId: ITEM, fromStock: true }] });
    expect(after.decidedAt).toBeTruthy();
    const { events } = (await api.tickets.get(OLD_DEAL)).ticket;
    expect(events.find((e) => e.kind === 'PROCUREMENT_SOURCE_DECIDED').message)
      .toBe('เลือกที่มาสินค้า: นำเข้า 0 รายการ · จากสต็อก 1 รายการ');
    await expect(api.tickets.decideProcurementSource(OLD_DEAL, lines(true)))
      .rejects.toMatchObject({ status: 409, message: 'เลือกที่มาสินค้าของดีลนี้แล้ว แก้ไขไม่ได้' });
  });

  it('CEO may decide an old deal', async () => {
    await api.auth.login(CEO);
    await api.tickets.decideProcurementSource(OLD_DEAL, lines(false));
    expect((await api.tickets.getProcurementSource(OLD_DEAL)).decidedAt).toBeTruthy();
  });

  it('new deal: an unchanged confirm succeeds and marks it decided; a change is 409', async () => {
    reset({ legacy: false });
    await api.auth.login(SALES);
    expect(await api.tickets.getProcurementSource(OLD_DEAL)).toMatchObject({ legacy: false, editable: false });
    await expect(api.tickets.decideProcurementSource(OLD_DEAL, lines(true)))
      .rejects.toMatchObject({ status: 409, message: 'ดีลนี้ใช้ที่มาสินค้าตามใบเสนอราคา แก้ไขไม่ได้' });
    await api.tickets.decideProcurementSource(OLD_DEAL, lines(false));
    expect((await api.tickets.getProcurementSource(OLD_DEAL)).decidedAt).toBeTruthy();
  });
});

describe('mockApi createDrafts source gate (mirrors ImportRequestService.procurementSourceAwaitingRep)', () => {
  beforeEach(() => {
    reset({ legacy: true });
    patchTicketForTests(OLD_DEAL, { paymentStatus: 'DEPOSIT_PAID' });
  });

  it('an undecided legacy deal is refused with 409; after the decision it creates', async () => {
    await api.auth.login(SALES);
    await expect(api.storedImportRequests.createDrafts(OLD_DEAL, {}))
      .rejects.toMatchObject({ status: 409, message: 'กด "จัดซื้อสินค้า" เพื่อเลือกที่มาสินค้าก่อนออกใบขอซื้อ (IR)' });
    await api.tickets.decideProcurementSource(OLD_DEAL, { lines: [{ itemId: ITEM, fromStock: false }] });
    await expect(api.storedImportRequests.createDrafts(OLD_DEAL, {}))
      .resolves.toBeTruthy();
  });
});
