import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ImportFulfilmentPage } from './ImportFulfilmentPage.jsx';
import { api } from '../../api/index.js';

globalThis.React = React;

// The page is now a per-FACTORY worklist (revision 2026-09-19): it reads
// api.importProgress.listAll and advances a factory's step in place, rather than the
// old four deal-level api.tickets.* transitions. api.tickets.list is kept only for the
// deal facts (customer/due date) a progress row does not carry.
vi.mock('../../api/index.js', async (importOriginal) => {
  const actual = await importOriginal();
  return {
    ...actual,
    api: {
      tickets: { list: vi.fn() },
      importProgress: {
        listAll: vi.fn(),
        advanceStep: vi.fn(),
        generateOrderEmail: vi.fn(),
      },
    },
  };
});

function progRow(over = {}) {
  return {
    id: 1,
    pricingRequestId: null,
    pricingRequestCode: null,
    ticketId: 1,
    ticketCode: 'PR-2026-0701',
    factoryName: 'Cotto Industry',
    importStep: 'ORDERED',
    importStepAt: '2026-09-10',
    eta: null,
    note: null,
    updatedBy: null,
    updatedByName: 'ฝ่ายนำเข้า',
    updatedAt: '2026-09-10',
    ...over,
  };
}

function ticket(over = {}) {
  return {
    id: 1,
    code: 'PR-2026-0701',
    title: 'ดีลทดสอบ',
    customerName: 'บริษัท ทดสอบ จำกัด',
    projectName: null,
    dueDate: null,
    overdue: false,
    ...over,
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

function dealCardFor(code) {
  const found = screen.getAllByTestId('fulfilment-deal').find((c) => c.textContent.includes(code));
  if (!found) throw new Error(`no rendered deal card for ${code}`);
  return found;
}

describe('ImportFulfilmentPage (per-factory)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.tickets.list.mockResolvedValue({ tickets: [ticket()] });
    api.importProgress.listAll.mockResolvedValue({ items: [progRow()] });
    api.importProgress.advanceStep.mockResolvedValue({ row: progRow({ importStep: 'PICKED_UP' }) });
  });

  it('groups per-factory rows into one card per deal, each factory as its own bar', async () => {
    api.importProgress.listAll.mockResolvedValue({
      items: [
        progRow({ id: 1, factoryName: 'Cotto Industry', importStep: 'ORDERED' }),
        progRow({ id: 2, factoryName: 'Duragres Thailand', importStep: 'IN_TRANSIT' }),
      ],
    });
    renderPage();

    await screen.findByTestId('fulfilment-deal');
    const card = dealCardFor('PR-2026-0701');
    expect(within(card).getByText('บริษัท ทดสอบ จำกัด')).not.toBeNull();
    expect(within(card).getByText('Cotto Industry')).not.toBeNull();
    expect(within(card).getByText('Duragres Thailand')).not.toBeNull();
    // Rollup chip counts warehouse-received factories (none yet, of 2).
    expect(within(card).getByText(/ถึงโกดัง 0\/2 โรงงาน/)).not.toBeNull();
  });

  it('chip counts read the number of SHIPMENTS at each step', async () => {
    api.importProgress.listAll.mockResolvedValue({
      items: [
        progRow({ id: 1, importStep: 'IN_TRANSIT' }),
        progRow({ id: 2, factoryName: 'B', importStep: 'IN_TRANSIT' }),
        progRow({ id: 3, factoryName: 'C', importStep: 'ORDERED' }),
      ],
    });
    renderPage();

    await screen.findByTestId('fulfilment-deal');
    expect(screen.getByTestId('step-chip-ALL').textContent).toMatch(/3/);
    expect(screen.getByTestId('step-chip-IN_TRANSIT').textContent).toMatch(/2/);
    expect(screen.getByTestId('step-chip-ORDERED').textContent).toMatch(/1/);
  });

  it('advances a factory to the NEXT step in place (never a deal-level transition)', async () => {
    renderPage();
    const card = await screen.findByTestId('fulfilment-deal');
    // ORDERED → PICKED_UP is the next step; the bar renders its advance button.
    fireEvent.click(within(card).getByTestId('advance-1'));

    await waitFor(() => expect(api.importProgress.advanceStep).toHaveBeenCalledWith(1, { targetStep: 'PICKED_UP' }));
  });

  it('keeps an all-received deal in the done group, out of the active list', async () => {
    api.importProgress.listAll.mockResolvedValue({
      items: [
        progRow({ id: 1, ticketId: 1, ticketCode: 'PR-2026-0701', importStep: 'RECEIVED' }),
        progRow({ id: 2, ticketId: 2, ticketCode: 'D-ACTIVE', factoryName: 'F2', importStep: 'ORDERED' }),
      ],
    });
    api.tickets.list.mockResolvedValue({
      tickets: [ticket(), ticket({ id: 2, code: 'D-ACTIVE', customerName: 'ลูกค้าสอง' })],
    });
    renderPage();

    // The received deal is not an active card…
    await screen.findByTestId('fulfilment-deal');
    expect(screen.getAllByTestId('fulfilment-deal')).toHaveLength(1);
    expect(dealCardFor('D-ACTIVE')).not.toBeNull();
    // …it sits in the collapsed done group instead.
    expect(within(screen.getByTestId('fulfilment-done')).getByText(/PR-2026-0701/)).not.toBeNull();
  });

  it('filtering to a step hides deals that have no factory at that step', async () => {
    api.importProgress.listAll.mockResolvedValue({
      items: [progRow({ id: 1, importStep: 'ORDERED' })],
    });
    renderPage();
    await screen.findByTestId('fulfilment-deal');

    fireEvent.click(screen.getByTestId('step-chip-RECEIVED'));
    expect(screen.queryByTestId('fulfilment-deal')).toBeNull();

    fireEvent.click(screen.getByTestId('step-chip-ORDERED'));
    expect(screen.getByTestId('fulfilment-deal')).not.toBeNull();
  });

  it('filters by customer or deal code from the search box', async () => {
    api.importProgress.listAll.mockResolvedValue({
      items: [
        progRow({ id: 1, ticketId: 1, ticketCode: 'D-ONE' }),
        progRow({ id: 2, ticketId: 2, ticketCode: 'D-TWO', factoryName: 'F2' }),
      ],
    });
    api.tickets.list.mockResolvedValue({
      tickets: [ticket({ id: 1, code: 'D-ONE', customerName: 'ลูกค้าหนึ่ง' }),
        ticket({ id: 2, code: 'D-TWO', customerName: 'ลูกค้าสอง' })],
    });
    renderPage();
    await screen.findAllByTestId('fulfilment-deal');

    fireEvent.change(screen.getByTestId('fulfilment-search'), { target: { value: 'D-TWO' } });
    expect(screen.getByText('ลูกค้าสอง')).not.toBeNull();
    expect(screen.queryByText('ลูกค้าหนึ่ง')).toBeNull();
  });

  it('shows a clear empty state when no deal has import progress yet', async () => {
    api.importProgress.listAll.mockResolvedValue({ items: [] });
    renderPage();
    expect(await screen.findByText('ยังไม่มีงานนำเข้ารายโรงงาน')).not.toBeNull();
  });
});
