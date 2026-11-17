import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ImportOverview } from './ImportOverview.jsx';
import { api } from '../../api/index.js';

globalThis.React = React;

vi.mock('../../api/index.js', async (importOriginal) => {
  const actual = await importOriginal();
  return {
    ...actual,
    api: {
      tickets: { list: vi.fn() },
      pricingRequests: { queue: vi.fn(), pickup: vi.fn() },
      storedImportRequests: { listForTicket: vi.fn() },
    },
  };
});

// Spec 2026-11-15 §7.11 item 1 / plan task 5.1 (design a-import-work): import's landing is ONE worklist grouped by
// the next step — รอรับเรื่อง (claim inline), รอราคาโรงงาน, กำลังขนส่ง (step + ETA), ถึงโกดังแล้ว — built from the
// two calls the page already made (api.pricingRequests.queue, api.tickets.list) plus the per-deal stored ใบขอซื้อ
// rows /fulfilment reads. No new endpoint.

const me = { id: 7, role: 'import', employeeId: 2, name: 'วรรณา นำเข้า' };
const never = () => new Promise(() => {});

const pcr = (id, status, o = {}) => ({
  id, status, ticketId: 1, customerName: `ลูกค้า ${id}`, requestCode: `PCR-${id}`, ticketCode: `PR-${id}`,
  recipientType: 'DESIGNER', assignedImportId: null, assignedImportName: null, requestedByName: 'คุณสมหมาย',
  itemCount: 3, submittedAt: `2026-11-1${id % 10}T03:00:00Z`, ...o,
});
let queue;
const QUEUE = () => [
  pcr(102, 'SUBMITTED', { submittedAt: '2026-11-12T03:00:00Z', itemCount: 5 }),
  pcr(101, 'SUBMITTED', { submittedAt: '2026-11-14T03:00:00Z', itemCount: 2 }),
  pcr(111, 'IMPORT_REVIEWING', { assignedImportId: 7, assignedImportName: 'วรรณา นำเข้า' }),
  pcr(112, 'AWAITING_FACTORY_RESPONSE', { assignedImportId: 7, assignedImportName: 'วรรณา นำเข้า' }),
  pcr(121, 'AWAITING_FACTORY_RESPONSE', { assignedImportId: 99, assignedImportName: 'สมศรี นำเข้า' }),
  // with the CEO, and done: not import's to work
  pcr(131, 'READY_FOR_CEO_REVIEW', { assignedImportId: 7 }),
  pcr(141, 'APPROVED_FOR_QUOTATION', { assignedImportId: 7 }),
];

const TICKETS = [
  { id: 3, code: 'PR-003', customerName: 'Siam Mall', fulfillmentStatus: 'IR_ISSUED' },
  { id: 4, code: 'PR-004', customerName: 'บ้านพักชะอำ', fulfillmentStatus: 'IR_ISSUED' },
  // pre-V184 chain, no stored rows: still in transit at deal level
  { id: 5, code: 'PR-005', customerName: 'Legacy Co', fulfillmentStatus: 'SHIPPING' },
  { id: 6, code: 'PR-006', customerName: 'Landmark', fulfillmentStatus: 'GOODS_RECEIVED' },
  // not candidates
  { id: 8, code: 'PR-008', customerName: 'Stock Only', fulfillmentStatus: 'FROM_STOCK' },
  { id: 9, code: 'PR-009', customerName: 'Delivered', fulfillmentStatus: 'FULLY_DELIVERED' },
];
const ir = (id, ticketId, importStep, o = {}) => ({
  id, ticketId, status: 'ISSUED', importStep, docNumber: `IR69${id}`, factoryName: `โรงงาน ${id}`,
  expectedArrivalFrom: null, expectedArrivalTo: null, importStepAt: '2026-11-08', ...o,
});
const ROWS = {
  3: [ir(31, 3, 'IN_TRANSIT', { factoryName: 'Mutina', expectedArrivalFrom: '2026-12-02', expectedArrivalTo: '2026-12-02' })],
  4: [ir(41, 4, 'AWAITING_CUSTOMS', { factoryName: 'Equipe' }), ir(42, 4, 'RECEIVED', { factoryName: 'Peronda' }),
    ir(43, 4, 'ORDERED', { status: 'SUPERSEDED' })],
  5: [],
  6: [ir(61, 6, 'RECEIVED', { factoryName: 'Fap', importStepAt: '2026-11-08' })],
};

function LocationProbe() {
  const location = useLocation();
  return <div data-testid="location-probe">{location.pathname}</div>;
}

function renderOverview(showToast = vi.fn()) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/']}>
        <Routes>
          <Route path="/" element={<ImportOverview user={me} showToast={showToast} />} />
          <Route path="*" element={<LocationProbe />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
  return { showToast };
}

const group = (key) => screen.getByTestId(`import-group-${key}`);
const settled = (key) => waitFor(() => {
  const el = group(key);
  expect(el.getAttribute('aria-busy')).toBeNull();
  return el;
});
const rowIds = (key) => within(group(key)).queryAllByTestId(/^import-row-/).map((el) => el.dataset.testid);
const probe = () => screen.getByTestId('location-probe').textContent;

describe('ImportOverview — the งานนำเข้า worklist', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    queue = QUEUE();
    api.pricingRequests.queue.mockImplementation(() => Promise.resolve({ items: queue }));
    api.tickets.list.mockResolvedValue({ tickets: TICKETS });
    api.storedImportRequests.listForTicket.mockImplementation((id) => Promise.resolve({ importRequests: ROWS[id] ?? [] }));
  });
  afterEach(cleanup);

  it('is titled งานนำเข้า and lists the four groups in next-step order with their counts', async () => {
    renderOverview();
    await settled('arrived');
    await settled('pickup');
    expect(screen.getByRole('heading', { name: 'งานนำเข้า' })).not.toBeNull();
    const order = screen.getAllByTestId(/^import-group-[a-z]+$/).map((el) => el.dataset.testid.replace('import-group-', ''));
    expect(order).toEqual(['pickup', 'pricing', 'transit', 'arrived']);
    expect(within(group('pickup')).getByTestId('group-count').textContent).toBe('2');
    expect(within(group('pricing')).getByTestId('group-count').textContent).toBe('2');
    expect(within(group('transit')).getByTestId('group-count').textContent).toBe('3');
    expect(within(group('arrived')).getByTestId('group-count').textContent).toBe('2');
    expect(screen.getByText(/จัดกลุ่มตามขั้นต่อไป · 9 งาน/)).not.toBeNull();
    // the old tile band and the separate panels are gone
    expect(screen.queryByText('ภาพรวมงานนำเข้า')).toBeNull();
    expect(screen.queryByTestId('in-transit-panel')).toBeNull();
    expect(screen.queryByTestId('ceo-waiting-panel')).toBeNull();
  });

  describe('รอรับเรื่อง', () => {
    it('lists SUBMITTED requests oldest first, with who sent it and how many lines', async () => {
      renderOverview();
      await settled('pickup');
      expect(rowIds('pickup')).toEqual(['import-row-pickup-102', 'import-row-pickup-101']);
      const row = screen.getByTestId('import-row-pickup-102');
      expect(row.textContent).toContain('PCR-102');
      expect(row.textContent).toContain('ลูกค้า 102');
      expect(row.textContent).toContain('คุณสมหมาย');
      expect(row.textContent).toContain('5 รายการ');
    });

    it('รับเรื่อง claims inline with the existing pickup call, stays on the page, and the row moves out', async () => {
      const { showToast } = renderOverview();
      await settled('pickup');
      api.pricingRequests.pickup.mockImplementation((id) => {
        queue = queue.map((r) => (r.id === id ? { ...r, status: 'IMPORT_REVIEWING', assignedImportId: 7 } : r));
        return Promise.resolve({ pricingRequest: {} });
      });
      fireEvent.click(within(screen.getByTestId('import-row-pickup-102')).getByRole('button', { name: 'รับเรื่อง' }));
      await waitFor(() => expect(api.pricingRequests.pickup).toHaveBeenCalledWith(102));
      await waitFor(() => expect(rowIds('pickup')).toEqual(['import-row-pickup-101']));
      expect(rowIds('pricing')).toContain('import-row-pricing-102');
      expect(showToast).toHaveBeenCalledWith('success', expect.stringContaining('รับเรื่อง'));
      expect(screen.queryByTestId('location-probe')).toBeNull();
    });

    it('รับเรื่อง is disabled while its claim is in flight, so a double tap claims once', async () => {
      renderOverview();
      await settled('pickup');
      let resolve;
      api.pricingRequests.pickup.mockReturnValue(new Promise((r) => { resolve = r; }));
      const button = within(screen.getByTestId('import-row-pickup-102')).getByRole('button', { name: 'รับเรื่อง' });
      fireEvent.click(button);
      await waitFor(() => expect(button.disabled).toBe(true));
      fireEvent.click(button);
      expect(api.pricingRequests.pickup).toHaveBeenCalledTimes(1);
      resolve({ pricingRequest: {} });
    });

    it('a refused claim (someone else got it first) says so and keeps the row', async () => {
      const { showToast } = renderOverview();
      await settled('pickup');
      api.pricingRequests.pickup.mockRejectedValue(new Error('รับเรื่องได้เฉพาะคำขอราคาที่ถูกยื่นแล้วเท่านั้น'));
      fireEvent.click(within(screen.getByTestId('import-row-pickup-101')).getByRole('button', { name: 'รับเรื่อง' }));
      await waitFor(() => expect(showToast).toHaveBeenCalledWith('error', 'รับเรื่องได้เฉพาะคำขอราคาที่ถูกยื่นแล้วเท่านั้น'));
      expect(rowIds('pickup')).toContain('import-row-pickup-101');
    });
  });

  describe('รอราคาโรงงาน', () => {
    it('shows MY requests in import’s hands by default; CEO and finished requests are not here', async () => {
      renderOverview();
      await settled('pricing');
      expect(rowIds('pricing').sort()).toEqual(['import-row-pricing-111', 'import-row-pricing-112']);
      fireEvent.click(within(screen.getByTestId('import-row-pricing-112')).getByRole('button', { name: 'เปิด' }));
      expect(probe()).toBe('/pricing-requests/112');
    });

    it('ทั้งทีม adds the team’s requests, naming who holds each', async () => {
      renderOverview();
      await settled('pricing');
      fireEvent.click(screen.getByRole('button', { name: 'ทั้งทีม' }));
      expect(rowIds('pricing').sort()).toEqual(['import-row-pricing-111', 'import-row-pricing-112', 'import-row-pricing-121']);
      expect(screen.getByTestId('import-row-pricing-121').textContent).toContain('สมศรี นำเข้า');
      expect(screen.getByRole('button', { name: 'ทั้งทีม' }).getAttribute('aria-pressed')).toBe('true');
    });
  });

  describe('กำลังขนส่ง and ถึงโกดังแล้ว', () => {
    it('lists every live factory shipment not yet at the warehouse with its step, n/6 and ETA; อัปเดต opens /fulfilment', async () => {
      renderOverview();
      await settled('transit');
      expect(rowIds('transit').sort()).toEqual(['import-row-transit-31', 'import-row-transit-41', 'import-row-transit-legacy-5']);
      const row = screen.getByTestId('import-row-transit-31');
      expect(row.textContent).toContain('IR6931');
      expect(row.textContent).toContain('Siam Mall');
      expect(row.textContent).toContain('Mutina');
      expect(row.textContent).toContain('กำลังเดินทาง');
      expect(row.textContent).toContain('4/6');
      expect(row.textContent).toContain('ETA');
      fireEvent.click(within(row).getByRole('button', { name: 'อัปเดต' }));
      expect(probe()).toBe('/fulfilment');
    });

    it('a pre-V184 deal with no stored rows is still listed, and opens its deal page', async () => {
      renderOverview();
      await settled('transit');
      const row = screen.getByTestId('import-row-transit-legacy-5');
      expect(row.textContent).toContain('Legacy Co');
      fireEvent.click(within(row).getByRole('button', { name: 'เปิด' }));
      expect(probe()).toBe('/import/deals/5');
    });

    it('ถึงโกดังแล้ว lists received shipments with no action (it is sales’ turn)', async () => {
      renderOverview();
      await settled('arrived');
      expect(rowIds('arrived').sort()).toEqual(['import-row-arrived-42', 'import-row-arrived-61']);
      expect(within(group('arrived')).queryAllByRole('button')).toHaveLength(0);
      expect(within(group('arrived')).getByText(/ไม่มีงานของคุณ/)).not.toBeNull();
    });
  });

  describe('empty, loading and failure', () => {
    it('an empty group keeps its header and says ไม่มีรายการ', async () => {
      queue = queue.filter((r) => r.status !== 'SUBMITTED');
      renderOverview();
      await settled('pickup');
      expect(within(group('pickup')).getByTestId('group-count').textContent).toBe('0');
      expect(within(group('pickup')).getByText('ไม่มีรายการ')).not.toBeNull();
    });

    it('nothing at all reads as one calm line', async () => {
      queue = [];
      api.tickets.list.mockResolvedValue({ tickets: [] });
      renderOverview();
      expect(await screen.findByText('ไม่มีงานนำเข้าที่ต้องทำตอนนี้')).not.toBeNull();
    });

    it('a failed queue marks only the two request groups; shipments still render', async () => {
      api.pricingRequests.queue.mockRejectedValue(new Error('boom'));
      renderOverview();
      await settled('transit');
      expect(within(group('pickup')).getByText('โหลดไม่สำเร็จ')).not.toBeNull();
      expect(within(group('pricing')).getByText('โหลดไม่สำเร็จ')).not.toBeNull();
      expect(rowIds('transit').length).toBe(3);
    });

    // Review H2: a deal whose ใบขอซื้อ rows failed to load is NOT a deal with nothing to do.
    it('every per-deal query failing shows the per-deal error, never the nothing-to-do line', async () => {
      queue = [];
      api.storedImportRequests.listForTicket.mockRejectedValue(new Error('boom'));
      renderOverview();
      const banner = await screen.findByTestId('import-rows-error');
      expect(banner.textContent).toContain('โหลดใบขอซื้อของงานต่อไปนี้ไม่สำเร็จ');
      expect(within(banner).getByRole('link', { name: /Siam Mall/ }).getAttribute('href')).toBe('/import/deals/3');
      expect(screen.queryByText('ไม่มีงานนำเข้าที่ต้องทำตอนนี้')).toBeNull();
      expect(screen.getByTestId('import-partial').textContent).toContain('ข้อมูลไม่ครบ — โหลดบางงานไม่สำเร็จ ลองใหม่');
    });

    it('some per-deal queries failing marks the shipment counts as partial, with a retry', async () => {
      api.storedImportRequests.listForTicket.mockImplementation((id) => (id === 4
        ? Promise.reject(new Error('boom'))
        : Promise.resolve({ importRequests: ROWS[id] ?? [] })));
      renderOverview();
      await settled('transit');
      expect(await screen.findByTestId('import-partial')).not.toBeNull();
      expect(rowIds('transit')).toContain('import-row-transit-31');
      expect(within(screen.getByTestId('import-rows-error')).getByRole('link', { name: /บ้านพักชะอำ/ })).not.toBeNull();
      api.storedImportRequests.listForTicket.mockImplementation((id) => Promise.resolve({ importRequests: ROWS[id] ?? [] }));
      fireEvent.click(screen.getByRole('button', { name: 'ลองใหม่' }));
      await waitFor(() => expect(screen.queryByTestId('import-partial')).toBeNull());
      expect(rowIds('transit')).toContain('import-row-transit-41');
    });

    it('while the deal list loads, the shipment groups are busy and the request groups are not', async () => {
      api.tickets.list.mockImplementation(never);
      renderOverview();
      await settled('pickup');
      expect(group('transit').getAttribute('aria-busy')).toBe('true');
      expect(group('arrived').getAttribute('aria-busy')).toBe('true');
    });
  });
});
