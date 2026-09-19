import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ImportFulfilmentPage } from './ImportFulfilmentPage.jsx';
import { api } from '../../api/index.js';

globalThis.React = React;

// PR-B port of Yang's per-factory redesign (origin/feat/per-factory-import-tracking, 65dfe171)
// onto the STORED aggregate PR-A shipped (api.storedImportRequests, our own six step codes —
// see importSteps.js's header). No cross-deal list endpoint exists for the stored aggregate, so
// this page reads api.tickets.list for candidates (fulfillmentStatus IR_ISSUED/GOODS_RECEIVED)
// and fetches each candidate's rows via api.storedImportRequests.listForTicket — see the page's
// own header comment for why this bounded N+1 is not a backend change.
vi.mock('../../api/index.js', async (importOriginal) => {
  const actual = await importOriginal();
  return {
    ...actual,
    api: {
      tickets: { list: vi.fn(), get: vi.fn() },
      storedImportRequests: {
        listForTicket: vi.fn(),
        advanceStep: vi.fn(),
        setLeadTime: vi.fn(),
        updateEmailDraft: vi.fn(),
        markEmailSent: vi.fn(),
        download: vi.fn(),
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

// api.storedImportRequests.listForTicket(ticketId) is called once per candidate ticket; this
// resolver keys a per-ticket row map so a test can supply different rows per deal.
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
    api.tickets.get.mockResolvedValue({ ticket: { summary: { fulfillmentStatus: 'IR_ISSUED' } } });
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

  it('chip counts read the number of SHIPMENTS at each step', async () => {
    mockRowsByTicket({
      1: [
        row({ id: 1, importStep: 'IN_TRANSIT' }),
        row({ id: 2, factoryName: 'B', importStep: 'IN_TRANSIT' }),
        row({ id: 3, factoryName: 'C', importStep: 'ORDERED' }),
      ],
    });
    renderPage();

    await screen.findByTestId('fulfilment-deal');
    expect(screen.getByTestId('step-chip-ALL').textContent).toMatch(/3/);
    expect(screen.getByTestId('step-chip-IN_TRANSIT').textContent).toMatch(/2/);
    expect(screen.getByTestId('step-chip-ORDERED').textContent).toMatch(/1/);
  });

  it('advances a factory to the chosen step in place (never a deal-level transition)', async () => {
    renderPage();
    const card = await screen.findByTestId('fulfilment-deal');
    // ORDERED's only forward step in the bar's default picker is PICKED_UP.
    fireEvent.click(within(card).getByTestId('advance-1'));

    await waitFor(() => expect(api.storedImportRequests.advanceStep)
      .toHaveBeenCalledWith(1, { targetStep: 'PICKED_UP' }));
  });

  it('keeps an all-received deal in the done group, out of the active list', async () => {
    // PR-B REVIEW ROUND 1, S2: "done" is keyed on the TICKET's own fulfillmentStatus
    // (GOODS_RECEIVED — the server-computed rollup), not merely "every ISSUED row I can see is
    // RECEIVED" — so this fixture's ticket must actually carry that status, matching what
    // ImportRequestService#applyImportRequestRollup would have written server-side.
    api.tickets.list.mockResolvedValue({
      tickets: [ticket({ fulfillmentStatus: 'GOODS_RECEIVED' }), ticket({ id: 2, code: 'D-ACTIVE', customerName: 'ลูกค้าสอง', fulfillmentStatus: 'IR_ISSUED' })],
    });
    mockRowsByTicket({
      1: [row({ id: 1, ticketId: 1, ticketCode: 'PR-2026-0701', importStep: 'RECEIVED' })],
      2: [row({ id: 2, ticketId: 2, ticketCode: 'D-ACTIVE', factoryName: 'F2', importStep: 'ORDERED' })],
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
    mockRowsByTicket({ 1: [row({ id: 1, importStep: 'ORDERED' })] });
    renderPage();
    await screen.findByTestId('fulfilment-deal');

    fireEvent.click(screen.getByTestId('step-chip-RECEIVED'));
    expect(screen.queryByTestId('fulfilment-deal')).toBeNull();

    fireEvent.click(screen.getByTestId('step-chip-ORDERED'));
    expect(screen.getByTestId('fulfilment-deal')).not.toBeNull();
  });

  it('filters by customer or deal code from the search box', async () => {
    api.tickets.list.mockResolvedValue({
      tickets: [ticket({ id: 1, code: 'D-ONE', customerName: 'ลูกค้าหนึ่ง' }),
        ticket({ id: 2, code: 'D-TWO', customerName: 'ลูกค้าสอง' })],
    });
    mockRowsByTicket({
      1: [row({ id: 1, ticketId: 1, ticketCode: 'D-ONE' })],
      2: [row({ id: 2, ticketId: 2, ticketCode: 'D-TWO', factoryName: 'F2' })],
    });
    renderPage();
    await screen.findAllByTestId('fulfilment-deal');

    fireEvent.change(screen.getByTestId('fulfilment-search'), { target: { value: 'D-TWO' } });
    expect(screen.getByText('ลูกค้าสอง')).not.toBeNull();
    expect(screen.queryByText('ลูกค้าหนึ่ง')).toBeNull();
  });

  it('shows a clear empty state when no deal has import progress yet', async () => {
    api.tickets.list.mockResolvedValue({ tickets: [] });
    mockRowsByTicket({});
    renderPage();
    expect(await screen.findByText('ยังไม่มีงานนำเข้ารายโรงงาน')).not.toBeNull();
  });

  it('shows a "ส่งอีเมลแล้ว" badge once markEmailSent has been recorded, "ยังไม่ส่งอีเมล" otherwise', async () => {
    mockRowsByTicket({
      1: [row({ id: 1, emailSentAt: null }), row({ id: 2, factoryName: 'B', emailSentAt: '2026-09-15T00:00:00Z', emailSentByName: 'สมชาย' })],
    });
    renderPage();
    await screen.findByTestId('fulfilment-deal');
    expect(screen.getByText('ยังไม่ส่งอีเมล')).not.toBeNull();
    expect(screen.getByText('ส่งอีเมลแล้ว')).not.toBeNull();
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

  it('lets import/CEO edit lead time on an issued row', async () => {
    api.storedImportRequests.setLeadTime.mockResolvedValue({ importRequest: row({ leadTimeMinDays: 20, leadTimeMaxDays: 30 }) });
    renderPage();
    const card = await screen.findByTestId('fulfilment-deal');
    fireEvent.click(within(card).getByTestId('lead-time-edit-1'));

    fireEvent.click(within(card).getByTestId('lead-time-save-1'));
    await waitFor(() => expect(api.storedImportRequests.setLeadTime)
      .toHaveBeenCalledWith(1, { leadTimeMinDays: 30, leadTimeMaxDays: 45 }));
  });

  // PR-B REVIEW ROUND 1, nit: a rejected lead-time save must leave the editor OPEN, not vanish
  // behind a save that never happened.
  it('LeadTimeInline stays open when the save is rejected (400), instead of closing on a failed save', async () => {
    api.storedImportRequests.setLeadTime.mockRejectedValue(Object.assign(new Error('ระยะเวลานำเข้าไม่ถูกต้อง'), { status: 400 }));
    renderPage();
    const card = await screen.findByTestId('fulfilment-deal');
    fireEvent.click(within(card).getByTestId('lead-time-edit-1'));
    fireEvent.click(within(card).getByTestId('lead-time-save-1'));

    await waitFor(() => expect(api.storedImportRequests.setLeadTime).toHaveBeenCalled());
    // The inputs (only rendered while the editor is open) must still be there.
    expect(within(card).getByTestId('lead-time-save-1')).not.toBeNull();
  });

  it('a blank lead-time save shows a validation message and does not call the API at all', async () => {
    renderPage();
    const card = await screen.findByTestId('fulfilment-deal');
    fireEvent.click(within(card).getByTestId('lead-time-edit-1'));
    const inputs = within(card).getAllByRole('spinbutton');
    fireEvent.change(inputs[0], { target: { value: '' } });
    fireEvent.click(within(card).getByTestId('lead-time-save-1'));

    expect(api.storedImportRequests.setLeadTime).not.toHaveBeenCalled();
    // Editor stays open — the same "stays open" contract as the rejected-save case above.
    expect(within(card).getByTestId('lead-time-save-1')).not.toBeNull();
  });

  // PR-B REVIEW ROUND 1, B1: the order-email save must send Java's own field names.
  it('order-email save sends emailTo/emailSubject/emailBody, not to/subject/body', async () => {
    api.storedImportRequests.updateEmailDraft.mockResolvedValue({ importRequest: row() });
    renderPage();
    const card = await screen.findByTestId('fulfilment-deal');
    fireEvent.click(within(card).getByTestId('order-email-1'));
    const modal = await screen.findByTestId('order-email-modal');
    const saveButton = within(modal).getByRole('button', { name: 'บันทึก' });
    fireEvent.click(saveButton);

    await waitFor(() => expect(api.storedImportRequests.updateEmailDraft).toHaveBeenCalledWith(1, {
      emailTo: '', emailSubject: 'Purchase order IR26001 - GL&R', emailBody: 'Dear Cotto Industry team,...',
    }));
  });

  it('hides "บันทึก" in the order-email modal once the email has already been sent', async () => {
    mockRowsByTicket({ 1: [row({ emailSentAt: '2026-09-18T00:00:00Z' })] });
    renderPage();
    const card = await screen.findByTestId('fulfilment-deal');
    fireEvent.click(within(card).getByTestId('order-email-1'));
    const modal = await screen.findByTestId('order-email-modal');
    expect(within(modal).queryByRole('button', { name: 'บันทึก' })).toBeNull();
  });

  // PR-B REVIEW ROUND 1, B3: advancing a step must reset the picker's default target, or a
  // second advance in a row submits a STALE target (behind the row's real current step) and 409s.
  describe('B3 — advance twice in a row', () => {
    it('resets the picker default after the first advance so a second advance targets the NEW next step', async () => {
      mockRowsByTicket({ 1: [row({ id: 1, importStep: 'CONTACTED' })] });
      // The mutation's own onSuccess invalidates storedImportRequests(1), which react-query
      // refetches automatically — so the mock's OWN resolution is where "the server's state
      // actually changed" belongs: by the time that refetch runs, listForTicket must already
      // return the NEW step, exactly like a real advanceStep call landing before the GET does.
      api.storedImportRequests.advanceStep.mockImplementationOnce(async () => {
        mockRowsByTicket({ 1: [row({ id: 1, importStep: 'ORDERED' })] });
        return { importRequest: row({ importStep: 'ORDERED' }) };
      });
      renderPage();
      const card = await screen.findByTestId('fulfilment-deal');
      // CONTACTED's only forward-adjacent default is ORDERED.
      fireEvent.click(within(card).getByTestId('advance-1'));
      await waitFor(() => expect(api.storedImportRequests.advanceStep)
        .toHaveBeenCalledWith(1, { targetStep: 'ORDERED' }));

      // Wait for the bar to actually re-render at the NEW current step before the second click —
      // this is the render FactoryProgressBar's own useEffect resets `target` on.
      await screen.findByText(/S13/);
      api.storedImportRequests.advanceStep.mockClear();
      fireEvent.click(within(screen.getByTestId('fulfilment-deal')).getByTestId('advance-1'));
      // Must target PICKED_UP (the step after the NEW current step ORDERED) — never ORDERED again,
      // which the row's own forward-only guard would 409 on. This is exactly what used to fail
      // before B3's fix: `target` state stayed at the FIRST render's default (ORDERED) forever.
      await waitFor(() => expect(api.storedImportRequests.advanceStep)
        .toHaveBeenCalledWith(1, { targetStep: 'PICKED_UP' }));
    });
  });

  // PR-B REVIEW ROUND 1, B4: widened candidate filter — PARTIALLY_DELIVERED (mixed stock+import,
  // factories still in transit) must stay visible, and a legacy/untracked deal must show with a
  // link rather than vanish.
  describe('B4 — widened candidate filter', () => {
    it('keeps a PARTIALLY_DELIVERED deal (mixed stock+import) visible with its factories', async () => {
      api.tickets.list.mockResolvedValue({
        tickets: [ticket({ fulfillmentStatus: 'PARTIALLY_DELIVERED' })],
      });
      mockRowsByTicket({ 1: [row({ id: 1, importStep: 'IN_TRANSIT' })] });
      renderPage();
      const card = await screen.findByTestId('fulfilment-deal');
      expect(within(card).getByText('Cotto Industry')).not.toBeNull();
    });

    it('excludes FROM_STOCK and FULLY_DELIVERED deals entirely', async () => {
      api.tickets.list.mockResolvedValue({
        tickets: [
          ticket({ id: 1, code: 'D-STOCK', fulfillmentStatus: 'FROM_STOCK' }),
          ticket({ id: 2, code: 'D-DONE', fulfillmentStatus: 'FULLY_DELIVERED' }),
        ],
      });
      mockRowsByTicket({});
      renderPage();
      expect(await screen.findByText('ยังไม่มีงานนำเข้ารายโรงงาน')).not.toBeNull();
    });

    it('shows a legacy/untracked deal (no stored ISSUED row) with a link to its own deal page instead of dropping it', async () => {
      api.tickets.list.mockResolvedValue({
        tickets: [ticket({ id: 9, code: 'D-LEGACY', customerName: 'ลูกค้าเก่า', fulfillmentStatus: 'IR_SENT' })],
      });
      mockRowsByTicket({ 9: [] });
      renderPage();
      const legacySection = await screen.findByTestId('fulfilment-legacy');
      expect(within(legacySection).getByText('ลูกค้าเก่า')).not.toBeNull();
      expect(within(legacySection).getByRole('link', { name: /D-LEGACY/ })).not.toBeNull();
    });
  });

  // PR-B REVIEW ROUND 2, X2: TicketService#applyImportRequestRollup (backend/.../TicketService.java
  // ~942-948, Owner decision 3) writes ONLY the GOODS_RECEIVED event for an already
  // PARTIALLY_DELIVERED deal (mixed stock+import, Case 8) — it never touches fulfillment_status,
  // since a delivery already under way must not be disturbed. So unlike the GOODS_RECEIVED case,
  // this page has no server-computed rollup to key "done" on for that status; it must derive it
  // from its OWN rows (every ISSUED row RECEIVED).
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

    it('keeps a PARTIALLY_DELIVERED deal active while another factory is still a DRAFT', async () => {
      api.tickets.list.mockResolvedValue({
        tickets: [ticket({ fulfillmentStatus: 'PARTIALLY_DELIVERED' })],
      });
      mockRowsByTicket({
        1: [row({ id: 1, importStep: 'RECEIVED' }), row({ id: 2, factoryName: 'B', status: 'DRAFT', importStep: null })],
      });
      renderPage();

      await screen.findByTestId('fulfilment-deal');
      expect(screen.queryByTestId('fulfilment-done')).toBeNull();
    });
  });

  // PR-B REVIEW ROUND 2, X3: the legacy section is for the pre-V184 chain only
  // (IR_ISSUED/IR_SENT/SHIPPING with no stored rows) — a deal that reached GOODS_RECEIVED or
  // PARTIALLY_DELIVERED with zero stored rows got there via pure stock (FROM_STOCK / a
  // stock-only mixed deal), which this page has nothing to show or link to. It must be dropped
  // entirely, not shown as if it were still on the legacy import chain.
  describe('X3 — legacy section scope', () => {
    it('does not list a pure-stock PARTIALLY_DELIVERED deal with no stored rows anywhere', async () => {
      api.tickets.list.mockResolvedValue({
        tickets: [ticket({ fulfillmentStatus: 'PARTIALLY_DELIVERED' })],
      });
      mockRowsByTicket({ 1: [] });
      renderPage();

      expect(await screen.findByText('ยังไม่มีงานนำเข้ารายโรงงาน')).not.toBeNull();
      expect(screen.queryByTestId('fulfilment-legacy')).toBeNull();
      expect(screen.queryByTestId('fulfilment-deal')).toBeNull();
      expect(screen.queryByTestId('fulfilment-done')).toBeNull();
    });

    it('does not list a GOODS_RECEIVED deal with no stored rows (e.g. reached via FROM_STOCK) in the legacy section', async () => {
      api.tickets.list.mockResolvedValue({
        tickets: [ticket({ fulfillmentStatus: 'GOODS_RECEIVED' })],
      });
      mockRowsByTicket({ 1: [] });
      renderPage();

      expect(await screen.findByText('ยังไม่มีงานนำเข้ารายโรงงาน')).not.toBeNull();
      expect(screen.queryByTestId('fulfilment-legacy')).toBeNull();
    });

    it('still lists an IR_SENT/SHIPPING deal with no stored rows in the legacy section', async () => {
      api.tickets.list.mockResolvedValue({
        tickets: [
          ticket({ id: 1, code: 'D-IR-SENT', customerName: 'ลูกค้าหนึ่ง', fulfillmentStatus: 'IR_SENT' }),
          ticket({ id: 2, code: 'D-SHIPPING', customerName: 'ลูกค้าสอง', fulfillmentStatus: 'SHIPPING' }),
        ],
      });
      mockRowsByTicket({ 1: [], 2: [] });
      renderPage();

      const legacySection = await screen.findByTestId('fulfilment-legacy');
      expect(within(legacySection).getByText('ลูกค้าหนึ่ง')).not.toBeNull();
      expect(within(legacySection).getByText('ลูกค้าสอง')).not.toBeNull();
    });
  });

  // PR-B REVIEW ROUND 2, X4: the completion toast used to claim "รับครบทุกโรงงาน" off a CLIENT-side
  // guess — every row THIS PAGE already had loaded was RECEIVED — computed BEFORE the advance's
  // own server-side rollup runs. That is wrong whenever the rollup declines to complete (a
  // required factory never issued at all, so not even in the loaded row set — mirrors mockApi's
  // own S3 fix), and it can never be right for PARTIALLY_DELIVERED at all, since
  // TicketService#applyImportRequestRollup writes only an EVENT for that status, never
  // fulfillment_status (Owner decision 3). The toast must instead be keyed on a FRESH read taken
  // after the advance actually lands.
  describe('X4 — completion toast reflects the refetched ticket, not a client-side guess', () => {
    it('shows the ordinary step-advanced toast, never the "รับครบทุกโรงงาน" claim, when the refetched ticket is NOT GOODS_RECEIVED', async () => {
      api.tickets.get.mockResolvedValue({ ticket: { summary: { fulfillmentStatus: 'IR_ISSUED' } } });
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
      api.tickets.get.mockResolvedValue({ ticket: { summary: { fulfillmentStatus: 'GOODS_RECEIVED' } } });
      mockRowsByTicket({ 1: [row({ id: 1, importStep: 'AWAITING_CUSTOMS' })] });
      api.storedImportRequests.advanceStep.mockResolvedValue({ importRequest: row({ id: 1, importStep: 'RECEIVED' }) });
      const showToast = vi.fn();
      renderPage(showToast);
      const card = await screen.findByTestId('fulfilment-deal');
      fireEvent.click(within(card).getByTestId('advance-1'));

      await waitFor(() => expect(showToast).toHaveBeenCalledWith('success', 'รับครบทุกโรงงาน — ส่งต่อฝ่ายขายเพื่อส่งมอบ'));
    });

    it('shows the completion toast for a PARTIALLY_DELIVERED deal once the refetched rows confirm every issued row is RECEIVED', async () => {
      api.tickets.list.mockResolvedValue({ tickets: [ticket({ fulfillmentStatus: 'PARTIALLY_DELIVERED' })] });
      api.tickets.get.mockResolvedValue({ ticket: { summary: { fulfillmentStatus: 'PARTIALLY_DELIVERED' } } });
      mockRowsByTicket({ 1: [row({ id: 1, importStep: 'AWAITING_CUSTOMS' })] });
      api.storedImportRequests.advanceStep.mockImplementationOnce(async () => {
        // The advance's own refetch (listForTicket, called directly by this page's onSuccess to
        // decide the toast) must see the POST-advance state, exactly like a real advanceStep call
        // landing before the follow-up read does — same pattern as the B3 test above.
        mockRowsByTicket({ 1: [row({ id: 1, importStep: 'RECEIVED' })] });
        return { importRequest: row({ id: 1, importStep: 'RECEIVED' }) };
      });
      const showToast = vi.fn();
      renderPage(showToast);
      const card = await screen.findByTestId('fulfilment-deal');
      fireEvent.click(within(card).getByTestId('advance-1'));

      await waitFor(() => expect(showToast).toHaveBeenCalledWith('success', 'รับครบทุกโรงงาน — ส่งต่อฝ่ายขายเพื่อส่งมอบ'));
    });

    it('does not claim completion for a PARTIALLY_DELIVERED deal while another issued row is still short of RECEIVED', async () => {
      api.tickets.list.mockResolvedValue({ tickets: [ticket({ fulfillmentStatus: 'PARTIALLY_DELIVERED' })] });
      api.tickets.get.mockResolvedValue({ ticket: { summary: { fulfillmentStatus: 'PARTIALLY_DELIVERED' } } });
      mockRowsByTicket({
        1: [row({ id: 1, importStep: 'AWAITING_CUSTOMS' }), row({ id: 2, factoryName: 'B', importStep: 'IN_TRANSIT' })],
      });
      api.storedImportRequests.advanceStep.mockResolvedValue({ importRequest: row({ id: 1, importStep: 'RECEIVED' }) });
      const showToast = vi.fn();
      renderPage(showToast);
      const card = await screen.findByTestId('fulfilment-deal');
      fireEvent.click(within(card).getByTestId('advance-1'));

      await waitFor(() => expect(showToast).toHaveBeenCalledWith('success', 'อัปเดตสถานะรายโรงงานแล้ว'));
      expect(showToast).not.toHaveBeenCalledWith('success', expect.stringContaining('รับครบทุกโรงงาน'));
    });

    it('does not claim completion for a PARTIALLY_DELIVERED deal while another factory is still a DRAFT', async () => {
      api.tickets.list.mockResolvedValue({ tickets: [ticket({ fulfillmentStatus: 'PARTIALLY_DELIVERED' })] });
      api.tickets.get.mockResolvedValue({ ticket: { summary: { fulfillmentStatus: 'PARTIALLY_DELIVERED' } } });
      const draftB = row({ id: 2, factoryName: 'B', status: 'DRAFT', importStep: null });
      mockRowsByTicket({ 1: [row({ id: 1, importStep: 'AWAITING_CUSTOMS' }), draftB] });
      api.storedImportRequests.advanceStep.mockImplementationOnce(async () => {
        mockRowsByTicket({ 1: [row({ id: 1, importStep: 'RECEIVED' }), draftB] });
        return { importRequest: row({ id: 1, importStep: 'RECEIVED' }) };
      });
      const showToast = vi.fn();
      renderPage(showToast);
      const card = await screen.findByTestId('fulfilment-deal');
      fireEvent.click(within(card).getByTestId('advance-1'));

      await waitFor(() => expect(showToast).toHaveBeenCalledWith('success', 'อัปเดตสถานะรายโรงงานแล้ว'));
      expect(showToast).not.toHaveBeenCalledWith('success', expect.stringContaining('รับครบทุกโรงงาน'));
    });
  });

  // PR-B REVIEW ROUND 1, S5: a failed tickets.list or per-deal rows fetch must render as an error.
  describe('S5 — error rendering', () => {
    it('shows an error banner when the deal list itself fails to load', async () => {
      api.tickets.list.mockRejectedValue(new Error('โหลดไม่สำเร็จ'));
      renderPage();
      expect(await screen.findByTestId('fulfilment-tickets-error')).not.toBeNull();
      // Must NOT read as "nothing to do".
      expect(screen.queryByText('ยังไม่มีงานนำเข้ารายโรงงาน')).toBeNull();
    });

    it('shows a per-deal error banner when one deal\'s rows fail to load, without hiding the rest', async () => {
      api.tickets.list.mockResolvedValue({
        tickets: [ticket({ id: 1, code: 'D-OK' }), ticket({ id: 2, code: 'D-ERR', customerName: 'ลูกค้าพัง', fulfillmentStatus: 'IR_ISSUED' })],
      });
      api.storedImportRequests.listForTicket.mockImplementation((ticketId) => (Number(ticketId) === 2
        ? Promise.reject(new Error('โหลดใบขอซื้อไม่สำเร็จ'))
        : Promise.resolve({ importRequests: [row({ id: 1, ticketId: 1, ticketCode: 'D-OK' })] })));
      renderPage();
      await screen.findByTestId('fulfilment-deal');
      expect(await screen.findByTestId('fulfilment-rows-error')).not.toBeNull();
      expect(screen.getByText('ลูกค้าพัง')).not.toBeNull();
    });
  });
});
