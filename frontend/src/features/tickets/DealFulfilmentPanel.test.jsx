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

    // NewFactoryCountryModal's `entries` state is seeded once from `factoryNames` via useState's lazy
    // initializer and does not re-derive when the PROP changes. A retry with a DIFFERENT (wider)
    // missing-factory set must not read `entries[newName]` (undefined, no optional chaining at the
    // `<select>`'s `value={entries[name].countryCode}`) and crash. Keying the modal by the retry key
    // remounts it fresh.
    it('does not crash when a retry 409s back with a DIFFERENT (wider) missing-factory set', async () => {
      api.storedImportRequests.createDrafts
        .mockRejectedValueOnce(Object.assign(new Error('กรุณาระบุประเทศให้กับโรงงานใหม่: Rex Ceramics'), { status: 409 }))
        .mockRejectedValueOnce(Object.assign(new Error('กรุณาระบุประเทศให้กับโรงงานใหม่: Rex Ceramics, New Factory'), { status: 409 }));
      renderPanel(OWNER, []);
      await createIrAndConfirm();

      const select = await screen.findByLabelText(/^ประเทศ/);
      fireEvent.change(select, { target: { value: 'CN' } });
      const submit = await screen.findByTestId('new-factory-country-submit');
      await waitFor(() => expect(submit.disabled).toBe(false));
      fireEvent.click(submit);

      await waitFor(() => expect(api.storedImportRequests.createDrafts).toHaveBeenCalledTimes(2));
      // Both factories from the WIDER retry set must render their own country picker — this would throw reading
      // `entries['New Factory']` off stale state without the remount.
      expect(await screen.findAllByLabelText(/^ประเทศ/)).toHaveLength(2);
      expect(screen.getByText('New Factory')).not.toBeNull();
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

    it('import does not see the footer edit control', async () => {
      renderPanel(IMPORT, [issuedRow()]);
      await screen.findByTestId(`ir-factory-card-11`);
      expect(screen.queryByTestId('ir-footer-open-11')).toBeNull();
    });
  });

  describe('mark-sent', () => {
    // The order email is import's job (งานนำเข้า / import deal page); the sales deal tab shows none of it.
    it('the owning sales rep: the factory card has no order-email button and no "อีเมลสั่งซื้อ:" line', async () => {
      renderPanel(OWNER, [issuedRow()]);
      await screen.findByTestId('ir-factory-card-11');
      expect(screen.queryByTestId('ir-email-open-11')).toBeNull();
      expect(screen.queryByText(/อีเมลสั่งซื้อ/)).toBeNull();
    });

    it('CEO keeps the order email on the deal tab (the ruling is for the sales rep only)', async () => {
      renderPanel(CEO, [issuedRow()]);
      expect((await screen.findByTestId('ir-email-open-11')).textContent).toBe('อีเมลสั่งซื้อ');
    });

    it('sales_manager (read-only) sees no email-edit control', async () => {
      renderPanel(SALES_MANAGER, [issuedRow()]);
      await screen.findByTestId('ir-factory-card-11');
      expect(screen.queryByTestId('ir-email-open-11')).toBeNull();
    });
  });

  describe('lead-time edit', () => {
    it('the owning rep can edit lead time on a DRAFT row', async () => {
      api.storedImportRequests.update.mockResolvedValue({ importRequest: draftRow({ leadTimeMinDays: 20, leadTimeMaxDays: 30 }) });
      renderPanel(OWNER, [draftRow()]);
      await screen.findByTestId('ir-factory-card-10');
      fireEvent.change(screen.getByTestId('ir-lead-min-10'), { target: { value: '20' } });
      fireEvent.change(screen.getByTestId('ir-lead-max-10'), { target: { value: '30' } });
      fireEvent.click(screen.getByTestId('ir-lead-save-10'));
      await waitFor(() => expect(api.storedImportRequests.update)
        .toHaveBeenCalledWith(10, { leadTimeMinDays: 20, leadTimeMaxDays: 30 }));
    });

    // ImportRequestService#update reads a null/null lead time as "no change", so a both-blank save on
    // a DRAFT clears nothing; toasting "saved" over an unchanged 30–45 was a false success.
    it('a both-blank lead-time save on a DRAFT is refused with a validation message, not reported as saved', async () => {
      const showToast = vi.fn();
      renderPanel(OWNER, [draftRow()], { showToast });
      await screen.findByTestId('ir-factory-card-10');
      fireEvent.change(screen.getByTestId('ir-lead-min-10'), { target: { value: '' } });
      fireEvent.change(screen.getByTestId('ir-lead-max-10'), { target: { value: '' } });
      fireEvent.click(screen.getByTestId('ir-lead-save-10'));
      expect(showToast).toHaveBeenCalledWith('error', expect.stringContaining('กรุณาระบุระยะเวลานำเข้า'));
      expect(api.storedImportRequests.update).not.toHaveBeenCalled();
    });

    // A viewer with no write path renders PLAIN TEXT, not a look-alike disabled input: import cannot
    // touch lead time until the row is ISSUED.
    it('import cannot edit lead time on a DRAFT row (post-issue only) — renders read-only text, not a disabled input', async () => {
      renderPanel(IMPORT, [draftRow()]);
      await screen.findByTestId('ir-factory-card-10');
      expect(screen.queryByTestId('ir-lead-min-10')).toBeNull();
      expect(await screen.findByTestId('ir-lead-readonly-10')).not.toBeNull();
    });

    // Two more wrong-way cases: sales_manager (the owning rep IS canFullWrite, so use sales_manager)
    // and sales_manager on an ISSUED row: neither is import/CEO (canAdvance) nor the owning rep on a
    // draft (canFullWrite), so both get read-only text on the ISSUED row too.
    it('sales_manager sees read-only lead-time text on an ISSUED row, never a disabled input', async () => {
      renderPanel(SALES_MANAGER, [issuedRow()]);
      await screen.findByTestId('ir-factory-card-11');
      expect(screen.queryByTestId('ir-lead-min-11')).toBeNull();
      expect(await screen.findByTestId('ir-lead-readonly-11')).not.toBeNull();
    });

    it('the owning sales rep sees read-only lead-time text on an ISSUED row (post-issue write moves to import/CEO)', async () => {
      renderPanel(OWNER, [issuedRow()]);
      await screen.findByTestId('ir-factory-card-11');
      expect(screen.queryByTestId('ir-lead-min-11')).toBeNull();
      expect(await screen.findByTestId('ir-lead-readonly-11')).not.toBeNull();
    });

    it('import can edit lead time on an ISSUED row', async () => {
      api.storedImportRequests.setLeadTime.mockResolvedValue({ importRequest: issuedRow({ leadTimeMinDays: 20, leadTimeMaxDays: 30 }) });
      renderPanel(IMPORT, [issuedRow()]);
      await screen.findByTestId('ir-factory-card-11');
      expect(screen.getByTestId('ir-lead-min-11').disabled).toBe(false);
      fireEvent.change(screen.getByTestId('ir-lead-min-11'), { target: { value: '20' } });
      fireEvent.change(screen.getByTestId('ir-lead-max-11'), { target: { value: '30' } });
      fireEvent.click(screen.getByTestId('ir-lead-save-11'));
      await waitFor(() => expect(api.storedImportRequests.setLeadTime)
        .toHaveBeenCalledWith(11, { leadTimeMinDays: 20, leadTimeMaxDays: 30 }));
    });
  });

  // The missing-country picker parses factory names out of a 409 message string (there is no
  // structured field). A retry that comes back with the SAME missing set must not reopen the modal
  // forever.
  describe('missing-country retry guard (S7)', () => {
    it('does not reopen the picker a second time when the retry 409s with the same missing names again', async () => {
      const showToast = vi.fn();
      api.storedImportRequests.createDrafts
        .mockRejectedValueOnce(Object.assign(new Error('กรุณาระบุประเทศให้กับโรงงานใหม่: Rex Ceramics'), { status: 409 }))
        .mockRejectedValueOnce(Object.assign(new Error('กรุณาระบุประเทศให้กับโรงงานใหม่: Rex Ceramics'), { status: 409 }));
      api.storedImportRequests.listForTicket.mockResolvedValue({ importRequests: [] });
      const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
      render(
        <QueryClientProvider client={queryClient}>
          <DealFulfilmentPanel user={OWNER} ticketId={1} summary={summary} items={[]} availableActions={[]} showToast={showToast} />
        </QueryClientProvider>,
      );
      await createIrAndConfirm();
      const select = await screen.findByLabelText(/^ประเทศ/);
      fireEvent.change(select, { target: { value: 'CN' } });
      const submit = await screen.findByTestId('new-factory-country-submit');
      await waitFor(() => expect(submit.disabled).toBe(false));
      fireEvent.click(submit);

      await waitFor(() => expect(api.storedImportRequests.createDrafts).toHaveBeenCalledTimes(2));
      // The SAME missing set came back a second time — the modal must not still be open (it would loop with an empty
      // country picker forever otherwise); a plain error surfaces instead.
      await waitFor(() => expect(screen.queryByTestId('new-factory-country-submit')).toBeNull());
      expect(showToast).toHaveBeenCalledWith('error', expect.stringContaining('ยังระบุโรงงานไม่ครบ'));
    });
  });

  // CEO footer editable from DRAFT, visible read-only to everyone else.
  describe('CEO footer on a DRAFT row (nit)', () => {
    it('CEO can open the footer editor on a DRAFT row, not just ISSUED', async () => {
      renderPanel(CEO, [draftRow({ vesselEtaNote: 'ประมาณ 10/10/26' })]);
      expect(await screen.findByTestId('ir-footer-open-10')).not.toBeNull();
    });

    it('a non-CEO viewer sees the footer status read-only on a DRAFT row', async () => {
      renderPanel(OWNER, [draftRow({ checkedByName: 'ราม', vesselEtaNote: 'ประมาณ 10/10/26' })]);
      expect(await screen.findByTestId('ir-footer-readonly-10')).not.toBeNull();
    });
  });

  // The footer form is not seeded once at mount: a note the server derived AFTER mount (issue()
  // writes vesselEtaNote) would open as a blank input, and saving any other field would write ''
  // over it.
  describe('CEO footer editor re-seeds from the row each time it opens', () => {
    it('shows the vessel ETA the server derived at issue, and saves it back unchanged', async () => {
      const note = 'ประมาณ 01/11/26 – 16/11/26 (30–45 วัน)';
      api.storedImportRequests.issue.mockResolvedValue({});
      api.storedImportRequests.update.mockResolvedValue({});
      renderPanel(CEO, [draftRow()]);
      const issue = await screen.findByTestId('ir-issue-10');
      api.storedImportRequests.listForTicket.mockResolvedValue({ importRequests: [issuedRow({ id: 10, vesselEtaNote: note })] });
      await issueIrAndConfirm(issue);
      await screen.findByTestId('factory-progress-10');
      fireEvent.click(screen.getByTestId('ir-footer-open-10'));
      expect(screen.getByLabelText('กำหนดเรือเข้าโดยประมาณ').value).toBe(note);
      fireEvent.click(screen.getByTestId('ir-footer-save-10'));
      await waitFor(() => expect(api.storedImportRequests.update)
        .toHaveBeenCalledWith(10, expect.objectContaining({ vesselEtaNote: note })));
    });
  });

  // The app's ConfirmDialog, not window.confirm().
  describe('delete-draft confirmation (nit)', () => {
    it('deleting a draft goes through the ConfirmDialog, not window.confirm', async () => {
      const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(true);
      api.storedImportRequests.deleteDraft.mockResolvedValue({ status: 'deleted' });
      renderPanel(OWNER, [draftRow()]);
      fireEvent.click(await screen.findByTestId('ir-delete-10'));
      // Two "ลบร่าง" buttons exist once the dialog opens (the row action + the dialog's own confirm) — the LAST one
      // is the dialog's.
      const buttons = await screen.findAllByRole('button', { name: 'ลบร่าง' });
      fireEvent.click(buttons[buttons.length - 1]);
      await waitFor(() => expect(api.storedImportRequests.deleteDraft).toHaveBeenCalledWith(10));
      expect(confirmSpy).not.toHaveBeenCalled();
      confirmSpy.mockRestore();
    });
  });

  // The create button relabels once the deal already has rows.
  describe('create-button relabel (nit)', () => {
    it('reads "ออกใบขอซื้อ (IR)" with no rows yet, and "ออกใบขอซื้อ (IR) โรงงานที่ยังไม่มี" once rows exist (CR-1 rename)', async () => {
      const { rerender } = renderPanel(OWNER, []);
      const initialButton = await screen.findByTestId('deal-fulfilment-create-ir-drafts');
      expect(initialButton.textContent).toBe('ออกใบขอซื้อ (IR)');

      api.storedImportRequests.listForTicket.mockResolvedValue({ importRequests: [draftRow()] });
      const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
      rerender(
        <QueryClientProvider client={queryClient}>
          <DealFulfilmentPanel user={OWNER} ticketId={2} summary={summary} items={[]} availableActions={[]} showToast={vi.fn()} />
        </QueryClientProvider>,
      );
      expect(await screen.findByText('ออกใบขอซื้อ (IR) โรงงานที่ยังไม่มี')).not.toBeNull();
    });
  });

  // A failed storedIrQuery fetch must not read as "no IR yet" nor bring back the legacy per-brand
  // buttons.
  describe('tab layout before the first IR (5a)', () => {
    const precedes = (a, b) => Boolean(a.compareDocumentPosition(b) & Node.DOCUMENT_POSITION_FOLLOWING);

    it('no issued IR yet: the stored-IR box is shown and the นำเข้าสินค้า progress card is not rendered', async () => {
      renderPanel(OWNER, [], { summary: { ...summary, fulfillmentStatus: null } });
      await screen.findByTestId('deal-fulfilment-stored-ir');
      expect(screen.queryByText('นำเข้าสินค้า')).toBeNull();
      expect(screen.queryByText(/ยังไม่ออกคำขอนำเข้า/)).toBeNull();
    });

    it('once fulfillmentStatus is set the progress card is back, ahead of the stored-IR box (as today)', async () => {
      renderPanel(OWNER, [issuedRow()], { summary: { ...summary, fulfillmentStatus: 'IR_ISSUED' } });
      const stored = await screen.findByTestId('deal-fulfilment-stored-ir');
      const card = screen.getByText('นำเข้าสินค้า');
      expect(precedes(card, stored)).toBe(true);
    });
  });

  it('before the first IR the commission-weight button stays reachable, below the stored-IR box, and opens its modal; no จองสินค้า beside it', async () => {
    renderPanel(CEO, [], {
      summary: { ...summary, fulfillmentStatus: null },
      availableActions: [{ action: 'SET_ITEM_WEIGHT_MULTIPLIER' }, { action: 'RESERVE_STOCK' }],
    });
    const stored = await screen.findByTestId('deal-fulfilment-stored-ir');
    const weight = await screen.findByTestId('deal-fulfilment-set-item-weight');
    expect(Boolean(stored.compareDocumentPosition(weight) & Node.DOCUMENT_POSITION_FOLLOWING)).toBe(true);
    // No reserve-from-stock feature, even if the server still advertised it.
    expect(screen.queryByTestId('deal-fulfilment-reserve-stock')).toBeNull();
    fireEvent.click(weight);
    expect(await screen.findByTestId('deal-fulfilment-set-item-weight-submit')).not.toBeNull();
  });

  describe('required-by field is folded behind a toggle (5b)', () => {
    const TOGGLE = '+ กำหนดวันที่ต้องการของ (ไม่บังคับ)';

    it('hidden until the toggle is clicked', async () => {
      renderPanel(OWNER, []);
      await screen.findByTestId('deal-fulfilment-create-ir-drafts');
      expect(screen.queryByTestId('deal-required-by-note')).toBeNull();
      fireEvent.click(screen.getByText(TOGGLE));
      expect(screen.getByTestId('deal-required-by-note')).not.toBeNull();
      expect(screen.getByTestId('deal-required-by-note-save')).not.toBeNull();
    });

    // ImportRequestService.REQUIRED_BY_ROLES = {sales, ceo}: a sales_manager holder gets the IR controls but not this field.
    it('a sales_manager holder gets no required-by toggle; a sales holder does', async () => {
      renderPanel({ ...SALES_MANAGER, canManageAllSalesDocuments: true }, []);
      await screen.findByTestId('deal-fulfilment-create-ir-drafts');
      expect(screen.queryByText(TOGGLE)).toBeNull();
    });
    it('a sales-role holder on another rep\'s deal gets the required-by toggle', async () => {
      renderPanel({ ...OTHER_REP, canManageAllSalesDocuments: true }, []);
      await screen.findByTestId('deal-fulfilment-create-ir-drafts');
      expect(screen.getByText(TOGGLE)).not.toBeNull();
    });

    it('once open, clearing all the text keeps the field open so the empty value can be saved', async () => {
      renderPanel(OWNER, [draftRow({ requiredByNote: 'Within 21/5/26' })]);
      const input = await screen.findByTestId('deal-required-by-note');
      await waitFor(() => expect(input.value).toBe('Within 21/5/26'));
      fireEvent.change(input, { target: { value: '' } });
      expect(screen.getByTestId('deal-required-by-note').value).toBe('');
      expect(screen.getByTestId('deal-required-by-note-save')).not.toBeNull();
    });

    it('a saved non-empty value shows it expanded, with no toggle needed', async () => {
      renderPanel(OWNER, [draftRow({ requiredByNote: 'Within 21/5/26' })]);
      const input = await screen.findByTestId('deal-required-by-note');
      await waitFor(() => expect(input.value).toBe('Within 21/5/26'));
    });
  });

  describe('every factory already covered (5c)', () => {
    const noCreateControls = () => {
      expect(screen.queryByTestId('deal-fulfilment-create-ir-drafts')).toBeNull();
      expect(screen.queryByTestId('deal-required-by-note')).toBeNull();
      expect(screen.queryByText(/กำหนดวันที่ต้องการของ/)).toBeNull();
      expect(screen.queryByText(/หนึ่งใบต่อหนึ่งโรงงาน/)).toBeNull();
    };

    it('hasUncoveredFactories=false: create button, required-by and helper line are gone; ทุกโรงงานออกใบขอซื้อแล้ว shows', async () => {
      renderPanel(OWNER, [issuedRow()], { hasUncoveredFactories: false });
      await screen.findByText('ทุกโรงงานออกใบขอซื้อแล้ว');
      noCreateControls();
    });

    it('false with no live IR row at all (nothing to import) says ไม่มีรายการที่ต้องออกใบขอซื้อ, not that every factory has one', async () => {
      renderPanel(OWNER, [], { hasUncoveredFactories: false });
      await screen.findByText('ไม่มีรายการที่ต้องออกใบขอซื้อ');
      expect(screen.queryByText('ทุกโรงงานออกใบขอซื้อแล้ว')).toBeNull();
    });

    it.each([['true', true], ['absent (older backend)', undefined]])('hasUncoveredFactories %s: create controls stay, no all-covered text', async (_n, flag) => {
      renderPanel(OWNER, [issuedRow()], { hasUncoveredFactories: flag });
      await screen.findByTestId('deal-fulfilment-create-ir-drafts');
      expect(screen.queryByText('ทุกโรงงานออกใบขอซื้อแล้ว')).toBeNull();
      expect(screen.getByText(/หนึ่งใบต่อหนึ่งโรงงาน/)).not.toBeNull();
      expect(screen.getByText('+ กำหนดวันที่ต้องการของ (ไม่บังคับ)')).not.toBeNull();
    });
  });

  describe('storedIrQuery failure (S5)', () => {
    it('shows an error, not "ยังไม่มีใบขอซื้อสำหรับดีลนี้", and does not resurrect the legacy IR block', async () => {
      // IMPORT (isFulfilment): the role the legacy per-brand block is gated on
      // (`isFulfilment && !hasStoredIrs`): the case that must NOT reappear on a fetch error.
      api.storedImportRequests.listForTicket.mockRejectedValue(new Error('เครือข่ายขัดข้อง'));
      const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
      render(
        <QueryClientProvider client={queryClient}>
          <DealFulfilmentPanel user={IMPORT} ticketId={1} summary={summary} items={[]} availableActions={[]} showToast={vi.fn()} />
        </QueryClientProvider>,
      );
      expect(await screen.findByTestId('deal-fulfilment-stored-ir-error')).not.toBeNull();
      expect(screen.queryByText('ยังไม่มีใบขอซื้อสำหรับดีลนี้')).toBeNull();
      expect(screen.queryByTestId('deal-fulfilment-import-request')).toBeNull();
    });
  });
});

// Sales presses ออกใบขอซื้อ (IR) once the quotation is approved; one IR per factory is created and
// each PDF downloads straight away so sales can hand it over; import then downloads the same IR from
// /fulfilment.
describe('DealFulfilmentPanel — ออกใบขอซื้อ (IR) downloads every created PDF (CR-1)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    globalThis.URL.createObjectURL = vi.fn(() => 'blob:ir');
    globalThis.URL.revokeObjectURL = vi.fn();
    api.storedImportRequests.download.mockResolvedValue(new Blob(['pdf'], { type: 'application/pdf' }));
  });

  it('relabels the button ออกใบขอซื้อ (IR) for the owning rep and CEO, and import still does not get it', async () => {
    renderPanel(OWNER, []);
    expect((await screen.findByTestId('deal-fulfilment-create-ir-drafts')).textContent).toBe('ออกใบขอซื้อ (IR)');
  });

  it('after a successful create, downloads the PDF of each IR it just created (one per factory)', async () => {
    const created = [issuedRow({ id: 21, factoryName: 'Cotto Industry' }), issuedRow({ id: 22, factoryId: 2, factoryName: 'Panaria' })];
    api.storedImportRequests.createDrafts.mockResolvedValueOnce({ importRequests: created });
    renderPanel(OWNER, []);
    await createIrAndConfirm();

    await waitFor(() => expect(api.storedImportRequests.download).toHaveBeenCalledTimes(2));
    expect(api.storedImportRequests.download.mock.calls.map((c) => c[0]).sort()).toEqual([21, 22]);
  });

  it('10-29: create ends with the rows ISSUED (no ออกเลข button) and the toast ออกใบขอซื้อ (IR) แล้ว', async () => {
    const showToast = vi.fn();
    const issued = [issuedRow({ id: 21, importStep: 'CONTACTED' }), issuedRow({ id: 22, factoryId: 2, factoryName: 'Panaria', docNumber: 'IR26002', importStep: 'CONTACTED' })];
    api.storedImportRequests.createDrafts.mockResolvedValueOnce({ importRequests: issued });
    renderPanel(OWNER, [], { showToast });
    // after the create the refetch returns the server's rows, which are already ISSUED
    api.storedImportRequests.listForTicket.mockResolvedValue({ importRequests: issued });
    await createIrAndConfirm();

    await waitFor(() => expect(showToast).toHaveBeenCalledWith('success', 'ออกใบขอซื้อ (IR) แล้ว'));
    await screen.findByText('Panaria');
    expect(screen.queryByTestId('ir-issue-21')).toBeNull();
    expect(screen.queryByTestId('ir-issue-22')).toBeNull();
    expect(screen.queryByTestId('ir-draft-hint-21')).toBeNull();
  });

  it('downloads only the NEW IRs when some factories already had one', async () => {
    const existing = draftRow({ id: 10, factoryName: 'Cotto Industry' });
    const fresh = issuedRow({ id: 23, factoryId: 3, factoryName: 'Rex Ceramics' });
    api.storedImportRequests.createDrafts.mockResolvedValueOnce({ importRequests: [existing, fresh] });
    renderPanel(OWNER, [existing]);
    await screen.findByText('Cotto Industry');
    await createIrAndConfirm();

    await waitFor(() => expect(api.storedImportRequests.download).toHaveBeenCalledTimes(1));
    expect(api.storedImportRequests.download).toHaveBeenCalledWith(23, undefined);
  });

  it('a failed PDF download does not undo the create: the toast says the IRs exist but the PDF failed', async () => {
    const showToast = vi.fn();
    api.storedImportRequests.createDrafts.mockResolvedValueOnce({ importRequests: [issuedRow({ id: 21 })] });
    api.storedImportRequests.download.mockRejectedValueOnce(new Error('ดาวน์โหลดใบขอซื้อไม่สำเร็จ'));
    api.storedImportRequests.listForTicket.mockResolvedValue({ importRequests: [] });
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
    render(
      <QueryClientProvider client={queryClient}>
        <DealFulfilmentPanel user={OWNER} ticketId={1} summary={summary} items={[]} availableActions={[]} showToast={showToast} />
      </QueryClientProvider>,
    );
    await createIrAndConfirm();
    await waitFor(() => expect(showToast).toHaveBeenCalledWith('success', expect.stringContaining('ออกใบขอซื้อ (IR)')));
    await waitFor(() => expect(showToast).toHaveBeenCalledWith('error', expect.stringContaining('ออกใบขอซื้อ (IR) แล้ว แต่ดาวน์โหลด')));
  });

  it('refreshes the stored IRs, the import deal and the fulfilment worklist after creating (F)', async () => {
    api.storedImportRequests.createDrafts.mockResolvedValueOnce({ importRequests: [issuedRow({ id: 21 })] });
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
    const invalidate = vi.spyOn(queryClient, 'invalidateQueries');
    api.storedImportRequests.listForTicket.mockResolvedValue({ importRequests: [] });
    render(
      <QueryClientProvider client={queryClient}>
        <DealFulfilmentPanel user={OWNER} ticketId={1} summary={summary} items={[]} availableActions={[]} showToast={vi.fn()} />
      </QueryClientProvider>,
    );
    await createIrAndConfirm();
    await waitFor(() => expect(api.storedImportRequests.createDrafts).toHaveBeenCalled());
    await waitFor(() => {
      const keys = invalidate.mock.calls.map((c) => JSON.stringify(c[0]?.queryKey));
      expect(keys.some((k) => k.includes('importRequests'))).toBe(true);
      // the cross-deal /fulfilment page reads the ticket list for its candidates
      expect(keys.some((k) => k.includes('"tickets","list"'))).toBe(true);
    });
  });
});

// The ส่งมอบสินค้า step must not print raw codes ("WAREHOUSE", "6: 250") in the history or the
// บันทึกการส่งสินค้า select. The wire value (what recordDelivery sends) stays the code; only what the
// person reads changes.
describe('DealFulfilmentPanel — ส่งมอบสินค้า shows words, not codes', () => {
  const deliveryItems = [
    { id: 6, brand: 'Brand X', model: 'Model Y', size: '60x60', qty: 500, qtyDelivered: 0 },
    // Same brand + model, other size: the history must tell the two apart.
    { id: 7, brand: 'Brand X', model: 'Model Y', size: '30x60', qty: 100, qtyDelivered: 0 },
  ];

  afterEach(() => {
    api.tickets.listDeliveries.mockResolvedValue({ items: [] });
    api.tickets.recordDelivery.mockReset();
  });

  it('the history names the source in Thai and each line by brand + model + size (fallback รายการ id), never "6: 250"', async () => {
    api.tickets.listDeliveries.mockResolvedValue({
      items: [
        { deliveryId: 1, deliveredAt: '2026-10-01T03:00:00Z', source: 'WAREHOUSE', items: [{ itemId: 6, qty: 250 }, { itemId: 99, qty: 3 }], deliveredByName: 'สมหญิง', note: null },
        { deliveryId: 2, deliveredAt: '2026-10-02T03:00:00Z', source: 'STOCK', items: [{ itemId: 7, qty: 10 }], deliveredByName: null, note: null },
      ],
    });
    renderPanel(OWNER, [], { items: deliveryItems });

    expect(await screen.findByText(/Brand X Model Y 60x60: 250/)).not.toBeNull();
    expect(screen.getByText(/รายการ 99: 3/)).not.toBeNull();
    expect(screen.getByText(/Brand X Model Y 30x60: 10/)).not.toBeNull();
    expect(screen.queryByText(/รายการ #/)).toBeNull();
    expect(screen.queryByText(/(^|[\s,])6: 250/)).toBeNull();
    expect(screen.getByText('คลังสินค้า', { selector: 'strong' })).not.toBeNull();
    expect(screen.getByText('สต็อก', { selector: 'strong' })).not.toBeNull();
    expect(screen.queryByText('WAREHOUSE')).toBeNull();
    expect(screen.queryByText('STOCK')).toBeNull();
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

// Slice 6c (prototype item 6.6): the confirm shows, per factory, how many items it gets and the expected lead time,
// from the coverage route's `splitPreview` — ImportRequestService's dry run of createDrafts' own split. The panel only
// renders what the server sent; it computes no split and no lead time of its own.
describe('DealFulfilmentPanel — ยืนยันออกใบขอซื้อ: รายการและระยะเวลานำเข้าต่อโรงงาน (slice 6c)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.storedImportRequests.createDrafts.mockReset();
    api.storedImportRequests.createDrafts.mockResolvedValue({ importRequests: [] });
  });
  const PREVIEW = {
    factories: [
      { factoryName: 'Cotto Industry', newFactory: false, itemCount: 3, leadTimeMinDays: 100, leadTimeMaxDays: 120, leadTimeSource: 'QUOTATION' },
      { factoryName: 'Rex Ceramics', newFactory: false, itemCount: 1, leadTimeMinDays: 75, leadTimeMaxDays: 90, leadTimeSource: 'COUNTRY_DEFAULT' },
      { factoryName: 'Polska Tile', newFactory: false, itemCount: 2, leadTimeMinDays: null, leadTimeMaxDays: null, leadTimeSource: null },
      { factoryName: 'Brand New Co', newFactory: true, itemCount: 1, leadTimeMinDays: null, leadTimeMaxDays: null, leadTimeSource: null },
    ],
    linesWithoutFactory: [],
  };
  const NAMES = PREVIEW.factories.map((f) => f.factoryName);
  const blocks = (dialog) => within(within(dialog).getByRole('list', { name: 'โรงงานที่จะออกใบขอซื้อ' })).getAllByRole('listitem');

  it('one block per factory the server previewed: name, n รายการ and นำเข้าประมาณ min–max วัน with its source', async () => {
    renderPanel(OWNER, [], { hasUncoveredFactories: true, uncoveredFactoryNames: NAMES, splitPreview: PREVIEW });
    const dialog = await openIrConfirm();

    expect(within(dialog).getByTestId('ir-confirm-summary').textContent).toBe('4 โรงงาน · ส่งถึงฝ่ายนำเข้าทันที');
    const [cotto, rex] = blocks(dialog);
    expect(within(cotto).getByTestId('ir-confirm-factory-name').textContent).toBe('Cotto Industry');
    expect(within(cotto).getByTestId('ir-confirm-factory-count').textContent).toBe('3 รายการ');
    expect(within(cotto).getByTestId('ir-confirm-factory-lead').textContent).toBe('นำเข้าประมาณ 100–120 วัน');
    expect(within(cotto).getByTestId('ir-confirm-factory-source').textContent).toBe('ตามใบเสนอราคา');
    expect(within(rex).getByTestId('ir-confirm-factory-lead').textContent).toBe('นำเข้าประมาณ 75–90 วัน');
    expect(within(rex).getByTestId('ir-confirm-factory-source').textContent).toBe('ค่าเริ่มต้นของประเทศ');
    // The names-only table and its "shown after issuing" note are replaced, not shown alongside.
    expect(within(dialog).queryByRole('table', { name: 'โรงงานที่จะออกใบขอซื้อ' })).toBeNull();
    expect(dialog.textContent).not.toContain('จะแสดงในใบขอซื้อแต่ละใบหลังออก');
  });

  it('no lead time yet: says it will be asked before issuing; a new factory is tagged and says its country comes first', async () => {
    renderPanel(OWNER, [], { hasUncoveredFactories: true, uncoveredFactoryNames: NAMES, splitPreview: PREVIEW });
    const [, , polska, brandNew] = blocks(await openIrConfirm());

    expect(within(polska).getByTestId('ir-confirm-factory-lead').textContent).toBe('ยังไม่มีระยะเวลานำเข้า · จะให้ระบุก่อนออก');
    expect(within(polska).queryByTestId('ir-confirm-factory-source')).toBeNull();
    expect(within(polska).queryByTestId('ir-confirm-factory-new')).toBeNull();
    expect(within(brandNew).getByTestId('ir-confirm-factory-new').textContent).toBe('โรงงานใหม่');
    expect(within(brandNew).getByTestId('ir-confirm-factory-lead').textContent)
      .toBe('ยังไม่มีระยะเวลานำเข้า · จะให้เลือกประเทศของโรงงานก่อนออก');
  });

  // Fix round L5: the warning says the IR cannot be issued, so the button agrees — disabled, described by the warning.
  // The server's 409 stays the backstop; this only stops the button contradicting the copy beside it.
  it('a line the split cannot place disables ออกใบขอซื้อ, with the warning as its stated reason, and sends nothing', async () => {
    renderPanel(OWNER, [], {
      hasUncoveredFactories: true,
      uncoveredFactoryNames: ['Cotto Industry'],
      splitPreview: {
        factories: [PREVIEW.factories[0]],
        linesWithoutFactory: [
          { ticketItemId: 31, code: 'Free Text One', size: '10x10 cm' },
          { ticketItemId: 32, code: 'Grout', size: null },
        ],
      },
    });
    const dialog = await openIrConfirm();

    expect(within(dialog).getByTestId('ir-confirm-unplaced').textContent)
      .toBe('2 รายการยังไม่มีโรงงาน — ออกใบขอซื้อไม่ได้จนกว่าจะระบุโรงงาน: Free Text One (10x10 cm), Grout');
    expect(within(dialog).getByTestId('ir-confirm-unplaced').getAttribute('role')).toBe('note');
    const confirm = within(dialog).getByRole('button', { name: 'ออกใบขอซื้อ' });
    expect(confirm.disabled).toBe(true);
    const reasonId = confirm.getAttribute('aria-describedby');
    expect(reasonId).toBeTruthy();
    expect(document.getElementById(reasonId)).toBe(within(dialog).getByTestId('ir-confirm-unplaced'));
    fireEvent.click(confirm);
    await act(async () => {});
    expect(api.storedImportRequests.createDrafts).not.toHaveBeenCalled();
  });

  it('every line placed: ออกใบขอซื้อ stays enabled and carries no disabled reason', async () => {
    renderPanel(OWNER, [], { hasUncoveredFactories: true, uncoveredFactoryNames: NAMES, splitPreview: PREVIEW });
    const confirm = within(await openIrConfirm()).getByRole('button', { name: 'ออกใบขอซื้อ' });
    expect(confirm.disabled).toBe(false);
    expect(confirm.getAttribute('aria-describedby')).toBeNull();
  });

  it('asks the server with the rep\'s unsaved factory pick, and shows the server\'s split instead of adding the pick by name', async () => {
    const directSummary = { ...summary, liveDirectQuotation: { id: 5, docStatus: 'APPROVED' } };
    const lines = [
      { id: 1, brand: 'SCG', model: 'Catalogue', factory: 'Cotto', catalogPriceId: 90, qty: 10 },
      { id: 3, brand: 'ค่าติดตั้ง', model: 'Free text', factory: null, catalogPriceId: null, qty: 10, qtyFromStock: 0 },
    ];
    renderPanel(OWNER, [], {
      summary: directSummary, items: lines, hasUncoveredFactories: true, uncoveredFactoryNames: ['Cotto'],
      splitPreview: { factories: [{ ...PREVIEW.factories[0], factoryName: 'Cotto', itemCount: 2 }], linesWithoutFactory: [] },
    });
    await waitFor(() => expect(api.storedImportRequests.coverage).toHaveBeenCalledWith(1, undefined));
    const button = await screen.findByTestId('deal-fulfilment-create-ir-drafts');
    const select = await screen.findByTestId('ir-line-factory-3');
    await waitFor(() => expect(within(select).getByRole('option', { name: 'Rex Ceramics' })).not.toBeNull());
    fireEvent.change(select, { target: { value: '7' } });
    await waitFor(() => expect(api.storedImportRequests.coverage)
      .toHaveBeenCalledWith(1, [{ ticketItemId: 3, factoryId: 7 }]));
    await waitFor(() => expect(button.disabled).toBe(false));

    const dialog = await openIrConfirm(button);
    // The server's answer is the whole truth: Rex Ceramics is not added on top of it by the client.
    expect(blocks(dialog).map((li) => within(li).getByTestId('ir-confirm-factory-name').textContent)).toEqual(['Cotto']);
    expect(within(dialog).getByTestId('ir-confirm-summary').textContent).toBe('1 โรงงาน · ส่งถึงฝ่ายนำเข้าทันที');
    fireEvent.click(within(dialog).getByRole('button', { name: 'ออกใบขอซื้อ' }));
    await waitFor(() => expect(api.storedImportRequests.createDrafts).toHaveBeenCalledTimes(1));
    expect(api.storedImportRequests.createDrafts.mock.calls).toEqual([[1, { itemFactories: [{ ticketItemId: 3, factoryId: 7 }] }]]);
  });

  it('keeps the warning first and never says ดีล', async () => {
    renderPanel(OWNER, [], {
      hasUncoveredFactories: true, uncoveredFactoryNames: NAMES,
      splitPreview: { ...PREVIEW, linesWithoutFactory: [{ ticketItemId: 31, code: 'X', size: null }] },
    });
    const dialog = await openIrConfirm();
    expect(dialog.querySelector('.modal-body').firstElementChild.firstElementChild)
      .toBe(within(dialog).getByTestId('ir-confirm-warning'));
    expect(dialog.textContent).not.toContain('ดีล');
  });

  it('a null splitPreview (what import and sales_manager receive) falls back to the names-only table', async () => {
    renderPanel(CEO, [], { hasUncoveredFactories: true, uncoveredFactoryNames: ['Cotto Industry'], splitPreview: null });
    const dialog = await openIrConfirm();
    expect(irConfirmFactories(dialog)).toEqual({ summary: '1 โรงงาน · ส่งถึงฝ่ายนำเข้าทันที', names: ['Cotto Industry'] });
    expect(within(dialog).queryByRole('list', { name: 'โรงงานที่จะออกใบขอซื้อ' })).toBeNull();
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

describe('DealFulfilmentPanel — ระบุสินค้าจากสต็อก (RESERVE_STOCK)', () => {
  const stockItems = [
    { id: 6, brand: 'Brand X', model: 'Model Y', size: '60x60', qty: 500, qtyDelivered: 0, qtyFromStock: 0 },
    { id: 7, brand: 'Brand X', model: 'Model Z', size: '30x60', qty: 100, qtyDelivered: 0, qtyFromStock: 100 },
  ];

  afterEach(() => api.tickets.reserveStock.mockReset());

  it('shows a จากสต็อก checkbox per line only when the deal advertises RESERVE_STOCK, checked where fully covered', async () => {
    renderPanel(OWNER, [], { items: stockItems, availableActions: [{ action: 'RESERVE_STOCK' }] });
    const first = await screen.findByTestId('deal-fulfilment-reserve-stock-6');
    expect(first.checked).toBe(false);
    expect(screen.getByTestId('deal-fulfilment-reserve-stock-7').checked).toBe(true);
  });

  it('shows no checkbox without the action', async () => {
    renderPanel(OWNER, [], { items: stockItems, availableActions: [] });
    await screen.findByText('Brand X Model Y');
    expect(screen.queryByTestId('deal-fulfilment-reserve-stock-6')).toBeNull();
  });

  const ACTIONS = [{ action: 'RESERVE_STOCK' }];
  const tick = async (id) => fireEvent.click(await screen.findByTestId(`deal-fulfilment-reserve-stock-${id}`));

  it('clicking an unticked line opens the dialog naming the line and does not call reserveStock yet', async () => {
    renderPanel(OWNER, [], { items: stockItems, availableActions: ACTIONS });
    await tick(6);
    const dialog = await screen.findByRole('dialog', { name: 'ยืนยันสินค้าจากสต็อก' });
    expect(dialog.textContent).toContain('Brand X Model Y');
    expect(dialog.textContent).toContain('500');
    expect(dialog.textContent).toContain('ข้ามขั้นสั่งนำเข้า (IR)');
    expect(dialog.textContent).toContain('ค่าคอมมิชชั่น');
    expect(api.tickets.reserveStock).not.toHaveBeenCalled();
  });

  it('confirming declares the whole line from stock once and closes the dialog', async () => {
    api.tickets.reserveStock.mockResolvedValue({});
    renderPanel(OWNER, [], { items: stockItems, availableActions: ACTIONS });
    await tick(6);
    const dialog = await screen.findByRole('dialog', { name: 'ยืนยันสินค้าจากสต็อก' });
    fireEvent.click(within(dialog).getByRole('button', { name: 'ยืนยันจากสต็อก' }));
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'ยืนยันสินค้าจากสต็อก' })).toBeNull());
    expect(api.tickets.reserveStock).toHaveBeenCalledTimes(1);
    expect(api.tickets.reserveStock).toHaveBeenCalledWith(1, { lines: [{ itemId: 6, qtyFromStock: 500 }] });
  });

  it('cancelling calls nothing and the checkbox stays unticked', async () => {
    renderPanel(OWNER, [], { items: stockItems, availableActions: ACTIONS });
    await tick(6);
    const dialog = await screen.findByRole('dialog', { name: 'ยืนยันสินค้าจากสต็อก' });
    fireEvent.click(within(dialog).getByRole('button', { name: 'ยกเลิก' }));
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'ยืนยันสินค้าจากสต็อก' })).toBeNull());
    expect(api.tickets.reserveStock).not.toHaveBeenCalled();
    expect(screen.getByTestId('deal-fulfilment-reserve-stock-6').checked).toBe(false);
  });

  it('clicking a covered line opens the untick dialog; confirming sends 0', async () => {
    api.tickets.reserveStock.mockResolvedValue({});
    renderPanel(OWNER, [], { items: stockItems, availableActions: ACTIONS });
    await tick(7);
    const dialog = await screen.findByRole('dialog', { name: 'ยกเลิกสินค้าจากสต็อก' });
    expect(dialog.textContent).toContain('Brand X Model Z');
    expect(dialog.textContent).toContain('กลับไปอยู่ในรายการนำเข้า');
    expect(api.tickets.reserveStock).not.toHaveBeenCalled();
    // The confirm must not read 'ยกเลิก': that is the back-out button everywhere else in the app.
    fireEvent.click(within(dialog).getByRole('button', { name: 'ยกเลิก' }));
    expect(api.tickets.reserveStock).not.toHaveBeenCalled();
    await tick(7);
    fireEvent.click(within(await screen.findByRole('dialog', { name: 'ยกเลิกสินค้าจากสต็อก' })).getByRole('button', { name: 'กลับไปสั่งนำเข้า' }));
    await waitFor(() => expect(api.tickets.reserveStock).toHaveBeenCalledTimes(1));
    expect(api.tickets.reserveStock).toHaveBeenCalledWith(1, { lines: [{ itemId: 7, qtyFromStock: 0 }] });
  });

});
