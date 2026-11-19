import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ImportFulfilmentPage } from './ImportFulfilmentPage.jsx';
import { api } from '../../api/index.js';

globalThis.React = React;

// PR-B port of Yang's per-factory redesign (origin/feat/per-factory-import-tracking, 65dfe171) onto the STORED
// aggregate PR-A shipped (api.storedImportRequests, our own six step codes — see importSteps.js's header).
vi.mock('../../api/index.js', async (importOriginal) => {
  const actual = await importOriginal();
  return {
    ...actual,
    api: {
      tickets: { list: vi.fn(), get: vi.fn() },
      importDeals: { get: vi.fn() },
      storedImportRequests: {
        listForTicket: vi.fn(),
        advanceStep: vi.fn(),
        setLeadTime: vi.fn(),
        updateEmailDraft: vi.fn(),
        markEmailSent: vi.fn(),
        download: vi.fn(),
        undoStep: vi.fn(),
      },
    },
  };
});

function row(over = {}) {
  return {
    id: 1,
    ticketId: 1,
    ticketCode: 'PR-2026-0701',
    factoryId: 1,
    factoryName: 'Cotto Industry',
    status: 'ISSUED',
    docNumber: 'IR26001',
    importStep: 'ORDERED',
    importStepAt: '2026-09-10',
    leadTimeMinDays: 30,
    leadTimeMaxDays: 45,
    expectedArrivalFrom: null,
    expectedArrivalTo: null,
    emailTo: null,
    emailSubject: 'Purchase order IR26001 - GL&R',
    emailBody: 'Dear Cotto Industry team,...',
    emailSentAt: null,
    emailSentByName: null,
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
    fulfillmentStatus: 'IR_ISSUED',
    ...over,
  };
}

// api.storedImportRequests.listForTicket(ticketId) is called once per candidate ticket; this resolver keys a
// per-ticket row map so a test can supply different rows per deal.
function mockRowsByTicket(map) {
  api.storedImportRequests.listForTicket.mockImplementation((ticketId) =>
    Promise.resolve({ importRequests: map[Number(ticketId)] ?? [] }));
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

describe('ImportFulfilmentPage (per-factory, stored aggregate)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.tickets.list.mockResolvedValue({ tickets: [ticket()] });
    // RC-02 (role check 2026-10-29): TicketService#get refuses import outright, so the deal read
    // is mocked the way Java answers it; the page reads import's own projection instead.
    api.tickets.get.mockRejectedValue(Object.assign(new Error('ไม่มีสิทธิ์เข้าถึงรายการนี้'), { status: 403 }));
    api.importDeals.get.mockResolvedValue({ deal: { fulfillmentStatus: 'IR_ISSUED' } });
    mockRowsByTicket({ 1: [row()] });
    api.storedImportRequests.advanceStep.mockResolvedValue({ importRequest: row({ importStep: 'PICKED_UP' }) });
  });

  it('groups per-factory rows into one card per deal, each factory as its own bar', async () => {
    mockRowsByTicket({
      1: [
        row({ id: 1, factoryName: 'Cotto Industry', importStep: 'ORDERED' }),
        row({ id: 2, factoryName: 'Duragres Thailand', importStep: 'IN_TRANSIT' }),
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

  it('advances a factory to the chosen step in place (never a deal-level transition)', async () => {
    renderPage();
    const card = await screen.findByTestId('fulfilment-deal');
    // ORDERED's only forward step in the bar's default picker is PICKED_UP.
    fireEvent.click(within(card).getByTestId('advance-1'));

    await waitFor(() => expect(api.storedImportRequests.advanceStep)
      .toHaveBeenCalledWith(1, { targetStep: 'PICKED_UP' }));
  });

  // Spec 2026-11-15 §7.11 item 3 / plan task 5.3 (design a-fulfil): this page is the ONE place a step is advanced,
  // and an advance offers a 30-second undo instead of being final at once.
  describe('the undo snackbar', () => {
    afterEach(() => vi.useRealTimers());

    it('an advance shows "เลื่อนเป็น … · IR · เลิกทำ"; เลิกทำ undoes THAT advance (expectedStep = its target)', async () => {
      api.storedImportRequests.undoStep.mockResolvedValue({ importRequest: row() });
      const { showToast } = renderPage();
      const card = await screen.findByTestId('fulfilment-deal');
      fireEvent.click(within(card).getByTestId('advance-1'));
      const snack = await screen.findByTestId('step-undo-snackbar');
      expect(snack.textContent).toContain('เลื่อนเป็น “ขนส่งรับของ”');
      expect(snack.textContent).toContain('IR26001');
      fireEvent.click(within(snack).getByRole('button', { name: 'เลิกทำ' }));
      await waitFor(() => expect(api.storedImportRequests.undoStep).toHaveBeenCalledWith(1, { expectedStep: 'PICKED_UP' }));
      await waitFor(() => expect(screen.queryByTestId('step-undo-snackbar')).toBeNull());
      expect(showToast).toHaveBeenCalledWith('success', expect.stringContaining('เลิกทำ'));
    });

    it('an advance to ถึงโกดัง offers no undo (it completes the shipment; the server refuses it too)', async () => {
      mockRowsByTicket({ 1: [row({ importStep: 'AWAITING_CUSTOMS' })] });
      api.storedImportRequests.advanceStep.mockResolvedValue({ importRequest: row({ importStep: 'RECEIVED' }) });
      renderPage();
      const card = await screen.findByTestId('fulfilment-deal');
      fireEvent.click(within(card).getByTestId('advance-1'));
      await waitFor(() => expect(api.storedImportRequests.advanceStep).toHaveBeenCalledWith(1, { targetStep: 'RECEIVED' }));
      await waitFor(() => expect(api.importDeals.get).toHaveBeenCalled());
      expect(screen.queryByTestId('step-undo-snackbar')).toBeNull();
    });
  });

  // Import has its own per-deal page (GET /api/import/deals/{id}); the whole-deal GET /api/tickets/{id} 403s
  // import, so a link into /tickets/:id from this worklist is a dead end.
  it('links every deal to import\'s own page (/import/deals/:id), never the whole-deal page', async () => {
    renderPage();
    await screen.findByTestId('fulfilment-deal');
    const hrefs = screen.getAllByRole('link').map((a) => a.getAttribute('href'));
    expect(hrefs).toContain('/import/deals/1');
    expect(hrefs.filter((h) => h.startsWith('/tickets'))).toEqual([]);
  });

  it('opens the order-email modal and marks it sent — import/CEO can send it', async () => {
    renderPage();
    const card = await screen.findByTestId('fulfilment-deal');
    fireEvent.click(within(card).getByTestId('order-email-1'));

    const sendButton = await screen.findByTestId('ir-email-mark-sent');
    api.storedImportRequests.markEmailSent.mockResolvedValue({ importRequest: row({ emailSentAt: '2026-09-19T00:00:00Z' }) });
    fireEvent.click(sendButton);

    await waitFor(() => expect(api.storedImportRequests.markEmailSent).toHaveBeenCalledWith(1));
  });

  // PR-B REVIEW ROUND 2, X2: TicketService#applyImportRequestRollup writes ONLY the GOODS_RECEIVED event for an
  // already PARTIALLY_DELIVERED deal — it never touches fulfillment_status, since a delivery already under way
  // must not be disturbed.
  describe('X2 — PARTIALLY_DELIVERED done-group rollup', () => {
    it('moves a PARTIALLY_DELIVERED deal into the done group once every issued row is RECEIVED', async () => {
      api.tickets.list.mockResolvedValue({
        tickets: [ticket({ fulfillmentStatus: 'PARTIALLY_DELIVERED' })],
      });
      mockRowsByTicket({
        1: [row({ id: 1, importStep: 'RECEIVED' }), row({ id: 2, factoryName: 'B', importStep: 'RECEIVED' })],
      });
      renderPage();

      await screen.findByTestId('fulfilment-done');
      expect(screen.queryByTestId('fulfilment-deal')).toBeNull();
      expect(within(screen.getByTestId('fulfilment-done')).getByText(/PR-2026-0701/)).not.toBeNull();
      // Done-group rows must not inflate the active step chips.
      expect(screen.getByTestId('step-chip-ALL').textContent).toMatch(/0/);
    });

    it('keeps a PARTIALLY_DELIVERED deal active while any issued row is still short of RECEIVED', async () => {
      api.tickets.list.mockResolvedValue({
        tickets: [ticket({ fulfillmentStatus: 'PARTIALLY_DELIVERED' })],
      });
      mockRowsByTicket({
        1: [row({ id: 1, importStep: 'RECEIVED' }), row({ id: 2, factoryName: 'B', importStep: 'IN_TRANSIT' })],
      });
      renderPage();

      const card = await screen.findByTestId('fulfilment-deal');
      expect(within(card).getByText('Cotto Industry')).not.toBeNull();
      expect(screen.queryByTestId('fulfilment-done')).toBeNull();
    });
  });

  // PR-B REVIEW ROUND 2, X4: the completion toast used to claim "รับครบทุกโรงงาน" off a CLIENT-side guess —
  // every row THIS PAGE already had loaded was RECEIVED — computed BEFORE the advance's own server-side rollup
  // runs.
  describe('X4 — completion toast reflects the refetched ticket, not a client-side guess', () => {
    it('shows the ordinary step-advanced toast, never the "รับครบทุกโรงงาน" claim, when the refetched ticket is NOT GOODS_RECEIVED', async () => {
      api.importDeals.get.mockResolvedValue({ deal: { fulfillmentStatus: 'IR_ISSUED' } });
      mockRowsByTicket({ 1: [row({ id: 1, importStep: 'AWAITING_CUSTOMS' })] });
      api.storedImportRequests.advanceStep.mockResolvedValue({ importRequest: row({ id: 1, importStep: 'RECEIVED' }) });
      const showToast = vi.fn();
      renderPage(showToast);
      const card = await screen.findByTestId('fulfilment-deal');
      fireEvent.click(within(card).getByTestId('advance-1'));

      await waitFor(() => expect(showToast).toHaveBeenCalledWith('success', 'อัปเดตสถานะรายโรงงานแล้ว'));
      expect(showToast).not.toHaveBeenCalledWith('success', expect.stringContaining('รับครบทุกโรงงาน'));
    });

    it('shows the completion toast when the refetched ticket is genuinely GOODS_RECEIVED', async () => {
      api.importDeals.get.mockResolvedValue({ deal: { fulfillmentStatus: 'GOODS_RECEIVED' } });
      mockRowsByTicket({ 1: [row({ id: 1, importStep: 'AWAITING_CUSTOMS' })] });
      api.storedImportRequests.advanceStep.mockResolvedValue({ importRequest: row({ id: 1, importStep: 'RECEIVED' }) });
      const showToast = vi.fn();
      renderPage(showToast);
      const card = await screen.findByTestId('fulfilment-deal');
      fireEvent.click(within(card).getByTestId('advance-1'));

      await waitFor(() => expect(showToast).toHaveBeenCalledWith('success', 'รับครบทุกโรงงาน — ส่งต่อฝ่ายขายเพื่อส่งมอบ'));
    });
  });
});

