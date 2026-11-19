import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ImportDealPage } from './ImportDealPage.jsx';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';

globalThis.React = React;

// ImportDealPage is import's OWN per-deal page: it reads GET /api/import/deals/{id} (api.importDeals.get)
// instead of the whole-deal GET /api/tickets/{id}, which the backend now refuses import.
vi.mock('../../api/index.js', async (importOriginal) => {
  const actual = await importOriginal();
  return {
    ...actual,
    api: {
      importDeals: { get: vi.fn() },
      tickets: {
        comment: vi.fn(),
        issueImportRequest: vi.fn(),
        markShipping: vi.fn(),
        markGoodsReceived: vi.fn(),
        markIrSent: vi.fn(),
        recordDelivery: vi.fn(),
      },
      attachments: { list: vi.fn(), fileUrl: (id) => `/api/attachments/${id}/file` },
      storedImportRequests: {
        advanceStep: vi.fn(),
        setLeadTime: vi.fn(),
        updateEmailDraft: vi.fn(),
        markEmailSent: vi.fn(),
        download: vi.fn(),
        update: vi.fn(),
        issue: vi.fn(),
        revise: vi.fn(),
        deleteDraft: vi.fn(),
      },
    },
  };
});

function irRow(over = {}) {
  return {
    id: 11,
    ticketId: 1,
    ticketCode: 'PR-2026-0701',
    factoryId: 1,
    factoryName: 'Cotto Industry',
    version: 1,
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
    items: [{ id: 101, code: 'CT-60x60', size: '60x60', qty: 120, unit: 'ตร.ม.', note: null }],
    ...over,
  };
}

function dealFixture(over = {}) {
  return {
    id: 1,
    code: 'PR-2026-0701',
    title: 'ดีลกระเบื้องโรงแรม',
    status: 'quotation_issued',
    lifecycle: 'ACTIVE',
    salesStage: 'PROCUREMENT',
    customerName: 'บริษัท ทดสอบ จำกัด',
    projectName: 'โครงการโรงแรมริมทะเล',
    createdByName: 'สมชาย ฝ่ายขาย',
    fulfillmentStatus: 'IR_ISSUED',
    items: [
      {
        id: 201, brand: 'Cotto', model: 'Ivory Lappato', color: 'ขาว', texture: null, size: '60x60',
        code: 'CT-60x60', qty: 120, qtySqm: 43.2, unit: 'SQM', qtyDelivered: 40, // QA-29f: the bare basis code ImportDealService sends as unit
        // A stray price field the DTO must never carry — the page must not render it regardless.
        rawPrice: 777,
      },
      {
        id: 202, brand: 'Duragres', model: 'Slate Grey', color: 'เทา', texture: null, size: '30x60',
        code: 'DG-30x60', qty: 50, qtySqm: null, unit: 'PIECE', qtyDelivered: 0,
      },
    ],
    importRequests: [
      irRow({ id: 11, factoryName: 'Cotto Industry', importStep: 'ORDERED' }),
      irRow({ id: 12, factoryName: 'Duragres Thailand', docNumber: 'IR26002', importStep: 'AWAITING_CUSTOMS' }),
      irRow({ id: 13, factoryName: 'Old Factory', status: 'SUPERSEDED', docNumber: 'IR26000', importStep: null }),
    ],
    comments: [
      { id: 301, actorName: 'สมชาย ฝ่ายขาย', message: 'ฝากเช็คเวลาส่งของด้วยนะคะ', createdAt: '2026-09-11T03:00:00Z' },
    ],
    ...over,
  };
}

function renderPage({ role = 'import', ticketId = 1 } = {}) {
  const showToast = vi.fn();
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries');
  const utils = render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[`/import/deals/${ticketId}`]}>
        <Routes>
          <Route
            path="/import/deals/:ticketId"
            element={<ImportDealPage user={{ id: 5, name: 'ฝ่ายนำเข้า', role }} showToast={showToast} />}
          />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
  return { ...utils, queryClient, invalidateSpy, showToast };
}

describe('ImportDealPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.importDeals.get.mockResolvedValue({ deal: dealFixture() });
    api.attachments.list.mockResolvedValue({
      attachments: [{ id: 401, fileName: 'PO-hotel.pdf', attachType: 'PO', uploadedBy: 9 }],
    });
    api.tickets.comment.mockResolvedValue({ ticket: {} });
    api.tickets.issueImportRequest.mockResolvedValue({ ticket: {} });
    api.tickets.markShipping.mockResolvedValue({ ticket: {} });
    api.tickets.markGoodsReceived.mockResolvedValue({ ticket: {} });
    api.storedImportRequests.advanceStep.mockResolvedValue({ importRequest: irRow({ importStep: 'PICKED_UP' }) });
  });

  it('lists items (brand / model / size / qty / unit) and renders NO price or cost', async () => {
    renderPage();
    const items = await screen.findByTestId('import-deal-items');
    expect(items.textContent).toContain('Cotto');
    expect(items.textContent).toContain('Ivory Lappato');
    expect(items.textContent).toContain('60x60');
    expect(items.textContent).toContain('120');
    expect(items.textContent).toContain('ตร.ม.');
    // QA-29f: qty is always pieces (unit is only the line's basis), so it reads แผ่น, never the raw code;
    // an SQM line keeps its area in brackets.
    expect(items.textContent).toContain('120 แผ่น(43.2 ตร.ม.)');
    expect(items.textContent).toContain('50 แผ่น');
    expect(items.textContent).not.toMatch(/PIECE|SQM/);
    const delivery = screen.getByTestId('import-deal-delivery');
    expect(within(delivery).getByTestId('import-deal-delivery-item-202').textContent).toMatch(/0\s*\/\s*50 แผ่น/);
    expect(delivery.textContent).not.toMatch(/PIECE|SQM/);
    const page = document.body.textContent;
    expect(page).not.toMatch(/777/);
    expect(page).not.toMatch(/ราคา|ต้นทุน|฿|THB|มูลค่า|ยอดรวม/);
  });

  it('gives import no issue / revise / delete control on the factory cards (those are owner/CEO only)', async () => {
    renderPage({ role: 'import' });
    await screen.findByTestId('ir-factory-card-11');
    expect(screen.queryByTestId('ir-issue-11')).toBeNull();
    expect(screen.queryByTestId('ir-revise-11')).toBeNull();
    expect(screen.queryByTestId('ir-delete-11')).toBeNull();
    // ...but the order-email action is import's.
    expect(screen.getByTestId('ir-email-open-11')).not.toBeNull();
  });

  // Moved here from the sales deal tab, which no longer shows the order email: the save payload must
  // use Java's field names (UpdateEmailDraftRequest), not to/subject/body (Jackson silently drops those).
  it('saving the order-email draft sends emailTo/emailSubject/emailBody', async () => {
    api.storedImportRequests.updateEmailDraft.mockResolvedValue({ importRequest: irRow() });
    renderPage({ role: 'import' });
    fireEvent.click(await screen.findByTestId('ir-email-open-11'));
    const subject = await screen.findByDisplayValue('Purchase order IR26001 - GL&R');
    fireEvent.change(subject, { target: { value: 'Revised' } });
    fireEvent.click(screen.getByTestId('ir-email-save-11'));
    await waitFor(() => expect(api.storedImportRequests.updateEmailDraft).toHaveBeenCalledWith(11, expect.objectContaining({
      emailSubject: 'Revised', emailBody: 'Dear Cotto Industry team,...',
    })));
  });

  it('shows a friendly "not in your import scope" message on a 403, with no deal content', async () => {
    api.importDeals.get.mockRejectedValue(Object.assign(new Error('ไม่มีสิทธิ์เข้าถึงรายการนี้'), { status: 403 }));
    renderPage();
    expect(await screen.findByTestId('import-deal-forbidden')).not.toBeNull();
    expect(screen.getByText(/งานนี้ไม่อยู่ในขอบเขตของฝ่ายนำเข้า/)).not.toBeNull();
    expect(screen.queryByTestId('import-deal-items')).toBeNull();
    expect(api.attachments.list).not.toHaveBeenCalled();
  });
  // Legacy deal-level actions ──────────────────────────────────────────────────────────────── A LEGACY deal is
  // one that never started per-factory tracking: no live importRequests rows.
  describe('legacy deal-level actions (deal with no per-factory rows)', () => {
    const legacyDeal = (over = {}) => dealFixture({ importRequests: [], ...over });
    const legacyButtons = () => [
      /ออกคำขอนำเข้า/, /บันทึกออกเดินทาง/, /ยืนยันรับเข้าคลัง/, /บันทึกส่งมอบ/, /ส่งคำขอนำเข้าแล้ว/,
    ].flatMap((name) => screen.queryAllByRole('button', { name }));

    it('IR_SENT: shows บันทึกออกเดินทาง; clicking it calls tickets.markShipping, toasts and refreshes every affected cache', async () => {
      api.importDeals.get.mockResolvedValue({ deal: legacyDeal({ fulfillmentStatus: 'IR_SENT' }) });
      const { invalidateSpy, showToast } = renderPage();

      const card = await screen.findByTestId('import-deal-legacy-action');
      expect(card.textContent).toContain('ดำเนินการนำเข้า (งานรูปแบบเดิม)');
      fireEvent.click(within(card).getByRole('button', { name: 'บันทึกออกเดินทาง' }));

      await waitFor(() => expect(api.tickets.markShipping).toHaveBeenCalledWith('1'));
      await waitFor(() => expect(showToast).toHaveBeenCalledWith('success', expect.any(String)));
      await waitFor(() => expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: queryKeys.importDeal('1') }));
      expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: queryKeys.ticketDetail('1') });
      expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: queryKeys.ticketActions('1') });
      expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ['tickets', 'list'] });
      // Exactly one legacy transition fired.
      expect(api.tickets.issueImportRequest).not.toHaveBeenCalled();
      expect(api.tickets.markGoodsReceived).not.toHaveBeenCalled();
    });

    it('CEO gets the same legacy action', async () => {
      api.importDeals.get.mockResolvedValue({ deal: legacyDeal({ fulfillmentStatus: 'IR_SENT' }) });
      renderPage({ role: 'ceo' });
      expect(await screen.findByRole('button', { name: 'บันทึกออกเดินทาง' })).not.toBeNull();
    });

    it('CEO gets ออกเลข beside the same hint on this page, outside the collapsed details', async () => {
      api.importDeals.get.mockResolvedValue({
        deal: dealFixture({ importRequests: [irRow({ id: 14, status: 'DRAFT', importStep: null })] }),
      });
      renderPage({ role: 'ceo' });
      const hint = await screen.findByTestId('ir-draft-hint-14');
      const issue = screen.getByTestId('ir-issue-14');
      expect(issue.closest('details')).toBeNull();
      expect(hint.parentElement.contains(issue)).toBe(true);
    });

    it.each(['quotation_issued', 'approved'])(
      'IR_ISSUED (status %s, no per-factory rows): shows ส่งคำขอนำเข้าแล้ว; clicking it calls tickets.markIrSent',
      async (status) => {
        api.tickets.markIrSent.mockResolvedValue({});
        api.importDeals.get.mockResolvedValue({ deal: legacyDeal({ status, fulfillmentStatus: 'IR_ISSUED' }) });
        const { showToast } = renderPage();
        fireEvent.click(await screen.findByRole('button', { name: 'ส่งคำขอนำเข้าแล้ว' }));
        await waitFor(() => expect(api.tickets.markIrSent).toHaveBeenCalledWith('1'));
        await waitFor(() => expect(showToast).toHaveBeenCalledWith('success', expect.any(String)));
      },
    );

    it('is gated to import/ceo — any other role sees no legacy control', async () => {
      api.importDeals.get.mockResolvedValue({ deal: legacyDeal({ fulfillmentStatus: 'IR_SENT' }) });
      renderPage({ role: 'sales' });
      await screen.findByTestId('import-deal-no-ir');
      expect(screen.queryByTestId('import-deal-legacy-action')).toBeNull();
      expect(legacyButtons()).toHaveLength(0);
    });
  });
});
