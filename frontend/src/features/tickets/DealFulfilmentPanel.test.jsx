import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { DealFulfilmentPanel } from './DealFulfilmentPanel.jsx';
import { api } from '../../api/index.js';

globalThis.React = React;

// Covers the "ใบขอซื้อรายโรงงาน" section (stored aggregate): role visibility, the country-required
// create flow, the CEO-only footer, mark-sent, and the lead-time edit. The rest of the panel
// (delivery/stock/legacy IR chain) is exercised via TicketDetailPage's own suite.
vi.mock('../../api/index.js', async (importOriginal) => {
  const actual = await importOriginal();
  return {
    ...actual,
    api: {
      importRequests: { brands: vi.fn().mockResolvedValue({ brands: [] }), pages: vi.fn(), download: vi.fn() },
      storedImportRequests: {
        listForTicket: vi.fn(),
        coverage: vi.fn(),
        createDrafts: vi.fn(),
        update: vi.fn(),
        issue: vi.fn(),
        revise: vi.fn(),
        deleteDraft: vi.fn(),
        advanceStep: vi.fn(),
        setLeadTime: vi.fn(),
        updateEmailDraft: vi.fn(),
        markEmailSent: vi.fn(),
        download: vi.fn(),
        setRequiredByNote: vi.fn(),
      },
      factoryConfigs: {
        names: vi.fn().mockResolvedValue({ factories: [{ id: 7, factoryName: 'Rex Ceramics' }, { id: 8, factoryName: 'Cotto' }] }),
      },
      priceImport: {
        countries: vi.fn().mockResolvedValue([
          { countryCode: 'CN', nameEn: 'China', nameTh: 'จีน' },
          { countryCode: 'ZZ', nameEn: 'Other', nameTh: 'อื่นๆ' },
        ]),
      },
      tickets: {
        listDeliveries: vi.fn().mockResolvedValue({ items: [] }),
        issueImportRequest: vi.fn(),
        markIrSent: vi.fn(),
        markShipping: vi.fn(),
        markGoodsReceived: vi.fn(),
        reserveStock: vi.fn(),
        updateItemWeightMultipliers: vi.fn(),
        recordDelivery: vi.fn(),
        completeDelivery: vi.fn(),
      },
    },
  };
});

function draftRow(over = {}) {
  return {
    id: 10, ticketId: 1, factoryId: 1, factoryName: 'Cotto Industry', version: 1, status: 'DRAFT',
    docNumber: null, leadTimeMinDays: 30, leadTimeMaxDays: 45, requiredByNote: null,
    vesselEtaNote: null, checkedByName: null, checkedDate: null, approvedByName: null, approvedDate: null,
    importStep: null, importStepAt: null, emailSentAt: null,
    items: [{ id: '10-1', code: 'ABC', size: '60x60', qty: 10, unit: 'ตร.ม.', note: null }],
    ...over,
  };
}
function issuedRow(over = {}) {
  return {
    ...draftRow(),
    id: 11, status: 'ISSUED', docNumber: 'IR26001', importStep: 'ORDERED', importStepAt: '2026-09-10',
    emailTo: 'factory@example.com', emailSubject: 'Purchase order IR26001 - GL&R', emailBody: 'Dear...',
    emailSentAt: null, emailSentByName: null,
    ...over,
  };
}

const summary = { createdById: 42, status: 'quotation_issued', fulfillmentStatus: null, paymentStatus: 'DEPOSIT_PAID' };

// `rows` may be an Error, to render the panel with a failed ใบขอซื้อ fetch.
function renderPanel(user, rows = [draftRow()], {
  summary: dealSummary = summary, availableActions = [], showToast = vi.fn(), items = [], ref = undefined,
  hasUncoveredFactories = undefined, // undefined = an older backend that does not send the flag
  uncoveredFactoryNames = undefined, // undefined = an older backend that sends no names
  procurementSource = undefined,
  events = undefined,
  splitPreview = undefined, // undefined = a backend before slice 6c (no split preview on coverage)
} = {}) {
  if (rows instanceof Error) api.storedImportRequests.listForTicket.mockRejectedValue(rows);
  else {
    api.storedImportRequests.listForTicket.mockResolvedValue({ importRequests: rows });
  }
  // The flag has its own endpoint; undefined = an older backend (no such route -> the call fails).
  if (hasUncoveredFactories === undefined) api.storedImportRequests.coverage.mockRejectedValue(new Error('404'));
  else {
    api.storedImportRequests.coverage.mockResolvedValue({
      hasUncoveredFactories, ...(uncoveredFactoryNames === undefined ? {} : { uncoveredFactoryNames }),
      ...(splitPreview === undefined ? {} : { splitPreview }),
    });
  }
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <DealFulfilmentPanel ref={ref} user={user} ticketId={1} summary={dealSummary} items={items} availableActions={availableActions} procurementSource={procurementSource} showToast={showToast} events={events} />
    </QueryClientProvider>,
  );
}

// renderPanel has no seam for the handler prop, so wrap it: re-render the same tree with it set.
function renderPanelWithChoose(user, onChooseProcurementSource, procurementSource, dealSummary = { ...summary, paymentStatus: 'DEPOSIT_PAID' }) {
  api.storedImportRequests.listForTicket.mockResolvedValue({ importRequests: [] });
  api.storedImportRequests.coverage.mockRejectedValue(new Error('404'));
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <DealFulfilmentPanel user={user} ticketId={1} summary={dealSummary} items={[]} availableActions={[]}
        procurementSource={procurementSource} onChooseProcurementSource={onChooseProcurementSource} showToast={vi.fn()} />
    </QueryClientProvider>,
  );
}

const OWNER = { id: 42, name: 'สมหญิง', role: 'sales' };
const OTHER_REP = { id: 99, name: 'อีกคน', role: 'sales' };
const CEO = { id: 1, name: 'ราม', role: 'ceo' };
const IMPORT = { id: 2, name: 'ฝ่ายนำเข้า', role: 'import' };
const SALES_MANAGER = { id: 3, name: 'ผจก.ขาย', role: 'sales_manager' };

// ออกใบขอซื้อ (IR) mints one numbered IR per factory and cannot be undone, so the click only opens
// this confirm; createDrafts runs when it is confirmed.
const IR_CONFIRM_TITLE = 'ยืนยันการออกใบขอซื้อ (IR)';
// Opening the confirm re-checks which factories are uncovered; the dialog is ready once that settles.
const IR_COVERAGE_LOADING = 'กำลังตรวจสอบโรงงาน';
async function openIrConfirm(button) {
  fireEvent.click(button ?? await screen.findByTestId('deal-fulfilment-create-ir-drafts'));
  return settledIrConfirm();
}
async function settledIrConfirm() {
  const dialog = await screen.findByRole('dialog', { name: IR_CONFIRM_TITLE });
  await waitFor(() => expect(dialog.textContent).not.toContain(IR_COVERAGE_LOADING));
  return dialog;
}
// Drawing m-ir: the confirm button reads ออกใบขอซื้อ (was ออกใบขอซื้อ (IR)).
const IR_CONFIRM_LABEL = 'ออกใบขอซื้อ';
// Fix round 1, F1: the create confirm's irreversibility warning.
const IR_IRREVERSIBLE = 'ออกเลข IR แล้วลบไม่ได้ · แก้ได้ด้วยการออกฉบับแก้ไขเท่านั้น';
async function createIrAndConfirm(button) {
  const dialog = await openIrConfirm(button);
  fireEvent.click(within(dialog).getByRole('button', { name: IR_CONFIRM_LABEL }));
}
// Drawing m-ir: the factories are a summary line ("N โรงงาน …") plus one table row each (was one sentence
// "จะออกใบขอซื้อ (IR) N ใบ: A, B"). Same facts: the count and the names, in order.
function irConfirmFactories(dialog) {
  return {
    summary: within(dialog).getByTestId('ir-confirm-summary').textContent,
    names: within(within(dialog).getByRole('table', { name: 'โรงงานที่จะออกใบขอซื้อ' })).getAllByRole('row').slice(1).map((tr) => tr.textContent),
  };
}

describe('DealFulfilmentPanel — ใบขอซื้อรายโรงงาน (V184 stored aggregate)', () => {
  beforeEach(() => vi.clearAllMocks());

  describe('W3-11 confirm before issuing the ใบขอซื้อ (IR)', () => {
    beforeEach(() => api.storedImportRequests.createDrafts.mockReset());

    it('clicking opens a dialog (one IR per factory, cannot be undone) and calls no api; confirming calls createDrafts exactly once', async () => {
      api.storedImportRequests.createDrafts.mockResolvedValue({ importRequests: [issuedRow()] });
      renderPanel(OWNER, []);
      const dialog = await openIrConfirm();
      expect(dialog.textContent).toContain('หนึ่งใบต่อโรงงาน');
      // Fix round 1, F1: the irreversibility warning is now "ออกเลข IR แล้วลบไม่ได้ · แก้ได้ด้วยการออกฉบับแก้ไขเท่านั้น"
      // (was "… ย้อนกลับไม่ได้"; an issued IR can be revised, so "cannot be undone" overstated it).
      expect(dialog.textContent).toContain(IR_IRREVERSIBLE);
      expect(api.storedImportRequests.createDrafts).not.toHaveBeenCalled();

      const confirm = within(dialog).getByRole('button', { name: IR_CONFIRM_LABEL });
      fireEvent.click(confirm);
      fireEvent.click(confirm);

      await waitFor(() => expect(api.storedImportRequests.createDrafts).toHaveBeenCalledTimes(1));
      await waitFor(() => expect(screen.queryByRole('dialog', { name: IR_CONFIRM_TITLE })).toBeNull());
    });

    // The picked factory may already be one the server named (same factory on a catalogued line): once.
    it('a pick that equals a server-named factory is not counted twice', async () => {
      const directSummary = { ...summary, liveDirectQuotation: { id: 5, docStatus: 'APPROVED' } };
      const lines = [
        { id: 1, brand: 'SCG', model: 'Catalogue', factory: 'Rex Ceramics', catalogPriceId: 90, qty: 10 },
        { id: 3, brand: 'ค่าติดตั้ง', model: 'Free text', factory: null, catalogPriceId: null, qty: 10, qtyFromStock: 0 },
      ];
      renderPanel(OWNER, [], {
        summary: directSummary, items: lines, hasUncoveredFactories: true, uncoveredFactoryNames: ['Rex Ceramics'],
      });
      const button = await screen.findByTestId('deal-fulfilment-create-ir-drafts');
      const select = await screen.findByTestId('ir-line-factory-3');
      await waitFor(() => expect(within(select).getByRole('option', { name: 'Rex Ceramics' })).not.toBeNull());
      fireEvent.change(select, { target: { value: '7' } });
      await waitFor(() => expect(button.disabled).toBe(false));

      const dialog = await openIrConfirm(button);
      expect(irConfirmFactories(dialog)).toEqual({ summary: '1 โรงงาน · ส่งถึงฝ่ายนำเข้าทันที', names: ['Rex Ceramics'] });
    });
  });

  describe('role visibility', () => {
    it('the owning sales rep sees the create-draft button', async () => {
      renderPanel(OWNER);
      expect(await screen.findByTestId('deal-fulfilment-create-ir-drafts')).not.toBeNull();
    });

    it('import does not see the create-draft button (read + advance only)', async () => {
      renderPanel(IMPORT);
      await screen.findByTestId('deal-fulfilment-stored-ir');
      expect(screen.queryByTestId('deal-fulfilment-create-ir-drafts')).toBeNull();
    });

    it('sales_manager does not see the create-draft button (read-only)', async () => {
      renderPanel(SALES_MANAGER);
      await screen.findByTestId('deal-fulfilment-stored-ir');
      expect(screen.queryByTestId('deal-fulfilment-create-ir-drafts')).toBeNull();
    });

    it('a non-owning sales rep sees no stored-IR section at all (read scoped to the deal owner)', () => {
      renderPanel(OTHER_REP);
      expect(screen.queryByTestId('deal-fulfilment-stored-ir')).toBeNull();
    });
  });

  // The deal-level chain (ส่งคำขอนำเข้าแล้ว → ออกเดินทาง → รับสินค้า) is refused by the server once ANY
  // live per-factory row exists, DRAFT included (TicketRepository#hasLiveImportRequests), so the
  // buttons are hidden on a draft too.
  describe('deal-level import chain vs per-factory drafts', () => {
    const legacyInFlight = { ...summary, fulfillmentStatus: 'IR_SENT' };
    const shipping = [{ action: 'SHIPPING' }];

    it('a deal whose only per-factory rows are DRAFT gets no deal-level step button (the server refuses it)', async () => {
      renderPanel(CEO, [draftRow()], { summary: legacyInFlight, availableActions: shipping });
      await screen.findByTestId('ir-factory-card-10');
      expect(screen.queryByTestId('deal-fulfilment-mark-shipping')).toBeNull();
      expect(screen.getByTestId('deal-fulfilment-ir-tracked-note')).not.toBeNull();
    });

    it('the deal-level step is gone once a row is ISSUED — the server would refuse it', async () => {
      renderPanel(CEO, [issuedRow()], { summary: legacyInFlight, availableActions: shipping });
      await screen.findByTestId('ir-factory-card-11');
      expect(screen.queryByTestId('deal-fulfilment-mark-shipping')).toBeNull();
      expect(screen.getByTestId('deal-fulfilment-ir-tracked-note')).not.toBeNull();
    });
  });

  // The create button mirrors TicketService#requireImportRequestIssuable(summary, true)
  // (DEPOSIT_NOTICE_ISSUED / DEPOSIT_PAID / AWAITING_FINAL_PAYMENT / FULLY_PAID, or a not-required
  // deposit policy that has reached CUSTOMER_CONFIRMED) instead of offering a click that can only 409.
  describe('stage-10 source gate on the create button', () => {
    const paid = { ...summary, paymentStatus: 'DEPOSIT_PAID' };
    it('an old deal whose source is not decided: disabled with a hint pointing at จัดซื้อสินค้า', async () => {
      renderPanel(OWNER, [], { summary: paid, procurementSource: { legacy: true, pending: true, decidedAt: null } });
      expect((await screen.findByTestId('deal-fulfilment-create-ir-drafts')).disabled).toBe(true);
      expect(screen.getByTestId('ir-source-hint').textContent).toContain('จัดซื้อสินค้า');
    });
    it.each([['CEO', CEO], ['owning rep', OWNER]])('%s: จัดซื้อสินค้า button when pending && editable and a handler is given; click calls it', async (_n, user) => {
      const onChoose = vi.fn();
      renderPanelWithChoose(user, onChoose, { legacy: true, pending: true, editable: true, decidedAt: null });
      fireEvent.click(await screen.findByTestId('deal-fulfilment-choose-source'));
      expect(onChoose).toHaveBeenCalledTimes(1);
    });
    // TicketService#canDeclareStockCoverage is CEO or a sales-role owner/keyer: the all-documents grant does not reach it.
    it('a canManageAllSalesDocuments holder on another rep\'s deal gets no จัดซื้อสินค้า button (the server refuses the declaration)', async () => {
      const holder = { ...OTHER_REP, canManageAllSalesDocuments: true };
      renderPanelWithChoose(holder, vi.fn(), { legacy: true, pending: true, editable: true, decidedAt: null });
      await screen.findByTestId('deal-fulfilment-create-ir-drafts');
      expect(screen.queryByTestId('deal-fulfilment-choose-source')).toBeNull();
      // ...and no hint telling them to press a button they cannot see
      expect(screen.queryByTestId('ir-source-hint')).toBeNull();
    });
    it('before the deposit is settled there is no จัดซื้อสินค้า button either (same gate as the IR button)', async () => {
      const onChoose = vi.fn();
      renderPanelWithChoose(OWNER, onChoose, { legacy: true, pending: true, editable: true, decidedAt: null },
        { ...summary, paymentStatus: 'CUSTOMER_CONFIRMED' });
      await screen.findByTestId('deal-fulfilment-stored-ir');
      expect(screen.queryByTestId('deal-fulfilment-choose-source')).toBeNull();
    });
  });

  describe('deposit-ready gate on the create button', () => {
    const dealAt = (paymentStatus, depositPolicy = 'REQUIRED') => ({ ...summary, paymentStatus, depositPolicy });

    it.each([['DEPOSIT_NOTICE_ISSUED'], ['DEPOSIT_PAID'], ['AWAITING_FINAL_PAYMENT'], ['FULLY_PAID']])(
      'is enabled, no hint, at paymentStatus %s (same set as the server gate)', async (paymentStatus) => {
        renderPanel(OWNER, [], { summary: dealAt(paymentStatus) });
        const button = await screen.findByTestId('deal-fulfilment-create-ir-drafts');
        expect(button.disabled).toBe(false);
        expect(screen.queryByTestId('ir-deposit-hint')).toBeNull();
      });
  });

  describe('country-required create flow', () => {
    it('a missing-country 409 opens the picker, and submitting retries with newFactoryCountries', async () => {
      api.storedImportRequests.createDrafts
        .mockRejectedValueOnce(Object.assign(new Error('กรุณาระบุประเทศให้กับโรงงานใหม่: Rex Ceramics'), { status: 409 }))
        .mockResolvedValueOnce({ importRequests: [draftRow({ factoryName: 'Rex Ceramics' })] });
      renderPanel(OWNER, []);
      await createIrAndConfirm();

      const submit = await screen.findByTestId('new-factory-country-submit');
      expect(submit.disabled).toBe(true);

      const select = await screen.findByLabelText(/^ประเทศ/);
      fireEvent.change(select, { target: { value: 'CN' } });
      await waitFor(() => expect(submit.disabled).toBe(false));
      fireEvent.click(submit);

      await waitFor(() => expect(api.storedImportRequests.createDrafts).toHaveBeenCalledWith(1, {
        newFactoryCountries: [{ factoryName: 'Rex Ceramics', countryCode: 'CN', countryOther: null }],
      }));
    });
  });

  describe('CEO-only footer', () => {
    it('CEO sees the footer edit control on an issued row', async () => {
      renderPanel(CEO, [issuedRow()]);
      expect(await screen.findByTestId('ir-footer-open-11')).not.toBeNull();
    });

    it('the owning sales rep does not see the footer edit control', async () => {
      renderPanel(OWNER, [issuedRow()]);
      await screen.findByTestId(`ir-factory-card-11`);
      expect(screen.queryByTestId('ir-footer-open-11')).toBeNull();
    });
  });
});

// Drawing m-ir (2026-11-16): the confirm body is rebuilt; createDrafts is sent exactly as before.
describe('DealFulfilmentPanel — ยืนยันการออกใบขอซื้อ (IR) (drawing m-ir)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.storedImportRequests.createDrafts.mockReset();
    api.storedImportRequests.createDrafts.mockResolvedValue({ importRequests: [] });
  });
  const confirmButton = (dialog) => within(dialog).getByRole('button', { name: /^ออกใบขอซื้อ/ });

  // Payload identity — captured against the pre-rebuild dialog and kept green through it.
  it('an ordinary deal: confirm sends createDrafts(ticketId, undefined) once', async () => {
    renderPanel(OWNER, [], { hasUncoveredFactories: true, uncoveredFactoryNames: ['Cotto Industry', 'Rex Ceramics'] });
    const dialog = await openIrConfirm();
    fireEvent.click(confirmButton(dialog));
    await waitFor(() => expect(api.storedImportRequests.createDrafts).toHaveBeenCalledTimes(1));
    expect(api.storedImportRequests.createDrafts.mock.calls).toEqual([[1, undefined]]);
  });

  it('wrong way round: import and sales_manager have no opener at all', async () => {
    for (const viewer of [IMPORT, SALES_MANAGER]) {
      const view = renderPanel(viewer, [], { hasUncoveredFactories: true, uncoveredFactoryNames: ['Cotto Industry'] });
      await screen.findByTestId('deal-fulfilment-stored-ir');
      expect(screen.queryByTestId('deal-fulfilment-create-ir-drafts')).toBeNull();
      view.unmount();
    }
  });
});

// Task 9: recording a delivery moved to the round card (DeliveryRoundsPanel, tested there): this panel keeps the delivery
// table and history, and no longer holds a record or complete button, whatever the server advertises.
describe('DealFulfilmentPanel — การบันทึกส่งสินค้าย้ายไปที่รอบส่ง', () => {
  const lines = [{ id: 21, brand: 'Equipe', model: 'Raku 20x20', qty: 120, qtyDelivered: 56, qtyFromStock: 0 }];

  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('no record or complete button for the owner or the CEO even when the legacy actions are advertised; a hint points to the round', async () => {
    for (const viewer of [OWNER, CEO]) {
      const view = renderPanel(viewer, [], {
        items: lines, availableActions: [{ action: 'RECORD_PARTIAL_DELIVERY' }, { action: 'COMPLETE_DELIVERY' }],
      });
      expect((await screen.findByTestId('delivery-record-hint')).textContent).toContain('หมายเหตุ');
      expect(screen.queryByTestId('deal-fulfilment-record-delivery')).toBeNull();
      expect(screen.queryByTestId('deal-fulfilment-complete')).toBeNull();
      expect(screen.getByRole('table', { name: 'การส่งสินค้าให้ลูกค้า' })).not.toBeNull();
      view.unmount();
    }
    expect(api.tickets.recordDelivery).not.toHaveBeenCalled();
    expect(api.tickets.completeDelivery).not.toHaveBeenCalled();
  });
});
