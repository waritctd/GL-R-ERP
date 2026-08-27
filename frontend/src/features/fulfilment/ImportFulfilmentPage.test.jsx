import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ImportFulfilmentPage } from './ImportFulfilmentPage.jsx';
import { api } from '../../api/index.js';

globalThis.React = React;

vi.mock('../../api/index.js', async (importOriginal) => {
  const actual = await importOriginal();
  return {
    ...actual,
    api: {
      tickets: {
        list: vi.fn(),
        issueImportRequest: vi.fn(),
        markIrSent: vi.fn(),
        markShipping: vi.fn(),
        markGoodsReceived: vi.fn(),
      },
    },
  };
});

function deal(overrides = {}) {
  return {
    id: 1,
    code: 'PR-2026-0701',
    title: 'ดีลทดสอบ',
    customerName: 'บริษัท ทดสอบ จำกัด',
    projectName: 'โครงการทดสอบ',
    status: 'quotation_issued',
    fulfillmentStatus: null,
    paymentStatus: 'DEPOSIT_PAID',
    overdue: false,
    dueDate: null,
    updatedAt: '2026-08-01',
    ...overrides,
  };
}

function renderPage(showToast = vi.fn()) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  const utils = render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/fulfilment']}>
        <ImportFulfilmentPage user={{ id: 5, name: 'ฝ่ายนำเข้า', role: 'import' }} showToast={showToast} />
      </MemoryRouter>
    </QueryClientProvider>,
  );
  return { ...utils, queryClient, showToast };
}

function rowFor(code) {
  const found = screen.getAllByTestId('fulfilment-row').find((r) => r.textContent.includes(code));
  if (!found) throw new Error(`no rendered row for ${code}`);
  return found;
}

// The chip bar and the rows carry the SAME Thai wording by design — both read it
// from IMPORT_ACTION_LABELS so a chip can never drift from the button it filters
// to — so an unscoped getByRole('button', { name: /ออกคำขอนำเข้า/ }) matches two
// elements and throws. Same collision PricingRequestQueuePage.test.jsx documents.
// Every chip assertion scopes here; every row assertion scopes to rowFor().
function chipBar() {
  return screen.getByRole('button', { name: /ทั้งหมด/ }).parentElement;
}

function chip(label) {
  return within(chipBar()).getByRole('button', { name: new RegExp(label) });
}

/** Click a row's action button and then its ยืนยัน — the two-step arm/confirm. */
function armAndConfirm(code) {
  fireEvent.click(within(rowFor(code)).getByTestId('fulfilment-action'));
  fireEvent.click(within(rowFor(code)).getByTestId('fulfilment-confirm-submit'));
}

describe('ImportFulfilmentPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.tickets.list.mockResolvedValue({ tickets: [deal()] });
  });

  it('renders one row per deal on the import axis, defaulting to ทั้งหมด', async () => {
    renderPage();

    expect(await screen.findByText('บริษัท ทดสอบ จำกัด')).not.toBeNull();
    expect(within(rowFor('PR-2026-0701')).getByTestId('fulfilment-action').textContent).toBe('ออกคำขอนำเข้า');
    await waitFor(() => expect(api.tickets.list).toHaveBeenCalledWith({}));
  });

  it('derives each row\'s action from nextFulfilmentActionCode, never a second copy', async () => {
    api.tickets.list.mockResolvedValue({
      tickets: [
        deal({ id: 1, code: 'D-NULL', fulfillmentStatus: null }),
        deal({ id: 2, code: 'D-ISSUED', fulfillmentStatus: 'IR_ISSUED' }),
        deal({ id: 3, code: 'D-SENT', fulfillmentStatus: 'IR_SENT' }),
        deal({ id: 4, code: 'D-SHIPPING', fulfillmentStatus: 'SHIPPING' }),
      ],
    });
    renderPage();
    await screen.findByText('D-NULL');

    expect(within(rowFor('D-NULL')).getByTestId('fulfilment-action').textContent).toBe('ออกคำขอนำเข้า');
    expect(within(rowFor('D-ISSUED')).getByTestId('fulfilment-action').textContent).toBe('ส่งคำขอนำเข้าแล้ว');
    expect(within(rowFor('D-SENT')).getByTestId('fulfilment-action').textContent).toBe('บันทึกออกเดินทาง');
    expect(within(rowFor('D-SHIPPING')).getByTestId('fulfilment-action').textContent).toBe('ยืนยันรับเข้าคลัง');
  });

  // Wrong-way-round, and the single most important assertion on this page: delivery
  // is out of scope (moving to Sales), so a delivery-ready deal must NOT appear here
  // at all — not as a row with a disabled button, not as a row linking elsewhere.
  it('excludes every recordDelivery deal — delivery is not this workspace\'s job', async () => {
    api.tickets.list.mockResolvedValue({
      tickets: [
        deal({ id: 1, code: 'D-IN-SCOPE', fulfillmentStatus: 'SHIPPING' }),
        deal({ id: 2, code: 'D-GOODS-RECEIVED', fulfillmentStatus: 'GOODS_RECEIVED' }),
        deal({ id: 3, code: 'D-FROM-STOCK', fulfillmentStatus: 'FROM_STOCK' }),
        deal({ id: 4, code: 'D-PARTIAL', fulfillmentStatus: 'PARTIALLY_DELIVERED' }),
      ],
    });
    renderPage();
    await screen.findByText('D-IN-SCOPE');

    expect(screen.getAllByTestId('fulfilment-row')).toHaveLength(1);
    expect(screen.queryByText('D-GOODS-RECEIVED')).toBeNull();
    expect(screen.queryByText('D-FROM-STOCK')).toBeNull();
    expect(screen.queryByText('D-PARTIAL')).toBeNull();
    expect(screen.queryByText('บันทึกส่งมอบ')).toBeNull();
  });

  it('excludes deals that have not reached the fulfilment chain or are past it', async () => {
    api.tickets.list.mockResolvedValue({
      tickets: [
        deal({ id: 1, code: 'D-DRAFT', status: 'draft', fulfillmentStatus: null }),
        deal({ id: 2, code: 'D-DONE', fulfillmentStatus: 'FULLY_DELIVERED' }),
      ],
    });
    renderPage();

    expect(await screen.findByText('ไม่มีงานนำเข้าในขั้นตอนนี้')).not.toBeNull();
    expect(screen.queryAllByTestId('fulfilment-row')).toHaveLength(0);
  });

  it('offers ทั้งหมด plus exactly the four in-scope stages as chips, with counts', async () => {
    api.tickets.list.mockResolvedValue({
      tickets: [
        deal({ id: 1, code: 'D-NULL', fulfillmentStatus: null }),
        deal({ id: 2, code: 'D-NULL-2', fulfillmentStatus: null }),
        deal({ id: 3, code: 'D-SHIPPING', fulfillmentStatus: 'SHIPPING' }),
      ],
    });
    renderPage();
    await screen.findByText('D-NULL');

    expect(within(chipBar()).getAllByRole('button').map((b) => b.textContent)).toEqual([
      'ทั้งหมด3',
      'ออกคำขอนำเข้า2',
      'ส่งคำขอนำเข้าแล้ว0',
      'บันทึกออกเดินทาง0',
      'ยืนยันรับเข้าคลัง1',
    ]);
    // บันทึกส่งมอบ is the fifth code nextFulfilmentActionCode can return and it is
    // deliberately not a chip here.
    expect(within(chipBar()).queryByRole('button', { name: /บันทึกส่งมอบ/ })).toBeNull();
  });

  it('narrows the rows to one stage when a chip is clicked', async () => {
    api.tickets.list.mockResolvedValue({
      tickets: [
        deal({ id: 1, code: 'D-NULL', fulfillmentStatus: null }),
        deal({ id: 2, code: 'D-SHIPPING', fulfillmentStatus: 'SHIPPING' }),
      ],
    });
    renderPage();
    await screen.findByText('D-NULL');

    fireEvent.click(chip('ยืนยันรับเข้าคลัง'));

    expect(screen.getByText('D-SHIPPING')).not.toBeNull();
    expect(screen.queryByText('D-NULL')).toBeNull();
  });

  it('filters by customer or deal code from the search box', async () => {
    api.tickets.list.mockResolvedValue({
      tickets: [
        deal({ id: 1, code: 'D-ONE', customerName: 'ลูกค้าหนึ่ง' }),
        deal({ id: 2, code: 'D-TWO', customerName: 'ลูกค้าสอง' }),
      ],
    });
    renderPage();
    await screen.findByText('ลูกค้าหนึ่ง');

    fireEvent.change(screen.getByTestId('fulfilment-search'), { target: { value: 'D-TWO' } });

    expect(screen.getByText('ลูกค้าสอง')).not.toBeNull();
    expect(screen.queryByText('ลูกค้าหนึ่ง')).toBeNull();
  });

  describe('the confirmation step', () => {
    // These four transitions are irreversible and monotonic server-side (re-issuing
    // an IR 409s), so a single click must never reach the API.
    it('does NOT call the API on the first click — it only arms the row', async () => {
      renderPage();
      await screen.findByText('PR-2026-0701');

      fireEvent.click(within(rowFor('PR-2026-0701')).getByTestId('fulfilment-action'));

      expect(api.tickets.issueImportRequest).not.toHaveBeenCalled();
      expect(within(rowFor('PR-2026-0701')).getByTestId('fulfilment-confirm')).not.toBeNull();
    });

    it('shows the exact state change and warns that it cannot be undone', async () => {
      renderPage();
      await screen.findByText('PR-2026-0701');

      fireEvent.click(within(rowFor('PR-2026-0701')).getByTestId('fulfilment-action'));

      const strip = within(rowFor('PR-2026-0701')).getByTestId('fulfilment-confirm');
      expect(strip.textContent).toContain('ยังไม่ออกคำขอนำเข้า → ออกคำขอนำเข้าแล้ว');
      expect(strip.textContent).toContain('ย้อนกลับไม่ได้');
      // The deal's payment state, shown because the backend's issueImportRequest
      // also gates on deposit readiness and a list row carries no availableActions.
      expect(strip.textContent).toContain('รับมัดจำแล้ว');
    });

    it('puts focus on ยกเลิก, so the safe option is the keyboard default', async () => {
      renderPage();
      await screen.findByText('PR-2026-0701');

      fireEvent.click(within(rowFor('PR-2026-0701')).getByTestId('fulfilment-action'));

      expect(document.activeElement).toBe(within(rowFor('PR-2026-0701')).getByTestId('fulfilment-cancel'));
    });

    it('sends nothing when the confirmation is cancelled or dismissed with Escape', async () => {
      renderPage();
      await screen.findByText('PR-2026-0701');

      fireEvent.click(within(rowFor('PR-2026-0701')).getByTestId('fulfilment-action'));
      fireEvent.click(within(rowFor('PR-2026-0701')).getByTestId('fulfilment-cancel'));
      expect(screen.queryByTestId('fulfilment-confirm')).toBeNull();

      fireEvent.click(within(rowFor('PR-2026-0701')).getByTestId('fulfilment-action'));
      // Escape is bound to the strip's buttons, not the non-interactive group
      // wrapping them — and the strip opens with focus already on ยกเลิก.
      fireEvent.keyDown(screen.getByTestId('fulfilment-cancel'), { key: 'Escape' });
      expect(screen.queryByTestId('fulfilment-confirm')).toBeNull();

      expect(api.tickets.issueImportRequest).not.toHaveBeenCalled();
    });

    it('arms only one row at a time', async () => {
      api.tickets.list.mockResolvedValue({
        tickets: [
          deal({ id: 1, code: 'D-ONE', fulfillmentStatus: null }),
          deal({ id: 2, code: 'D-TWO', fulfillmentStatus: null }),
        ],
      });
      renderPage();
      await screen.findByText('D-ONE');

      fireEvent.click(within(rowFor('D-ONE')).getByTestId('fulfilment-action'));
      fireEvent.click(within(rowFor('D-TWO')).getByTestId('fulfilment-action'));

      expect(screen.getAllByTestId('fulfilment-confirm')).toHaveLength(1);
      expect(within(rowFor('D-TWO')).getByTestId('fulfilment-confirm')).not.toBeNull();
    });

    it('disarms the row when the stage filter changes', async () => {
      renderPage();
      await screen.findByText('PR-2026-0701');

      fireEvent.click(within(rowFor('PR-2026-0701')).getByTestId('fulfilment-action'));
      fireEvent.click(chip('ทั้งหมด'));

      expect(screen.queryByTestId('fulfilment-confirm')).toBeNull();
    });
  });

  describe('the four in-place mutations', () => {
    it.each([
      ['issueImportRequest', null, 'ออกคำขอนำเข้าแล้ว'],
      ['markIrSent', 'IR_ISSUED', 'ส่งคำขอนำเข้าแล้ว'],
      ['markShipping', 'IR_SENT', 'บันทึกว่าสินค้าออกเดินทางแล้ว'],
      ['markGoodsReceived', 'SHIPPING', 'รับเข้าคลังแล้ว — ดีลนี้ส่งต่อไปยังขั้นตอนส่งมอบ'],
    ])('%s fires from the row and toasts on success', async (method, fulfillmentStatus, toastMessage) => {
      api.tickets.list.mockResolvedValue({ tickets: [deal({ id: 42, code: 'D-ACT', fulfillmentStatus })] });
      api.tickets[method].mockResolvedValue({ ticket: {} });
      const showToast = vi.fn();
      renderPage(showToast);
      await screen.findByText('D-ACT');

      armAndConfirm('D-ACT');

      await waitFor(() => expect(api.tickets[method]).toHaveBeenCalledWith(42));
      await waitFor(() => expect(showToast).toHaveBeenCalledWith('success', toastMessage));
    });

    it('invalidates the same query keys DealFulfilmentPanel does, so the deal page agrees', async () => {
      api.tickets.list.mockResolvedValue({ tickets: [deal({ id: 42, code: 'D-ACT' })] });
      api.tickets.issueImportRequest.mockResolvedValue({ ticket: {} });
      const { queryClient } = renderPage();
      const invalidate = vi.spyOn(queryClient, 'invalidateQueries');
      await screen.findByText('D-ACT');

      armAndConfirm('D-ACT');

      await waitFor(() => expect(api.tickets.issueImportRequest).toHaveBeenCalled());
      const keys = invalidate.mock.calls.map(([arg]) => JSON.stringify(arg.queryKey));
      expect(keys).toContain(JSON.stringify(['tickets', 'detail', 42]));
      expect(keys).toContain(JSON.stringify(['tickets', 'actions', 42]));
      expect(keys).toContain(JSON.stringify(['tickets', 'deliveries', 42]));
      expect(keys).toContain(JSON.stringify(['tickets', 'list']));
      expect(keys).toContain(JSON.stringify(['dashboardSummary']));
      expect(keys).toContain(JSON.stringify(['notifications']));
    });

    // The button is an affordance, not an authorization: the backend gates
    // issueImportRequest on deposit readiness too, which a list row cannot see.
    // The server's own Thai message has to reach the operator verbatim.
    it('surfaces the server\'s refusal message and leaves the row in place', async () => {
      api.tickets.list.mockResolvedValue({ tickets: [deal({ id: 42, code: 'D-ACT' })] });
      api.tickets.issueImportRequest.mockRejectedValue(new Error('คำขอนำเข้านี้ถูกออกไปแล้ว'));
      const showToast = vi.fn();
      renderPage(showToast);
      await screen.findByText('D-ACT');

      armAndConfirm('D-ACT');

      await waitFor(() => expect(showToast).toHaveBeenCalledWith('error', 'คำขอนำเข้านี้ถูกออกไปแล้ว'));
      expect(screen.queryByTestId('fulfilment-confirm')).toBeNull();
      expect(rowFor('D-ACT')).not.toBeNull();
    });

    it('calls only the endpoint for the row\'s own stage', async () => {
      api.tickets.list.mockResolvedValue({ tickets: [deal({ id: 42, code: 'D-ACT', fulfillmentStatus: 'IR_SENT' })] });
      api.tickets.markShipping.mockResolvedValue({ ticket: {} });
      renderPage();
      await screen.findByText('D-ACT');

      armAndConfirm('D-ACT');

      await waitFor(() => expect(api.tickets.markShipping).toHaveBeenCalledWith(42));
      expect(api.tickets.issueImportRequest).not.toHaveBeenCalled();
      expect(api.tickets.markIrSent).not.toHaveBeenCalled();
      expect(api.tickets.markGoodsReceived).not.toHaveBeenCalled();
    });
  });
});
