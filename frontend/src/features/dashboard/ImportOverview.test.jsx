import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { fireEvent, render, screen, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ImportOverview } from './ImportOverview.jsx';
import { api } from '../../api/index.js';

globalThis.React = React;

vi.mock('../../api/index.js', async (importOriginal) => {
  const actual = await importOriginal();
  return {
    ...actual,
    api: {
      tickets: { list: vi.fn() },
      pricingRequests: { queue: vi.fn() },
    },
  };
});

const user = { role: 'import', employeeId: 2, name: 'นำเข้า ทดสอบ' };
const employee = { nickName: 'นำเข้า' };

// One ticket per fulfilment stage (issueImportRequest / markIrSent /
// markShipping / markGoodsReceived / recordDelivery) plus a draft deal whose
// only open item is an unpicked pricing request — covers every branch of
// nextImportAction/nextFulfilmentActionCode (importActions.js) the worklist
// and conveyor pulse both read from.
const TICKETS = [
  { id: 1, code: 'D-001', title: 'ดีล 1', customerName: 'บริษัท A', status: 'quotation_issued', fulfillmentStatus: null, overdue: false, updatedAt: '2026-07-01T00:00:00Z' },
  { id: 2, code: 'D-002', title: 'ดีล 2', customerName: 'บริษัท B', status: 'quotation_issued', fulfillmentStatus: 'IR_ISSUED', overdue: true, updatedAt: '2026-07-02T00:00:00Z' },
  { id: 3, code: 'D-003', title: 'ดีล 3', customerName: 'บริษัท C', status: 'quotation_issued', fulfillmentStatus: 'IR_SENT', overdue: false, updatedAt: '2026-07-03T00:00:00Z' },
  { id: 4, code: 'D-004', title: 'ดีล 4', customerName: 'บริษัท D', status: 'quotation_issued', fulfillmentStatus: 'SHIPPING', overdue: false, updatedAt: '2026-07-04T00:00:00Z' },
  { id: 5, code: 'D-005', title: 'ดีล 5', customerName: 'บริษัท E', status: 'quotation_issued', fulfillmentStatus: 'GOODS_RECEIVED', overdue: false, updatedAt: '2026-07-05T00:00:00Z' },
  { id: 6, code: 'D-006', title: 'ดีล 6', customerName: 'บริษัท F', status: 'draft', fulfillmentStatus: null, overdue: false, updatedAt: '2026-07-06T00:00:00Z' },
];

const PRICING_REQUESTS = [
  { id: 101, ticketId: 6, status: 'SUBMITTED' },
  { id: 102, ticketId: 1, status: 'IMPORT_REVIEWING' },
];

function renderOverview() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/']}>
        <ImportOverview user={user} employee={employee} />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('ImportOverview', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.tickets.list.mockResolvedValue({ tickets: TICKETS });
    api.pricingRequests.queue.mockResolvedValue({ items: PRICING_REQUESTS });
  });

  it('renders without SalesTabs (import no longer browses the deal pipeline)', async () => {
    renderOverview();
    await screen.findByText('สิ่งที่ต้องทำ');
    expect(screen.queryByLabelText('งานขาย (Sales)')).toBeNull();
    expect(screen.queryByText('ดีลทั้งหมด')).toBeNull();
  });

  it('computes conveyor-pulse bucket counts from the ticket list + pricing queue', async () => {
    renderOverview();
    // Both queries resolve async — wait for the worklist to actually paint
    // before reading pulse counts, or every tile reads its 0 initial state.
    await screen.findAllByText(/^บริษัท /);

    // Scoped to the pulse panel: "เกินกำหนด" also appears as a StatusBadge on
    // the overdue worklist row below, so an unscoped getByText would match twice.
    const pulsePanel = screen.getByText('สถานะงานทั้งหมด').closest('section');
    function pulseCount(label) {
      return within(pulsePanel).getByText(label).closest('button').querySelector('span').textContent;
    }

    expect(pulseCount('เกินกำหนด')).toBe('1'); // D-002 only
    expect(pulseCount('ตั้งราคา')).toBe('2'); // both pricing-request rows
    expect(pulseCount('จัดซื้อ/IR')).toBe('2'); // D-001 (issue) + D-002 (send)
    expect(pulseCount('ขนส่ง')).toBe('1'); // D-003 (markShipping pending)
    expect(pulseCount('รับเข้าคลัง')).toBe('1'); // D-004 (markGoodsReceived pending)
    expect(pulseCount('ส่งมอบ')).toBe('1'); // D-005 (recordDelivery pending)
  });

  // "กำลังขนส่ง" also renders D-003/D-004 by customer name (a separate,
  // unfiltered tracker — see IN_TRANSIT_STATUSES), so every lookup below is
  // scoped to the "สิ่งที่ต้องทำ" panel specifically to avoid matching that
  // second occurrence.
  function worklistPanel() {
    return screen.getByText('สิ่งที่ต้องทำ').closest('section');
  }

  it('gives each worklist row the correct next-action CTA and sorts overdue first', async () => {
    renderOverview();
    await screen.findAllByText(/^บริษัท /);
    const panel = worklistPanel();

    const rows = within(panel).getAllByText(/^บริษัท /).map((el) => el.textContent);
    expect(rows).toHaveLength(6);
    // D-002 is the only overdue deal — it must lead the worklist regardless
    // of its fulfilment stage.
    expect(rows[0]).toBe('บริษัท B');

    function ctaFor(company) {
      // strong.customerName -> "flex items-center gap-2" wrapper -> "min-w-0
      // flex-1" info block -> the row div that also holds the CTA <Button>.
      const row = within(panel).getByText(company).closest('div').parentElement.parentElement;
      return within(row).getByRole('button').textContent;
    }

    expect(ctaFor('บริษัท A')).toBe('ออก IR');
    expect(ctaFor('บริษัท B')).toBe('ส่ง IR');
    expect(ctaFor('บริษัท C')).toBe('บันทึกออกเดินทาง');
    expect(ctaFor('บริษัท D')).toBe('ยืนยันรับเข้าคลัง');
    expect(ctaFor('บริษัท E')).toBe('บันทึกส่งมอบ');
    expect(ctaFor('บริษัท F')).toBe('รับงาน · ขอราคา');
  });

  it('filters the worklist when a conveyor bucket is clicked, and clears on ล้างตัวกรอง', async () => {
    renderOverview();
    await screen.findAllByText(/^บริษัท /);

    expect(within(worklistPanel()).getAllByText(/^บริษัท /)).toHaveLength(6);

    fireEvent.click(screen.getByText('รับเข้าคลัง').closest('button'));
    expect(await within(worklistPanel()).findAllByText(/^บริษัท /)).toHaveLength(1);
    expect(within(worklistPanel()).getByText('บริษัท D')).toBeTruthy();

    fireEvent.click(screen.getByText('ล้างตัวกรอง'));
    expect(await within(worklistPanel()).findAllByText(/^บริษัท /)).toHaveLength(6);
  });
});
