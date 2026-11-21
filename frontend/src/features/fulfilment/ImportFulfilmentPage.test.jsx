import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ImportFulfilmentPage } from './ImportFulfilmentPage.jsx';
import { ShipmentRoundPage } from './ShipmentRoundPage.jsx';
import { api } from '../../api/index.js';

globalThis.React = React;

vi.mock('../../api/index.js', async (importOriginal) => {
  const actual = await importOriginal();
  return {
    ...actual,
    api: {
      tickets: { list: vi.fn() },
      shipmentRounds: {
        list: vi.fn(), get: vi.fn(), advance: vi.fn(), undo: vi.fn(), updateFacts: vi.fn(),
        uploadFile: vi.fn(), deleteFile: vi.fn(), downloadFile: vi.fn(),
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
  lines: [{ code: 'W1', brand: null, model: 'Stone White', size: '80x80', unit: 'แผ่น', remainingQty: 450 }],
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
    expect(await screen.findAllByText('IR-TEST-009')).not.toHaveLength(0);
    expect(screen.getAllByText('Test Stone Co. · รอบ ทดสอบ').length).toBeGreaterThan(0);
    expect(screen.getAllByRole('link', { name: 'รายละเอียด' })[0].getAttribute('href')).toBe('/fulfilment/rounds/7');
    expect(screen.queryByText('จัดรอบขนส่ง')).toBeNull(); // planning a round is a later slice
  });

  it('advances a round from the queue asking only that step\'s facts, then offers the 30 s undo', async () => {
    vi.mocked(api.shipmentRounds.advance).mockResolvedValue({
      round: round({ importStep: 'AWAITING_CUSTOMS', undoUntil: new Date(Date.now() + 30_000).toISOString() }),
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
    await waitFor(() => expect(api.shipmentRounds.undo).toHaveBeenCalledWith(7, { expectedStep: 'AWAITING_CUSTOMS' }));
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

  it('a read-only viewer sees the queue without the advance button', async () => {
    renderAt('/fulfilment', { id: 9, name: 'ผู้จัดการขาย', role: 'sales_manager' });
    expect(await screen.findAllByText('IR-TEST-009')).not.toHaveLength(0);
    expect(screen.queryByTestId('round-advance-7')).toBeNull();
  });
});

describe('รอบขนส่ง page', () => {
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
