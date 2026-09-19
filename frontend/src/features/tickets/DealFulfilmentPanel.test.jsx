import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { DealFulfilmentPanel } from './DealFulfilmentPanel.jsx';
import { api } from '../../api/index.js';

globalThis.React = React;

// PR-B: the "ใบขอซื้อรายโรงงาน" section (V184 stored aggregate) added to DealFulfilmentPanel.
// Covers role visibility, the country-required create flow, the CEO-only footer, mark-sent, and
// the lead-time edit — see import-request-per-factory-PLAN.md's Verify section. Everything else
// on this panel (delivery/stock/legacy IR chain) is exercised indirectly via TicketDetailPage's
// own suite; this file is scoped to the new section only.
vi.mock('../../api/index.js', async (importOriginal) => {
  const actual = await importOriginal();
  return {
    ...actual,
    api: {
      importRequests: { brands: vi.fn().mockResolvedValue({ brands: [] }), pages: vi.fn(), download: vi.fn() },
      storedImportRequests: {
        listForTicket: vi.fn(),
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

const summary = { createdById: 42, status: 'quotation_issued', fulfillmentStatus: null };

function renderPanel(user, rows = [draftRow()]) {
  api.storedImportRequests.listForTicket.mockResolvedValue({ importRequests: rows });
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <DealFulfilmentPanel user={user} ticketId={1} summary={summary} items={[]} availableActions={[]} showToast={vi.fn()} />
    </QueryClientProvider>,
  );
}

const OWNER = { id: 42, name: 'สมหญิง', role: 'sales' };
const OTHER_REP = { id: 99, name: 'อีกคน', role: 'sales' };
const CEO = { id: 1, name: 'ราม', role: 'ceo' };
const IMPORT = { id: 2, name: 'ฝ่ายนำเข้า', role: 'import' };
const SALES_MANAGER = { id: 3, name: 'ผจก.ขาย', role: 'sales_manager' };

describe('DealFulfilmentPanel — ใบขอซื้อรายโรงงาน (V184 stored aggregate)', () => {
  beforeEach(() => vi.clearAllMocks());

  describe('role visibility', () => {
    it('the owning sales rep sees the create-draft button', async () => {
      renderPanel(OWNER);
      expect(await screen.findByTestId('deal-fulfilment-create-ir-drafts')).not.toBeNull();
    });

    it('CEO sees the create-draft button', async () => {
      renderPanel(CEO);
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

  describe('country-required create flow', () => {
    it('a missing-country 409 opens the picker, and submitting retries with newFactoryCountries', async () => {
      api.storedImportRequests.createDrafts
        .mockRejectedValueOnce(Object.assign(new Error('กรุณาระบุประเทศให้กับโรงงานใหม่: Rex Ceramics'), { status: 409 }))
        .mockResolvedValueOnce({ importRequests: [draftRow({ factoryName: 'Rex Ceramics' })] });
      renderPanel(OWNER, []);
      fireEvent.click(await screen.findByTestId('deal-fulfilment-create-ir-drafts'));

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

    it('picking อื่นๆ (ZZ) requires a typed country name before the submit button enables', async () => {
      api.storedImportRequests.createDrafts
        .mockRejectedValueOnce(Object.assign(new Error('กรุณาระบุประเทศให้กับโรงงานใหม่: New Factory'), { status: 409 }));
      renderPanel(OWNER, []);
      fireEvent.click(await screen.findByTestId('deal-fulfilment-create-ir-drafts'));

      const select = await screen.findByLabelText(/^ประเทศ/);
      fireEvent.change(select, { target: { value: 'ZZ' } });
      const submit = screen.getByTestId('new-factory-country-submit');
      expect(submit.disabled).toBe(true);

      fireEvent.change(screen.getByLabelText(/^ระบุชื่อประเทศ/), { target: { value: 'Vietnam' } });
      await waitFor(() => expect(submit.disabled).toBe(false));
    });

    // PR-B REVIEW ROUND 1, S5's own fix, given its own direct coverage (nit).
    it('shows the countries-load error message and keeps the submit button disabled when priceImport.countries fails', async () => {
      api.priceImport.countries.mockRejectedValueOnce(new Error('เชื่อมต่อไม่ได้'));
      api.storedImportRequests.createDrafts
        .mockRejectedValueOnce(Object.assign(new Error('กรุณาระบุประเทศให้กับโรงงานใหม่: Rex Ceramics'), { status: 409 }));
      renderPanel(OWNER, []);
      fireEvent.click(await screen.findByTestId('deal-fulfilment-create-ir-drafts'));

      const errorMsg = await screen.findByTestId('new-factory-country-error');
      expect(errorMsg.textContent).toContain('โหลดรายชื่อประเทศไม่สำเร็จ');
      expect(errorMsg.textContent).toContain('เชื่อมต่อไม่ได้');
      expect(screen.getByTestId('new-factory-country-submit').disabled).toBe(true);
    });

    // Nit: NewFactoryCountryModal's `entries` state is seeded once from `factoryNames` via
    // useState's lazy initializer — it does not re-derive when the PROP changes. A retry that
    // comes back with a DIFFERENT (wider) missing-factory set than the one the modal opened with
    // must not read `entries[newName]` (undefined, no optional chaining at the `<select>`'s
    // `value={entries[name].countryCode}`) and crash. Keying the modal by the retry key forces
    // React to remount it fresh instead of reusing stale state built for the old name list.
    it('does not crash when a retry 409s back with a DIFFERENT (wider) missing-factory set', async () => {
      api.storedImportRequests.createDrafts
        .mockRejectedValueOnce(Object.assign(new Error('กรุณาระบุประเทศให้กับโรงงานใหม่: Rex Ceramics'), { status: 409 }))
        .mockRejectedValueOnce(Object.assign(new Error('กรุณาระบุประเทศให้กับโรงงานใหม่: Rex Ceramics, New Factory'), { status: 409 }));
      renderPanel(OWNER, []);
      fireEvent.click(await screen.findByTestId('deal-fulfilment-create-ir-drafts'));

      const select = await screen.findByLabelText(/^ประเทศ/);
      fireEvent.change(select, { target: { value: 'CN' } });
      const submit = await screen.findByTestId('new-factory-country-submit');
      await waitFor(() => expect(submit.disabled).toBe(false));
      fireEvent.click(submit);

      await waitFor(() => expect(api.storedImportRequests.createDrafts).toHaveBeenCalledTimes(2));
      // Both factories from the WIDER retry set must render their own country picker — this
      // would throw reading `entries['New Factory']` off stale state without the remount.
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
    it('the owning rep can open the email modal and mark it sent', async () => {
      api.storedImportRequests.markEmailSent.mockResolvedValue({ importRequest: issuedRow({ emailSentAt: '2026-09-19T00:00:00Z' }) });
      renderPanel(OWNER, [issuedRow()]);
      fireEvent.click(await screen.findByTestId('ir-email-open-11'));
      const sendButton = await screen.findByTestId('ir-email-mark-sent-11');
      fireEvent.click(sendButton);
      await waitFor(() => expect(api.storedImportRequests.markEmailSent).toHaveBeenCalledWith(11));
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

    // PR-B REVIEW ROUND 1, S1: a viewer with no write path renders PLAIN TEXT, not a look-alike
    // disabled input — import cannot touch lead time until the row is ISSUED.
    it('import cannot edit lead time on a DRAFT row (post-issue only) — renders read-only text, not a disabled input', async () => {
      renderPanel(IMPORT, [draftRow()]);
      await screen.findByTestId('ir-factory-card-10');
      expect(screen.queryByTestId('ir-lead-min-10')).toBeNull();
      expect(await screen.findByTestId('ir-lead-readonly-10')).not.toBeNull();
    });

    // S1's other two wrong-way cases: sales (non-owning excluded already; owning rep IS
    // canFullWrite so use sales_manager here) and sales_manager on an ISSUED row — neither is
    // import/CEO (canAdvance) nor the owning rep on a draft (canFullWrite), so both get read-only
    // text on the ISSUED row too.
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

  // PR-B REVIEW ROUND 1, B1: the email-draft save must send Java's own field names
  // (emailTo/emailSubject/emailBody — UpdateEmailDraftRequest), not to/subject/body, which Jackson
  // silently drops (unknown JSON properties are ignored) and the update's own COALESCE keeps the
  // OLD value with a 200 OK — a save that looked like it worked but never touched the row.
  describe('order-email save payload (B1)', () => {
    it('CLICKING save sends emailTo/emailSubject/emailBody, not to/subject/body', async () => {
      api.storedImportRequests.updateEmailDraft.mockResolvedValue({ importRequest: issuedRow() });
      renderPanel(OWNER, [issuedRow()]);
      fireEvent.click(await screen.findByTestId('ir-email-open-11'));
      const subjectInput = await screen.findByDisplayValue('Purchase order IR26001 - GL&R');
      fireEvent.change(subjectInput, { target: { value: 'Purchase order IR26001 - GL&R (revised)' } });
      fireEvent.click(screen.getByTestId('ir-email-save-11'));
      await waitFor(() => expect(api.storedImportRequests.updateEmailDraft).toHaveBeenCalledWith(11, {
        emailTo: 'factory@example.com',
        emailSubject: 'Purchase order IR26001 - GL&R (revised)',
        emailBody: 'Dear...',
      }));
    });

    it('hides "บันทึก" once the email has already been sent', async () => {
      renderPanel(OWNER, [issuedRow({ emailSentAt: '2026-09-18T00:00:00Z', emailSentByName: 'สมหญิง' })]);
      fireEvent.click(await screen.findByTestId('ir-email-open-11'));
      await screen.findByText(/ส่งแล้ว/);
      expect(screen.queryByTestId('ir-email-save-11')).toBeNull();
    });
  });

  // PR-B REVIEW ROUND 1, S7: the missing-country picker parses factory names out of a 409
  // message string (there is no structured field). A retry that comes back with the SAME missing
  // set must not reopen the modal forever.
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
      fireEvent.click(await screen.findByTestId('deal-fulfilment-create-ir-drafts'));
      const select = await screen.findByLabelText(/^ประเทศ/);
      fireEvent.change(select, { target: { value: 'CN' } });
      const submit = await screen.findByTestId('new-factory-country-submit');
      await waitFor(() => expect(submit.disabled).toBe(false));
      fireEvent.click(submit);

      await waitFor(() => expect(api.storedImportRequests.createDrafts).toHaveBeenCalledTimes(2));
      // The SAME missing set came back a second time — the modal must not still be open (it would
      // loop with an empty country picker forever otherwise); a plain error surfaces instead.
      await waitFor(() => expect(screen.queryByTestId('new-factory-country-submit')).toBeNull());
      expect(showToast).toHaveBeenCalledWith('error', expect.stringContaining('ยังระบุโรงงานไม่ครบ'));
    });
  });

  // PR-B REVIEW ROUND 1, nit: CEO footer editable from DRAFT, visible read-only to everyone else.
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

  // PR-B REVIEW ROUND 1, nit: window.confirm() replaced with the app's ConfirmDialog.
  describe('delete-draft confirmation (nit)', () => {
    it('deleting a draft goes through the ConfirmDialog, not window.confirm', async () => {
      const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(true);
      api.storedImportRequests.deleteDraft.mockResolvedValue({ status: 'deleted' });
      renderPanel(OWNER, [draftRow()]);
      fireEvent.click(await screen.findByTestId('ir-delete-10'));
      // Two "ลบร่าง" buttons exist once the dialog opens (the row action + the dialog's own
      // confirm) — the LAST one is the dialog's.
      const buttons = await screen.findAllByRole('button', { name: 'ลบร่าง' });
      fireEvent.click(buttons[buttons.length - 1]);
      await waitFor(() => expect(api.storedImportRequests.deleteDraft).toHaveBeenCalledWith(10));
      expect(confirmSpy).not.toHaveBeenCalled();
      confirmSpy.mockRestore();
    });
  });

  // PR-B REVIEW ROUND 1, nit: the create button relabels once the deal already has rows.
  describe('create-button relabel (nit)', () => {
    it('reads "สร้างใบขอซื้อ" with no rows yet, and "สร้างใบขอซื้อโรงงานที่ยังไม่มี" once rows exist', async () => {
      const { rerender } = renderPanel(OWNER, []);
      const initialButton = await screen.findByTestId('deal-fulfilment-create-ir-drafts');
      expect(initialButton.textContent).toBe('สร้างใบขอซื้อ');

      api.storedImportRequests.listForTicket.mockResolvedValue({ importRequests: [draftRow()] });
      const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
      rerender(
        <QueryClientProvider client={queryClient}>
          <DealFulfilmentPanel user={OWNER} ticketId={2} summary={summary} items={[]} availableActions={[]} showToast={vi.fn()} />
        </QueryClientProvider>,
      );
      expect(await screen.findByText('สร้างใบขอซื้อโรงงานที่ยังไม่มี')).not.toBeNull();
    });
  });

  // PR-B REVIEW ROUND 1, S5: a failed storedIrQuery fetch must not read as "no IR yet" nor bring
  // back the legacy per-brand buttons.
  describe('storedIrQuery failure (S5)', () => {
    it('shows an error, not "ยังไม่มีใบขอซื้อสำหรับดีลนี้", and does not resurrect the legacy IR block', async () => {
      // IMPORT (isFulfilment): the role the legacy per-brand block is gated on
      // (`isFulfilment && !hasStoredIrs`) — the case that must NOT reappear on a fetch error.
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
