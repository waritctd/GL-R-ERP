import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { DepositNoticePage } from './DepositNoticePage.jsx';
import { api } from '../../api/index.js';

globalThis.React = React;

vi.mock('../../api/index.js', () => ({
  api: {
    depositNotices: {
      noteTemplates: vi.fn(),
      listByTicket: vi.fn(),
      createDraft: vi.fn(),
      update: vi.fn(),
      preview: vi.fn(),
      issue: vi.fn(),
      downloadXlsx: vi.fn(),
      downloadPdf: vi.fn(),
    },
    pricingRequests: {
      listForTicket: vi.fn(),
      createDepositNoticeFromQuotation: vi.fn(),
    },
    customers: {
      search: vi.fn(),
    },
  },
}));

function renderDepositNoticePage(props = {}) {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  });

  return render(
    <QueryClientProvider client={queryClient}>
      <DepositNoticePage
        ticketId="701"
        onBack={vi.fn()}
        onNavigateTickets={vi.fn()}
        showToast={vi.fn()}
        {...props}
      />
    </QueryClientProvider>,
  );
}

describe('DepositNoticePage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.depositNotices.noteTemplates.mockResolvedValue({
      templates: [{ id: 1, text: 'หมายเหตุมาตรฐาน', defaultSelected: true }],
    });
    api.customers.search.mockResolvedValue({ customers: [] });
    api.depositNotices.listByTicket.mockResolvedValue({ depositNotices: [] });
    api.depositNotices.createDraft.mockResolvedValue({
      depositNotice: {
        id: 9001, ticketId: 701, version: 1, status: 'DRAFT', docNumber: null,
        customerName: 'บริษัท ทดสอบ จำกัด', customerTaxId: '', customerAddress: '',
        projectName: '', reference: '', depositPercent: 0.5, notes: [], items: [],
      },
    });
    // Default: no pricing requests on this ticket at all — every existing (pre-chain) test
    // below relies on the legacy ticket-level createDraft path, which is exactly the fallback
    // this resolution degrades to.
    api.pricingRequests.listForTicket.mockResolvedValue({ items: [] });
    api.pricingRequests.createDepositNoticeFromQuotation.mockResolvedValue({
      depositNotice: {
        id: 9003, ticketId: 701, version: 1, status: 'DRAFT', docNumber: null,
        customerName: 'บริษัท ทดสอบ จำกัด', customerTaxId: '', customerAddress: '',
        projectName: '', reference: 'QT-2026-0001', depositPercent: 0.5, notes: [], items: [],
      },
    });
    api.depositNotices.update.mockResolvedValue({ depositNotice: {} });
    api.depositNotices.issue.mockResolvedValue({
      depositNotice: { id: 9002, docNumber: 'DN-0001', status: 'ISSUED' },
    });
  });

  // Shared fixture for the UX-03 issue-validation tests below: a DRAFT with
  // one fully-valid line item (subtotal 500 -> deposit 250 -> vat 17.5 ->
  // total 267.5, all > 0), so each test only has to break the ONE rule it's
  // asserting on.
  const validDraftDoc = {
    id: 9002, ticketId: 701, version: 1, status: 'DRAFT', docNumber: null,
    customerName: 'บริษัท ทดสอบ จำกัด', customerTaxId: '', customerAddress: '',
    projectName: '', reference: '', depositPercent: 0.5, notes: [],
    items: [
      { seq: 1, description: 'กระเบื้อง A', qty: 5, unit: 'แผ่น', unitPrice: 100, netUnitPrice: 100 },
    ],
  };

  // UX-03: an empty customerName (its label already carries a `*`) must be
  // flagged inline on that field, must not open the confirm dialog, and must
  // never reach api.depositNotices.issue.
  it('blocks issue with an empty customer name, flags it inline, and never opens the confirm dialog', async () => {
    api.depositNotices.listByTicket.mockResolvedValue({ depositNotices: [validDraftDoc] });
    renderDepositNoticePage();

    const nameInput = await screen.findByDisplayValue('บริษัท ทดสอบ จำกัด');
    fireEvent.change(nameInput, { target: { value: '' } });
    fireEvent.click(screen.getByRole('button', { name: /ออกเอกสาร/ }));

    const error = await screen.findByText('กรุณากรอกชื่อบริษัท / หน่วยงาน');
    expect(error.getAttribute('role')).toBe('alert');
    expect(nameInput.getAttribute('aria-invalid')).toBe('true');
    expect(nameInput.getAttribute('aria-describedby')).toBe(error.id);
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(api.depositNotices.issue).not.toHaveBeenCalled();
  });

  // UX-03: a line item missing its description must flag THAT item's
  // description field specifically — not the whole form, not the other
  // (valid) item's fields.
  it('blocks issue when a line item is missing its description, flagging that item specifically', async () => {
    api.depositNotices.listByTicket.mockResolvedValue({
      depositNotices: [{
        ...validDraftDoc,
        items: [
          { seq: 1, description: 'กระเบื้อง A', qty: 5, unit: 'แผ่น', unitPrice: 100, netUnitPrice: 100 },
          { seq: 2, description: '', qty: 5, unit: 'แผ่น', unitPrice: 100, netUnitPrice: 100 },
        ],
      }],
    });
    renderDepositNoticePage();

    await screen.findByDisplayValue('บริษัท ทดสอบ จำกัด');
    fireEvent.click(screen.getByRole('button', { name: /ออกเอกสาร/ }));

    const error = await screen.findByText('กรุณากรอกรายละเอียดสินค้าในรายการที่ 2');
    expect(error.getAttribute('role')).toBe('alert');

    const row1Description = document.getElementById('doc-item-0-description');
    const row2Description = document.getElementById('doc-item-1-description');
    expect(row1Description.getAttribute('aria-invalid')).not.toBe('true');
    expect(row2Description.getAttribute('aria-invalid')).toBe('true');
    expect(row2Description.getAttribute('aria-describedby')).toBe(error.id);
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(api.depositNotices.issue).not.toHaveBeenCalled();
  });

  // UX-03 rule 4: a ฿0 deposit notice is meaningless and would still advance
  // the ticket's payment track — issuing it must be blocked even though
  // rules 1-3 (customer name, >=1 item, description+qty) are all satisfied.
  it('blocks issue when the computed total is zero', async () => {
    api.depositNotices.listByTicket.mockResolvedValue({
      depositNotices: [{
        ...validDraftDoc,
        items: [
          { seq: 1, description: 'กระเบื้อง A', qty: 5, unit: 'แผ่น', unitPrice: 0, netUnitPrice: 0 },
        ],
      }],
    });
    renderDepositNoticePage();

    await screen.findByDisplayValue('บริษัท ทดสอบ จำกัด');
    fireEvent.click(screen.getByRole('button', { name: /ออกเอกสาร/ }));

    expect(await screen.findByText(/ยอดเงินรวมต้องมากกว่า 0/)).not.toBeNull();
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(api.depositNotices.issue).not.toHaveBeenCalled();
  });

  // Regression guard for the SCOPING DECISION: this is a draft editor and
  // "บันทึก" must stay fully permissive — saving an incomplete draft (no
  // customer name, no items) is a legitimate workflow and must never be
  // blocked by the issue-time validation added above.
  it('still saves an incomplete draft (no customer name, no items) via บันทึก', async () => {
    api.depositNotices.listByTicket.mockResolvedValue({
      depositNotices: [{ ...validDraftDoc, customerName: '', items: [] }],
    });
    renderDepositNoticePage();

    await screen.findByText('รายการสินค้า (0 รายการ)');
    fireEvent.click(screen.getByRole('button', { name: 'บันทึก' }));

    await waitFor(() => expect(api.depositNotices.update).toHaveBeenCalledTimes(1));
    expect(api.depositNotices.update).toHaveBeenCalledWith(
      9002,
      expect.objectContaining({ customerName: '', items: [] }),
    );
    // No issue-validation errors should have been introduced by saving.
    expect(screen.queryByRole('alert')).toBeNull();
  });

  // A fully valid document opens the confirm dialog, and confirming it calls
  // api.depositNotices.issue for that document.
  it('opens the confirm dialog for a valid document and issues it on confirm', async () => {
    api.depositNotices.listByTicket.mockResolvedValue({ depositNotices: [validDraftDoc] });
    renderDepositNoticePage();

    await screen.findByDisplayValue('บริษัท ทดสอบ จำกัด');
    fireEvent.click(screen.getByRole('button', { name: /ออกเอกสาร/ }));

    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText('ยืนยันการออกเอกสาร')).not.toBeNull();

    fireEvent.click(within(dialog).getByRole('button', { name: 'ออกเอกสาร' }));

    await waitFor(() => expect(api.depositNotices.issue).toHaveBeenCalledWith(9002));
  });

  it('renders the empty-state and lets the user create a draft', async () => {
    renderDepositNoticePage();

    expect(await screen.findByText('ยังไม่มีใบแจ้งยอดเงินรับมัดจำ')).not.toBeNull();

    const createButton = screen.getByRole('button', { name: /สร้างเอกสารฉบับร่าง/ });
    fireEvent.click(createButton);

    await waitFor(() => expect(api.depositNotices.createDraft).toHaveBeenCalledTimes(1));
    expect(api.depositNotices.createDraft).toHaveBeenCalledWith('701', {
      notes: ['หมายเหตุมาตรฐาน'],
      depositPercent: 0.5,
    });

    // Mutation succeeding invalidates depositNotices(ticketId), which refetches
    // listByTicket — the second call is that background refetch.
    await waitFor(() => expect(api.depositNotices.listByTicket).toHaveBeenCalledTimes(2));
  });

  // Branch fix (deposit-notice autofill): a chain deal — one of this ticket's pricing requests
  // has an accepted customer quotation (status QUOTATION_ACCEPTED) — must use the correct
  // endpoint instead of the legacy ticket-level route.
  it('uses the pricing-request quotation endpoint when the ticket has an accepted quotation', async () => {
    api.pricingRequests.listForTicket.mockResolvedValue({
      items: [
        { id: 501, status: 'QUOTATION_ISSUED' },
        { id: 502, status: 'QUOTATION_ACCEPTED' },
      ],
    });
    renderDepositNoticePage();

    const createButton = await screen.findByRole('button', { name: /สร้างเอกสารฉบับร่าง/ });
    fireEvent.click(createButton);

    await waitFor(() => expect(api.pricingRequests.createDepositNoticeFromQuotation).toHaveBeenCalledTimes(1));
    expect(api.pricingRequests.createDepositNoticeFromQuotation).toHaveBeenCalledWith(502, { depositPercent: 0.5 });
    expect(api.depositNotices.createDraft).not.toHaveBeenCalled();
  });

  // No pricing request on this ticket has an accepted quotation (e.g. still mid-negotiation,
  // or a legacy ticket with pricing requests that never went through the chain) — must fall
  // back to today's ticket-level route rather than erroring.
  it('falls back to the ticket-level route when no pricing request has an accepted quotation', async () => {
    api.pricingRequests.listForTicket.mockResolvedValue({
      items: [{ id: 501, status: 'QUOTATION_ISSUED' }],
    });
    renderDepositNoticePage();

    const createButton = await screen.findByRole('button', { name: /สร้างเอกสารฉบับร่าง/ });
    fireEvent.click(createButton);

    await waitFor(() => expect(api.depositNotices.createDraft).toHaveBeenCalledTimes(1));
    expect(api.depositNotices.createDraft).toHaveBeenCalledWith('701', {
      notes: ['หมายเหตุมาตรฐาน'],
      depositPercent: 0.5,
    });
    expect(api.pricingRequests.createDepositNoticeFromQuotation).not.toHaveBeenCalled();
  });

  // A failure anywhere in the resolution step (network error, permission edge case) must
  // degrade to the fallback path, never break the button or surface an unhandled rejection.
  it('falls back to the ticket-level route when resolving pricing requests fails', async () => {
    api.pricingRequests.listForTicket.mockRejectedValue(new Error('network error'));
    renderDepositNoticePage();

    const createButton = await screen.findByRole('button', { name: /สร้างเอกสารฉบับร่าง/ });
    fireEvent.click(createButton);

    await waitFor(() => expect(api.depositNotices.createDraft).toHaveBeenCalledTimes(1));
    expect(api.depositNotices.createDraft).toHaveBeenCalledWith('701', {
      notes: ['หมายเหตุมาตรฐาน'],
      depositPercent: 0.5,
    });
    expect(api.pricingRequests.createDepositNoticeFromQuotation).not.toHaveBeenCalled();
  });

  it('renders the existing draft document instead of the empty state', async () => {
    api.depositNotices.listByTicket.mockResolvedValue({
      depositNotices: [{
        id: 9002, ticketId: 701, version: 1, status: 'DRAFT', docNumber: null,
        customerName: 'บริษัท ทดสอบ จำกัด', customerTaxId: '1234567890123', customerAddress: 'กรุงเทพฯ',
        projectName: 'โครงการ A', reference: 'PO-001', depositPercent: 0.5, notes: [], items: [],
      }],
    });

    renderDepositNoticePage();

    await waitFor(() => expect(screen.queryByText('ยังไม่มีใบแจ้งยอดเงินรับมัดจำ')).toBeNull());
    expect(await screen.findByDisplayValue('บริษัท ทดสอบ จำกัด')).not.toBeNull();
  });

  // Issue #756: the VAT rate must come from the served document's vatPercent, not a
  // hardcoded 0.07 literal — DepositNoticeDto.vatPercent (backend/src/main/java/th/co/glr/hr/
  // deposit/DepositNoticeDto.java:25) is the authoritative source once a document exists.
  // subtotal 500 -> deposit(50%) 250 -> VAT at 10% = 25.00 / at 7% = 17.50 — chosen so the two
  // rates produce clearly different, unambiguous money strings (no shared substring) and the
  // assertion cannot pass by coincidence.
  it('uses the served document vatPercent (10%) in the VAT label and amount, not the 7% fallback', async () => {
    api.depositNotices.listByTicket.mockResolvedValue({
      depositNotices: [{ ...validDraftDoc, vatPercent: 0.10 }],
    });
    renderDepositNoticePage();

    await screen.findByDisplayValue('บริษัท ทดสอบ จำกัด');

    // Summary panel: label carries the served 10% rate, not a hardcoded "7%" — and the VAT
    // amount is deposit(250) * 0.10 = 25.00, not deposit * 0.07 = 17.50.
    expect(screen.getByText('ภาษีมูลค่าเพิ่ม 10% (คิดจากมัดจำ)')).not.toBeNull();
    expect(screen.queryByText(/ภาษีมูลค่าเพิ่ม 7%/)).toBeNull();
    expect(screen.getByText('25.00 บาท')).not.toBeNull();
    expect(screen.queryByText('17.50 บาท')).toBeNull();

    // ConfirmDialog carries the same served rate + amount (validDraftDoc is a fully valid
    // document, so "ออกเอกสาร" opens the dialog with no issue-validation errors in the way).
    fireEvent.click(screen.getByRole('button', { name: /ออกเอกสาร/ }));
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText('ภาษีมูลค่าเพิ่ม 10%')).not.toBeNull();
    expect(within(dialog).queryByText(/ภาษีมูลค่าเพิ่ม 7%$/)).toBeNull();
    expect(within(dialog).getByText('25.00 บาท')).not.toBeNull();
    expect(within(dialog).queryByText('17.50 บาท')).toBeNull();
  });
});
