import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ImportFulfilmentPage } from './ImportFulfilmentPage.jsx';
import { ShipmentRoundPage } from './ShipmentRoundPage.jsx';
import { irRows } from './shipmentRounds.js';
import { api } from '../../api/index.js';

globalThis.React = React;

vi.mock('../../api/index.js', async (importOriginal) => {
  const actual = await importOriginal();
  return {
    ...actual,
    api: {
      tickets: { list: vi.fn() },
      shipmentRounds: {
        list: vi.fn(), get: vi.fn(), advance: vi.fn(), undo: vi.fn(), updateFacts: vi.fn(), returnLotToWaiting: vi.fn(),
        createRound: vi.fn(), addLots: vi.fn(), changeLot: vi.fn(), removeLot: vi.fn(), moveLot: vi.fn(),
        addDocument: vi.fn(), updateDocument: vi.fn(), deleteDocument: vi.fn(), attachFile: vi.fn(), removeFile: vi.fn(), downloadFile: vi.fn(),
      },
    },
  };
});

const lot = (over = {}) => ({
  lotId: 1, importRequestId: 11, docNumber: 'IR-TEST-001', ticketId: 101, ticketCode: 'PR-TEST-0001',
  customerName: 'บริษัท ตัวอย่าง จำกัด', projectName: 'โครงการ ทดสอบ', itemId: 1, code: 'Slate', brand: null, model: 'Slate Grey',
  size: '60x60', unit: 'แผ่น', qty: 800, itemQty: 1200, lotNo: 1, lotCount: 2, remainingQty: 400, ...over,
});

function round(over = {}) {
  return {
    id: 7, factoryId: 1, factoryName: 'Test Stone Co.', source: 'PORTAL', importStep: 'IN_TRANSIT',
    contactedOn: '2026-10-01', orderedOn: '2026-10-05', pickedUpOn: '2026-10-20', inTransitOn: '2026-10-25',
    awaitingCustomsOn: null, receivedOn: null, stepByName: 'ฝ่ายนำเข้า', stepNote: null,
    facts: { label: 'รอบ ทดสอบ', transportMode: 'SEA', vessel: 'TEST VESSEL', etaOn: '2026-11-22' },
    lots: [lot(), lot({ lotId: 2, importRequestId: 12, docNumber: 'IR-TEST-002', ticketId: 102, ticketCode: 'PR-TEST-0002',
      projectName: null, customerName: 'ลูกค้า สอง', qty: 320, itemQty: 320, lotNo: 1, lotCount: 1, remainingQty: 0 })],
    documents: [
      { id: 1, kind: 'PROFORMA', docNo: 'PI-TEST-1', docDate: null, note: null, fileName: null },
      { id: 2, kind: 'INVOICE', docNo: 'INV-TEST-A', docDate: null, note: null, fileName: null },
      { id: 3, kind: 'INVOICE', docNo: 'INV-TEST-B', docDate: null, note: 'PO สอง', fileName: null },
    ],
    undoUntil: null, undoById: null, undoFromStep: null, ...over,
  };
}

const waitingIr = {
  importRequestId: 21, docNumber: 'IR-TEST-009', factoryId: 2, factoryName: 'Waiting Ceramics', ticketId: 109,
  ticketCode: 'PR-TEST-0009', customerName: 'ลูกค้า รอ', projectName: null,
  lines: [{ itemId: 5, code: 'W1', brand: null, model: 'Stone White', size: '80x80', unit: 'แผ่น', remainingQty: 450 }],
};

function renderAt(path, user = { id: 5, name: 'ฝ่ายนำเข้า', role: 'import' }, showToast = vi.fn()) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path="/fulfilment" element={<ImportFulfilmentPage user={user} showToast={showToast} />} />
          <Route path="/fulfilment/rounds/:id" element={<ShipmentRoundPage user={user} showToast={showToast} />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
  return showToast;
}

beforeEach(() => {
  vi.mocked(api.shipmentRounds.list).mockResolvedValue({ rounds: [round()], waiting: [waitingIr] });
  vi.mocked(api.shipmentRounds.get).mockResolvedValue({ round: round() });
  vi.mocked(api.tickets.list).mockResolvedValue({ tickets: [
    // legacy: pre-V184 chain, no stored IR row on any round
    { id: 301, code: 'PR-TEST-0301', customerName: 'ลูกค้า เดิม', title: 'งานเดิม', fulfillmentStatus: 'IR_SENT', overdue: false },
    // not legacy: its IR waits for a round
    { id: 109, code: 'PR-TEST-0009', customerName: 'ลูกค้า รอ', title: 'งานรอ', fulfillmentStatus: 'IR_ISSUED', overdue: false },
  ] });
});
afterEach(() => vi.clearAllMocks());

describe('การขนส่ง queue (shipment rounds)', () => {
  it('lists the IRs still waiting for a round and the rounds, each linking to its round page', async () => {
    renderAt('/fulfilment');
    fireEvent.click(await screen.findByTestId('waiting-toggle'));
    expect(await screen.findAllByText('IR-TEST-009')).not.toHaveLength(0);
    fireEvent.click(screen.getByTestId('waiting-toggle'));
    expect(screen.queryByText('IR-TEST-009')).toBeNull();
    expect(screen.getAllByText('Test Stone Co. · รอบ ทดสอบ').length).toBeGreaterThan(0);
    expect(screen.getAllByRole('link', { name: 'รายละเอียด' })[0].getAttribute('href')).toBe('/fulfilment/rounds/7');
    expect(screen.getByTestId('plan-round')).toBeTruthy(); // the filled จัดรอบขนส่ง button of the waiting section
  });

  it('searches the numbers on a round\'s documents', async () => {
    renderAt('/fulfilment');
    await screen.findAllByText('Test Stone Co. · รอบ ทดสอบ');
    fireEvent.change(screen.getByTestId('fulfilment-search'), { target: { value: 'INV-TEST-B' } });
    expect(screen.getAllByText('Test Stone Co. · รอบ ทดสอบ').length).toBeGreaterThan(0);
  });

  it('shows a retry, not the empty state, when the legacy deals fail to load', async () => {
    vi.mocked(api.shipmentRounds.list).mockResolvedValue({ rounds: [], waiting: [] });
    vi.mocked(api.tickets.list).mockRejectedValue(new Error('boom'));
    renderAt('/fulfilment');
    expect(await screen.findByTestId('fulfilment-deals-error')).toBeTruthy();
    expect(screen.queryByText('ยังไม่มีรอบขนส่ง')).toBeNull();
  });

  it('an order with no project reads "ตัวอย่าง · requester" and "ไม่มีโครงการ", with no deal link', async () => {
    vi.mocked(api.shipmentRounds.list).mockResolvedValue({ rounds: [], waiting: [{
      importRequestId: 31, docNumber: 'IR-TEST-031', factoryId: 2, factoryName: 'Waiting Ceramics', ticketId: null, ticketCode: null,
      customerName: null, projectName: null, orderPurpose: 'SAMPLE', purposeNote: 'ให้ผู้ออกแบบ (ทดสอบ)', requesterName: 'คุณ ทดสอบ',
      lines: [{ itemId: 9, code: 'S1', brand: null, model: 'Slate', size: '60x60', unit: 'แผ่น', remainingQty: 12 }] }] });
    renderAt('/fulfilment');
    expect((await screen.findAllByText('ตัวอย่าง · คุณ ทดสอบ')).length).toBeGreaterThan(0);
    expect(screen.getAllByText(/ไม่มีโครงการ/).length).toBeGreaterThan(0);
    expect(screen.getAllByText('IR-TEST-031')[0].closest('a')).toBeNull(); // no deal to open
    expect(screen.getAllByTestId('ir-pdf-31').length).toBeGreaterThan(0); // import gets the order form to send
    expect(screen.queryByTestId('waiting-toggle')).toBeNull(); // no rounds: the list is the page, nothing to hide it for
  });

  it('advances a round from the queue asking only that step\'s facts, then offers the 30 s undo', async () => {
    vi.mocked(api.shipmentRounds.advance).mockResolvedValue({
      round: round({ importStep: 'AWAITING_CUSTOMS', advanceId: 'adv-uuid-1', undoUntil: new Date(Date.now() + 30_000).toISOString() }),
    });
    vi.mocked(api.shipmentRounds.undo).mockResolvedValue({ round: round() });
    renderAt('/fulfilment');
    fireEvent.click((await screen.findAllByTestId('round-advance-7'))[0]);
    const sheet = await screen.findByTestId('advance-round-sheet');
    // IN_TRANSIT -> the next step is AWAITING_CUSTOMS: arrival date only, no vessel / invoice fields.
    expect(within(sheet).getByLabelText('วันที่ของถึงไทย')).toBeTruthy();
    expect(within(sheet).queryByLabelText('ชื่อเรือ')).toBeNull();

    fireEvent.click(within(sheet).getByTestId('advance-round-submit'));
    await waitFor(() => expect(api.shipmentRounds.advance).toHaveBeenCalledWith(7,
      expect.objectContaining({ targetStep: 'AWAITING_CUSTOMS', expectedStep: 'IN_TRANSIT' })));
    fireEvent.click(await screen.findByRole('button', { name: 'เลิกทำ' }));
    await waitFor(() => expect(api.shipmentRounds.undo).toHaveBeenCalledWith(7, { expectedStep: 'AWAITING_CUSTOMS', advanceId: 'adv-uuid-1' }));
  });

  it('short arrival: unticking complete lists every lot with its received quantity, capped at the lot, and sends them', async () => {
    vi.mocked(api.shipmentRounds.advance).mockResolvedValue({ round: round({ importStep: 'RECEIVED' }) });
    renderAt('/fulfilment');
    fireEvent.click((await screen.findAllByTestId('round-advance-7'))[0]);
    const sheet = await screen.findByTestId('advance-round-sheet');
    fireEvent.click(within(sheet).getByLabelText(/ถึงโกดัง/));
    expect(within(sheet).queryByTestId('lot-receipts')).toBeNull(); // arrived complete: nothing to ask
    fireEvent.click(within(sheet).getByLabelText(/ของเข้าครบตามอินวอยซ์/));
    fireEvent.change(within(sheet).getByLabelText('หมายเหตุ (ของไม่ครบ) *'), { target: { value: 'ขาดบางส่วน' } });
    const first = within(sheet).getByLabelText(/IR-TEST-001/);
    expect(first.value).toBe('800'); // default = the lot's quantity
    fireEvent.change(first, { target: { value: '900' } });
    expect(within(sheet).getByTestId('advance-round-submit').disabled).toBe(true); // more than the lot
    fireEvent.change(first, { target: { value: '750' } });
    fireEvent.change(within(sheet).getByLabelText(/IR-TEST-002/), { target: { value: '0' } }); // 0 is allowed
    await waitFor(() => expect(within(sheet).getByTestId('advance-round-submit').disabled).toBe(false));
    fireEvent.click(within(sheet).getByTestId('advance-round-submit'));
    await waitFor(() => expect(api.shipmentRounds.advance).toHaveBeenCalledWith(7, expect.objectContaining({
      targetStep: 'RECEIVED', lotReceipts: [{ lotId: 1, qty: 750 }, { lotId: 2, qty: 0 }] })));
  });

  it('an unticked complete-per-invoice needs a note before it can be saved', async () => {
    renderAt('/fulfilment');
    fireEvent.click((await screen.findAllByTestId('round-advance-7'))[0]);
    const sheet = await screen.findByTestId('advance-round-sheet');
    fireEvent.click(within(sheet).getByLabelText(/ถึงโกดัง/));
    fireEvent.click(within(sheet).getByLabelText(/ของเข้าครบตามอินวอยซ์/));
    expect(within(sheet).getByTestId('advance-round-submit').disabled).toBe(true);
    fireEvent.change(within(sheet).getByLabelText('หมายเหตุ (ของไม่ครบ) *'), { target: { value: 'ขาด 2 กล่อง' } });
    expect(within(sheet).getByTestId('advance-round-submit').disabled).toBe(false);
  });

  it('keeps the legacy section: a pre-V184 deal with no IR row links to its deal, a deal with a waiting IR does not', async () => {
    renderAt('/fulfilment');
    const legacy = await screen.findByTestId('fulfilment-legacy');
    const deals = within(legacy).getAllByTestId('fulfilment-legacy-deal');
    expect(deals).toHaveLength(1);
    expect(within(deals[0]).getByRole('link').getAttribute('href')).toBe('/import/deals/301');
  });

  it('RECEIVED is never undoable, so it gets a toast and no undo snackbar', async () => {
    vi.mocked(api.shipmentRounds.advance).mockResolvedValue({ round: round({ importStep: 'RECEIVED', undoUntil: new Date(Date.now() + 30_000).toISOString() }) });
    const showToast = renderAt('/fulfilment');
    fireEvent.click((await screen.findAllByTestId('round-advance-7'))[0]);
    const sheet = await screen.findByTestId('advance-round-sheet');
    fireEvent.click(within(sheet).getByLabelText(/ถึงโกดัง/));
    fireEvent.click(within(sheet).getByTestId('advance-round-submit'));
    await waitFor(() => expect(showToast).toHaveBeenCalledWith('success', 'อัปเดตสถานะรอบขนส่งแล้ว'));
    expect(screen.queryByTestId('step-undo-snackbar')).toBeNull();
  });

  it('a portal round with no numbers asks for a proforma and an invoice number to reach ถึงโกดัง, and sends them', async () => {
    vi.mocked(api.shipmentRounds.get).mockResolvedValue({ round: round({ documents: [{ id: 2, kind: 'INVOICE', docNo: 'INV-TEST-A' }] }) });
    vi.mocked(api.shipmentRounds.list).mockResolvedValue({ rounds: [round({ documents: [{ id: 2, kind: 'INVOICE', docNo: 'INV-TEST-A' }] })], waiting: [] });
    vi.mocked(api.shipmentRounds.advance).mockResolvedValue({ round: round({ importStep: 'RECEIVED' }) });
    renderAt('/fulfilment');
    fireEvent.click((await screen.findAllByTestId('round-advance-7'))[0]);
    const sheet = await screen.findByTestId('advance-round-sheet');
    fireEvent.click(within(sheet).getByLabelText(/ถึงโกดัง/));
    // The invoice already has a number: only the proforma is asked.
    expect(within(sheet).queryByLabelText(/เลขที่ Invoice/)).toBeNull();
    const submit = within(sheet).getByTestId('advance-round-submit');
    expect(submit.disabled).toBe(true);
    fireEvent.change(within(sheet).getByLabelText(/เลขที่ Proforma invoice/), { target: { value: 'PI-NEW' } });
    expect(submit.disabled).toBe(false);
    fireEvent.click(submit);
    await waitFor(() => expect(api.shipmentRounds.advance).toHaveBeenCalledWith(7, expect.objectContaining({
      targetStep: 'RECEIVED', documents: [{ kind: 'PROFORMA', docNo: 'PI-NEW' }] })));
  });

  it('จัดรอบขนส่ง: ticks a waiting IR, a smaller quantity splits it, more than remains is refused', async () => {
    vi.mocked(api.shipmentRounds.createRound).mockResolvedValue({ round: round({ id: 9 }) });
    renderAt('/fulfilment');
    fireEvent.click(await screen.findByTestId('plan-round'));
    const sheet = await screen.findByTestId('plan-round-sheet');
    const submit = within(sheet).getByTestId('plan-round-submit');
    expect(submit.disabled).toBe(true); // nothing ticked
    fireEvent.click(within(sheet).getByLabelText('ลูกค้า รอ'));
    const qty = within(sheet).getByLabelText(/ไปรอบนี้/);
    expect(qty.value).toBe('450'); // default = all that remains
    fireEvent.change(qty, { target: { value: '500' } });
    expect(within(sheet).getByText(/เกินที่เหลือ/)).toBeTruthy();
    expect(submit.disabled).toBe(true);
    fireEvent.change(qty, { target: { value: '200' } });
    expect(within(sheet).getByText('เหลือ 250 ไปรอบถัดไป')).toBeTruthy();
    fireEvent.click(submit);
    await waitFor(() => expect(api.shipmentRounds.createRound).toHaveBeenCalledWith({
      factoryId: 2, label: null, transportMode: 'SEA', lots: [{ importRequestId: 21, itemId: 5, qty: 200 }] }));
  });

  it('queue cards show one row per IR: the summed quantity when the lines share a unit, else "n รายการ"', () => {
    const rows = irRows(round({ lots: [lot({ lotId: 1, qty: 10, lotNo: 1, lotCount: 2 }), lot({ lotId: 2, itemId: 2, qty: 5 }), lot({ lotId: 3, importRequestId: 12, docNumber: 'IR-TEST-002', unit: 'กล่อง', qty: 1, lotCount: 1 }), lot({ lotId: 4, importRequestId: 12, docNumber: 'IR-TEST-002', unit: 'แผ่น', qty: 1, lotCount: 1 })] }));
    expect(rows).toHaveLength(2);
    expect(rows[0]).toMatchObject({ lotText: 'ล็อต 1 จาก 2', qtyText: '15 แผ่น' });
    expect(rows[1]).toMatchObject({ lotText: 'ครบทั้งใบ', qtyText: '2 รายการ' });
  });

  it('a read-only viewer sees the queue without the advance button', async () => {
    renderAt('/fulfilment', { id: 9, name: 'ผู้จัดการขาย', role: 'sales_manager' });
    expect((await screen.findAllByText('Test Stone Co. · รอบ ทดสอบ')).length).toBeGreaterThan(0);
    expect(screen.queryByTestId('round-advance-7')).toBeNull();
    expect(screen.queryByTestId('plan-round')).toBeNull();
  });
});

describe('รอบขนส่ง page', () => {
  const shortRound = () => round({ importStep: 'RECEIVED', receivedOn: '2026-11-01', lots: [lot({ qty: 140, itemQty: 140, lotCount: 1, remainingQty: 0, receivedQty: 130 })] });

  it('a short arrival shows on the lot of the round page: ถึงโกดัง 130 จาก 140 · ขาด 10', async () => {
    vi.mocked(api.shipmentRounds.get).mockResolvedValue({ round: shortRound() });
    renderAt('/fulfilment/rounds/7');
    expect((await screen.findAllByTestId('lot-short'))[0].textContent).toBe('ถึงโกดัง 130 จาก 140 แผ่น · ขาด 10 แผ่น');
  });

  it('a lot recorded as 0 received can go back to รอจัดรอบ (confirm first); a lot with any quantity has no such button', async () => {
    const zero = round({ importStep: 'RECEIVED', receivedOn: '2026-11-01', lots: [
      lot({ lotId: 1, qty: 100, itemQty: 100, lotCount: 1, remainingQty: 0, receivedQty: 0 }),
      lot({ lotId: 2, importRequestId: 12, docNumber: 'IR-TEST-002', qty: 100, itemQty: 100, lotCount: 1, remainingQty: 0, receivedQty: 100 })] });
    vi.mocked(api.shipmentRounds.get).mockResolvedValue({ round: zero });
    vi.mocked(api.shipmentRounds.returnLotToWaiting).mockResolvedValue({ round: zero });
    renderAt('/fulfilment/rounds/7');
    expect((await screen.findAllByTestId('lot-return-1'))[0].textContent).toBe('ไม่ได้รับของ · กลับไปรอจัดรอบ');
    expect(screen.queryByTestId('lot-return-2')).toBeNull();
    fireEvent.click(screen.getAllByTestId('lot-return-1')[0]);
    fireEvent.click(await screen.findByRole('button', { name: 'กลับไปรอจัดรอบ' }));
    await waitFor(() => expect(api.shipmentRounds.returnLotToWaiting).toHaveBeenCalledWith('7', 1));
  });

  it('a short arrival shows on the queue row too', async () => {
    vi.mocked(api.shipmentRounds.list).mockResolvedValue({ rounds: [shortRound()], waiting: [] });
    renderAt('/fulfilment');
    expect((await screen.findAllByText(/ถึงโกดัง 130 จาก 140 แผ่น · ขาด 10/)).length).toBeGreaterThan(0);
  });

  it('lets import manage lots only until the round has left: remove goes back to waiting; from IN_TRANSIT it is read-only', async () => {
    vi.mocked(api.shipmentRounds.get).mockResolvedValue({ round: round({ importStep: 'ORDERED' }) });
    vi.mocked(api.shipmentRounds.removeLot).mockResolvedValue({ round: round({ importStep: 'ORDERED' }) });
    renderAt('/fulfilment/rounds/7');
    fireEvent.click((await screen.findAllByTestId('lot-manage-1'))[0]);
    fireEvent.click(await screen.findByTestId('lot-remove'));
    await waitFor(() => expect(api.shipmentRounds.removeLot).toHaveBeenCalledWith(7, 1));
  });

  it('has no lot actions once the round is in transit', async () => {
    renderAt('/fulfilment/rounds/7');
    await screen.findByText('TEST VESSEL');
    expect(screen.queryByTestId('lot-manage-1')).toBeNull();
    expect(screen.queryByTestId('round-add-ir')).toBeNull();
  });

  it('shows the steps with their dates, the facts and each IR with its lot', async () => {
    renderAt('/fulfilment/rounds/7');
    expect(await screen.findByText('TEST VESSEL')).toBeTruthy();
    expect(document.querySelector('[data-step="IN_TRANSIT"]').getAttribute('data-state')).toBe('now');
    expect(document.querySelector('[data-step="ORDERED"]').getAttribute('data-state')).toBe('done');
    expect(screen.getAllByText('ล็อต 1 จาก 2').length).toBeGreaterThan(0);
    expect(screen.getAllByText('IR-TEST-001')[0].closest('a').getAttribute('href')).toBe('/import/deals/101');
    // The file is optional: no warning when none is attached.
    // Documents are ONE list; a round may carry several invoices.
    const docs = within(screen.getByTestId('round-documents'));
    expect(docs.getByText('INV-TEST-A')).toBeTruthy();
    expect(docs.getByText('INV-TEST-B')).toBeTruthy();
    expect(docs.getByTestId('doc-add')).toBeTruthy();
    expect(screen.getByText(/ต้องมีเลขที่ Proforma และ Invoice/)).toBeTruthy(); // PORTAL round: the numbers are expected
  });

  it('labels the vessel by transport mode: an air round says เที่ยวบิน', async () => {
    vi.mocked(api.shipmentRounds.get).mockResolvedValue({ round: round({ facts: { transportMode: 'AIR', vessel: 'TG-123' } }) });
    renderAt('/fulfilment/rounds/7');
    expect(await screen.findByText('TG-123')).toBeTruthy();
    expect(screen.getByText('เที่ยวบิน')).toBeTruthy();
    expect(screen.queryByText('ชื่อเรือ')).toBeNull();
  });

  it('a migrated round is exempt from the number rule: no "ต้องมีเลขที่" line', async () => {
    vi.mocked(api.shipmentRounds.get).mockResolvedValue({ round: round({ source: 'MIGRATED', documents: [], facts: { label: 'รอบ เดิม' } }) });
    renderAt('/fulfilment/rounds/7');
    await screen.findByTestId('round-documents');
    expect(screen.queryByText(/ต้องมีเลขที่ Proforma/)).toBeNull();
  });
});
