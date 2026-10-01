import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ImportDealPage } from './ImportDealPage.jsx';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';

globalThis.React = React;

// ImportDealPage is import's OWN per-deal page: it reads GET /api/import/deals/{id}
// (api.importDeals.get) instead of the whole-deal GET /api/tickets/{id}, which the backend now
// refuses import. The DTO carries NO price/cost field, so this file also pins that the page never
// renders one even if a stray field were present (defence in depth, not a claim about the server).
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
        code: 'CT-60x60', qty: 120, qtySqm: 43.2, unit: 'ตร.ม.', qtyDelivered: 40,
        // A stray price field the DTO must never carry — the page must not render it regardless.
        rawPrice: 777,
      },
      {
        id: 202, brand: 'Duragres', model: 'Slate Grey', color: 'เทา', texture: null, size: '30x60',
        code: 'DG-30x60', qty: 50, qtySqm: null, unit: 'กล่อง', qtyDelivered: 0,
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

  it('loads the deal through api.importDeals.get — never the whole-deal endpoint', async () => {
    renderPage({ ticketId: 1 });
    await screen.findByRole('heading', { level: 1 });
    expect(api.importDeals.get).toHaveBeenCalledWith('1');
  });

  it('shows the deal code, customer and project in the header, with no money totals', async () => {
    renderPage();
    const heading = await screen.findByRole('heading', { level: 1 });
    expect(heading.textContent).toContain('บริษัท ทดสอบ จำกัด');
    expect(screen.getAllByText(/PR-2026-0701/).length).toBeGreaterThan(0);
    expect(screen.getByText(/โครงการโรงแรมริมทะเล/)).not.toBeNull();
  });

  it('renders one per-factory card per LIVE importRequests row (SUPERSEDED dropped) plus the rollup chip', async () => {
    renderPage();
    await screen.findByTestId('ir-factory-card-11');
    expect(screen.getByTestId('ir-factory-card-12')).not.toBeNull();
    expect(screen.queryByTestId('ir-factory-card-13')).toBeNull();
    // 1 of the 2 issued factories has reached Thailand (AWAITING_CUSTOMS or later).
    expect(screen.getByTestId('import-deal-ir-rollup-chip').textContent).toMatch(/ถึงไทย 1\/2 โรงงาน/);
  });

  it('shows delivery status read-only: the fulfilment label and delivered/ordered per item, no write control', async () => {
    renderPage();
    const panel = await screen.findByTestId('import-deal-delivery');
    expect(within(panel).getByText('ออกคำขอนำเข้าแล้ว')).not.toBeNull();
    expect(within(panel).getByTestId('import-deal-delivery-item-201').textContent).toMatch(/40\s*\/\s*120/);
    expect(within(panel).getByTestId('import-deal-delivery-item-202').textContent).toMatch(/0\s*\/\s*50/);
    // Delivery is Sales's — the section carries no button or input at all.
    expect(within(panel).queryAllByRole('button')).toHaveLength(0);
    expect(within(panel).queryAllByRole('textbox')).toHaveLength(0);
  });

  it('lists items (brand / model / size / qty / unit) and renders NO price or cost', async () => {
    renderPage();
    const items = await screen.findByTestId('import-deal-items');
    expect(items.textContent).toContain('Cotto');
    expect(items.textContent).toContain('Ivory Lappato');
    expect(items.textContent).toContain('60x60');
    expect(items.textContent).toContain('120');
    expect(items.textContent).toContain('ตร.ม.');
    const page = document.body.textContent;
    expect(page).not.toMatch(/777/);
    expect(page).not.toMatch(/ราคา|ต้นทุน|฿|THB|มูลค่า|ยอดรวม/);
  });

  it('lists the comment thread and posts a new comment through api.tickets.comment, then refreshes the import deal', async () => {
    const { invalidateSpy } = renderPage();
    const thread = await screen.findByTestId('import-deal-comments');
    expect(within(thread).getByText('ฝากเช็คเวลาส่งของด้วยนะคะ')).not.toBeNull();

    fireEvent.change(screen.getByTestId('import-deal-comment-input'), { target: { value: '  รับทราบครับ  ' } });
    fireEvent.click(screen.getByTestId('import-deal-comment-submit'));

    await waitFor(() => expect(api.tickets.comment).toHaveBeenCalledWith('1', { message: 'รับทราบครับ' }));
    await waitFor(() => expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: queryKeys.importDeal('1') }));
  });

  it('does not post an empty comment', async () => {
    renderPage();
    await screen.findByTestId('import-deal-comments');
    expect(screen.getByTestId('import-deal-comment-submit').disabled).toBe(true);
    fireEvent.click(screen.getByTestId('import-deal-comment-submit'));
    expect(api.tickets.comment).not.toHaveBeenCalled();
  });

  it('lists attachments with a download link, and offers no upload or delete control', async () => {
    renderPage();
    const link = await screen.findByRole('link', { name: 'ดูไฟล์' });
    expect(screen.getByText('PO-hotel.pdf')).not.toBeNull();
    expect(link.getAttribute('href')).toBe('/api/attachments/401/file');
    expect(screen.queryByText(/แนบไฟล์/)).toBeNull();
    expect(screen.queryByLabelText(/ลบไฟล์แนบ/)).toBeNull();
  });

  it('lets import advance a factory step, and refreshes the import deal AND the deal tab / worklist caches (sync)', async () => {
    const { invalidateSpy } = renderPage();
    fireEvent.click(await screen.findByTestId('advance-11'));

    await waitFor(() => expect(api.storedImportRequests.advanceStep)
      .toHaveBeenCalledWith(11, { targetStep: 'PICKED_UP' }));
    await waitFor(() => expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: queryKeys.importDeal(1) }));
    // The pre-existing surfaces keep being refreshed alongside it.
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: queryKeys.storedImportRequests(1) });
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: queryKeys.ticketDetail(1) });
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

  it('surfaces a per-factory ใบขอซื้อ PDF download on each issued card, and it downloads', async () => {
    api.storedImportRequests.download.mockResolvedValue(new Blob(['pdf'], { type: 'application/pdf' }));
    renderPage({ role: 'import' });
    const btn = await screen.findByTestId('ir-download-inline-11');
    expect(btn).not.toBeNull();
    fireEvent.click(btn);
    await waitFor(() => expect(api.storedImportRequests.download).toHaveBeenCalledWith(11, undefined));
  });

  it('shows a friendly "not in your import scope" message on a 403, with no deal content', async () => {
    api.importDeals.get.mockRejectedValue(Object.assign(new Error('ไม่มีสิทธิ์เข้าถึงรายการนี้'), { status: 403 }));
    renderPage();
    expect(await screen.findByTestId('import-deal-forbidden')).not.toBeNull();
    expect(screen.getByText(/ไม่อยู่ในขอบเขตงานนำเข้า/)).not.toBeNull();
    expect(screen.queryByTestId('import-deal-items')).toBeNull();
    expect(api.attachments.list).not.toHaveBeenCalled();
  });

  it('shows a not-found state on a 404', async () => {
    api.importDeals.get.mockRejectedValue(Object.assign(new Error('ไม่พบดีลนี้'), { status: 404 }));
    renderPage();
    expect(await screen.findByTestId('import-deal-not-found')).not.toBeNull();
  });

  it('shows a generic error with the server message on any other failure', async () => {
    api.importDeals.get.mockRejectedValue(Object.assign(new Error('เซิร์ฟเวอร์ขัดข้อง'), { status: 500 }));
    renderPage();
    expect(await screen.findByTestId('import-deal-error')).not.toBeNull();
    expect(screen.getByText(/เซิร์ฟเวอร์ขัดข้อง/)).not.toBeNull();
  });

  it('shows a busy skeleton while loading', () => {
    api.importDeals.get.mockReturnValue(new Promise(() => {}));
    renderPage();
    expect(screen.getByLabelText('กำลังโหลดดีล')).not.toBeNull();
  });

  it('says so when the deal has no per-factory ใบขอซื้อ yet', async () => {
    api.importDeals.get.mockResolvedValue({ deal: dealFixture({ importRequests: [] }) });
    renderPage();
    expect(await screen.findByTestId('import-deal-no-ir')).not.toBeNull();
  });
  // ── Legacy deal-level actions ────────────────────────────────────────────────────────────────
  // A LEGACY deal is one that never started per-factory tracking: no live importRequests rows. For
  // those, nextImportAction still routes issueImportRequest / markShipping / markGoodsReceived to
  // THIS page (importActions.js), so this page must perform them — DealFulfilmentPanel, where they
  // used to live, is closed to import (GET /api/tickets/{id} 403s it).
  describe('legacy deal-level actions (deal with no per-factory rows)', () => {
    const legacyDeal = (over = {}) => dealFixture({ importRequests: [], ...over });
    const legacyButtons = () => [
      /ออกคำขอนำเข้า/, /บันทึกออกเดินทาง/, /ยืนยันรับเข้าคลัง/, /บันทึกส่งมอบ/, /ส่งคำขอนำเข้าแล้ว/,
    ].flatMap((name) => screen.queryAllByRole('button', { name }));

    it('IR_SENT: shows บันทึกออกเดินทาง; clicking it calls tickets.markShipping, toasts and refreshes every affected cache', async () => {
      api.importDeals.get.mockResolvedValue({ deal: legacyDeal({ fulfillmentStatus: 'IR_SENT' }) });
      const { invalidateSpy, showToast } = renderPage();

      const card = await screen.findByTestId('import-deal-legacy-action');
      expect(card.textContent).toContain('ดำเนินการนำเข้า (ดีลรูปแบบเดิม)');
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

    it('fulfillmentStatus null: shows ออกคำขอนำเข้า and calls tickets.issueImportRequest', async () => {
      api.importDeals.get.mockResolvedValue({ deal: legacyDeal({ fulfillmentStatus: null }) });
      renderPage();
      const card = await screen.findByTestId('import-deal-legacy-action');
      fireEvent.click(within(card).getByRole('button', { name: 'ออกคำขอนำเข้า' }));
      await waitFor(() => expect(api.tickets.issueImportRequest).toHaveBeenCalledWith('1'));
      expect(api.tickets.markShipping).not.toHaveBeenCalled();
    });

    it('SHIPPING: shows ยืนยันรับเข้าคลัง and calls tickets.markGoodsReceived', async () => {
      api.importDeals.get.mockResolvedValue({ deal: legacyDeal({ fulfillmentStatus: 'SHIPPING' }) });
      renderPage();
      const card = await screen.findByTestId('import-deal-legacy-action');
      fireEvent.click(within(card).getByRole('button', { name: 'ยืนยันรับเข้าคลัง' }));
      await waitFor(() => expect(api.tickets.markGoodsReceived).toHaveBeenCalledWith('1'));
      expect(api.tickets.markShipping).not.toHaveBeenCalled();
    });

    it('CEO gets the same legacy action', async () => {
      api.importDeals.get.mockResolvedValue({ deal: legacyDeal({ fulfillmentStatus: 'IR_SENT' }) });
      renderPage({ role: 'ceo' });
      expect(await screen.findByRole('button', { name: 'บันทึกออกเดินทาง' })).not.toBeNull();
    });

    it('toasts the server message and does NOT refresh caches when the transition is refused', async () => {
      api.importDeals.get.mockResolvedValue({ deal: legacyDeal({ fulfillmentStatus: 'IR_SENT' }) });
      api.tickets.markShipping.mockRejectedValue(new Error('ดีลนี้ติดตามการนำเข้าด้วยใบขอซื้อรายโรงงาน'));
      const { invalidateSpy, showToast } = renderPage();
      fireEvent.click(await screen.findByRole('button', { name: 'บันทึกออกเดินทาง' }));
      await waitFor(() => expect(showToast).toHaveBeenCalledWith('error', 'ดีลนี้ติดตามการนำเข้าด้วยใบขอซื้อรายโรงงาน'));
      expect(invalidateSpy).not.toHaveBeenCalledWith({ queryKey: queryKeys.importDeal('1') });
    });

    it('disables the button while the transition is in flight (no double submit)', async () => {
      api.importDeals.get.mockResolvedValue({ deal: legacyDeal({ fulfillmentStatus: 'IR_SENT' }) });
      api.tickets.markShipping.mockReturnValue(new Promise(() => {}));
      renderPage();
      const btn = await screen.findByRole('button', { name: 'บันทึกออกเดินทาง' });
      fireEvent.click(btn);
      await waitFor(() => expect(btn.disabled).toBe(true));
      fireEvent.click(btn);
      expect(api.tickets.markShipping).toHaveBeenCalledTimes(1);
    });

    it('shows NO legacy action when the deal has live per-factory rows (the tracker owns it)', async () => {
      // IR_SENT would be markShipping for a legacy deal — but this deal HAS rows.
      api.importDeals.get.mockResolvedValue({ deal: dealFixture({ fulfillmentStatus: 'IR_SENT' }) });
      renderPage();
      await screen.findByTestId('ir-factory-card-11');
      expect(screen.queryByTestId('import-deal-legacy-action')).toBeNull();
      expect(legacyButtons()).toHaveLength(0);
    });

    it('a deal whose only rows are SUPERSEDED is still legacy (no LIVE rows)', async () => {
      api.importDeals.get.mockResolvedValue({
        deal: dealFixture({
          fulfillmentStatus: 'IR_SENT',
          importRequests: [irRow({ id: 13, status: 'SUPERSEDED', importStep: null })],
        }),
      });
      renderPage();
      expect(await screen.findByRole('button', { name: 'บันทึกออกเดินทาง' })).not.toBeNull();
    });

    it.each(['GOODS_RECEIVED', 'FROM_STOCK', 'PARTIALLY_DELIVERED'])(
      'shows NO action at %s — delivery (recordDelivery) is Sales, read-only here',
      async (fulfillmentStatus) => {
        api.importDeals.get.mockResolvedValue({ deal: legacyDeal({ fulfillmentStatus }) });
        renderPage();
        await screen.findByTestId('import-deal-delivery');
        expect(screen.queryByTestId('import-deal-legacy-action')).toBeNull();
        expect(legacyButtons()).toHaveLength(0);
        expect(api.tickets.recordDelivery).not.toHaveBeenCalled();
      },
    );

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

    it('shows NO action before the quotation is issued (deal not in the fulfilment chain yet)', async () => {
      api.importDeals.get.mockResolvedValue({ deal: legacyDeal({ status: 'pricing', fulfillmentStatus: null }) });
      renderPage();
      await screen.findByTestId('import-deal-no-ir');
      expect(screen.queryByTestId('import-deal-legacy-action')).toBeNull();
    });

    it('is gated to import/ceo — any other role sees no legacy control', async () => {
      api.importDeals.get.mockResolvedValue({ deal: legacyDeal({ fulfillmentStatus: 'IR_SENT' }) });
      renderPage({ role: 'sales' });
      await screen.findByTestId('import-deal-no-ir');
      expect(screen.queryByTestId('import-deal-legacy-action')).toBeNull();
      expect(legacyButtons()).toHaveLength(0);
    });

    it('renders no price or cost anywhere on a legacy deal', async () => {
      api.importDeals.get.mockResolvedValue({ deal: legacyDeal({ fulfillmentStatus: null }) });
      renderPage();
      await screen.findByTestId('import-deal-legacy-action');
      const page = document.body.textContent;
      expect(page).not.toMatch(/777/);
      expect(page).not.toMatch(/ราคา|ต้นทุน|฿|THB|มูลค่า|ยอดรวม/);
    });
  });

  // ── At-a-glance status strip ─────────────────────────────────────────────────────────────────
  describe('status strip', () => {
    it('summarises factories arrived, overall delivery % and the fulfilment status', async () => {
      renderPage();
      const strip = await screen.findByTestId('import-deal-status-strip');
      expect(within(strip).getByTestId('import-deal-ir-rollup-chip').textContent).toMatch(/ถึงไทย 1\/2 โรงงาน/);
      // Item bars are 33% (40/120) and 0% (0/50): the strip shows their mean, 17%.
      expect(within(strip).getByTestId('import-deal-delivery-chip').textContent).toMatch(/ส่งมอบ 17%/);
      expect(within(strip).getByText('ออกคำขอนำเข้าแล้ว')).not.toBeNull();
    });

    it('omits the factory chip when nothing is issued, and the delivery chip when there are no items', async () => {
      api.importDeals.get.mockResolvedValue({ deal: dealFixture({ importRequests: [], items: [], fulfillmentStatus: null }) });
      renderPage();
      const strip = await screen.findByTestId('import-deal-status-strip');
      expect(within(strip).queryByTestId('import-deal-ir-rollup-chip')).toBeNull();
      expect(within(strip).queryByTestId('import-deal-delivery-chip')).toBeNull();
    });
  });
});
