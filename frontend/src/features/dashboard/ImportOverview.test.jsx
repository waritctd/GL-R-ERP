import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ImportOverview } from './ImportOverview.jsx';
import { api } from '../../api/index.js';
import { formatThaiDate, greetingName } from '../../utils/format.js';
import { expectFigure, figureRows } from '../../test/fixtures/dashboardMoney.js';

globalThis.React = React;

vi.mock('../../api/index.js', async (importOriginal) => {
  const actual = await importOriginal();
  return {
    ...actual,
    api: {
      tickets: { list: vi.fn() },
      pricingRequests: { queue: vi.fn() },
      storedImportRequests: { listForTicket: vi.fn() },
    },
  };
});

const user = { id: 7, role: 'import', employeeId: 2, name: 'นำเข้า ทดสอบ' };
const employee = { nickName: 'นำเข้า' };
const never = () => new Promise(() => {});

// One ticket per fulfilment stage; D-005 (GOODS_RECEIVED) is delivery-ready, which is Sales' prompt:
// no row and no count on this page. D-001 (no IR yet) is no import to-do either.
const TICKETS = [
  { id: 1, code: 'D-001', title: 'ดีล 1', customerName: 'บริษัท A', status: 'quotation_issued', fulfillmentStatus: null, overdue: false, updatedAt: '2026-07-01T00:00:00Z' },
  { id: 2, code: 'D-002', title: 'ดีล 2', customerName: 'บริษัท B', status: 'quotation_issued', fulfillmentStatus: 'IR_ISSUED', overdue: true, updatedAt: '2026-07-02T00:00:00Z' },
  { id: 3, code: 'D-003', title: 'ดีล 3', customerName: 'บริษัท C', status: 'quotation_issued', fulfillmentStatus: 'IR_SENT', overdue: false, updatedAt: '2026-07-03T00:00:00Z' },
  { id: 4, code: 'D-004', title: 'ดีล 4', customerName: 'บริษัท D', status: 'quotation_issued', fulfillmentStatus: 'SHIPPING', overdue: false, updatedAt: '2026-07-04T00:00:00Z' },
  { id: 5, code: 'D-005', title: 'ดีล 5', customerName: 'บริษัท E', status: 'quotation_issued', fulfillmentStatus: 'GOODS_RECEIVED', overdue: false, updatedAt: '2026-07-05T00:00:00Z' },
  { id: 6, code: 'D-006', title: 'ดีล 6', customerName: 'บริษัท F', status: 'draft', fulfillmentStatus: null, overdue: false, updatedAt: '2026-07-06T00:00:00Z' },
];

const pcr = (id, status, o = {}) => ({
  id, status, ticketId: 1, customerName: `ลูกค้า PCR ${id}`, requestCode: `PCR-${id}`, ticketCode: `PR-${id}`,
  recipientType: 'DESIGNER', assignedImportId: null, ...o,
});
const PRICING_REQUESTS = [
  // unclaimed inbox
  pcr(101, 'SUBMITTED', { ticketId: 6 }),
  pcr(102, 'SUBMITTED'),
  // mine: 1 รับเรื่อง + 4 เจรจากับโรงงาน (one of them the retired COSTING_IN_PROGRESS) = 5, one past the cap of 3
  pcr(111, 'IMPORT_REVIEWING', { assignedImportId: 7 }),
  pcr(112, 'AWAITING_FACTORY_RESPONSE', { assignedImportId: 7 }),
  pcr(113, 'AWAITING_FACTORY_RESPONSE', { assignedImportId: 7 }),
  pcr(114, 'COSTING_IN_PROGRESS', { assignedImportId: 7 }),
  pcr(115, 'AWAITING_FACTORY_RESPONSE', { assignedImportId: 7 }),
  // somebody else's, and mine-but-already-with-the-CEO, and finished: none are "mine to work"
  pcr(121, 'AWAITING_FACTORY_RESPONSE', { assignedImportId: 99 }),
  pcr(131, 'READY_FOR_CEO_REVIEW', { assignedImportId: 7 }),
  pcr(132, 'CEO_REVIEWING', { assignedImportId: 99 }),
  pcr(141, 'APPROVED_FOR_QUOTATION', { assignedImportId: 7 }),
];

// Answer like the server: honour status / assignedImportId / activeOnly, so the page may fetch one
// list or several and still see the same data.
function serveQueue(items = PRICING_REQUESTS) {
  api.pricingRequests.queue.mockImplementation((params = {}) => Promise.resolve({
    items: items.filter((r) => (params.status == null || r.status === params.status)
      && (params.assignedImportId == null || r.assignedImportId === params.assignedImportId)
      && (!params.activeOnly || !['APPROVED_FOR_QUOTATION', 'QUOTATION_ISSUED', 'CANCELLED', 'SUPERSEDED'].includes(r.status))),
  }));
}

function LocationProbe() {
  const location = useLocation();
  return <div data-testid="location-probe">{location.pathname}{location.search}</div>;
}

function renderOverview() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/']}>
        <Routes>
          <Route path="/" element={<ImportOverview user={user} employee={employee} />} />
          <Route path="*" element={<LocationProbe />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

const group = (key) => screen.getByTestId(`worklist-group-${key}`);
const findGroup = (key) => screen.findByTestId(`worklist-group-${key}`);
const probe = () => screen.getByTestId('location-probe').textContent;
const cta = (key, id) => within(screen.getByTestId(`worklist-row-${key}-${id}`)).getByRole('button');

describe('ImportOverview', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.tickets.list.mockResolvedValue({ tickets: TICKETS });
    serveQueue();
    api.storedImportRequests.listForTicket.mockResolvedValue({ importRequests: [] });
  });

  it('titles the page ภาพรวมงานนำเข้า with a greeting and today\'s date, and drops the old tile strip', async () => {
    renderOverview();
    await findGroup('import');
    expect(screen.getByRole('heading', { name: 'ภาพรวมงานนำเข้า' })).not.toBeNull();
    expect(screen.getByText(`สวัสดี, ${greetingName('นำเข้า')} · ${formatThaiDate(new Date().toISOString())}`)).not.toBeNull();
    expect(screen.queryByText('สถานะงานทั้งหมด')).toBeNull();
    // the finance flag is gone, and so is the clickable tile filter
    expect(screen.queryByText('เกินกำหนด')).toBeNull();
    expect(screen.queryByText('ล้างตัวกรอง')).toBeNull();
    expect(document.querySelector('button[aria-pressed]')).toBeNull();
  });

  describe('loading and failure (per source)', () => {
    it('renders the pricing groups as soon as the queue lands while the deal list is still loading; only นำเข้า waits', async () => {
      api.tickets.list.mockImplementation(never);
      renderOverview();
      await findGroup('pickup');
      expect(group('import').getAttribute('aria-busy')).toBe('true');
      expect(group('pickup').getAttribute('aria-busy')).toBeNull();
      expectFigure('รอรับเรื่อง', '2 คำขอ');
      expect(screen.queryByTestId('worklist-total')).toBeNull(); // would be a partial sum
    });

    it('shows a failed pricing queue as โหลดไม่สำเร็จ on its groups while the fulfilment group still renders', async () => {
      api.pricingRequests.queue.mockRejectedValue(new Error('boom'));
      renderOverview();
      await findGroup('import');
      const failed = await screen.findAllByText('โหลดไม่สำเร็จ');
      expect(failed.map((el) => el.closest('[data-testid^="worklist-group-"]').dataset.testid).sort()).toEqual(['mine', 'pickup']);
      expect(group('import').getAttribute('aria-busy')).toBeNull();
      expect(within(group('import')).getByTestId('worklist-row-import-2')).not.toBeNull();
    });
  });

  describe('figures band', () => {
    it('shows four counts in chain order with the captions of the approved design', async () => {
      renderOverview();
      await findGroup('import');
      const labels = [...document.querySelectorAll('dl dt')].map((el) => el.textContent);
      expect(labels).toEqual(['รอรับเรื่อง', 'กำลังขอราคา (ของฉัน)', 'รอ CEO ตั้งราคา', 'นำเข้า / ขนส่ง']);
      expect(figureRows('รอรับเรื่อง')[0]).toContain('2 คำขอ');
      expect(figureRows('รอรับเรื่อง')[0]).toContain('ยังไม่มีใครรับ');
      expect(figureRows('กำลังขอราคา (ของฉัน)')[0]).toContain('5 คำขอ');
      expect(figureRows('กำลังขอราคา (ของฉัน)')[0]).toContain('รับเรื่อง 1 · เจรจากับโรงงาน 4');
      expect(figureRows('รอ CEO ตั้งราคา')[0]).toContain('2 คำขอ');
      expect(figureRows('รอ CEO ตั้งราคา')[0]).toContain('ส่งต่อแล้ว ไม่ต้องทำอะไร');
      expect(figureRows('นำเข้า / ขนส่ง')[0]).toContain('3 ดีล');
      expect(figureRows('นำเข้า / ขนส่ง')[0]).toContain('ออกใบขอซื้อ 1 · ขนส่ง 1 · ถึงคลัง 1');
    });

    it('every number equals the group or panel it summarises (and the queue page\'s own tab filter)', async () => {
      renderOverview();
      await findGroup('import');
      const rowCount = (g) => within(g).queryAllByTestId(/^worklist-row-/).length;
      // groups cap at 3 rows, so compare to the header count they print
      expect(group('pickup').textContent).toContain('2 รายการ');
      expect(group('mine').textContent).toContain('5 รายการ');
      expect(group('import').textContent).toContain('3 รายการ');
      expect(rowCount(group('pickup'))).toBe(2);
      expect(rowCount(group('mine'))).toBe(3);
      // รอ CEO panel lists exactly the band's 2
      expect(within(screen.getByTestId('ceo-waiting-panel')).queryAllByTestId(/^ceo-waiting-row-/)).toHaveLength(2);
    });

    it('counts a per-factory shipment on ขนส่ง (2 of 3 stored rows), not the deal-level status', async () => {
      api.tickets.list.mockResolvedValue({ tickets: [{ ...TICKETS[0], id: 21, code: 'D-021', fulfillmentStatus: 'IR_ISSUED' }] });
      api.storedImportRequests.listForTicket.mockResolvedValue({ importRequests: [
        { id: 1, ticketId: 21, status: 'ISSUED', importStep: 'IN_TRANSIT', factoryName: 'โรงงาน ก' },
        { id: 2, ticketId: 21, status: 'ISSUED', importStep: 'IN_TRANSIT', factoryName: 'โรงงาน ข' },
        { id: 3, ticketId: 21, status: 'ISSUED', importStep: 'ORDERED', factoryName: 'โรงงาน ค' },
      ] });
      renderOverview();
      await vi.waitFor(() => expect(figureRows('นำเข้า / ขนส่ง')[0]).toContain('ขนส่ง 2'));
    });
  });

  describe('กำลังขอราคา (ของฉัน) filter', () => {
    it('keeps only my requests in an import-side status; others\' requests and CEO statuses are out', async () => {
      renderOverview();
      await findGroup('mine');
      const shown = ['111', '112', '113'].map((id) => screen.queryByTestId(`worklist-row-mine-${id}`));
      shown.forEach((el) => expect(el).not.toBeNull());
      // 5 are mine; 121 (assigned to 99), 131 (mine but READY_FOR_CEO_REVIEW), 132, 141 never show up in this group
      ['121', '131', '132', '141', '101'].forEach((id) => expect(within(group('mine')).queryByTestId(`worklist-row-mine-${id}`), id).toBeNull());
      expectFigure('กำลังขอราคา (ของฉัน)', '5 คำขอ');
    });

    it('reads 0 when nothing is assigned to me, even though other people have open requests', async () => {
      serveQueue(PRICING_REQUESTS.filter((r) => r.assignedImportId !== 7));
      renderOverview();
      await findGroup('pickup');
      expectFigure('กำลังขอราคา (ของฉัน)', '0 คำขอ');
      expect(screen.queryByTestId('worklist-group-mine')).toBeNull();
    });
  });

  describe('สิ่งที่ต้องทำ worklist', () => {
    it('groups in the fixed order with a total badge counting every row', async () => {
      renderOverview();
      await findGroup('import');
      const order = [...document.querySelectorAll('[data-testid^="worklist-group-"]')]
        .map((el) => el.dataset.testid.replace('worklist-group-', ''));
      expect(order).toEqual(['pickup', 'mine', 'import']);
      ['รับเรื่องใหม่', 'ขอราคากับโรงงาน', 'นำเข้า'].forEach((label, i) => {
        expect(group(['pickup', 'mine', 'import'][i]).textContent).toContain(label);
      });
      expect(screen.getByTestId('worklist-total').textContent).toBe('10'); // 2 + 5 + 3
    });

    it('รับเรื่องใหม่: one row per SUBMITTED request, with PCR · PR · recipient, and a รับเรื่อง button to the request', async () => {
      renderOverview();
      await findGroup('pickup');
      const row = screen.getByTestId('worklist-row-pickup-101');
      expect(row.textContent).toContain('ลูกค้า PCR 101');
      expect(row.textContent).toContain('PCR-101 · PR-101 · ผู้ออกแบบ');
      expect(cta('pickup', 101).textContent).toBe('รับเรื่อง');
      expect(screen.getByTestId('worklist-row-pickup-102')).not.toBeNull();
      fireEvent.click(cta('pickup', 101));
      expect(probe()).toBe('/pricing-requests/101');
    });

    it('ขอราคากับโรงงาน: my requests show PCR · status label and an เปิดคำขอ button; capped at 3 with อีก 2 รายการ', async () => {
      renderOverview();
      await findGroup('mine');
      expect(screen.getByTestId('worklist-row-mine-111').textContent).toContain('PCR-111 · รับเรื่อง');
      expect(screen.getByTestId('worklist-row-mine-112').textContent).toContain('PCR-112 · เจรจาราคากับโรงงาน');
      expect(within(group('mine')).queryByTestId('worklist-row-mine-114')).toBeNull();
      expect(within(group('mine')).getByText('อีก 2 รายการ')).not.toBeNull();
      expect(cta('mine', 112).textContent).toBe('เปิดคำขอ');
      fireEvent.click(cta('mine', 112));
      expect(probe()).toBe('/pricing-requests/112');
    });

    it('นำเข้า: the existing fulfilment rows, labels and targets unchanged, overdue deal first; delivery-ready and no-IR deals have no row', async () => {
      renderOverview();
      await findGroup('import');
      const rows = within(group('import')).getAllByTestId(/^worklist-row-import-/).map((el) => el.dataset.testid);
      expect(rows).toEqual(['worklist-row-import-2', 'worklist-row-import-3', 'worklist-row-import-4']);
      expect(cta('import', 2).textContent).toBe('อัปเดตสถานะนำเข้า');
      expect(cta('import', 3).textContent).toBe('บันทึกออกเดินทาง');
      expect(cta('import', 4).textContent).toBe('ยืนยันรับเข้าคลัง');
      ['1', '5', '6'].forEach((id) => expect(screen.queryByTestId(`worklist-row-import-${id}`), id).toBeNull());
      fireEvent.click(cta('import', 3));
      expect(probe()).toBe('/import/deals/3');
      cleanup();
      renderOverview();
      await findGroup('import');
      fireEvent.click(cta('import', 2));
      expect(probe()).toBe('/fulfilment');
    });

    it('ดูทั้งหมด → opens the request queue for the two pricing groups and /fulfilment for นำเข้า', async () => {
      const open = async (key) => {
        renderOverview();
        await findGroup('import');
        fireEvent.click(within(group(key)).getByText('ดูทั้งหมด →'));
        const where = probe();
        cleanup();
        return where;
      };
      expect(await open('pickup')).toBe('/pricing-requests');
      expect(await open('mine')).toBe('/pricing-requests');
      expect(await open('import')).toBe('/fulfilment');
    });

    it('hides empty groups and shows the empty line when there is nothing to do', async () => {
      api.tickets.list.mockResolvedValue({ tickets: [] });
      serveQueue([]);
      renderOverview();
      await screen.findByText('ไม่มีงานที่ต้องดำเนินการตอนนี้');
      ['pickup', 'mine', 'import'].forEach((k) => expect(screen.queryByTestId(`worklist-group-${k}`), k).toBeNull());
    });
  });

  describe('right column', () => {
    it('รอ CEO ตั้งราคา lists the READY_FOR_CEO_REVIEW / CEO_REVIEWING requests with a รอ CEO badge and no button', async () => {
      renderOverview();
      const panel = await screen.findByTestId('ceo-waiting-panel');
      expect(within(panel).getByText('รอ CEO ตั้งราคา')).not.toBeNull();
      const row = within(panel).getByTestId('ceo-waiting-row-131');
      expect(row.textContent).toContain('ลูกค้า PCR 131');
      expect(row.textContent).toContain('PCR-131 · PR-131');
      expect(within(row).getByText('รอ CEO')).not.toBeNull();
      expect(within(panel).getByTestId('ceo-waiting-row-132')).not.toBeNull(); // someone else's, still awareness
      expect(within(panel).queryAllByRole('button')).toHaveLength(0);
    });

    it('hides รอ CEO ตั้งราคา when nothing is with the CEO', async () => {
      serveQueue(PRICING_REQUESTS.filter((r) => !['READY_FOR_CEO_REVIEW', 'CEO_REVIEWING'].includes(r.status)));
      renderOverview();
      await findGroup('pickup');
      expect(screen.queryByTestId('ceo-waiting-panel')).toBeNull();
      expect(screen.queryByText('รอ CEO ตั้งราคา', { selector: 'h2, h3' })).toBeNull();
    });

    it('กำลังขนส่ง lists in-transit deals and opens the import deal page', async () => {
      renderOverview();
      const panel = await screen.findByTestId('in-transit-panel');
      await vi.waitFor(() => expect(within(panel).getByText('บริษัท C')).not.toBeNull());
      fireEvent.click(within(panel).getByText('บริษัท C').closest('button'));
      expect(probe()).toBe('/import/deals/3');
    });

    it('กำลังขนส่ง lists a deal whose factory shipment is in transit even when the deal-level status is not', async () => {
      api.tickets.list.mockResolvedValue({ tickets: [{ ...TICKETS[0], id: 21, code: 'D-021', customerName: 'บริษัท รายโรงงาน', fulfillmentStatus: 'IR_ISSUED' }] });
      api.storedImportRequests.listForTicket.mockResolvedValue({ importRequests: [{ id: 1, ticketId: 21, status: 'ISSUED', importStep: 'IN_TRANSIT' }] });
      renderOverview();
      const panel = await screen.findByTestId('in-transit-panel');
      await vi.waitFor(() => expect(within(panel).getByText('บริษัท รายโรงงาน')).not.toBeNull());
    });

    it('กำลังขนส่ง empty is one quiet line, not an EmptyState block', async () => {
      api.tickets.list.mockResolvedValue({ tickets: [TICKETS[1]] }); // IR_ISSUED only: nothing on the water
      renderOverview();
      const panel = await screen.findByTestId('in-transit-panel');
      expect(within(panel).getByText('ไม่มีสินค้าระหว่างขนส่งตอนนี้')).not.toBeNull();
      expect(panel.querySelector('[class*="EmptyState"], .empty-state')).toBeNull();
      expect(within(panel).queryAllByRole('button')).toHaveLength(0);
    });
  });
});
