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
});
