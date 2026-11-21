import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { IncomingGoodsPanel } from './IncomingGoodsPanel.jsx';
import { api } from '../../api/index.js';

globalThis.React = React;

vi.mock('../../api/index.js', async (importOriginal) => {
  const actual = await importOriginal();
  return { ...actual, api: { tickets: { incoming: vi.fn(), comment: vi.fn() } } };
});

const lot = (over = {}) => ({
  lotId: 1, product: 'Slate Grey 60x60', unit: 'แผ่น', qty: 800, receivedQty: null, lotNo: 1, lotCount: 2, step: 'IN_TRANSIT',
  expectedOn: '2026-11-22', vessel: 'TEST VESSEL', port: 'แหลมฉบับ', etdActualOn: '2026-11-08', etaOn: '2026-11-22',
  contactedOn: '2026-10-01', orderedOn: '2026-10-05', pickedUpOn: '2026-10-20', inTransitOn: '2026-11-08', receivedOn: null, ...over,
});
const data = {
  wantedBy: 'ภายใน 30 พ.ย.', totalUnit: 'แผ่น', totalOrdered: 1600, totalReceived: 400,
  irs: [
    { importRequestId: 11, docNumber: 'IR-TEST-001', factoryName: 'Test Stone Co.', importStep: 'CONTACTED', unit: 'แผ่น', ordered: 1200, received: 0,
      lots: [lot()], waiting: [{ product: 'Slate Grey 60x60', unit: 'แผ่น', remainingQty: 400 }] },
    { importRequestId: 12, docNumber: 'IR-TEST-002', factoryName: 'Test Stone Co.', importStep: 'RECEIVED', unit: 'แผ่น', ordered: 400, received: 400,
      lots: [lot({ lotId: 2, qty: 400, receivedQty: 400, lotNo: 1, lotCount: 1, step: 'RECEIVED', receivedOn: '2026-11-12' })], waiting: [] },
  ],
};

function renderPanel(props = {}) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const showToast = vi.fn();
  render(<QueryClientProvider client={client}><IncomingGoodsPanel ticketId={101} showToast={showToast} {...props} /></QueryClientProvider>);
  return showToast;
}

afterEach(() => vi.clearAllMocks());

describe('ของกำลังมา (deal ส่งของ tab)', () => {
  it('shows one block per IR with the meter, the lot rows and a รอจัดรอบ row, and the header total', async () => {
    vi.mocked(api.tickets.incoming).mockResolvedValue(data);
    renderPanel();
    expect(await screen.findByTestId('incoming-total')).toBeTruthy();
    expect(screen.getByTestId('incoming-total').textContent).toContain('400 / 1,600');
    expect(screen.getAllByText('ครบทั้งใบ').length).toBeGreaterThan(0); // the whole-round IR: one row
    expect(screen.getAllByText('ล็อต 1 จาก 2').length).toBeGreaterThan(0);
    expect(screen.getAllByText('รอจัดรอบ').length).toBeGreaterThan(0);
    expect(within(screen.getByTestId('incoming-ir-12')).getByRole('progressbar').getAttribute('aria-valuenow')).toBe('100');
  });

  it('opens a read-only lot sheet with the steps, the wanted date, and sends a message through the deal thread', async () => {
    vi.mocked(api.tickets.incoming).mockResolvedValue(data);
    vi.mocked(api.tickets.comment).mockResolvedValue({});
    renderPanel({ canMessage: true });
    fireEvent.click((await screen.findAllByTestId('incoming-open-1'))[0]);
    const sheet = await screen.findByTestId('incoming-lot-sheet');
    expect(within(sheet).getByText('ภายใน 30 พ.ย.')).toBeTruthy();
    expect(sheet.querySelector('[data-step="IN_TRANSIT"]')).toBeTruthy();
    fireEvent.click(within(sheet).getByTestId('incoming-message-open'));
    fireEvent.change(within(sheet).getByLabelText('ข้อความถึงฝ่ายนำเข้า'), { target: { value: 'ลูกค้าอยากได้เร็วขึ้น' } });
    fireEvent.click(within(sheet).getByTestId('incoming-message-send'));
    await waitFor(() => expect(api.tickets.comment).toHaveBeenCalledWith(101, expect.objectContaining({ message: expect.stringContaining('ลูกค้าอยากได้เร็วขึ้น') })));
  });

  it('shows the shortfall of a lot that arrived short', async () => {
    vi.mocked(api.tickets.incoming).mockResolvedValue({ ...data, irs: [{ ...data.irs[1], lots: [lot({ lotId: 2, qty: 400, receivedQty: 350, step: 'RECEIVED', lotCount: 1 })] }] });
    renderPanel();
    expect((await screen.findAllByText('ขาด 50 แผ่น')).length).toBeGreaterThan(0);
  });

  it('renders nothing for a deal with no issued IR', async () => {
    vi.mocked(api.tickets.incoming).mockResolvedValue({ wantedBy: null, totalUnit: null, totalOrdered: null, totalReceived: null, irs: [] });
    renderPanel();
    await waitFor(() => expect(api.tickets.incoming).toHaveBeenCalled());
    expect(screen.queryByTestId('incoming-panel')).toBeNull();
  });
});
